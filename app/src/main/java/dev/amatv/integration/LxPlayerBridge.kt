package dev.amatv.integration

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import dev.amatv.luoxue.AudioStream
import dev.amatv.luoxue.LxPlayback
import dev.amatv.luoxue.toLxSong
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 落雪音源的 Media3 播放桥（对应 MusicKitBridge 的对外接口面）。
 *
 * 决策记录（音源接入核对报告 §6.3）：所有点播全走 LX 直链（Media3 ExoPlayer），
 * 未命中提示换源、不回退 MusicKit；Apple 登录态仅保留元数据能力。
 *
 * 与 MusicKitBridge 的契约对齐点（App 层 `applyPlaybackSnapshot` 依赖）：
 *  - `preparePlayback` 返回 generation；snapshot 携带同值 `requestGeneration`；
 *  - 准备好后 `hasPlayerCurrentTrack = true` 且 `currentTrack` 即请求的 Track
 *    （`pendingRequestedSongCatalogId` 校验靠它）；
 *  - 播完发 `isEnded = true`（App 据此推进队列）；
 *  - 失败发 `errorMessage` + `failureKind`（`TrackUnavailable` 触发 App 的
 *    跳过/重试语义，即「未命中换源」提示）。
 *
 * 直链过期处理（veil 同款）：每首歌最多一次重解析，保持进度恢复播放；
 * 重解析仍失败则报 TrackUnavailable。
 */
