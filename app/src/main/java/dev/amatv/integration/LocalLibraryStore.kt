package dev.amatv.integration

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * v0.6 local library: favorites + user/imported playlists + recently played.
 *
 * Ported from veil.music LocalLibraryStore; dropped Hilt/Moshi, uses manual
 * construction + org.json full read/write. Persisted to
 * filesDir/local_library.json (a few hundred tracks ~ 200KB), so no Room:
 * writes go through a single-thread executor (tmp + fsync + atomic rename),
 * reads happen only on first access.
 *
 * Imported tracks carry no Apple id (id is an imp-* form); playback resolves
 * them via the LX source engine by title + artist + duration.
 */
class LocalLibraryStore(context: Context) {

    data class LocalPlaylist(
        val id: String,
        val name: String,
        val coverUrl: String? = null,
        /** local / qq / netease / kugou / text: source label. */
        val source: String = SOURCE_LOCAL,
        val sourceUrl: String? = null,
        /** Stable origin key for "add to list" (album:xxx / playlist:xxx). */
        val originKey: String? = null,
        val songs: List<Track> = emptyList(),
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    ) {
        val trackCount: Int get() = songs.size
        val sourceLabel: String get() = when (source) {
            SOURCE_QQ -> "QQ Music"
            SOURCE_NETEASE -> "Netease"
            SOURCE_KUGOU -> "Kugou"
            SOURCE_TEXT -> "Text import"
            SOURCE_APPLE -> "Apple Music"
            else -> "Local"
        }
        val isImported: Boolean get() = source != SOURCE_LOCAL
    }

