package dev.amatv.luoxue

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7 落雪音源命中率 / 可用性回归测试（纯 JVM，不依赖 Android 运行时）。
 *
 * 覆盖本次优化的四处结构性修复：
 *  1) 版本标记提取（死代码修复）；
 *  2) 艺人别名 / 繁简归一化；
 *  3) Dice bigram 对称评分；
 *  4) 可用性判断：长度/码率未知时不再误杀完整曲目。
 */
class TrackMatcherTest {

    @Test
    fun versionTags_detectsLiveRemixOnRawTitle() {
        assertTrue(TrackMatcher.versionTags("晴天 (Live)").contains("live"))
        assertTrue(TrackMatcher.versionTags("Anti-Hero (Remix)").contains("remix"))
        assertTrue(TrackMatcher.versionTags("Song - Piano Version").contains("piano"))
        assertTrue(TrackMatcher.versionTags("晴天").isEmpty())
    }

    @Test
    fun versionMismatch_isPenalized() {
        val original = TrackMatcher.score(
            Candidate("wy", "1", "晴天", "周杰伦", "叶惠美", 269),
            title = "晴天", artist = "周杰伦", album = "叶惠美", durationSec = 269,
        )
        val live = TrackMatcher.score(
            Candidate("wy", "1", "晴天 Live", "周杰伦", "叶惠美", 269),
            title = "晴天", artist = "周杰伦", album = "叶惠美", durationSec = 269,
        )
        // 原版得分须明显高于 live 版（版本惩罚生效）
        assertTrue("original=$original live=$live", original - live >= 10)
    }

    @Test
    fun canonicalArtist_expandsAliasAndSimplifies() {
        assertEquals("五月天", TrackMatcher.canonicalArtist("Mayday"))
        assertEquals("周杰伦", TrackMatcher.canonicalArtist("Jay Chou"))
        // 繁体「周杰倫」归一后应与简体一致
        assertEquals(TrackMatcher.canonicalArtist("周杰伦"), TrackMatcher.canonicalArtist("周杰倫"))
    }

    @Test
    fun normalize_stripsBracketsAndVersionNoise() {
        assertEquals("晴天", TrackMatcher.normalize("晴天 (Live版)"))
        assertEquals("hotelcalifornia", TrackMatcher.normalize("Hotel California (Remastered 2013)"))
    }

    @Test
    fun score_sameSongHighDifferentSongLow() {
        val same = TrackMatcher.score(
            Candidate("wy", "1", "晴天", "周杰伦", "叶惠美", 269),
            "晴天", "周杰伦", "叶惠美", 269,
        )
        val other = TrackMatcher.score(
            Candidate("wy", "2", "Wayfarer", "Another Artist", "X", 200),
            "晴天", "周杰伦", "叶惠美", 269,
        )
        assertTrue("same=$same other=$other", same > other + 30)
        assertTrue(TrackMatcher.isAcceptable(
            Candidate("wy", "1", "晴天", "周杰伦", "叶惠美", 269).also { it.score = same },
        ))
    }

    @Test
    fun durationFallback_matchesByArtistAndTime() {
        val ranked = TrackMatcher.rankByDurationOnly(
            listOf(
                Candidate("wy", "1", "晴天Live现场", "周杰伦", "", 271),
                Candidate("tx", "2", "无关歌曲", "周杰伦", "", 120),
                Candidate("kg", "3", "晴天", "周杰伦", "", 268),
            ),
            artist = "周杰伦", durationSec = 269,
        )
        assertTrue(ranked.isNotEmpty())
        // 时长接近的两首应排在时长差很大的前面
        assertTrue(ranked.first().durationSec in 268..271)
    }
}

class AudioResolverAcceptanceTest {

    private fun probe(
        ok: Boolean = true, kind: String = "mp3", kbps: Int = 0, bytes: Long = 0L,
    ) = StreamVerifier.Probe(ok, kind, "audio/mpeg", kbps, bytes, 200)

    @Test
    fun flacAlwaysAccepted() {
        assertTrue(AudioResolver.isAcceptableStream(probe(kind = "flac", kbps = 0, bytes = 0), 96))
        assertTrue(AudioResolver.isAcceptableStream(probe(kind = "flac24bit"), 96))
    }

    @Test
    fun knownBitrateBelowThresholdRejected() {
        // 明确低码率 + 明确小体积 = 试听片段
        assertFalse(AudioResolver.isAcceptableStream(probe(kbps = 64, bytes = 600_000L), 96))
    }

    @Test
    fun unknownBitrateFullSizeAccepted() {
        //  regressions：旧实现按 0kbps 直接拒，误杀 5MB 正常 mp3
        assertTrue(AudioResolver.isAcceptableStream(probe(kbps = 0, bytes = 5_000_000L), 96))
    }

    @Test
    fun unknownBitrateAndUnknownLengthAccepted() {
        // chunked CDN 无 Content-Range：魔数已验证，不应误杀
        assertTrue(AudioResolver.isAcceptableStream(probe(kbps = 0, bytes = 0L), 96))
    }

    @Test
    fun badContainerRejected() {
        assertFalse(AudioResolver.isAcceptableStream(probe(ok = false, kind = "unknown"), 96))
    }
}

class StreamVerifierSniffTest {

    private val verifier = StreamVerifier(OkHttpClient())

    @Test
    fun sniffsKnownContainers() {
        assertEquals("flac", verifier.sniff("fLaC123456789012".toByteArray()))
        assertEquals("mp3", verifier.sniff("ID3000000000000".toByteArray()))
        assertEquals(
            "mp3",
            verifier.sniff(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        )
        assertEquals("mp4/aac", verifier.sniff("____ftypisom0000".toByteArray()))
        assertEquals("ogg", verifier.sniff("OggS000000000000".toByteArray()))
    }

    @Test
    fun shortOrUnknownReturnsUnknown() {
        assertEquals("unknown", verifier.sniff("abc".toByteArray()))
        assertEquals("unknown", verifier.sniff("XXXXXXXXXXXXXXXX".toByteArray()))
    }
}
