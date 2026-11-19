package dev.amatv.luoxue

import android.os.Build

/**
 * 曲目匹配：把 Apple 元数据与平台候选对齐。
 *
 * P0 实测暴露的三个坑，这里逐一处理：
 *  1) 繁简差异（後來的我們 / 后来的我们）→ ICU 繁转简 + 常见字兜底
 *  2) 艺人英文名（Mayday / 五月天）→ 别名表
 *  3) 跨语言曲名（Wayfarer / 山海）→ 无法靠标题，退化到"艺人+时长"第二遍搜索
 */
object TrackMatcher {

    private const val MIN_ACCEPT = 45

    private val VERSION_WORDS = listOf(
        "live", "remix", "piano", "cover", "karaoke", "instrumental", "acoustic", "dj", "demo",
        "remaster", "remastered", "deluxe", "edition", "version", "explicit", "mono", "stereo",
    )

    /**
     * 常见繁体 → 简体（API 23 无 ICU 时使用；24+ 走 Transliterator）。
     * 与歌词简繁开关共用同一份字表（[SimplifiedChinese.T2S_FALLBACK]），
     * 避免「匹配用一份、显示用另一份」两套字表越走越偏。
     */
    private val T2S = SimplifiedChinese.T2S_FALLBACK

    /** Apple 常用英文艺人名 → 平台中文名（按需扩充，先覆盖高频）。 */
    private val ARTIST_ALIAS = mapOf(
        "mayday" to "五月天",
        "jay chou" to "周杰伦",
        "g.e.m." to "邓紫棋",
        "gem" to "邓紫棋",
        "eason chan" to "陈奕迅",
        "jolin tsai" to "蔡依林",
        "jj lin" to "林俊杰",
        "a-mei" to "张惠妹",
        "amei" to "张惠妹",
        "fish leong" to "梁静茹",
        "stefanie sun" to "孙燕姿",
        "wang leehom" to "王力宏",
        "lee hom wang" to "王力宏",
        "no party for cao dong" to "草东没有派对",
        "sunset rollercoaster" to "落日飞车",
        "accusefive" to "告五人",
        "hins cheung" to "张敬轩",
        "khalil fong" to "方大同",
        "yoga lin" to "林宥嘉",
        "hebe tien" to "田馥甄",
        "sodagreen" to "苏打绿",
        "escape plan" to "逃跑计划",
        "new pants" to "新裤子",
        "taylor swift" to "泰勒斯威夫特",
        "adele" to "阿黛尔",
        "ed sheeran" to "艾德希兰",
        "bruno mars" to "布鲁诺马尔斯",
        "maroon 5" to "魔力红",
        "coldplay" to "酷玩乐队",
        "linkin park" to "林肯公园",
        "beyonce" to "碧昂丝",
        "one direction" to "单向组合",
        "justin bieber" to "贾斯汀比伯",
        "ariana grande" to "爱莉安娜格兰德",
        "billie eilish" to "比莉艾利什",
        "weeknd" to "威肯",
        "the weeknd" to "威肯",
        "dua lipa" to "杜阿利帕",
        "shawn mendes" to "肖恩蒙德兹",
        "alan walker" to "艾伦沃克",
        "imagine dragons" to "梦龙乐队",
        "queen" to "皇后乐队",
        "beyond" to "beyond",
        "张国荣" to "leslie cheung",
        "邓丽君" to "teresa teng",
        "张学友" to "jacky cheung",
        "刘德华" to "andy lau",
        "郭富城" to "aaron kwok",
        "林忆莲" to "sandy lam",
        "莫文蔚" to "karen mok",
        "蔡依林" to "jolin tsai",
        "张韶涵" to "angela chang",
        "五月天" to "mayday",
        "苏打绿" to "sodagreen",
        "告五人" to "accusefive",
    )

    /**
     * v0.7：从**原始**标题提取版本标记（live/remix/...）。
     * [normalize] 会抹掉这些词以保证标题主体可比，若在归一化串上做版本比对，
     * 惩罚条件永远为假（旧实现的死代码）。必须在原文上单独提取。
     */
    fun versionTags(raw: String): Set<String> {
        val lower = raw.lowercase()
        return VERSION_WORDS.filter { w ->
            Regex("(^|[^a-z])" + Regex.escape(w) + "([^a-z]|$)").containsMatchIn(lower)
        }.toSet()
    }