    /** Recently played: a valid play (>10s) with timestamp, newest first. */
    data class RecentPlay(
        val track: Track,
        val playedAt: Long,
    )

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "local_library.json")
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-library-writer").apply { isDaemon = true }
    }
    private val lock = Any()

    private val _favorites = MutableStateFlow<List<Track>>(emptyList())
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentPlay>>(emptyList())
    val recents: StateFlow<List<RecentPlay>> = _recents.asStateFlow()

    init {
        runCatching {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                _favorites.value = root.optJSONArray("favorites")?.let { jsonToTracks(it) }.orEmpty()
                _playlists.value = root.optJSONArray("playlists")?.let { jsonToPlaylists(it) }.orEmpty()
                _recents.value = root.optJSONArray("recents")?.let { jsonToRecents(it) }.orEmpty()
                Log.i(TAG, "loaded favorites=${_favorites.value.size} " +
                    "playlists=${_playlists.value.size} recents=${_recents.value.size}")
            }
        }.onFailure {
            // A parse failure (corrupt / killed mid-write) must never silently become an
            // empty library: the next persist would overwrite and destroy user data.
            // Preserve the bad file, rebuild from scratch.
            Log.w(TAG, "load failed: ${it.message}")
            runCatching {
                val damaged = File(appContext.filesDir, "local_library.corrupt.json")
                if (damaged.exists()) damaged.delete()
                file.copyTo(damaged, overwrite = true)
                Log.w(TAG, "damaged library preserved at ${damaged.name}")
            }
        }
    }

    // Favorites

    fun isFavorite(trackId: String): Boolean = _favorites.value.any { it.id == trackId }

    /** Returns the state after the op: true = now favorited, false = removed. */
    fun toggleFavorite(track: Track): Boolean = synchronized(lock) {
        val current = _favorites.value
        val exists = current.any { it.id == track.id }
        _favorites.value = if (exists) current.filterNot { it.id == track.id }
        else listOf(track) + current.filterNot { it.id == track.id }
        persistLocked()
        !exists
    }

    fun addFavorite(track: Track) = synchronized(lock) {
        _favorites.value = listOf(track) + _favorites.value.filterNot { it.id == track.id }
        persistLocked()
    }

    /** "Add to list" result: distinguishes created / appended / already-there. */
    sealed interface PlaylistUpsert {
        data class Created(val playlist: LocalPlaylist, val added: Int) : PlaylistUpsert
        data class Updated(val playlist: LocalPlaylist, val added: Int) : PlaylistUpsert
        data class Unchanged(val playlist: LocalPlaylist) : PlaylistUpsert
    }

    fun addAsPlaylist(
        name: String, originKey: String, coverUrl: String?, tracks: List<Track>,
    ): PlaylistUpsert = synchronized(lock) {
        val existing = _playlists.value.firstOrNull { it.originKey == originKey }
        if (existing != null) {
            val known = existing.songs.mapTo(HashSet()) { it.id }
            val fresh = tracks.filter { known.add(it.id) }
            val healedCover = coverUrl != null &&
                (existing.coverUrl == null || existing.coverUrl.contains("{"))
            if (fresh.isEmpty() && !healedCover) return@synchronized PlaylistUpsert.Unchanged(existing)
            val merged = existing.copy(
                songs = existing.songs + fresh,
                coverUrl = if (healedCover) coverUrl else existing.coverUrl,
                updatedAt = System.currentTimeMillis(),
            )
            _playlists.value = _playlists.value.map { if (it.id == existing.id) merged else it }
            persistLocked()
            return@synchronized if (fresh.isEmpty()) PlaylistUpsert.Unchanged(merged)
            else PlaylistUpsert.Updated(merged, fresh.size)
        }
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = "lp-" + UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "New playlist" },
            coverUrl = coverUrl,
            originKey = originKey,
            songs = tracks,
            createdAt = now,
            updatedAt = now,
        )
        _playlists.value = listOf(playlist) + _playlists.value
        persistLocked()
        PlaylistUpsert.Created(playlist, tracks.size)
    }

    fun removeFavorite(trackId: String) = synchronized(lock) {
        _favorites.value = _favorites.value.filterNot { it.id == trackId }
        persistLocked()
    }

    fun clearFavorites() = synchronized(lock) {
        _favorites.value = emptyList()
        persistLocked()
    }

    // Playlists

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): LocalPlaylist = synchronized(lock) {
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = "lp-" + UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "New playlist" },
            songs = tracks,
            createdAt = now,
            updatedAt = now,
        )
        _playlists.value = listOf(playlist) + _playlists.value
        persistLocked()
        playlist
    }

    /** Save an import; same id overrides (re-import refreshes), else prepends. */
    fun saveImported(playlist: LocalPlaylist): LocalPlaylist = synchronized(lock) {
        val now = System.currentTimeMillis()
        val existing = _playlists.value.firstOrNull { it.id == playlist.id }
        val merged = playlist.copy(
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        _playlists.value = listOf(merged) + _playlists.value.filterNot { it.id == playlist.id }
        persistLocked()
        merged
    }

    fun deletePlaylist(playlistId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        persistLocked()
    }

    fun renamePlaylist(playlistId: String, name: String) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(name = name.trim(), updatedAt = System.currentTimeMillis()) else it
        }
        persistLocked()
    }

    fun addSongToPlaylist(playlistId: String, track: Track) = synchronized(lock) {
        _playlists.value = _playlists.value.map {
            if (it.id != playlistId || it.songs.any { s -> s.id == track.id }) it
            else it.copy(songs = it.songs + track, updatedAt = System.currentTimeMillis())
        }
        persistLocked()
    }

    fun removeSongFromPlaylist(playlistId: String, trackId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map {
            if (it.id != playlistId) it
            else it.copy(songs = it.songs.filterNot { s -> s.id == trackId }, updatedAt = System.currentTimeMillis())
        }
        persistLocked()
    }

    fun playlist(playlistId: String): LocalPlaylist? = _playlists.value.firstOrNull { it.id == playlistId }

    // Recently played

    /** Record a valid play (>10s). Dedup by track, newest first, cap at RECENT_MAX. */
    fun recordRecent(track: Track) = synchronized(lock) {
        val now = System.currentTimeMillis()
        _recents.value = listOf(RecentPlay(track, now)) +
            _recents.value.filterNot { it.track.id == track.id }
        if (_recents.value.size > RECENT_MAX) {
            _recents.value = _recents.value.take(RECENT_MAX)
        }
        persistLocked()
    }

    fun clearRecents() = synchronized(lock) {
        _recents.value = emptyList()
        persistLocked()
    }

    // Aggregates

    /** Favorites + all playlist tracks, deduped by id. */
    fun allTracks(): List<Track> {
        val seen = HashSet<String>()
        return (_favorites.value + _playlists.value.flatMap { it.songs }).filter { seen.add(it.id) }
    }

    // Persistence

    private fun persistLocked() {
        val root = JSONObject()
            .put("favorites", tracksToJson(_favorites.value))
            .put("playlists", playlistsToJson(_playlists.value))
            .put("recents", recentsToJson(_recents.value))
            .put("version", 1)
        val json = runCatching { root.toString() }.getOrNull() ?: return
        writer.execute {
            runCatching {
                val bytes = json.toByteArray()
                val tmp = File(file.parentFile, file.name + ".tmp")
                // Write a temp file, fsync, then rename over: if the process is killed
                // mid-write the target is either the previous complete file or the new one.
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(bytes); out.flush(); out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    java.io.FileOutputStream(file).use { out ->
                        out.write(bytes); out.flush(); out.fd.sync()
                    }
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
        }
    }

    // JSON codec (org.json; the main module has no Moshi)

    private fun tracksToJson(tracks: List<Track>): JSONArray = JSONArray().apply {
        tracks.forEach { t ->
            put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("durationMs", t.durationMs)
                put("catalogId", t.catalogId)
                put("catalogKind", t.catalogKind.name)
                put("resourceId", t.resourceId)
                put("resourceType", t.resourceType)
                put("artworkUrl", t.artworkUrl)
                put("motionArtworkUrl", t.motionArtworkUrl)
                put("motionPreviewUrl", t.motionPreviewUrl)
                put("hasLyricsAvailable", t.hasLyricsAvailable)
                put("palette", JSONArray().apply { t.palette.forEach { c -> put(c.toArgb()) } })
            })
        }
    }

    private fun jsonToTracks(array: JSONArray): List<Track> {
        val out = ArrayList<Track>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val colors = o.optJSONArray("palette")
            // Normalise to at least two colors: stored playlists imported before
            // the gradient guard existed only carry one, and a one-color palette
            // makes every Compose gradient throw.
            val rawPalette = if (colors == null || colors.length() == 0) {
                emptyList()
            } else (0 until colors.length()).map { Color(colors.getInt(it)) }
            val palette = when {
                rawPalette.size >= 2 -> rawPalette
                rawPalette.size == 1 -> listOf(rawPalette[0], rawPalette[0])
                else -> listOf(Color(0xFF202428), Color(0xFF121417))
            }
            val kindRaw = o.optString("catalogKind")
            out += Track(
                id = o.getString("id"),
                title = o.getString("title"),
                artist = o.optString("artist").ifBlank { "Unknown artist" },
                album = o.optString("album"),
                durationMs = o.optLong("durationMs").coerceAtLeast(1L),
                palette = palette,
                lyrics = emptyList(),
                catalogId = o.optString("catalogId").takeIf { it.isNotBlank() && it != "null" },
                catalogKind = runCatching { CatalogKind.valueOf(kindRaw) }.getOrDefault(CatalogKind.Song),
                resourceId = o.optString("resourceId").takeIf { it.isNotBlank() && it != "null" },
                resourceType = o.optString("resourceType").takeIf { it.isNotBlank() && it != "null" },
                artworkUrl = o.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
                motionArtworkUrl = o.optString("motionArtworkUrl").takeIf { it.isNotBlank() && it != "null" },
                motionPreviewUrl = o.optString("motionPreviewUrl").takeIf { it.isNotBlank() && it != "null" },
                hasLyricsAvailable = o.optBoolean("hasLyricsAvailable"),
                recommendedByYtm = false,
                ytmVideoId = null,
            )
        }
        return out
    }

    private fun playlistsToJson(list: List<LocalPlaylist>): JSONArray = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("coverUrl", p.coverUrl)
                put("source", p.source)
                put("sourceUrl", p.sourceUrl)
                put("originKey", p.originKey)
                put("createdAt", p.createdAt)
                put("updatedAt", p.updatedAt)
                put("songs", tracksToJson(p.songs))
            })
        }
    }

    private fun jsonToPlaylists(array: JSONArray): List<LocalPlaylist> {
        val out = ArrayList<LocalPlaylist>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out += LocalPlaylist(
                id = o.getString("id"),
                name = o.getString("name"),
                coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
                source = o.optString("source").takeIf { it.isNotBlank() } ?: SOURCE_LOCAL,
                sourceUrl = o.optString("sourceUrl").takeIf { it.isNotBlank() && it != "null" },
                originKey = o.optString("originKey").takeIf { it.isNotBlank() && it != "null" },
                songs = o.optJSONArray("songs")?.let { jsonToTracks(it) }.orEmpty(),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
        return out
    }

    private fun recentsToJson(recents: List<RecentPlay>): JSONArray = JSONArray().apply {
        recents.forEach { r ->
            put(JSONObject().apply {
                put("playedAt", r.playedAt)
                put("track", tracksToJson(listOf(r.track)).opt(0))
            })
        }
    }

    private fun jsonToRecents(array: JSONArray): List<RecentPlay> {
        val out = ArrayList<RecentPlay>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val track = o.optJSONObject("track")?.let { jsonToTracks(JSONArray().put(it)).firstOrNull() }
            if (track != null) out += RecentPlay(track, o.optLong("playedAt"))
        }
        return out
    }

    companion object {
        private const val TAG = "LocalLibrary"
        const val SOURCE_LOCAL = "local"
        const val SOURCE_QQ = "qq"
        const val SOURCE_NETEASE = "netease"
        const val SOURCE_KUGOU = "kugou"
        const val SOURCE_TEXT = "text"
        const val SOURCE_APPLE = "apple"
        /** Max recently-played entries to retain. */
        const val RECENT_MAX = 100
    }
}
