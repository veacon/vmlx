package dev.amatv.luoxue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * 平台搜索层：Apple 侧只给歌名/歌手，这里补上平台曲目 ID。
 *
 * 为什么必须自建：落雪自定义源协议只有 musicUrl（ID -> 直链），不含搜索。
 * P0 实测：只给歌名时 15 个脚本仅 1 个可用且只出 128k；补上 ID 后 7 个脚本可出 FLAC。
 */
class PlatformSearch(private val http: OkHttpClient) {

    /**
     * 三个平台并发搜索：彼此没有依赖，串行等于把首曲解析平白拖长 2 个 RTT
     * （任一平台超时还会把后面两个一起拖住）。失败的平台独立降级为空列表。
     *
     * v0.4：query 使用 TrackMatcher.canonicalArtist 做「规范艺人」归一化
     * （主艺人提取 → 别名映射 → 繁转简），避免 Apple 元数据 "Jay Chou" /
     * 「周杰倫」 搜不到国内曲库，这是「周杰伦《落雪》命中不稳定」的结构性主因。
     */
    /**
     * v0.7：搜索结果短缓存。同一首歌在以下场景会重复搜索三平台：
     *  - 手动换源（excludeScript 强制重解析）；
     *  - 直链过期重解析；
     *  - 预取命中后真正播放。
     * 每次都是 3 个网络 RTT，是"切换音源等待长"的隐形大头。短 TTL 缓存
     * 只缓存**成功且非空**的结果，失败不缓存（避免一次抖动被记住）；容量
     * 有界，按访问顺序淘汰，避免长时间播放积累。
     */
    private data class CacheEntry(val at: Long, val items: List<Candidate>)
    private val queryCache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > 48
    }

    @Synchronized
    private fun cachedQuery(key: String): List<Candidate>? {
        val e = queryCache[key] ?: return null
        if (System.currentTimeMillis() - e.at > QUERY_TTL_MS) {
            queryCache.remove(key)
            return null
        }
        return e.items
    }

    @Synchronized
    private fun rememberQuery(key: String, items: List<Candidate>) {
        if (items.isNotEmpty()) queryCache[key] = CacheEntry(System.currentTimeMillis(), items)
    }

    suspend fun searchAll(title: String, artist: String): List<Candidate> {
        val q = listOf(title.trim(), TrackMatcher.canonicalArtist(artist))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (q.isEmpty()) return emptyList()
        cachedQuery(q)?.let { return it }
        val result = coroutineScope {
            val wy = async(Dispatchers.IO) { runCatching { netease(q) }.getOrDefault(emptyList()) }
            val tx = async(Dispatchers.IO) { runCatching { qq(q) }.getOrDefault(emptyList()) }
            val kg = async(Dispatchers.IO) { runCatching { kugou(q) }.getOrDefault(emptyList()) }
            wy.await() + tx.await() + kg.await()
        }
        rememberQuery(q, result)
        return result
    }

    /** 跨语言曲名兜底：只按规范艺人搜，匹配层再用时长认歌。同样并发。 */
    suspend fun searchByArtist(artist: String, limit: Int = 12): List<Candidate> {
        val q = TrackMatcher.canonicalArtist(artist)
        if (q.isEmpty()) return emptyList()
        return coroutineScope {
            val wy = async(Dispatchers.IO) { runCatching { netease(q, limit) }.getOrDefault(emptyList()) }
            val tx = async(Dispatchers.IO) { runCatching { qq(q, limit) }.getOrDefault(emptyList()) }
            val kg = async(Dispatchers.IO) { runCatching { kugou(q, limit) }.getOrDefault(emptyList()) }
            wy.await() + tx.await() + kg.await()
        }
    }

    // v0.4：搜索侧已改用 TrackMatcher.canonicalArtist（别名 + 繁转简），
    // 原 primaryArtist 已移除；probeSource 等诊断路径走 LxPlayback 内嵌的 searchAll，
    // 同样自动受益。

    private fun get(url: String, referer: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    // ── 网易云 ──────────────────────────────────────────────────────────────
    fun netease(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://music.163.com/api/search/get/web".toHttpUrl().newBuilder()
            .addQueryParameter("s", q)
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", "0")
            .build().toString()
        val root = JSONObject(get(url, "https://music.163.com/"))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val artists = s.optJSONArray("artists")
            val artistName = (0 until (artists?.length() ?: 0))
                .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                .joinToString(" / ")
            Candidate(
                source = "wy",
                id = s.optString("id"),
                title = s.optString("name"),
                artist = artistName,
                album = s.optJSONObject("album")?.optString("name").orEmpty(),
                durationSec = (s.optLong("duration") / 1000).toInt(),
                extra = mapOf("songmid" to s.optString("id"), "songId" to s.optString("id")),
            )
        }
    }

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────
    fun qq(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("w", q)
            .addQueryParameter("format", "json")
            .addQueryParameter("n", limit.toString())
            .addQueryParameter("p", "1")
            .build().toString()
        val root = JSONObject(get(url, "https://y.qq.com/"))
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val singers = s.optJSONArray("singer")
            val artistName = (0 until (singers?.length() ?: 0))
                .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
                .joinToString(" / ")
            Candidate(
                source = "tx",
                id = s.optString("songmid"),
                title = s.optString("songname"),
                artist = artistName,
                album = s.optString("albumname"),
                durationSec = s.optInt("interval"),
                extra = mapOf(
                    "songmid" to s.optString("songmid"),
                    "strMediaMid" to s.optString("media_mid"),
                    "mid" to s.optString("songmid"),
                ),
            )
        }
    }

    // ── 酷狗 ────────────────────────────────────────────────────────────────
    fun kugou(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://songsearch.kugou.com/song_search_v2".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", q)
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", limit.toString())
            .build().toString()
        val root = JSONObject(get(url, "https://www.kugou.com/"))
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            Candidate(
                source = "kg",
                id = s.optString("FileHash"),
                title = s.optString("SongName").replace(Regex("<[^>]+>"), ""),
                artist = s.optString("SingerName").replace(Regex("<[^>]+>"), ""),
                album = s.optString("AlbumName"),
                durationSec = s.optInt("Duration"),
                extra = mapOf(
                    "hash" to s.optString("FileHash"),
                    "album_id" to s.optString("AlbumID"),
                    "album_audio_id" to s.optString("AlbumAudioID"),
                ),
            )
        }
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        /** 搜索结果缓存有效期：覆盖一次播放会话，换源/重试基本零网络开销。 */
        private const val QUERY_TTL_MS = 10 * 60_000L
    }
}
