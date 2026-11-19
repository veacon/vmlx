package dev.amatv.ui

/**
 * Lyrics timing stepper shared by the settings page.
 *
 * The settings page shows a *delay* (positive = lyrics appear later).
 * [PlaybackPreferences.lyricOffsetMs] stores the value added to the lyric
 * clock, which is the inverse of that delay.
 */
internal const val LyricsTimingStepMs = 5L
internal const val MaxLyricsTimingOffsetMs = 3_000L

/** Apply one stepper press. [delayStepMs] > 0 means "show lyrics later". */
internal fun offsetAfterLyricsTimingStep(offsetMs: Long, delayStepMs: Long): Long =
    (offsetMs - delayStepMs).coerceIn(-MaxLyricsTimingOffsetMs, MaxLyricsTimingOffsetMs)

/** Delay shown on the settings page for a stored offset. */
internal fun lyricsTimingDelayMs(offsetMs: Long): Long = -offsetMs
