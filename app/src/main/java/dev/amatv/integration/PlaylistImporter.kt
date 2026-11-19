package dev.amatv.integration

import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v0.6 playlist importer: parses QQ Music / Netease / Kugou public playlists (or a
 * plain-text song list) into local [Track].
 *
 * Ported from veil.music PlaylistImporter (same approach as GoMusic /
 * music-api-server / musicapi; no login required):
 *  - QQ: fcg_v8_playlist_cp.fcg; Netease: api/v6/playlist/detail;
 *    Kugou: mobilecdn.kugou.com/api/v3/special.
 *
 * Imported [Track] carries no Apple id (id is an imp-* form); playback resolves it
 * via the LX source engine by title + artist + duration. Uses a standalone
 * OkHttpClient (no Apple Auth/Cookie interceptors).
 */
class PlaylistImporter(
    private val http: OkHttpClient,
    private val store: LocalLibraryStore,
) {

    sealed interface Result {
        data class Ok(
            val playlist: LocalLibraryStore.LocalPlaylist,
            val imported: Int,
            val total: Int,
        ) : Result

        data class Err(val message: String) : Result
    }

    private val client = http.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val urlRegex = Regex("https?://[\\S]+")
    private val bareIdRegex = Regex("[?&#](?:id|disstid|specialid)=(\\d{4,})")
    private val pathIdRegex = Regex("/(?:playlist|taoge|single|diss)/(\\d{4,})")

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0 Safari/537.36"

    suspend fun import(raw: String, preferredName: String? = null): Result = withContext(Dispatchers.IO) {
        val input = raw.trim()
        if (input.isEmpty()) return@withContext Result.Err("Paste a playlist link or song list")

        val url = urlRegex.find(input)?.value
        if (url == null) return@withContext importText(input, preferredName)

        try {
            val resolved = resolveShortUrl(url)
            val id = extractId(input) ?: extractId(resolved) ?: scrapeIdFromPage(resolved)
            if (id == null) {
                return@withContext Result.Err("Could not read a playlist ID; check it is a public playlist link")
            }
            when {
                isQQ(resolved) || isQQ(input) -> importQQ(id, preferredName)
                isNetease(resolved) || isNetease(input) -> importNetease(id, preferredName)
                isKugou(resolved) || isKugou(input) -> importKugou(id, preferredName)
                else -> Result.Err("Unsupported platform; QQ Music / Netease / Kugou are supported")
            }
        } catch (e: Exception) {
            Log.w(TAG, "import failed: ${e.message}")
            Result.Err("Import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // Platform implementations

    private fun importQQ(id: String, preferredName: String?): Result {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg" +
            "?id=$id&format=json&newsong=1&platform=yqq&hostUin=0&needNewCode=0&utf8=1"
        val body = get(url, mapOf("Referer" to "https://y.qq.com/")) ?: return Result.Err("QQ Music API returned no response")
        val root = JSONObject(body)
        val cdlist = root.optJSONObject("data")?.optJSONArray("cdlist")
        val cd = if (cdlist != null && cdlist.length() > 0) cdlist.optJSONObject(0) else null
            ?: return Result.Err("Playlist not found or private")
        val list = cd.optJSONArray("songlist") ?: return Result.Err("No public tracks in this playlist")
        val songs = ArrayList<Track>(list.length())
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val title = s.optString("name").ifBlank { s.optString("title") }
            if (title.isBlank()) continue
            val mid = s.optString("mid").ifBlank { s.optString("songmid") }
            val singers = s.optJSONArray("singer")
            val artists = buildList {
                if (singers != null) for (j in 0 until singers.length()) {
                    singers.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val album = s.optJSONObject("album")
            val albumName = album?.optString("name").orEmpty()
            val albumMid = album?.optString("mid").orEmpty()
            songs += importedTrack(
                id = "imp-qq-${mid.ifBlank { title.hashCode().toString() }}",
                title = title,
                artist = artists.joinToString(" / ").ifBlank { "Unknown artist" },
                album = albumName,
                durationMs = s.optInt("interval", 0) * 1000L,
                artworkUrl = albumMid.takeIf { it.isNotBlank() }
                    ?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" },
            )
        }
        val name = cd.optString("dissname").ifBlank { preferredName ?: "QQ playlist" }
        val cover = cd.optString("logo").ifBlank { cd.optString("headurl") }.takeIf { it.isNotBlank() }
        return save(name, cover, LocalLibraryStore.SOURCE_QQ, "https://y.qq.com/n/ryqq/playlist/$id", songs, list.length())
    }

    private fun importNetease(id: String, preferredName: String?): Result {
        val url = "https://music.163.com/api/v6/playlist/detail?id=$id&n=1000&s=0"
        val body = get(url, mapOf("Referer" to "https://music.163.com/")) ?: return Result.Err("Netease API returned no response")
        val root = JSONObject(body)
        val pl = root.optJSONObject("playlist") ?: return Result.Err("Playlist not found or private")
        val tracks = pl.optJSONArray("tracks") ?: return Result.Err("No public tracks in this playlist")
        val songs = ArrayList<Track>(tracks.length())
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val title = t.optString("name")
            if (title.isBlank()) continue
            val artists = buildList {
                t.optJSONArray("ar")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val album = t.optJSONObject("al")
            val pic = album?.optString("picUrl").orEmpty()
            songs += importedTrack(
                id = "imp-ne-${t.optLong("id")}",
                title = title,
                artist = artists.joinToString(" / ").ifBlank { "Unknown artist" },
                album = album?.optString("name").orEmpty(),
                durationMs = t.optLong("dt", 0L),
                artworkUrl = pic.takeIf { it.isNotBlank() }?.let { "$it?param=500y500" },
            )
        }
        val name = pl.optString("name").ifBlank { preferredName ?: "Netease playlist" }
        val cover = pl.optString("coverImgUrl").takeIf { it.isNotBlank() }
        return save(name, cover, LocalLibraryStore.SOURCE_NETEASE, "https://music.163.com/#/playlist?id=$id", songs, tracks.length())
    }

    private fun importKugou(id: String, preferredName: String?): Result {
        var name = preferredName ?: "Kugou playlist"
        var cover: String? = null
        runCatching {
            val detail = get(
                "http://mobilecdn.kugou.com/api/v3/special/detail?specialid=$id&plat=0&version=8000",
                emptyMap(),
            )
            if (detail != null) {
                val d = JSONObject(detail).optJSONObject("data")
                d?.optString("specialname")?.takeIf { it.isNotBlank() }?.let { name = it }
                d?.optString("imgurl")?.takeIf { it.isNotBlank() }?.let { cover = it }
            }
        }
        if (name == "Kugou playlist" || cover == null) {
            runCatching {
                val html = get("https://www.kugou.com/yy/special/single/$id.html", emptyMap())
                    ?: return@runCatching
                Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.getOrNull(1)
                    ?.substringBefore("_")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { name = it }
            }
        }

        val songs = ArrayList<Track>()
        var total = 0
        var page = 1
        while (page <= 10) {
            val body = get(
                "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$id&page=$page&pagesize=100&version=8000&plat=0",
                emptyMap(),
            ) ?: break
            val data = JSONObject(body).optJSONObject("data") ?: break
            total = data.optInt("total", total)
            val info = data.optJSONArray("info") ?: break
            if (info.length() == 0) break
            for (i in 0 until info.length()) {
                val s = info.optJSONObject(i) ?: continue
                val filename = s.optString("filename")
                if (filename.isBlank()) continue
                val dash = filename.indexOf(" - ")
                val artist = if (dash > 0) filename.substring(0, dash).trim() else "Unknown artist"
                val title = if (dash > 0) filename.substring(dash + 3).trim() else filename.trim()
                songs += importedTrack(
                    id = "imp-kg-${s.optString("hash").ifBlank { s.optString("audio_id") }}",
                    title = title,
                    artist = artist,
                    album = s.optString("album_name"),
                    durationMs = s.optInt("duration", 0) * 1000L,
                    artworkUrl = null,
                )
            }
            if (songs.size >= total) break
            page++
        }
        if (songs.isEmpty()) return Result.Err("Playlist not found or has no public tracks")
        return save(name, cover, LocalLibraryStore.SOURCE_KUGOU, "https://www.kugou.com/yy/special/single/$id.html", songs, total)
    }

    /** Plain text: each line "title - artist[- album]", "title TAB artist", or just a title. */
    private fun importText(text: String, preferredName: String?): Result {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return Result.Err("No importable song lines")
        val songs = ArrayList<Track>(lines.size)
        for (line in lines.take(MAX_TRACKS)) {
            val parts = when {
                line.contains("\t") -> line.split("\t").map { it.trim() }
                line.contains(" - ") -> line.split(" - ").map { it.trim() }
                line.contains(" \u2013 ") -> line.split(" \u2013 ").map { it.trim() }
                else -> listOf(line)
            }
            val title = parts.getOrNull(0).orEmpty()
            if (title.isBlank() || title.length > 120) continue
            if (title.startsWith("共") && title.endsWith("首")) continue
            songs += importedTrack(
                id = "imp-txt-${title.hashCode()}-${parts.getOrNull(1)?.hashCode() ?: 0}",
                title = title,
                artist = parts.getOrNull(1).orEmpty().ifBlank { "Unknown artist" },
                album = parts.getOrNull(2).orEmpty(),
                durationMs = 0L,
                artworkUrl = null,
            )
        }
        if (songs.isEmpty()) return Result.Err("No songs recognized; example: Title - Artist")
        val name = preferredName?.takeIf { it.isNotBlank() } ?: "Text playlist (${songs.size})"
        return save(name, null, LocalLibraryStore.SOURCE_TEXT, null, songs, songs.size)
    }

    private fun save(
        name: String,
        cover: String?,
        source: String,
        sourceUrl: String?,
        songs: List<Track>,
        total: Int,
    ): Result {
        if (songs.isEmpty()) return Result.Err("No importable tracks in the playlist")
        val playlist = LocalLibraryStore.LocalPlaylist(
            id = "imp-$source-${sourceUrl?.hashCode() ?: name.hashCode()}",
            name = name,
            coverUrl = cover,
            source = source,
            sourceUrl = sourceUrl,
            songs = songs.take(MAX_TRACKS),
        )
        store.saveImported(playlist)
        Log.i(TAG, "imported '" + name + "' source=" + source + " songs=" + songs.size + "/" + total)
        return Result.Ok(playlist, songs.size, total)
    }

    /**
     * Uniform construction for imported tracks: neutral two-stop grey palette,
     * no Apple metadata, catalogKind = Song.
     *
     * The palette MUST hold at least two colors: Compose gradient shaders reject
     * a single color and used to crash the library screen on import.
     */
    private fun importedTrack(
        id: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        artworkUrl: String?,
    ): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        palette = listOf(Color(0xFF2B3038), Color(0xFF11141A)),
        lyrics = emptyList(),
        catalogKind = CatalogKind.Song,
        artworkUrl = artworkUrl,
    )

    // Link handling

    private fun isQQ(url: String) = url.contains("y.qq.com") || (url.contains("qq.com") && url.contains("taoge"))
    private fun isNetease(url: String) =
        url.contains("music.163.com") || url.contains("163cn.tv") || url.contains("y.music.163.com")
    private fun isKugou(url: String) = url.contains("kugou.com")

    private fun extractId(raw: String): String? {
        bareIdRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        pathIdRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /** Expand short links (163cn.tv / c6.y.qq.com/base/u?__= / url.cn) to the real URL. */
    private fun resolveShortUrl(url: String): String {
        if (extractId(url) != null) return url
        val looksShort = listOf("163cn.tv", "c6.y.qq.com", "url.cn", "t.cn", "u.y.qq.com").any { url.contains(it) }
        if (!looksShort) return url
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", ua).build()
            client.newCall(req).execute().use { it.request.url.toString() }
        }.getOrDefault(url)
    }

    /** When a short link lands on an H5 page, scrape the playlist id from the HTML. */
    private fun scrapeIdFromPage(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Referer", "https://y.qq.com/").build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            val patterns = listOf(
                Regex("disstid[\"']?\\s*[:=]\\s*[\"']?(\\d{5,})"),
                Regex("playlistId[\"']?\\s*[:=]\\s*[\"']?(\\d{5,})"),
                Regex("/playlist/(\\d{5,})"),
                Regex("taoge\\.html\\?id=(\\d{5,})"),
            )
            patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
        }
    }.getOrNull()

    private fun get(url: String, headers: Map<String, String>): String? = runCatching {
        val builder = Request.Builder().url(url).header("User-Agent", ua)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP " + resp.code + " " + url)
                return@use null
            }
            resp.body?.string()
        }
    }.getOrElse {
        Log.w(TAG, "request failed " + url + ": " + (it.message ?: "unknown"))
        null
    }

    companion object {
        private const val TAG = "PlaylistImporter"
        private const val MAX_TRACKS = 1000
    }
}