class LxPlayerBridge(
    private val context: Context,
    private val lx: LxPlayback,
    initialDecoderMode: DecoderMode = DecoderMode.DEFAULT,
) {
    companion object {
        private const val TAG = "AmAtvLxPlayer"
        private const val SNAPSHOT_POLL_MS = 250L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepareGeneration = AtomicLong(0L)

    /**
     * v0.8: decoder path (veil.music DecoderMode). Read when the player is built;
     * [applyDecoderMode] rebuilds a live player in place so the switch is immediate.
     */
    @Volatile private var decoderMode: DecoderMode = initialDecoderMode

    @Volatile private var playbackListener: ((PlaybackSnapshot) -> Unit)? = null
    @Volatile private var requestedTrack: Track? = null
    @Volatile private var activeGeneration = 0L
    @Volatile private var desiredPlaying = false
    @Volatile private var resumePositionMs = 0L
    @Volatile private var prepared = false
    @Volatile private var ended = false
    @Volatile private var pendingPlaybackError: String? = null
    @Volatile private var pendingFailureKind: PlaybackFailureKind? = null
    @Volatile private var retriedSongId: String? = null
    @Volatile private var latestSnapshot: PlaybackSnapshot? = null
    @Volatile private var snapshotScheduled = false
    private var resolveJob: Job? = null

    /** 当前生效的直链信息（音源/音质/格式展示与诊断用；StateFlow 供 UI 重组）。 */
    @Volatile private var currentStream: AudioStream? = null
    private val _streamInfo = kotlinx.coroutines.flow.MutableStateFlow<AudioStream?>(null)
    val streamInfo: kotlinx.coroutines.flow.StateFlow<AudioStream?> = _streamInfo
    fun currentStreamInfo(): AudioStream? = currentStream

    private var player: ExoPlayer? = null

    private val snapshotPoll = object : Runnable {
        override fun run() {
            if (playbackListener == null) return
            dispatchSnapshot()
            mainHandler.postDelayed(this, SNAPSHOT_POLL_MS)
        }
    }

    private fun ensurePlayer(): ExoPlayer? {
        if (player == null) {
            player = runCatching {
                // v0.8: 软解/硬解切换（veil 同款）。硬解 = 仅平台 MediaCodec；
                // 软解 = FFmpeg 扩展渲染器优先（缺原生库时静默回落系统解码）。
                val renderersFactory = DefaultRenderersFactory(context)
                    .setExtensionRendererMode(
                        if (decoderMode == DecoderMode.HARDWARE) {
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                        } else {
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        },
                    )
                    .setEnableDecoderFallback(true)
                ExoPlayer.Builder(context, renderersFactory)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    )
                    .setHandleAudioBecomingNoisy(true)
                    .build()
                    .also {
                        it.addListener(playerListener)
                        Log.i(TAG, "Player built decoder=${decoderMode.key} (${FfmpegSupport.describe()})")
                    }
            }.onFailure { Log.e(TAG, "ExoPlayer create failed", it) }.getOrNull()
        }
        return player
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) ended = true
            dispatchSnapshot()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            dispatchSnapshot()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    // ── 对外接口（与 MusicServices 使用 MusicKitBridge 的方式对齐）────────────

    @Synchronized
    fun preparePlayback(
        track: Track,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ): Long {
        val generation = prepareGeneration.incrementAndGet()
        activeGeneration = generation
        requestedTrack = track
        desiredPlaying = playWhenReady
        resumePositionMs = positionMs.coerceAtLeast(0L)
        ended = false
        prepared = false
        pendingPlaybackError = null
        pendingFailureKind = null
        currentStream = null
        _streamInfo.value = null
        ensurePlayer()
        resolveJob?.cancel()
        mainHandler.post {
            // 新请求立即停掉旧歌；解析期间以 buffering 态等待。
            player?.stop()
            player?.clearMediaItems()
            dispatchSnapshot()
        }
        Log.i(TAG, "Prepare generation=$generation track=${track.id} kind=${track.catalogKind} " +
            "positionMs=$resumePositionMs playWhenReady=$playWhenReady")
        resolveJob = scope.launch {
            val song = track.toLxSong()
            val stream = runCatching { lx.resolve(song) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            if (generation != prepareGeneration.get() || generation != activeGeneration) return@launch
            if (stream == null) {
                pendingFailureKind = PlaybackFailureKind.TrackUnavailable
                pendingPlaybackError = "落雪音源未命中「${song.title}」，可尝试换源（长按换源/切下一首）"
                Log.w(TAG, "Resolve miss: ${song.title} generation=$generation")
                dispatchSnapshot()
                return@launch
            }
            _streamInfo.value = stream
            currentStream = stream
            retriedSongId = null
            mainHandler.post {
                if (generation != prepareGeneration.get() || generation != activeGeneration) return@post
                val p = ensurePlayer() ?: return@post
                p.setMediaItem(buildMediaItem(song, stream), resumePositionMs)
                p.prepare()
                p.playWhenReady = desiredPlaying
                prepared = true
                Log.i(TAG, "LX stream ready generation=$generation source=${stream.source} " +
                    "quality=${stream.quality} kind=${stream.kind} kbps=${stream.kbps}")
                dispatchSnapshot()
            }
        }
        return generation
    }

    fun play() {
        desiredPlaying = true
        mainHandler.post {
            val p = player ?: return@post
            p.playWhenReady = true
            if (p.playbackState == Player.STATE_READY) p.play()
        }
    }

    fun pause() {
        desiredPlaying = false
        mainHandler.post {
            player?.pause()
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            val p = player ?: return@post
            ended = false
            p.seekTo(positionMs.coerceAtLeast(0L))
        }
    }

    /** App 队列推进由 PlaybackQueueController 负责；桥内单曲播放无相邻条目。 */
    fun skipToNext() { /* no-op: queue advance is app-owned */ }
    fun skipToPrevious() { /* no-op: queue advance is app-owned */ }

    fun playbackSnapshot(): PlaybackSnapshot? = latestSnapshot

    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        playbackListener = listener
        mainHandler.removeCallbacks(snapshotPoll)
        if (listener != null) mainHandler.post(snapshotPoll)
    }

    /** LX 播放无 MusicKit 频谱会话；返回 0 表示频谱不可用。 */
    fun audioSessionId(): Int = player?.audioSessionId ?: 0

    @Synchronized
    fun release() {
        prepareGeneration.incrementAndGet()
        activeGeneration = 0L
        mainHandler.removeCallbacks(snapshotPoll)
        snapshotScheduled = false
        resolveJob?.cancel()
        resolveJob = null
        val old = player
        mainHandler.post {
            old?.release()
        }
        player = null
        playbackListener = null
        latestSnapshot = null
        requestedTrack = null
        currentStream = null
        _streamInfo.value = null
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private fun buildMediaItem(song: dev.amatv.luoxue.Song, stream: AudioStream): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(stream.url)
        stream.mimeType.takeIf { it.isNotBlank() }?.let(builder::setMimeType)
        return builder.build()
    }

    /**
     * 手动换源（播放页菜单键面板，veil 同款语义）：跳过「当前命中的脚本」强制重解析，
     * 保持当前进度与播放状态；解析不到则报 TrackUnavailable（不自动跳下一首，由用户决定）。
     * 返回 false 表示当前没有可换源的播放（未解析/已释放）。
     */
    @Synchronized
    fun switchSource(): Boolean {
        val track = requestedTrack ?: return false
        val stream = currentStream ?: return false
        if (prepareGeneration.get() != activeGeneration) return false
        val excludeScript = stream.script.takeIf { it.isNotBlank() }
        val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: resumePositionMs
        val wasPlaying = desiredPlaying
        val generation = activeGeneration
        retriedSongId = track.id   // 复用防死循环标记：手动换源后直链错误不再自动重解析同源
        lx.invalidate(track.id)
        prepared = false
        ended = false
        pendingPlaybackError = null
        pendingFailureKind = null
        currentStream = null
        _streamInfo.value = null
        dispatchSnapshot()
        resolveJob?.cancel()
        Log.i(TAG, "Switch source track=${track.id} excludeScript=$excludeScript pos=${positionMs}ms")
        resolveJob = scope.launch {
            val song = track.toLxSong()
            val newStream = runCatching { lx.resolve(song, force = true, excludeScript = excludeScript) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            if (prepareGeneration.get() != generation || activeGeneration != generation) return@launch
            if (newStream == null) {
                pendingFailureKind = PlaybackFailureKind.TrackUnavailable
                pendingPlaybackError = "换源未命中「${song.title}」（已跳过 ${excludeScript ?: "当前源"}）"
                Log.w(TAG, "Switch source miss: ${song.title} exclude=$excludeScript")
                dispatchSnapshot()
                return@launch
            }
            _streamInfo.value = newStream
            currentStream = newStream
            mainHandler.post {
                if (prepareGeneration.get() != generation || activeGeneration != generation) return@post
                val p = ensurePlayer() ?: return@post
                p.setMediaItem(buildMediaItem(song, newStream), positionMs)
                p.prepare()
                p.playWhenReady = wasPlaying
                prepared = true
                Log.i(TAG, "Switch source ok generation=$generation source=${newStream.source} " +
                    "script=${newStream.script} quality=${newStream.quality} kbps=${newStream.kbps}")
                dispatchSnapshot()
            }
        }
        return true
    }

    /**
     * v0.8: decoder switch (veil.music's setDecoderMode). The decoder is picked when a
     * player is built, so a live player is rebuilt in place: position and play/pause
     * intent are captured first, the same already-resolved stream is re-prepared at the
     * same offset on the new decoder path. No re-resolve, no queue churn - the switch is
     * observable immediately instead of "from the next song". When nothing is playing the
     * mode just takes effect on the next preparePlayback.
     */
    @Synchronized
    fun applyDecoderMode(mode: DecoderMode) {
        if (mode == decoderMode) return
        decoderMode = mode
        Log.i(TAG, "Decoder mode -> ${mode.key} (${FfmpegSupport.describe()})")
        val track = requestedTrack ?: return
        val stream = currentStream ?: return
        if (prepareGeneration.get() != activeGeneration) return
        val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: resumePositionMs
        val wasPlaying = desiredPlaying
        val generation = activeGeneration
        prepared = false
        ended = false
        val old = player
        player = null
        currentStream = stream
        mainHandler.post { runCatching { old?.release() } }
        mainHandler.post {
            if (prepareGeneration.get() != generation || generation != activeGeneration) return@post
            val p = ensurePlayer() ?: return@post
            p.setMediaItem(buildMediaItem(track.toLxSong(), stream), positionMs)
            p.prepare()
            p.playWhenReady = wasPlaying
            prepared = true
            Log.i(TAG, "Decoder rebuild ok generation=$generation mode=${mode.key} pos=${positionMs}ms")
            dispatchSnapshot()
        }
    }

    /** 直链 TTL≈21 分钟：过期/403 时重解析一次并保持进度（veil 同款防死循环标记）。 */
    private fun handlePlayerError(error: PlaybackException) {
        val track = requestedTrack ?: return
        if (prepareGeneration.get() != activeGeneration) return
        val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: resumePositionMs
        val wasPlaying = desiredPlaying
        Log.w(TAG, "Player error generation=$activeGeneration track=${track.id} " +
            "errorCode=${error.errorCodeName} pos=${positionMs}ms cause=${error.cause?.message}")
        if (retriedSongId != track.id) {
            retriedSongId = track.id
            lx.invalidate(track.id)
            prepared = false
            dispatchSnapshot()
            resolveJob?.cancel()
            resolveJob = scope.launch {
                // v0.4：自动重解析也传入 excludeScript，避免「原地命中同一失效源」。
                // 跳过当前直链的脚本，落到下一个可用脚本（若该源还有其它脚本）。
                val excludeScript = currentStream?.script?.takeIf { it.isNotBlank() }
                val stream = runCatching {
                    lx.resolve(track.toLxSong(), force = true, excludeScript = excludeScript)
                }.getOrNull()
                if (prepareGeneration.get() != activeGeneration) return@launch
                if (stream == null) {
                    pendingFailureKind = PlaybackFailureKind.TrackUnavailable
                    pendingPlaybackError = "直链已失效且重解析未命中「${track.title}」（已跳过 ${excludeScript ?: "未知源"}），可手动换源或跳过"
                    dispatchSnapshot()
                    return@launch
                }
                _streamInfo.value = stream
                currentStream = stream
                mainHandler.post {
                    if (prepareGeneration.get() != activeGeneration) return@post
                    val p = player ?: return@post
                    p.setMediaItem(buildMediaItem(track.toLxSong(), stream), positionMs)
                    p.prepare()
                    p.playWhenReady = wasPlaying
                    prepared = true
                    Log.i(TAG, "Re-resolved generation=$activeGeneration source=${stream.source} " +
                        "script=${stream.script} quality=${stream.quality} kbps=${stream.kbps}")
                    dispatchSnapshot()
                }
            }
            return
        }
        pendingFailureKind = PlaybackFailureKind.TrackUnavailable
        pendingPlaybackError = "播放失败「${track.title}」（${error.errorCodeName}），可尝试换源"
        dispatchSnapshot()
    }

    private fun dispatchSnapshot() {
        if (playbackListener == null || snapshotScheduled) return
        snapshotScheduled = true
        val generation = prepareGeneration.get()
        mainHandler.post {
            snapshotScheduled = false
            if (generation != prepareGeneration.get() || generation != activeGeneration) return@post
            val snapshot = buildSnapshot(generation) ?: return@post
            latestSnapshot = snapshot
            playbackListener?.invoke(snapshot)
        }
    }

    private fun buildSnapshot(generation: Long): PlaybackSnapshot? {
        val track = requestedTrack ?: return null
        val p = player
        val position = p?.currentPosition?.coerceAtLeast(0L) ?: resumePositionMs
        val duration = p?.duration?.takeIf { it > 0L } ?: track.durationMs.coerceAtLeast(1L)
        val buffering = p?.playbackState == Player.STATE_BUFFERING
        val playing = p?.isPlaying == true
        return PlaybackSnapshot(
            currentTrack = track,
            hasPlayerCurrentTrack = prepared,
            queue = listOf(track),
            positionMs = position,
            durationMs = duration,
            isPlaying = playing && pendingPlaybackError == null,
            isBuffering = buffering && pendingPlaybackError == null,
            canSkipNext = true,
            canSkipPrevious = true,
            repeatMode = PlaybackRepeatMode.Off,
            shuffleEnabled = false,
            autoplayEnabled = false,
            canSetRepeat = true,
            canSetShuffle = true,
            canSetAutoplay = false,
            errorMessage = pendingPlaybackError,
            capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            isEnded = ended && prepared,
            requestGeneration = generation,
            isPrepared = prepared || pendingPlaybackError != null,
            failureKind = pendingFailureKind,
        )
    }
}
