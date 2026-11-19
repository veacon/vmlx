package dev.amatv.integration

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * v0.8: which audio decoder path the LX player is built with (veil.music's DecoderMode).
 *
 * [SOFTWARE] prefers the bundled FFmpeg extension renderer
 * ([DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER]); MediaCodec stays as the
 * fallback for formats FFmpeg cannot handle.
 *
 * [HARDWARE] disables extension renderers entirely (EXTENSION_RENDERER_MODE_OFF) and lets
 * the platform MediaCodec decoders run. Factory default. Some TV firmwares ship AAC/FLAC
 * decoders that misbehave (double speed, crackle) - the settings switch lets the user fall
 * back to SOFTWARE without shipping a new build.
 *
 * The decoder is chosen when the player is built, so switching rebuilds the player in
 * place (same track, same position) - see LxPlayerBridge.applyDecoderMode.
 */
enum class DecoderMode(val key: String) {
    SOFTWARE("sw"),
    HARDWARE("hw");

    companion object {
        /** Factory default: hardware / MediaCodec. A stored user choice always wins. */
        val DEFAULT = HARDWARE

        /** Unknown/absent values fall back to the factory default. */
        fun fromKey(raw: String?): DecoderMode = entries.firstOrNull { it.key == raw } ?: DEFAULT

        private const val PREFS_FILE = "vmlx_decoder_preferences"
        private const val KEY_MODE = "decoder_mode"

        fun load(context: Context): DecoderMode {
            val prefs = prefs(context)
            return fromKey(runCatching { prefs.getString(KEY_MODE, null) }.getOrNull())
        }

        fun save(context: Context, mode: DecoderMode) {
            prefs(context).edit().putString(KEY_MODE, mode.key).apply()
        }

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }
}

/**
 * Observability for the bundled FFmpeg extension. The native lib ships only for
 * armeabi-v7a; on a 64-bit-only device System.loadLibrary throws UnsatisfiedLinkError and
 * Media3's FFmpeg renderer silently becomes unavailable (no crash, soft decode falls back
 * to the platform decoder). Surfacing it keeps "some formats do not play" debuggable.
 */
internal object FfmpegSupport {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("ffmpegJNI") }.isSuccess
    }

    val deviceAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"

    fun describe(): String = "ffmpeg=${if (available) "ok" else "missing"} abi=$deviceAbi"
}
