package dev.amatv.luoxue

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 音频音质档位。（移植自 veil.music，去除 Hilt 依赖改为手动构造。）
 *
 * [ladder] 是「请求 → 兜底」的降级顺序，交给落雪脚本按平台可用列表过滤；
 * [minKbps] 是直链校验门槛：低于它的流会被判定为试听片段/假档位并换源。
 * 档位对齐落雪脚本的 MUSIC_QUALITIES 公共集合：
 * 128k / 192k / 320k / flac / flac24bit / hires（每带）。
 */
enum class AudioQuality(
    val key: String,
    val ladder: List<String>,
    val minKbps: Int,
) {
    /** 128 kbps：省流量，弱网更稳。 */
    STANDARD("128k", listOf("128k"), 96),

    /** 192 kbps：中品质量。 */
    MEDIUM("192k", listOf("192k", "128k"), 96),

    /** 320 kbps：体积与兼容性平衡最好。 */
    HIGH("320k", listOf("320k", "192k", "128k"), 96),

    /** 无损：优先 FLAC，拿不到再退 320k。 */
    LOSSLESS("flac", listOf("flac", "320k", "192k", "128k"), 96),

    /** 高解析无损（Hi-Res，默认档）：优先 24bit FLAC，逐级退到普通无损。 */
    HIRES("hires", listOf("flac24bit", "flac", "320k", "192k", "128k"), 96);

    /** 面向设置项 Stepper 的展示名。 */
    val label: String get() = when (this) {
        STANDARD -> "标准 128k"
        MEDIUM -> "中品 192k"
        HIGH -> "高品質 320k"
        LOSSLESS -> "无损 FLAC"
        HIRES -> "Hi-Res 无损"
    }

    /** 设置项说明文案。 */
    val hint: String get() = when (this) {
        STANDARD -> "省流量，弱网更稳"
        MEDIUM -> "比标准更清晰，体积适中"
        HIGH -> "体积与音质平衡，推荐"
        LOSSLESS -> "优先 FLAC，拿不到退 320k"
        HIRES -> "优先 24bit 无损，逐级回退"
    }

    /** 遥控器左右键走这里。刻意用「夹住」而不是循环：循环会让 128k 按一下左键直接跳到无损。 */
    fun next(direction: Int): AudioQuality {
        val i = (ordinal + direction.coerceIn(-1, 1)).coerceIn(0, entries.lastIndex)
        return entries[i]
    }

    companion object {
        /** 出厂默认：Hi-Res 无损（优先 24bit FLAC），用户仍可在设置页随时改档。 */
        val DEFAULT = HIRES

        fun fromKey(key: String?): AudioQuality =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 音质选择。落雪解析层与设置页共用，改档即时生效（清解析缓存）。 */
class AudioQualityPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("audio_quality_prefs", Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(AudioQuality.fromKey(prefs.getString(KEY, null)))
    val quality: StateFlow<AudioQuality> = _quality

    fun get(): AudioQuality = _quality.value

    fun set(quality: AudioQuality) {
        if (quality == _quality.value) return
        prefs.edit { putString(KEY, quality.key) }
        _quality.value = quality
    }

    private companion object {
        const val KEY = "quality"
    }
}
