package dev.amatv.veilsource

import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * veil.music 未登录浏览数据层的 am_atv 移植（独立"未登录数据层"，方案 A）。
 *
 * 数据来源与 veil `data/datasource/DirectBrowseSource.browse()` 完全同源：
 * GET `amp-api.music.apple.com/v1/editorial/{sf}/groupings?name=music&platform=web`
 * ——music.apple.com 官网 Browse/New 页的同一份编辑 GROUPING，匿名即可访问。
 *
 * 解析规则（veil 原样）：
 *  - `data[0].relationships.tabs.data[0]`：默认 tab；
 *  - tab 的 children 里 `editorialElementKind` 326/327 为横向 shelf（title = name）；
 *  - kind 316 为「New」精选位（children 是 editorial 卡片，designBadge 是角标文案）；
 *  - 兜底交给调用方（AtvApp 现有 charts 三行）。
 *
 * 映射目标：am_atv 的 `ContentShelf`/`Track` 模型（UI 零改动）。电台卡片
 * （stations / ra.*）与 MV 卡片在解析层直接过滤——LX 无对应源、电台为 Widevine。
 */
class VeilBrowseSource(
    private val auth: AppleDisplayAuth,
    private val storefrontProvider: suspend () -> String,
) {

    /** 官网编辑分组 shelves。编辑数据为空时返回空列表，由调用方走 charts 兜底。 */
    suspend fun shelves(locale: String, includeSpotlight: Boolean): List<ContentShelf> =
        withContext(Dispatchers.IO) {
            val sf = runCatching { storefrontProvider() }.getOrNull() ?: return@withContext emptyList()
            val bearer = auth.bearer() ?: return@withContext emptyList()
            val root = fetchGrouping(sf, locale, bearer)
                ?: run {
                    // bearer 可能过期：401 之外的情况不值得重试；401 在 fetchGrouping
                    // 内部已经换过一次 token。到这里直接放弃，走 charts 兜底。
                    return@withContext emptyList()
                }
            parseGrouping(root, includeSpotlight)
        }

    private suspend fun fetchGrouping(sf: String, locale: String, bearer: String): JSONObject? {
        val encodedLocale = URLEncoder.encode(locale, Charsets.UTF_8.name())
        val url = "https://amp-api.music.apple.com/v1/editorial/$sf/groupings" +
            "?name=music&l=$encodedLocale&platform=web&include=tabs" +
            "&extend=editorialArtwork&art%5Burl%5D=f&limit%5Bcontents%5D=24"
        var token = bearer
        for (attempt in 0..1) {
            val request = auth.catalogRequest(url, token) ?: return null
            val outcome = runCatching {
                AppleDisplayHttp.client.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }.getOrNull() ?: return null
            val (code, body) = outcome
            if (code in 200..299) {
                return runCatching { JSONObject(body) }.getOrNull()
            }
            if (code == 401 && attempt == 0) {
                // token 被拒：强制重抓一次再试（veil 拦截器的同款单次重试）。
                token = auth.bearer(forceRefresh = true) ?: return null
                continue
            }
            Log.w(TAG, "editorial grouping HTTP $code")
            return null
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseGrouping(root: JSONObject, includeSpotlight: Boolean): List<ContentShelf> {
        val shelves = mutableListOf<ContentShelf>()
        runCatching {
            val data = root.optJSONArray("data") ?: return@runCatching
            val grouping = data.optJSONObject(0) ?: return@runCatching
            val tab = grouping.optJSONObject("relationships")
                ?.optJSONObject("tabs")?.optJSONArray("data")?.optJSONObject(0)
                ?: return@runCatching
            val kids = tab.optJSONObject("relationships")
                ?.optJSONObject("children")?.optJSONArray("data") ?: return@runCatching

            for (index in 0 until kids.length()) {
                val kid = kids.optJSONObject(index) ?: continue
                val attrs = kid.optJSONObject("attributes") ?: continue
                val kind = attrs.optString("editorialElementKind")
                when (kind) {
                    "326", "327" -> {
                        val title = attrs.optString("name").ifBlank { attrs.optString("title") }
                        if (title.isEmpty()) continue
                        val contents = kid.optJSONObject("relationships")
                            ?.optJSONObject("contents")?.optJSONArray("data") ?: continue
                        val items = contents.toTracks()
                        if (items.isNotEmpty()) {
                            shelves += ContentShelf(
                                id = "veil:$kind:${kid.optString("id", title)}",
                                title = title,
                                items = items,
                                featured = false,
                            )
                        }
                    }
                    "316" -> {
                        if (!includeSpotlight) continue
                        val cards = kid.optJSONObject("relationships")
                            ?.optJSONObject("children")?.optJSONArray("data") ?: continue
                        val featured = mutableListOf<Track>()
                        val seen = mutableSetOf<String>()
                        for (cardIndex in 0 until cards.length()) {
                            val card = cards.optJSONObject(cardIndex) ?: continue
                            val cardAttrs = card.optJSONObject("attributes")
                            val content = card.optJSONObject("relationships")
                                ?.optJSONObject("contents")?.optJSONArray("data")?.optJSONObject(0)
                                ?: continue
                            val track = content.toTrack() ?: continue
                            if (!seen.add(track.catalogId ?: track.id)) continue
                            val badge = cardAttrs?.optString("designBadge").orEmpty()
                            featured += if (badge.isNotBlank()) track.copy(album = badge) else track
                        }
                        if (featured.size >= 2) {
                            shelves.add(0, ContentShelf(
                                id = "veil:new",
                                title = SPOTLIGHT_TITLE,
                                items = featured,
                                featured = true,
                            ))
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "grouping parse failed", it) }
        return shelves
    }

    // ── 匿名搜索（amp-api catalog）────────────────────────────────────────

    /** 未登录搜索：`v1/catalog/{sf}/search`（amp-api 匿名，veil 同款通道）。 */
    suspend fun search(term: String, locale: String): List<Track> = withContext(Dispatchers.IO) {
        if (term.isBlank()) return@withContext emptyList()
        val sf = runCatching { storefrontProvider() }.getOrNull() ?: return@withContext emptyList()
        var bearer = auth.bearer() ?: return@withContext emptyList()
        var refreshedToken = false
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        val url = "https://amp-api.music.apple.com/v1/catalog/$sf/search" +
            "?term=$encoded&types=songs,albums,playlists&limit=15"
        repeat(2) {
            val request = auth.catalogRequest(url, bearer) ?: return@withContext emptyList()
            val (code, body) = runCatching {
                AppleDisplayHttp.client.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }.getOrNull() ?: return@withContext emptyList()
            if (code == 401 && !refreshedToken) {
                refreshedToken = true
                bearer = auth.bearer(forceRefresh = true) ?: return@withContext emptyList()
                return@repeat
            }
            if (code !in 200..299) {
                Log.w(TAG, "search HTTP $code")
                return@withContext emptyList()
            }
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
            val results = root.optJSONObject("results") ?: return@withContext emptyList()
            val out = mutableListOf<Track>()
            for (key in listOf("songs", "albums", "playlists")) {
                val data = results.optJSONObject(key)?.optJSONArray("data") ?: continue
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.toTrack()?.let(out::add)
                }
            }
            return@withContext out.distinctBy { it.id }
        }
        emptyList()
    }

    // ── 集合详情（歌单/专辑曲目，amp-api 匿名 catalog）─────────────────────

    /**
     * 歌单/专辑曲目（未登录也可用）：`v1/catalog/{sf}/{playlists|albums}/{id}/tracks`。
     * 与 am_atv 现有 api.music.apple.com 链路同源，但走 amp-api 匿名通道，
     * 不依赖 bundled developer token —— 这正是"点击歌单/专辑显示无法载入曲目"的修复。
     */
    suspend fun collectionTracks(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val type = when (track.catalogKind) {
            CatalogKind.Album -> "albums"
            CatalogKind.Playlist -> "playlists"
            else -> return@withContext emptyList()
        }
        val id = (track.resourceId ?: track.catalogId ?: track.id).removePrefix("apple:")
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) return@withContext emptyList()
        val sf = runCatching { storefrontProvider() }.getOrNull() ?: return@withContext emptyList()
        var bearer = auth.bearer() ?: return@withContext emptyList()
        var refreshedToken = false
        val tracks = mutableListOf<Track>()
        var path: String? = "https://amp-api.music.apple.com/v1/catalog/$sf/$type/$id/tracks?limit=100"
        var page = 0
        while (path != null && page < 4) {
            val request = auth.catalogRequest(path, bearer) ?: return@withContext tracks
            val (code, body) = runCatching {
                AppleDisplayHttp.client.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }.getOrNull() ?: return@withContext tracks
            if (code == 401 && !refreshedToken) {
                refreshedToken = true
                bearer = auth.bearer(forceRefresh = true) ?: return@withContext tracks
                continue
            }
            if (code !in 200..299) {
                Log.w(TAG, "collection tracks HTTP $code ($type/$id)")
                return@withContext tracks
            }
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext tracks
            val data = root.optJSONArray("data") ?: break
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toTrack()?.let(tracks::add)
            }
            val next = root.optString("next").takeIf { it.isNotBlank() && it != "null" }
            path = next?.let {
                if (it.startsWith("http")) it else "https://amp-api.music.apple.com$it"
            }
            page++
        }
        tracks
    }

    // ── 卡片 → am_atv Track 映射（veil itemFromRaw 同款字段选取）──────────────

    private fun JSONArray.toTracks(): List<Track> {
        val out = mutableListOf<Track>()
        for (index in 0 until length()) {
            optJSONObject(index)?.toTrack()?.let(out::add)
        }
        return out
    }

    private fun JSONObject.toTrack(): Track? {
        val type = optString("type")
        // 电台（stations / ra.*）与视频（music-videos 等）：LX 无对应源，解析层直接剔除。
        if (type == "stations" || type.startsWith("ra.")) return null
        if (type == "music-videos" || type == "uploaded-videos") return null
        val attrs = optJSONObject("attributes") ?: return null
        val rawArtwork = attrs.optJSONObject("artwork")?.optString("url").orEmpty()
        val artwork = rawArtwork.replace("{w}", "600").replace("{h}", "600")
            .replace("{f}", "jpg").takeIf { it.startsWith("https://") }
            ?: return null   // veil：没有封面的卡片看起来是坏的，直接丢弃
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val kind = when (type) {
            "songs" -> CatalogKind.Song
            "albums" -> CatalogKind.Album
            "playlists" -> CatalogKind.Playlist
            "apple-curators", "curators" -> CatalogKind.Playlist
            else -> return null
        }
        val title = attrs.optString("name").ifBlank { "Unknown" }
        val artist = attrs.optString("artistName").ifBlank {
            attrs.optString("curatorName").ifBlank { "Apple Music" }
        }
        val editorialNotes = attrs.optJSONObject("editorialNotes")
        val note = editorialNotes?.optString("short")?.takeIf { it.isNotBlank() }
            ?: editorialNotes?.optString("standard")?.takeIf { it.isNotBlank() }
        val album = when (kind) {
            CatalogKind.Song -> attrs.optString("albumName").ifBlank { note ?: "Song" }
            CatalogKind.Album -> note ?: "Album"
            CatalogKind.Playlist -> note ?: "Playlist"
            else -> note ?: "Music Video"
        }
        return Track(
            id = "apple:$id",
            title = title,
            artist = artist,
            album = album,
            durationMs = attrs.optLong("durationInMillis", 48_000L).coerceAtLeast(1L),
            palette = paletteFor(id),
            lyrics = emptyList(),
            catalogId = id,
            catalogKind = kind,
            artworkUrl = artwork,
            hasLyricsAvailable = attrs.optBoolean("hasLyrics", false),
            resourceId = id,
            resourceType = type,
        )
    }

    /** 与 AppleMusicApi.paletteFor 同款调色板：同一条目两种数据层里颜色一致。 */
    private fun paletteFor(id: String): List<Color> {
        val palettes = listOf(
            listOf(Color(0xFF38104E), Color(0xFFA41E62), Color(0xFFE17665)),
            listOf(Color(0xFF102E49), Color(0xFF147185), Color(0xFFD3A25E)),
            listOf(Color(0xFF242044), Color(0xFF5B55A7), Color(0xFFEA786D)),
            listOf(Color(0xFF143A32), Color(0xFF378C69), Color(0xFFD6C94D)),
        )
        return palettes[(id.hashCode() and Int.MAX_VALUE) % palettes.size]
    }

    companion object {
        private const val TAG = "AmAtvVeilSource"

        /** 「New」精选位标题。仅 Browse 页展示（veil browse() 同款）。 */
        const val SPOTLIGHT_TITLE = "New"

        /** 系统语言 → grouping 的 `l` 参数。 */
        fun localeParam(languageTag: String): String {
            val lower = languageTag.lowercase()
            return when {
                lower.startsWith("zh-hant") || lower.startsWith("zh-tw") ||
                    lower.startsWith("zh-hk") || lower.startsWith("zh-mo") -> "zh-TW"
                lower.startsWith("zh") -> "zh-CN"
                else -> "en-US"
            }
        }
    }
}

/** veilsource 层共享的 HTTP 客户端（独立于播放解析与展示抓取）。 */
internal object AppleDisplayHttp {
    val client: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
