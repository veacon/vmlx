package dev.amatv.integration

import android.util.Log
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 第三方歌词源（网易云 / QQ / 酷狗 / 酷我 / 咪咕）——veil 同源移植（v0.2）。
 *
 * 为什么需要它：am_atv 的 Apple TTML 歌词（AppleMusicLyricsClient）必须带
 * media-user-token，未登录一律 401 → 歌词空白。v0.2 起歌词统一走本层，
 * 用「标题 + 艺人 + 时长」在五大平台匹配同名曲目，登录态同样生效
 * （Apple TTML 留作后续登录态增强）。
 *
 * 设计取舍（与 veil 一致）：
 *  - 独立 OkHttpClient：绝不能带上 Apple 的 Authorization / Cookie 拦截器。
 *  - 两段式（搜索 → 取词）：平台搜索接口返回的候选里有时长和艺人，能有效
 *    避免「同名不同曲」拿到错词。
 *  - 只依赖公开接口（不带 cookie / 不做登录），失败就交给下一个源。
 *  - 顺序即优先级：网易云 → QQ → 酷狗 → 酷我 → 咪咕，第一个命中的即返回。
 */
class ThirdPartyLyrics {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val TAG = "ThirdPartyLyrics"
        const val MIN_SCORE = 42.0
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }

    private data class Hit(
        val id: String,
        val title: String,
        val artists: String,
        val durationSec: Int,
        val score: Double,
    )

    // ── 对外 ────────────────────────────────────────────────────────────────

    /** 歌词 + 命中的来源（"网易云" / "QQ音乐" / "酷狗" / "酷我" / "咪咕"）。供「歌词来源」展示。 */
    data class Result(val lines: List<LyricLine>, val source: String)

    /** 级联候选顺序；「歌词换源」面板用它展示可跳过的源。 */
    val sourceLabels: List<String> = listOf("网易云", "QQ音乐", "酷狗", "酷我", "咪咕")

    /**
     * 按优先级级联：网易云 → QQ → 酷狗 → 酷我 → 咪咕，返回第一条可用歌词；都没有则空。
     *
     * [excludeSources] 是「歌词换源」要跳过的源标签集合（如 {"网易云"}），命中后从下一个源开始试，
     * 避免换源又落回同一个平台。为空则正常全级联。
     */
    suspend fun fetch(
        title: String,
        artist: String,
        durationSec: Long,
        excludeSources: Set<String> = emptySet(),
    ): Result {
        if (title.isBlank()) return Result(emptyList(), "")
        if ("网易云" !in excludeSources) {
            netease(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "网易云") }
        }
        if ("QQ音乐" !in excludeSources) {
            qq(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "QQ音乐") }
        }
        if ("酷狗" !in excludeSources) {
            kugou(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "酷狗") }
        }
        if ("酷我" !in excludeSources) {
            kuwo(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "酷我") }
        }
        if ("咪咕" !in excludeSources) {
            migu(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "咪咕") }
        }
        return Result(emptyList(), "")
    }

    // ── 请求小工具 ──────────────────────────────────────────────────────────

    /** 同步 GET（调用方已在 Dispatchers.IO 内）。失败返回 null，不向上抛。 */
    private fun getBody(url: String, referer: String): String? = try {
        http.newCall(
            Request.Builder().url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", referer)
                .build()
        ).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ── 网易云 ──────────────────────────────────────────────────────────────

    private suspend fun netease(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val url = "https://music.163.com/api/search/get/web?type=1&limit=10&s=" + enc(q)
                val body = getBody(url, "https://music.163.com/") ?: return@withContext emptyList()
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
                    ?: return@withContext emptyList()
                val hits = (0 until songs.length()).mapNotNull { i ->
                    val o = songs.optJSONObject(i) ?: return@mapNotNull null
                    val artists = o.optJSONArray("artists")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optJSONObject(it)?.optString("name").orEmpty() }
                    }.orEmpty()
                    val duration = (o.optLong("duration", 0L) / 1000L).toInt()
                    Hit(
                        id = o.optString("id"),
                        title = o.optString("name"),
                        artists = artists,
                        durationSec = duration,
                        score = score(title, artist, durationSec, o.optString("name"), artists, duration),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lyricUrl = "https://music.163.com/api/song/lyric?id=${h.id}&lv=1&kv=1&tv=-1"
                    val lrc = getBody(lyricUrl, "https://music.163.com/")
                        ?.let { JSONObject(it).optJSONObject("lrc")?.optString("lyric") }
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "netease hit id=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "netease failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────

    private suspend fun qq(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
                    "?format=json&n=10&cr=1&g_tk=5381&w=" + enc(q)
                val body = getBody(url, "https://y.qq.com/") ?: return@withContext emptyList()
                val list = JSONObject(jsonOnly(body)).optJSONObject("data")
                    ?.optJSONObject("song")?.optJSONArray("list") ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val singers = o.optJSONArray("singer")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optJSONObject(it)?.optString("name").orEmpty() }
                    }.orEmpty()
                    val duration = o.optInt("interval", 0)
                    Hit(
                        id = o.optString("songmid"),
                        title = o.optString("songname"),
                        artists = singers,
                        durationSec = duration,
                        score = score(title, artist, durationSec, o.optString("songname"), singers, duration),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                        "?songmid=${h.id}&format=json&nobase64=1&g_tk=5381"
                    val lrc = getBody(lyricUrl, "https://y.qq.com/portal/player.html")
                        ?.let { JSONObject(jsonOnly(it)).optString("lyric") }
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "qq hit mid=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "qq failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    // ── 酷狗 ────────────────────────────────────────────────────────────────

    private suspend fun kugou(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                // 搜索拿 hash（歌词需要 hash 参与 accesskey 计算）
                val searchUrl = "https://songsearch.kugou.com/song_search_v2?keyword=" + enc(q) +
                    "&page=1&pagesize=8&platform=WebFilter"
                val body = getBody(searchUrl, "https://www.kugou.com/")
                    ?: return@withContext emptyList()
                val list = JSONObject(body).optJSONObject("data")?.optJSONArray("lists")
                    ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("SongName").replace(Regex("<[^>]+>"), "")
                    val singers = o.optString("SingerName").replace(Regex("<[^>]+>"), "")
                    Hit(
                        id = o.optString("FileHash"),
                        title = name,
                        artists = singers,
                        durationSec = o.optInt("Duration"),
                        score = score(title, artist, durationSec, name, singers, o.optInt("Duration")),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = kugouLyric(h.id, h.durationSec) ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "kugou hit hash=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "kugou failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    /** 酷狗歌词：search 拿 id+accesskey → download 拿 base64 的 lrc。 */
    private fun kugouLyric(hash: String, durationSec: Int): String? {
        val key = kugouKey(hash, durationSec)
        val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=&hash=$hash&duration=${durationSec * 1000}&accesskey=$key"
        val candidates = getBody(searchUrl, "https://www.kugou.com/")
            ?.let { JSONObject(it).optJSONArray("candidates") } ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val id = first.optString("id")
        val accesskey = first.optString("accesskey")
        if (id.isBlank() || accesskey.isBlank()) return null
        val dl = getBody(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8",
            "https://www.kugou.com/",
        ) ?: return null
        val content = JSONObject(dl).optString("content").takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /** 酷狗 accesskey：md5(hash + "kgcloud") 的小写 hex。 */
    private fun kugouKey(hash: String, durationSec: Int): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest("${hash}kgcloud".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── 酷我 ────────────────────────────────────────────────────────────────

    private suspend fun kuwo(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val searchUrl = "https://search.kuwo.cn/r.s?all=" + enc(q) +
                    "&ft=music&rformat=json&encoding=utf8&rn=10&vipver=MUSIC_8.7.7.0&plat=pc"
                val body = getBody(searchUrl, "https://www.kuwo.cn/")
                    ?: return@withContext emptyList()
                val list = JSONObject(jsonOnly(body)).optJSONArray("abslist")
                    ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("NAME")
                    val singers = o.optString("ARTIST")
                    val dur = o.optInt("DURATION")
                    Hit(
                        id = o.optString("MUSICRID").removePrefix("MUSIC_"),
                        title = name,
                        artists = singers,
                        durationSec = dur,
                        score = score(title, artist, durationSec, name, singers, dur),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lines = kuwoLyric(h.id) ?: continue
                    Log.i(TAG, "kuwo hit rid=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "kuwo failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    /** 酷我歌词：songinfoandlrc 返回 lrclist（time + lineLyric）。 */
    private fun kuwoLyric(rid: String): List<LyricLine>? {
        val lrclist = getBody(
            "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$rid&httpsStatus=1",
            "https://m.kuwo.cn/",
        )?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("lrclist") } ?: return null
        val out = mutableListOf<Pair<Long, String>>()
        for (i in 0 until lrclist.length()) {
            val o = lrclist.optJSONObject(i) ?: continue
            val t = o.optString("time").toDoubleOrNull() ?: continue
            val line = o.optString("lineLyric").takeIf { it.isNotBlank() } ?: continue
            out.add(((t * 1000).toLong()) to line)
        }
        if (out.isEmpty()) return null
        out.sortBy { it.first }
        return out.mapIndexed { i, (startMs, text) ->
            val endMs = if (i + 1 < out.size) out[i + 1].first else startMs + 5000
            lyricLine(startMs, endMs, text)
        }
    }

    // ── 咪咕 ────────────────────────────────────────────────────────────────

    private suspend fun migu(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val searchUrl = "https://m.music.migu.cn/migu/remoting/scr_search_tag?pgc=1&rows=10" +
                    "&keyword=" + enc(q) + "&type=2"
                val body = getBody(searchUrl, "https://m.music.migu.cn/")
                    ?: return@withContext emptyList()
                val list = JSONObject(body).optJSONArray("musics")
                    ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("songName")
                    val singers = o.optJSONArray("singerName")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optString(it) }
                    }.orEmpty()
                    val cpid = o.optString("copyrightId")
                    Hit(
                        id = cpid,
                        title = name,
                        artists = singers,
                        durationSec = 0,
                        score = score(title, artist, durationSec, name, singers, 0),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = getBody(
                        "https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId=" + enc(h.id),
                        "https://music.migu.cn/",
                    )?.let {
                        JSONObject(it).optString("lyric").takeIf { s -> s.isNotBlank() }
                            ?: JSONObject(it).optString("lrcText").takeIf { s -> s.isNotBlank() }
                    } ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "migu hit cpid=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "migu failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    // ── 匹配打分 ────────────────────────────────────────────────────────────

    private fun score(
        wantTitle: String, wantArtist: String, wantDur: Long,
        gotTitle: String, gotArtists: String, gotDur: Int,
    ): Double {
        val t = similarity(norm(wantTitle), norm(gotTitle))
        val a = similarity(norm(wantArtist), norm(gotArtists))
        val d = when {
            wantDur <= 0L || gotDur <= 0 -> 0.5
            kotlin.math.abs(wantDur - gotDur) <= 2 -> 1.0
            kotlin.math.abs(wantDur - gotDur) <= 5 -> 0.7
            kotlin.math.abs(wantDur - gotDur) <= 12 -> 0.35
            else -> 0.0
        }
        // 标题是硬指标，艺人/时长只在标题接近时才加权
        return (t * 60.0) + (a * 25.0) + (d * 15.0)
    }

    /** 去掉括号内的版本说明、空格与标点，小写化。 */
    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("""[（(\[【].*?[）)\]】]"""), "")
        .replace(Regex("""[\s\-_·・.,，。!！?？'"“”‘’]"""), "")
        .trim()

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.85
        return dice(a, b)
    }

    /** 字符二元组 Dice 系数，对中英文都还算稳。 */
    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        var inter = 0
        val pool = gb.toMutableMap()
        for ((g, n) in ga) {
            val have = pool[g] ?: 0
            if (have > 0) {
                val use = minOf(n, have)
                inter += use
                pool[g] = have - use
            }
        }
        return 2.0 * inter / (a.length - 1 + b.length - 1)
    }

    private fun grams(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until s.length - 1) {
            val g = s.substring(i, i + 2)
            m[g] = (m[g] ?: 0) + 1
        }
        return m
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** QQ 的 fcg 接口偶尔套一层 callback(...)，截出纯 JSON。 */
    private fun jsonOnly(body: String): String {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else body
    }
}

/** LRC → am_atv 词级 LyricLine。行内整个文本作为一个 timed word（Line 级时间轴）。 */
internal fun lyricLine(startMs: Long, endMs: Long, text: String): LyricLine = LyricLine(
    words = listOf(
        LyricWord(
            // v0.2：本项目只有简体版，第三方歌词正文统一繁转简。
            text = dev.amatv.i18n.TraditionalChinese.toSimplified(text),
            startMs = startMs,
            endMs = endMs.coerceAtLeast(startMs + 1L),
            hasTrailingWhitespace = false,
        ),
    ),
    lineStartMs = startMs,
    lineEndMs = endMs.coerceAtLeast(startMs + 1L),
    joinWithSpaces = false,
)

/** LRC → LyricLine 列表。与 Apple 通道共用同一套时间轴语义。 */
internal object LyricsLrc {

    private val tagRe = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /**
     * 头部元信息行（v0.2 过滤）：网易云/QQ 等平台的 LRC 前 10~30 秒常是
     * 「作词 : X」「曲：Y」「编曲：Z」「制作人：…」这类制作人员名单，
     * 带时间戳会被当歌词显示（用户反馈的「周杰伦...」即此类行）。
     * 只过滤明确带标签冒号的短行，避免误伤真实歌词。
     */
    private val creditLineRe = Regex(
        """^[\s\[]*(?:作词|作曲|编曲|制作人|监制|出品|发行|统筹|企划|录音|混音|母带|和声|合声|吉他|贝斯|鼓|弦乐|键盘|人声|后期|OP|SP|词|曲|原唱|演唱)\s*[:：].{0,60}$""",
        RegexOption.IGNORE_CASE,
    )

    /** 歌曲标题行，如「晴天 - 周杰伦 (Jay Chou)」；只在非常靠前的行生效。 */
    private val titleLineRe = Regex("""^.+?\s+[-–—]\s+.+?(?:\s*\([^)]*\))?$""")

    /** 纯符号/省略号行（"......"、"* * *"、"— —" 等），无演唱内容。 */
    private val punctOnlyRe = Regex("""^[\s.…·•\-—–_*~～、。，,;；:：!！?？'"“”‘’()（）\[\]【】]+$""")

    /** 过滤 [ti:] / [ar:] / [offset:] 这类元信息行，只保留带时间戳的正文。 */
    fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<Pair<Long, String>>()
        var seenContent = false
        for (raw in lrc.lines()) {
            val text = raw.replace(tagRe, "").trim()
            if (text.isEmpty()) continue
            if (!tagRe.containsMatchIn(raw)) continue
            if (punctOnlyRe.matches(text)) continue
            if (creditLineRe.matches(text)) continue
            // 标题行只在第一句真实歌词出现前过滤，避免误伤歌词正文里的 "A - B"。
            if (!seenContent && titleLineRe.matches(text)) continue
            seenContent = true
            for (m in tagRe.findAll(raw)) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                out.add((min * 60000 + sec * 1000 + frac) to text)
            }
        }
        out.sortBy { it.first }
        return out.mapIndexed { i, (startMs, text) ->
            val endMs = if (i + 1 < out.size) out[i + 1].first else startMs + 5000
            lyricLine(startMs, endMs, text)
        }
    }
}
