package dev.amatv.luoxue

/**
 * 落雪解析层的曲目视图（移植自 veil.music data.model.Song，字段保持同名以便解析层原样工作）。
 * 由 am_atv 的 Track 一行映射而来，不承载 UI 职责。
 */
data class Song(
    val id:             String,
    val title:          String,
    val artistName:     String,
    val albumName:      String,
    val durationMs:     Long,
    val artworkUrl:     String?,
    val artworkBgColor: String? = null,
    val previewUrl:     String? = null,
    val hasLyrics:      Boolean = false,
    val trackNumber:    Int?    = null,
    val genreNames:     List<String> = emptyList(),
    val artistId:       String? = null,
    val albumId:        String? = null,
    val type:           String  = "songs",
    val dateAdded:      String? = null,
) {
    /** Music-video rows play through the fullscreen video path, not the audio player. */
    val isMusicVideo: Boolean get() = type.contains("music-video")

    fun artworkUrl(size: Int) = artworkUrl
        ?.replace("{w}", "$size")
        ?.replace("{h}", "$size")
        ?.replace("{f}", "jpg")

    val durationFormatted: String get() {
        val s = durationMs / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** am_atv Track → 落雪 Song 的唯一映射入口。 */
fun dev.amatv.model.Track.toLxSong(): Song = Song(
    id = id,
    title = title,
    artistName = artist,
    albumName = album,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
)
