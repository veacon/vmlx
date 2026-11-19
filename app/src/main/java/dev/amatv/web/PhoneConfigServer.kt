package dev.amatv.web

import android.content.Context
import android.net.Uri
import dev.amatv.integration.PlaylistImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Minimal phone configuration web server (ported from veil.music InAppWebServer).
 *
 * - Listens on the LAN (8080, else 8082..8090, else system-assigned).
 * - GET  /                     -> embedded HTML page (paste playlist link / song list)
 * - POST /playlist-import      -> importer.import(url|text, name) -> saves into local library
 * - GET  /status               -> small JSON status
 *
 * QR code shown on TV encodes serverUrl() == "http://<lan-ip>:<port>" (no token).
 * Head + body MUST share one BufferedInputStream, otherwise the browser's form body
 * gets swallowed by the reader's 8KB prefetch (symptom: submit appears to hang).
 */
class PhoneConfigServer(
    context: Context,
    private val importer: PlaylistImporter,
    private val appleMusic: AppleMusicBridge? = null,
) {
    /** Account operations the 8080 page is allowed to ask the TV app to perform. */
    interface AppleMusicBridge {
        fun available(): Boolean
        fun signedIn(): Boolean

        /**
         * v0.8: 登录用开发者令牌（可空）。打包令牌不可用时由电视端自行从 Apple 官网
         * 抓取（veil 同款兜底）；null = 无法获取，/am 页转手动导入令牌方案。
         */
        suspend fun developerToken(): String?

        suspend fun completeSignIn(userToken: String): Boolean
    }

    private val appContext = context.applicationContext
    private val _endpoint = MutableStateFlow(PhoneServerEndpoint())
    internal val endpoint: PhoneServerEndpoint get() = _endpoint.value
    private val networkAddress = PhoneNetworkAddress(appContext) { lan ->
        _endpoint.value = _endpoint.value.copy(address = lan.host, networkConnected = lan.connected)
    }
    private var job: Job? = null
    private var appScope: CoroutineScope? = null
    private var listener: ServerSocket? = null
    private val pairingNonce = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })

    val port: Int get() = endpoint.port
    val running: Boolean get() = endpoint.phase == PhoneServerPhase.READY

    /** Full URL encoded in the TV QR code; empty until READY with a LAN address. */
    fun serverUrl(): String {
        val e = endpoint
        if (e.phase != PhoneServerPhase.READY || e.address.isEmpty()) return ""
        return "http://${e.address}:${e.port}"
    }

    /** Reactive URL for the QR dialog (network callback is async). */
    val serverUrlFlow: StateFlow<String> by lazy {
        _endpoint.map { e ->
            if (e.phase == PhoneServerPhase.READY && e.address.isNotEmpty())
                "http://${e.address}:${e.port}" else ""
        }.stateIn(appScope ?: throw IllegalStateException("boot() first"), kotlinx.coroutines.flow.SharingStarted.Eagerly, "")
    }

    /** Apple Music phone sign-in page, merged into the same 8080 admin surface. */
    val amLoginUrlFlow: StateFlow<String> by lazy {
        _endpoint.map { e ->
            if (e.phase == PhoneServerPhase.READY && e.address.isNotEmpty())
                "http://${e.address}:${e.port}/am" else ""
        }.stateIn(appScope ?: throw IllegalStateException("boot() first"), kotlinx.coroutines.flow.SharingStarted.Eagerly, "")
    }

    fun boot(scope: CoroutineScope) {
        appScope = scope
        start(scope)
    }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        appScope = scope
        _endpoint.value = PhoneServerEndpoint(phase = PhoneServerPhase.STARTING)
        networkAddress.start()
        job = scope.launch(Dispatchers.IO) {
            var socket: ServerSocket? = null
            try {
                val bound = PhoneServerSocket.bind()
                socket = bound
                listener = bound
                synchronized(this@PhoneConfigServer) {
                    if (!isActive) return@launch
                    _endpoint.value = PhoneServerEndpoint(
                        phase = PhoneServerPhase.READY,
                        port = bound.localPort,
                        address = _endpoint.value.address,
                        networkConnected = _endpoint.value.networkConnected,
                    )
                }
                while (isActive) {
                    val client = bound.accept()
                    launch { handle(client) }
                }
            } catch (t: Throwable) {
                _endpoint.value = _endpoint.value.copy(phase = PhoneServerPhase.FAILED)
            } finally {
                runCatching { socket?.close() }
                if (_endpoint.value.phase == PhoneServerPhase.READY) {
                    _endpoint.value = _endpoint.value.copy(phase = PhoneServerPhase.FAILED)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { listener?.close() }
        listener = null
        networkAddress.stop()
        _endpoint.value = PhoneServerEndpoint(phase = PhoneServerPhase.STOPPED)
    }

    private data class Head(val method: String, val target: String, val headers: Map<String, String>)

    private suspend fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val out = BufferedOutputStream(socket.getOutputStream())
            val head = readHeaders(input) ?: return
            val path = head.target.substringBefore('?')
            var body = ""
            if (head.method in listOf("POST", "PUT", "PATCH")) {
                if (head.headers["expect"].equals("100-continue", true)) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    out.flush()
                }
                body = readBody(input, head.headers)
            }
            val query = Uri.parse("http://localhost${head.target}")
            when {
                head.method == "GET" && path == "/" -> {
                    val note = when (query.getQueryParameter("import")) {
                        "ok" -> "导入完成，请在电视资料库中查看。"
                        "fail" -> "导入失败，请检查链接后重试。"
                        else -> ""
                    }
                    send(out, 200, "text/html", html(note))
                }
                head.method == "GET" && path == "/am" -> {
                    val bridge = appleMusic
                    if (bridge == null) {
                        send(out, 503, "text/html", amUnavailablePage("电视端账号服务未就绪。"))
                        return
                    }
                    if (bridge.signedIn()) {
                        send(out, 200, "text/html", amSignedInPage())
                        return
                    }
                    // v0.8: 令牌三级级联（打包 → 官网抓取）。都拿不到时渲染手动导入
                    // 令牌页（veil 书签方案兜底），登录入口永远可达。
                    val token = runCatching { bridge.developerToken() }.getOrNull()
                    if (token.isNullOrBlank()) {
                        send(out, 200, "text/html", amManualPage())
                        return
                    }
                    send(out, 200, "text/html", amLoginPage(token))
                }
                head.method == "POST" && path == "/am-token" -> {
                    val bridge = appleMusic
                    val origin = head.headers["origin"]
                    val authority = head.headers["host"]?.let { "http://$it" }
                    if (bridge == null || origin == null || origin != authority ||
                        head.headers["x-vmlx-pairing"] != pairingNonce
                    ) {
                        send(out, 403, "text/plain", "Request rejected")
                        return
                    }
                    val value = body.trim()
                    if (value.length !in 32..8_192 || value.any { it.code !in 33..126 }) {
                        send(out, 400, "text/plain", "Invalid authorization result")
                        return
                    }
                    val ok = runCatching { bridge.completeSignIn(value) }.getOrDefault(false)
                    if (ok) send(out, 200, "text/plain", "OK")
                    else send(out, 400, "text/plain", "Invalid authorization result")
                }
                head.method == "GET" && path == "/status" -> {
                    val am = appleMusic?.signedIn() == true
                    send(out, 200, "application/json", """{"running":$running,"url":"${serverUrl()}","appleMusicSignedIn":$am}""")
                }
                head.method == "POST" && path == "/playlist-import" -> {
                    val raw = parseField(body, "url").ifBlank { parseField(body, "text") }
                    val result = kotlinx.coroutines.runBlocking {
                        runCatching { importer.import(raw, parseField(body, "name").ifBlank { null }) }
                            .getOrNull()
                    }
                    val ok = result is PlaylistImporter.Result.Ok
                    redirect(out, "/?import=" + if (ok) "ok" else "fail")
                }
                else -> send(out, 404, "text/plain", "Not found")
            }
            out.flush()
        } catch (t: Throwable) {
            // A single bad request must never kill the accept loop.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHeaders(input: BufferedInputStream): Head? {
        val line = readAsciiLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readAsciiLine(input) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        return Head(parts[0], parts[1], headers)
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.toInt()) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): String {
        val bytes = if (headers["transfer-encoding"].equals("chunked", true)) {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val sizeLine = readAsciiLine(input)?.trim().orEmpty()
                if (sizeLine.isEmpty()) continue
                val size = sizeLine.substringBefore(';').toIntOrNull(16) ?: 0
                if (size == 0) {
                    readAsciiLine(input)
                    break
                }
                val buf = ByteArray(size)
                var off = 0
                while (off < size) {
                    val n = input.read(buf, off, size - off)
                    if (n < 0) break
                    off += n
                }
                out.write(buf, 0, off)
                readAsciiLine(input)
            }
            out.toByteArray()
        } else {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 0) {
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(buf, off, len - off)
                    if (n < 0) break
                    off += n
                }
                buf.copyOf(off)
            } else ByteArray(0)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun parseField(body: String, field: String): String {
        val raw = body.split("&").find { it.startsWith("$field=") }
            ?.removePrefix("$field=")?.replace("+", " ") ?: return ""
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = mapOf(
            200 to "OK", 302 to "Found", 303 to "See Other",
            400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            503 to "Service Unavailable"
        )[code] ?: "Error"
        val resp = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n$extra\r\n"
        out.write(resp.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
    }

    private fun redirect(out: OutputStream, to: String) =
        send(out, 303, "text/plain", "", "Location: $to\r\n")

    private fun html(note: String): String = """<!DOCTYPE html>
<html lang=zh><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>vmlx TV - 管理</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#101014;color:#eee;font:16px/1.5 -apple-system,system-ui,sans-serif}
.wrap{max-width:560px;margin:0 auto;padding:24px 18px 60px}
h1{font-size:22px;margin:0 0 6px}p.sub{color:#9a9aa2;font-size:14px;margin:0 0 22px}
.card{background:#1c1c20;border:1px solid #2c2c32;border-radius:14px;padding:18px;margin-bottom:16px}
h2{font-size:16px;margin:0 0 10px}label{display:block;color:#9a9aa2;font-size:13px;margin:12px 0 6px}
input,textarea{width:100%;background:#26262c;color:#fff;border:1px solid #3a3a42;border-radius:10px;
padding:11px 12px;font:15px/1.4 system-ui;outline:none}input:focus,textarea:focus{border-color:#fa233b}
textarea{min-height:110px;resize:vertical}
button{width:100%;background:#fa233b;color:#fff;border:0;border-radius:10px;padding:13px;font-size:16px;font-weight:600;margin-top:16px}
.btn{display:block;text-align:center;background:#fa233b;color:#fff;border-radius:10px;padding:13px;font-size:16px;font-weight:600;margin-top:16px;text-decoration:none}
.note{background:#173a20;color:#9be3a2;border-radius:10px;padding:10px 12px;font-size:14px;margin-bottom:14px}
</style></head><body><div class=wrap>
<h1>vmlx TV 管理</h1>
<p class=sub>在手机上完成资料库导入与 Apple Music 登录。</p>
${if (note.isNotBlank()) "<div class=note>$note</div>" else ""}
${if (appleMusic != null) """
<div class=card><h2>Apple Music</h2>
<p class=sub style="margin:0">登录只用于刷新电视上的「现在收听」数据。播放仍走落雪音源，播放记录不会回传 Apple Music。</p>
${if (appleMusic.signedIn()) "<div class=note>已登录 Apple Music</div><a class=btn href=/am>重新登录</a>"
else "<a class=btn href=/am>登录 Apple Music</a>"}
</div>""" else ""}
<div class=card><h2>导入到资料库</h2>
<form method=POST action=/playlist-import>
<label for=url>歌单链接</label>
<input id=url name=url type=url autocomplete=off autocapitalize=off spellcheck=false placeholder="https://y.qq.com/n/ryqq/playlist/7338005124">
<label for=text>或粘贴歌曲列表（可选）</label>
<textarea id=text name=text autocomplete=off autocapitalize=off spellcheck=false placeholder="晴天 - 周杰伦&#10;富士山下 - 陈奕迅"></textarea>
<label for=name>歌单名称（可选）</label>
<input id=name name=name type=text autocomplete=off placeholder="我的歌单">
<button type=submit>导入到资料库</button>
</form></div>
</div></body></html>"""

    private fun amPageStyle(): String = """
        html{color-scheme:dark}*{box-sizing:border-box}
        body{margin:0;min-height:100svh;display:grid;place-items:center;
          background:radial-gradient(at 40% 0%,#391326,#0b0b0e 65%);color:#f5f5f7;
          font:17px -apple-system,BlinkMacSystemFont,sans-serif}
        main{width:min(88vw,420px);padding:40px 0}
        h1{font-size:32px;letter-spacing:-.7px;line-height:1.2}
        p{line-height:1.6;color:#aaa}
        button{width:100%;padding:17px;border:0;border-radius:18px;background:#fa355a;color:white;
          font:600 17px -apple-system,BlinkMacSystemFont,sans-serif;margin:20px 0}
        button:disabled{opacity:.45}
        #status{min-height:80px}
        a{color:#ff6c85}
        .btn{display:block;text-align:center;background:#fa355a;color:#fff;border-radius:18px;
          padding:17px;margin:20px 0;text-decoration:none;font-weight:600}
    """.trimIndent()

    private fun amLoginPage(developerToken: String): String {
        val tokenLiteral = JSONObject.quote(developerToken)
        val nonceLiteral = JSONObject.quote(pairingNonce)
        return """<!doctype html><html lang="zh-Hans"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>登录 Apple Music</title>
<style>${amPageStyle()}</style></head>
<body><main><h1>在手机上登录 Apple Music</h1>
<p>使用 Apple 登录。完成后电视会自动刷新「现在收听」，请保持此页开启。</p>
<button id="signin" disabled>准备中…</button>
<p id="status" role="status"></p>
<p><a href="/">返回管理页</a></p>
</main>
<script>
var button=document.getElementById('signin'), status=document.getElementById('status'), music=null, sending=false;
var returnedFromApple=new RegExp('^#[A-Za-z0-9+/]{200,}={0,2}$').test(window.location.hash);
function failed(){button.disabled=false;button.textContent='重试';status.textContent='登录未完成，请重试。若未开启 Apple 页面，请允许弹出式窗口。';}
function deliver(value){
  if(sending)return;
  if(typeof value!=='string'||value.length<32){failed();return;}
  sending=true;button.disabled=true;
  fetch('/am-token',{method:'POST',headers:{'Content-Type':'text/plain','X-VMLX-Pairing':$nonceLiteral},body:value,cache:'no-store'})
    .then(function(response){
      if(!response.ok){sending=false;failed();return;}
      status.textContent='登录结果已送到电视，请查看电视上的完成状态。';
      button.remove();
    })
    .catch(function(){sending=false;failed();});
}
document.addEventListener('musickitloaded',function(){
  try {
    Promise.resolve(MusicKit.configure({developerToken:$tokenLiteral,app:{name:'vmlx TV',build:'vmlx'}}))
      .then(function(){
        music=MusicKit.getInstance();
        button.disabled=false;button.textContent='继续登录';
        if(returnedFromApple&&music.musicUserToken)deliver(music.musicUserToken);
      }).catch(failed);
  } catch(e) { failed(); }
});
button.onclick=function(){
  if(!music){window.location.reload();return;}
  if(sending)return;
  button.disabled=true;status.textContent='请在 Apple 登录页完成授权。';
  try{
    Promise.resolve(music.authorize()).then(function(value){
      deliver(typeof value==='string'?value:music.musicUserToken);
    },failed);
  }catch(e){failed();}
};
</script>
<script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js" onerror="failed()"></script>
</body></html>"""
    }

    private fun amSignedInPage(): String = """<!doctype html><html lang="zh-Hans"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Apple Music 已登录</title>
<style>${amPageStyle()}</style></head>
<body><main><h1>Apple Music 已登录</h1>
<p>电视上的「现在收听」会使用这个账号的数据。播放仍走落雪音源，播放记录不会回传 Apple Music。</p>
<a class="btn" href="/am">重新登录</a><p><a href="/">返回管理页</a></p>
</main></body></html>"""

    /**
     * v0.8: 手动导入令牌页（MusicKit 一键登录拿不到开发者令牌时的兜底，veil.music
     * 书签方案同款思路）：用户在手机浏览器登录 Apple Music 网页版，运行登录代码
     * （bookmarklet）提取 musicUserToken，回到本页粘贴提交。
     */
    private fun amManualPage(): String {
        val nonceLiteral = JSONObject.quote(pairingNonce)
        // 注意：JS 字符串里的正则 \\s 在浏览器解析后是 \s；书签代码内层只有单引号，
        // 外层用双引号包裹（Kotlin 三引号原样输出）。
        val bookmarklet = "javascript:(function(){var t='';try{t=window.MusicKit.getInstance().musicUserToken||''}catch(e){}if(!t){var m=document.cookie.match(/(?:^|;\\s*)media-user-token=([^;]*)/);if(m){try{t=decodeURIComponent(m[1])}catch(e){}}}if(!t){alert('未找到令牌：请先登录 Apple Music 网页版，打开资料库后再运行本代码');return}var ok=false;try{var i=document.createElement('textarea');i.value=t;document.body.appendChild(i);i.select();ok=document.execCommand('copy');document.body.removeChild(i)}catch(e){}if(ok){alert('令牌已复制到剪贴板，请回到电视管理页粘贴提交')}else{window.prompt('长按全选复制令牌：',t)}})()"
        return """<!doctype html><html lang="zh-Hans"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>登录 Apple Music</title>
<style>
html{color-scheme:dark}*{box-sizing:border-box}
body{margin:0;min-height:100svh;background:radial-gradient(at 40% 0%,#391326,#0b0b0e 65%);color:#f5f5f7;
  font:16px -apple-system,BlinkMacSystemFont,sans-serif}
main{width:min(92vw,480px);margin:0 auto;padding:32px 0 60px}
h1{font-size:26px;letter-spacing:-.5px;line-height:1.25}
p{line-height:1.6;color:#aaa;font-size:15px}
ol{line-height:1.7;color:#ccc;font-size:15px;padding-left:20px}
a{color:#ff6c85}
textarea{width:100%;height:74px;background:#1d1d22;color:#eee;border:1px solid #3a3a42;border-radius:10px;
  padding:10px;font:12px monospace;outline:none;resize:none;word-break:break-all}
textarea:focus{border-color:#fa355a}
label{display:block;color:#9a9aa2;font-size:13px;margin:14px 0 6px}
button{width:100%;padding:14px;border:0;border-radius:14px;background:#fa355a;color:white;
  font:600 16px -apple-system,BlinkMacSystemFont,sans-serif;margin:12px 0}
button.ghost{background:#3a2a30;color:#ffb0ba}
#status{min-height:24px;color:#9be3a2;font-size:14px}
hr{border:0;border-top:1px solid #2c2c32;margin:18px 0}
</style></head>
<body><main>
<h1>登录 Apple Music（手动导入令牌）</h1>
<p>电视暂时拿不到一键登录的开发者令牌，可按下面步骤手动导入，只需做一次。</p>
<ol>
<li>在本手机浏览器打开 <a href="https://music.apple.com/cn/browse">music.apple.com</a>，登录 Apple ID 并进入「资料库」页面。</li>
<li>点「复制登录代码」，在浏览器里新建一个书签，把书签地址替换成这段代码（手机 Safari：任意页面加书签 → 编辑书签 → 粘贴网址）。</li>
<li>回到已登录的 music.apple.com 页面，从书签栏运行该书签，令牌会自动复制到剪贴板。</li>
<li>回到本页，把令牌粘贴到下面输入框，点「提交到电视」。</li>
</ol>
<button class="ghost" id="copycode">复制登录代码</button>
<textarea id="code" readonly></textarea>
<hr>
<label for="token">登录令牌（eyJ 开头的长串）</label>
<textarea id="token" placeholder="粘贴从 Apple Music 网页版复制的令牌"></textarea>
<button id="submit">提交到电视</button>
<p id="status" role="status"></p>
<p><a href="/">返回管理页</a></p>
</main>
<script>
var CODE = "$bookmarklet";
var codeBox = document.getElementById('code');
codeBox.value = CODE;
document.getElementById('copycode').onclick = function () {
  var done = false;
  try {
    var i = document.createElement('textarea');
    i.value = CODE; document.body.appendChild(i); i.select();
    done = document.execCommand('copy'); document.body.removeChild(i);
  } catch (e) {}
  if (!done && navigator.clipboard) { navigator.clipboard.writeText(CODE); done = true; }
  this.textContent = done ? '已复制，去浏览器新建书签粘贴' : '复制失败，请长按选中下面文本框手动复制';
};
document.getElementById('submit').onclick = function () {
  var value = (codeBox.ownerDocument.getElementById('token').value || '').trim();
  var status = document.getElementById('status');
  if (value.length < 32) { status.style.color = '#ffb0ba'; status.textContent = '请先粘贴登录令牌。'; return; }
  status.style.color = '#9be3a2'; status.textContent = '正在提交到电视…';
  fetch('/am-token', { method: 'POST', headers: { 'Content-Type': 'text/plain', 'X-VMLX-Pairing': $nonceLiteral }, body: value, cache: 'no-store' })
    .then(function (r) {
      if (r.ok) { status.textContent = '令牌已提交，请查看电视上的完成状态。'; }
      else { status.style.color = '#ffb0ba'; status.textContent = '电视拒绝了这份令牌，请确认是从 Apple Music 网页版复制的新令牌后重试。'; }
    })
    .catch(function () { status.style.color = '#ffb0ba'; status.textContent = '提交失败，请检查与电视的网络连接后重试。'; });
};
</script>
</body></html>"""
    }

    private fun amUnavailablePage(message: String): String = """<!doctype html><html lang="zh-Hans"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>无法登录 Apple Music</title>
<style>${amPageStyle()}</style></head>
<body><main><h1>无法登录 Apple Music</h1>
<p>$message</p><p><a href="/">返回管理页</a></p>
</main></body></html>"""
}

