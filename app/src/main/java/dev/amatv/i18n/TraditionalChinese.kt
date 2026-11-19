package dev.amatv.i18n

import android.icu.text.Transliterator

/**
 * v0.2：本项目只有简体版本——繁体 → 简体统一转换。
 *
 * 背景：Apple 元数据里港澳台歌手名/曲名常是繁体（如「周杰倫」），第三方歌词
 * 平台返回的正文多为简体。为界面统一，显示层与歌词解析层都用本工具归一化。
 *
 * 实现：系统 ICU `Transliterator("Traditional-Simplified")`（API 24+，minSdk 26
 * 可用，覆盖完整）；Transliterator 实例非线程安全，读取包一层锁。ICU 不可用时
 * 回退到 [dev.amatv.luoxue.SimplifiedChinese.T2S_FALLBACK] 常用字表（覆盖有限）。
 */
object TraditionalChinese {

    private val transliterator: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Traditional-Simplified") }.getOrNull()
    }

    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        val engine = transliterator ?: return fallback(text)
        return synchronized(engine) {
            runCatching { engine.transliterate(text) }.getOrDefault(fallback(text))
        }
    }

    private fun fallback(text: String): String = buildString(text.length) {
        text.forEach { ch -> append(dev.amatv.luoxue.SimplifiedChinese.T2S_FALLBACK[ch] ?: ch) }
    }
}