    fun normalize(raw: String): String {
        var s = raw.lowercase()
        // 先用内置字表保证任何环境（含纯 JVM 单测）行为一致；
        // ICU 可用时再补一遍覆盖字表未收录的字。
        s = s.map { T2S[it] ?: it }.joinToString("")
        s = runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate(s)
            } else s
        }.getOrDefault(s)
        s = s.replace(Regex("\\(.*?\\)|\\[.*?\\]|（.*?）|【.*?】"), " ")
        s = s.replace(Regex("[\\s\\-_/·,，.。'’\"“”!！?？:：&+]"), "")
        VERSION_WORDS.forEach { s = s.replace(it, "") }
        return s
    }

    /**
     * v0.4：共享的「规范艺人」入口——主艺人提取 → 别名映射 → 繁转简。
     * 仅做小写 + 繁转简 + 别名，不清洗标点（标点清洗由 normalize 负责）。
     * 供搜索层（PlatformSearch）与匹配层（本类）共用，保证两侧归一化一致，
     * 避免 Apple 元数据 "Jay Chou" 搜不到中文曲库、或「周杰倫」与「周杰伦」对不上。
     */
    fun canonicalArtist(raw: String): String {
        val first = raw.split("&", ",", "、", "/").first().trim()
        val lower = first.lowercase()
        val aliased = ARTIST_ALIAS[lower] ?: lower
        return normalize(aliased)
    }

    private fun normArtist(raw: String): String = canonicalArtist(raw)

    /**
     * v0.7：改用对称 Dice 系数。旧实现 `a.count{it in b}/a.size` 只按目标长度归一，
     * 候选比目标长（如带后缀）时召回虚高、短标题又容易 0 分，区分度差。
     * 对称后两侧长度都参与，短/长标题都稳定落在 0..30。
     */
    private fun bigramScore(target: String, cand: String): Int {
        if (target.isEmpty() || cand.isEmpty()) return 0
        val a = (0 until target.length - 1).map { target.substring(it, it + 2) }.toSet()
        val b = (0 until cand.length - 1).map { cand.substring(it, it + 2) }.toSet()
        if (a.isEmpty() && b.isEmpty()) return if (target == cand) 30 else 0
        if (a.isEmpty() || b.isEmpty()) return 0
        val overlap = a.count { it in b }
        return (30.0 * 2 * overlap / (a.size + b.size)).toInt()
    }

    fun score(cand: Candidate, title: String, artist: String, album: String, durationSec: Int): Int {
        val nt = normalize(title)
        val nc = normalize(cand.title)
        var s = when {
            nt == nc -> 55
            nc.contains(nt) || nt.contains(nc) -> 38
            else -> bigramScore(nt, nc)
        }

        val at = normArtist(artist)
        val ac = normArtist(cand.artist)
        var artistOk = false
        if (at.isNotEmpty() && ac.isNotEmpty()) {
            s += when {
                at == ac -> { artistOk = true; 25 }
                ac.contains(at) || at.contains(ac) -> { artistOk = true; 18 }
                normalize(artist).contains(normalize(cand.artist)) -> { artistOk = true; 12 }
                else -> 0
            }
        }

        if (album.isNotBlank() && cand.album.isNotBlank() && normalize(album) == normalize(cand.album)) s += 8

        var durDiff = -1
        if (durationSec > 0 && cand.durationSec > 0) {
            durDiff = kotlin.math.abs(durationSec - cand.durationSec)
            s += when {
                durDiff <= 2 -> 12
                durDiff <= 5 -> 8
                durDiff <= 10 -> 3
                durDiff > 30 -> -10
                else -> 0
            }
        }

        // 版本一致性：在**原始**标题上比对版本标记。
        // 旧实现在归一化串上比对（normalize 已抹掉版本词），条件永远为假=死代码，
        // 导致 "Never Gonna Give You Up" 会命中它的 live/remix 版。
        val tv = versionTags(title)
        val cv = versionTags(cand.title)
        if (tv != cv) {
            // 一方有版本词另一方没有，或两版不同 → 强惩罚（避免版本错配）
            s -= if (tv.isEmpty() || cv.isEmpty()) 16 else 12
        }

        // 同名不同曲：标题撞上但艺人完全不符、时长又对不上时，不能算命中
        // （P0 例：Apple 的 Wayfarer 被匹配到另一位歌手的同名曲）
        if (!artistOk && durDiff > 5) s -= 20
        return s
    }

    /** 标题+艺人一遍匹配。 */
    fun rank(
        cands: List<Candidate>,
        title: String,
        artist: String,
        album: String = "",
        durationSec: Int = 0,
    ): List<Candidate> {
        cands.forEach { it.score = score(it, title, artist, album, durationSec) }
        return cands.sortedByDescending { it.score }
    }

    /**
     * 跨语言曲名兜底：只靠时长 + 艺人弱匹配。
     * v0.4：时长阈值放宽——别名命中艺人时由 ±3s 放宽到 ±8s（权重 30，仍低于
     * 精确 ±2s 的 50；艺人不符的候选会被过滤），覆盖周杰伦大量 live/remix/
     * 同名版本，避免跨语言/重版本时兜底搜不到。
     */
    fun rankByDurationOnly(
        cands: List<Candidate>,
        artist: String,
        durationSec: Int,
    ): List<Candidate> {
        val at = normArtist(artist)
        return cands.map { c ->
            var s = 0
            val ac = normArtist(c.artist)
            val artistHit = at.isNotEmpty() && ac.isNotEmpty() &&
                (at == ac || ac.contains(at) || at.contains(ac))
            if (durationSec > 0 && c.durationSec > 0) {
                val d = kotlin.math.abs(durationSec - c.durationSec)
                s += if (artistHit) {
                    when {
                        d <= 2 -> 50
                        d <= 8 -> 30
                        else -> 0
                    }
                } else {
                    when {
                        d <= 2 -> 50
                        d <= 3 -> 40
                        else -> 0
                    }
                }
            }
            if (artistHit) s += 20
            c.score = s
            c
        }.filter { it.score >= 40 }.sortedByDescending { it.score }
    }

    fun isAcceptable(c: Candidate) = c.score >= MIN_ACCEPT
}
