package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.amatv.BuildConfig
import dev.amatv.model.ContentShelf
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MusicConnectionState {
    Demo,
    ReadyToSignIn,
    SignedIn,
}

interface RecommendationProvider {
    suspend fun createAmStation(songId: String): Pair<String?, List<Track>>
    suspend fun amStationNextTracks(stationId: String): List<Track>
    suspend fun ytmRadio(
        seed: Track,
        continuation: String? = null,
        excludedCatalogIds: Set<String> = emptySet(),
        continuationSeedVideoId: String? = null,
    ): MusicServices.ResolvedRadioPage
}

class MusicServices(context: Context) : RecommendationProvider {
    private val appContext = context.applicationContext
    val contentCache = ContentCache.get(appContext)
    private val tokenStore = SecureTokenStore(appContext)
    private val webAuthenticationSession = WebAuthenticationSession(appContext)
    private val developerTokens = DeveloperTokenClient(
        endpoint = BuildConfig.MUSICKIT_TOKEN_ENDPOINT,
        tokenStore = tokenStore,
        bundledToken = BuildConfig.MUSICKIT_DEVELOPER_TOKEN,
    )
    private val bridge = MusicKitBridge(appContext)
    private val lxPlayback = dev.amatv.luoxue.LxPlayback(
        appContext,
        dev.amatv.luoxue.AudioQualityPreferences(appContext),
    )
    private val lxPlayer = LxPlayerBridge(appContext, lxPlayback)
    private val songSpectrum = SongAudioSpectrum(appContext, lxPlayer::audioSessionId)
    private val mediaSession = PlaybackMediaSession(appContext)
    private val playbackPreparation = Mutex()
    private val api = AppleMusicApi(
        storefront = BuildConfig.APPLE_MUSIC_STOREFRONT,
        developerTokens = developerTokens,
        userToken = tokenStore::readUserToken,
    )
    private val appleLyrics = AppleMusicLyricsClient(
        storefront = BuildConfig.APPLE_MUSIC_STOREFRONT,
        storefrontProvider = api::userStorefront,
        userToken = tokenStore::readUserToken,
        debugDumpDir = if (BuildConfig.DEBUG) java.io.File(appContext.cacheDir, "lyrics-ttml") else null,
        contentCache = contentCache,
    )

    /**
     * v0.2 歌词层（veil 同源移植）：第三方五源级联（网易云 → QQ → 酷狗 → 酷我 → 咪咕）。
     * Apple TTML 歌词必须 media-user-token，未登录一律 401 → 歌词空白；
     * v0.2 起未登录与登录态统一走第三方层，Apple TTML 留作后续登录态增强。
     */
    private val thirdPartyLyrics = ThirdPartyLyrics()

    /** 当前歌词命中的第三方来源（网易云 / QQ音乐 / …），供播放页音源面板展示与歌词换源。 */
    private val _lyricsSource = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lyricsSource: kotlinx.coroutines.flow.StateFlow<String?> = _lyricsSource
    fun currentLyricsSource(): String? = _lyricsSource.value
    private val google = GoogleTvAuthorization(tokenStore)
    private val ytm = YtmInnertubeClient(google::accessToken)
    private val resolver = CrossCatalogResolver(api)

    /**
     * veil 匿名浏览数据层（音源接入 §3.2 方案 A）：未登录时 Home/Browse 的
     * 数据来源，与 veil.music 同源（amp-api 匿名 catalog + 官网编辑分组）。
     */
    private val veilSource = dev.amatv.veilsource.VeilBrowseSource(
        auth = dev.amatv.veilsource.AppleDisplayAuth.get(appContext),
        storefrontProvider = {
            runCatching { api.effectiveStorefront() }
                .getOrDefault(BuildConfig.APPLE_MUSIC_STOREFRONT)
        },
    )

    /** 未登录 Home/Browse 的 veil 编辑分组 shelves（空 = 调用方走 charts 兜底）。 */
    suspend fun veilShelves(locale: String, includeSpotlight: Boolean): List<ContentShelf> =
        veilSource.shelves(locale, includeSpotlight)

    val ytmPairingConfigured get() = google.configured
    val ytmSignedIn get() = google.signedIn
    suspend fun startYtmPairing(): GoogleTvPairing = google.startPairing()
    suspend fun awaitYtmPairing(pairing: GoogleTvPairing) = google.awaitPairing(pairing)
    fun signOutYtm() = google.signOut()

    data class ResolvedRadioPage(
        val tracks: List<Track>,
        val continuation: String?,
        val seedVideoId: String?,
    )

    private suspend fun resolveRecommendation(song: YtmSong): Track? = try {
        resolver.resolve(song)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w("AMATV-YTM", "Skipping recommendation ${song.videoId}", error)
        null
    }

    override suspend fun ytmRadio(
        seed: Track,
        continuation: String?,
        excludedCatalogIds: Set<String>,
        continuationSeedVideoId: String?,
    ): ResolvedRadioPage {
        val saved = YtmResolutionCursor.decode(continuation)
        val videoId = saved?.seedVideoId
            ?: continuationSeedVideoId?.takeIf { continuation != null }
            ?: seed.ytmVideoId ?: ytm.searchSong(seed.title, seed.artist, seed.durationMs)?.videoId
            ?: return ResolvedRadioPage(emptyList(), null, null)
        val candidates: List<YtmSong>
        val providerNext: String?
        if (saved != null && saved.pending.isNotEmpty()) {
            candidates = saved.pending
            providerNext = saved.providerContinuation
        } else {
            // A corrupt local cursor restarts the original seed instead of sending
            // application metadata as an undocumented provider token.
            val page = ytm.radio(videoId, continuation.takeUnless { YtmResolutionCursor.isLocal(it) })
            candidates = page.songs
            providerNext = page.continuation
        }
        // Resolve a bounded batch in provider order. Preserve every remaining song
        // in the persisted cursor rather than dropping everything after take(15).
        val batch = candidates.take(12)
        val resolved = coroutineScope {
            batch.map { song -> async { resolveRecommendation(song) } }.awaitAll()
                .filterNotNull().filter { it.catalogId !in excludedCatalogIds }
                .distinctBy { it.catalogId }
        }
        val next = YtmResolutionCursor(videoId, candidates.drop(batch.size), providerNext).encode()
        return ResolvedRadioPage(resolved, next, videoId)
    }

    suspend fun ytmHomeShelves(): List<ContentShelf> = coroutineScope {
        ytm.home().sortedByDescending { shelf ->
            when {
                shelf.title.contains("supermix", ignoreCase = true) -> 3
                shelf.title.contains("listen again", ignoreCase = true) -> 2
                else -> 1
            }
        }.take(3).mapIndexed { index, shelf ->
            async {
                val songs = if (shelf.playlistId != null && shelf.songs.isEmpty()) {
                    ytm.playlist(shelf.playlistId)
                } else shelf.songs
                val tracks = songs.take(6).map { song ->
                    async { resolveRecommendation(song) }
                }.awaitAll().filterNotNull()
                ContentShelf("ytm:$index", "YTM · ${shelf.title}", tracks)
            }
        }.awaitAll().filter { it.items.isNotEmpty() }
    }

    val connectionState: MusicConnectionState
        get() = when {
            tokenStore.readUserToken() != null -> MusicConnectionState.SignedIn
            isCatalogConfigured ->
                MusicConnectionState.ReadyToSignIn
            else -> MusicConnectionState.Demo
        }

    val setupMessage: String?
        get() = when {
            !isCatalogConfigured -> AppText.text(R.string.services_catalog_not_configured)
            BuildConfig.MUSICKIT_DEVELOPER_TOKEN.isNotBlank() &&
                !DeveloperTokenPolicy.isUsable(BuildConfig.MUSICKIT_DEVELOPER_TOKEN) &&
                BuildConfig.MUSICKIT_TOKEN_ENDPOINT.isBlank() -> AppText.text(R.string.services_credentials_expired_update)
            else -> null
        }

    val isCatalogConfigured: Boolean
        get() = DeveloperTokenPolicy.isConfigured(
            BuildConfig.MUSICKIT_DEVELOPER_TOKEN, BuildConfig.MUSICKIT_TOKEN_ENDPOINT,
        )

    suspend fun webAuthenticationIntent(): Intent {
        // Gecko owns a fresh private profile. Do not initialize the system
        // WebView provider on a compatibility build whose provider may be broken.
        if (!BuildConfig.AUTH_BROWSER_IS_GECKO) webAuthenticationSession.prepareForSignIn()
        val developerToken = developerTokens.token()
        return Intent().setClassName(appContext, BuildConfig.AUTH_BROWSER_ACTIVITY).putExtra(
            MusicKitWebAuthActivity.EXTRA_DEVELOPER_TOKEN,
            developerToken,
        )
    }

    fun completeWebAuthentication(data: Intent): String {
        val userToken = checkNotNull(
            data.getStringExtra(MusicKitWebAuthActivity.EXTRA_USER_TOKEN),
        ) { AppText.text(R.string.services_auth_invalid_user_token) }
        tokenStore.writeUserToken(userToken)
        mediaSession.clear()
        bridge.release()
        return userToken
    }

    fun signOut() {
        webAuthenticationSession.markLocalWebSessionForReset()
        songSpectrum.setActive(false)
        mediaSession.clear()
        bridge.release()
        tokenStore.clear()
    }

    suspend fun recommendations(): List<Track> = api.recommendations()
    suspend fun recommendationShelves(): List<ContentShelf> = api.recommendationShelves()
    suspend fun recentlyPlayed(): List<Track> = api.recentlyPlayed()
    suspend fun recentlyPlayedTracks(): List<Track> = api.recentlyPlayedTracks()
    suspend fun recentlyAdded(): List<Track> = api.recentlyAdded()
    suspend fun libraryPlaylists(): List<Track> = api.libraryPlaylists()
    suspend fun librarySongs(): List<Track> = api.librarySongs()
    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage = api.libraryPlaylistsPage(nextPage)
    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage = api.librarySongsPage(nextPage)
    suspend fun charts(types: String = "albums,songs,playlists"): List<Track> =
        api.charts(types)
    suspend fun liveRadio(): List<Track> = api.liveRadio()
    /** 搜索：未登录走 veil 匿名 catalog（amp-api）；已登录沿用现有链路。 */
    suspend fun search(term: String, locale: String = "zh-CN"): List<Track> {
        if (tokenStore.readUserToken() == null) return veilSource.search(term, locale)
        return api.search(term)
    }
    override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> =
        api.createContinuousStation(songId)
    override suspend fun amStationNextTracks(stationId: String): List<Track> =
        api.stationNextTracks(stationId)
    /**
     * 歌单/专辑曲目：未登录走 veil 匿名 catalog（amp-api，不依赖 bundled token）；
     * 已登录沿用现有 user-storefront 链路。修复"点击歌单/专辑显示无法载入曲目"。
     */
    suspend fun collectionTracks(collection: Track): List<Track> {
        if (tokenStore.readUserToken() == null) {
            // 未登录：只走 veil 匿名通道（api.music.apple.com 在 Demo 态无 bundled token 必失败）。
            return veilSource.collectionTracks(collection)
        }
        return api.collectionTracks(collection)
    }

    /**
     * v0.2 歌词分流：统一走第三方五源级联（标题+艺人+时长匹配），不依赖登录态。
     * [excludeSources] 是「歌词换源」要跳过的源标签集合；空集即正常全级联。
     * 失败返回空列表（调用方 requestLyricsWithRetry 会把空结果归为 Unavailable）。
     */
    suspend fun lyricsFor(
        track: Track,
        localization: LyricsLocalizationRequest = LyricsLocalizationRequest.Disabled,
        excludeSources: Set<String> = emptySet(),
    ): List<LyricLine> {
        val result = thirdPartyLyrics.fetch(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationMs / 1000L,
            excludeSources = excludeSources,
        )
        _lyricsSource.value = result.source.takeIf { it.isNotBlank() }
        Log.i("AMATV-Lyrics", "Third-party lyrics: source=${result.source} lines=${result.lines.size} " +
            "exclude=$excludeSources '${track.title}'")
        return result.lines
    }

    /** Resolve all songs through account-storefront equivalence, retaining source provenance. */
    suspend fun resolvePlayableTrack(track: Track): Track {
        if (track.catalogKind != dev.amatv.model.CatalogKind.Song) return track
        // 未登录走落雪音源：解析靠标题/艺人匹配，不依赖 Apple 等价链路（后者需要账号态）。
        if (tokenStore.readUserToken() == null) return track
        checkNotNull(track.catalogId) { AppText.text(R.string.services_catalog_id_missing) }
        return resolvePlayableSongTrack(track, api::resolveCatalogSongId)
    }

    suspend fun prepareAndPlay(track: Track): Long = preparePlayback(track)

    /**
     * 播放分流（音源接入核对报告 §6.3 用户拍板）：所有点播全走落雪 LX 直链
     * （Media3 ExoPlayer），未命中提示换源、不回退 MusicKit；Apple 登录态仅保留
     * 元数据能力（浏览/详情/歌词仍走 AppleMusicApi）。
     */
    suspend fun preparePlayback(
        track: Track,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ): Long {
        return playbackPreparation.withLock {
            withContext(Dispatchers.Default) {
                if (playWhenReady) mediaSession.playbackRequested()
                lxPlayer.preparePlayback(track, positionMs, playWhenReady)
            }
        }
    }

    /** 当前 LX 直链信息（音源/音质/格式），供播放页展示。 */
    fun currentLxStreamInfo(): dev.amatv.luoxue.AudioStream? = lxPlayer.currentStreamInfo()

    /** LX 直链信息流（解析成功后更新；新请求/释放时清空）。 */
    fun lxStreamInfo(): kotlinx.coroutines.flow.StateFlow<dev.amatv.luoxue.AudioStream?> =
        lxPlayer.streamInfo

    fun lxPlayback(): dev.amatv.luoxue.LxPlayback = lxPlayback

    /** 手动换源（播放页菜单键面板）：跳过当前命中脚本强制重解析，保持进度。 */
    fun switchLxSource(): Boolean = lxPlayer.switchSource()

    fun audioSpectrum(): AudioSpectrum = songSpectrum.sample()
    fun setSpectrumActive(enabled: Boolean) = songSpectrum.setActive(enabled)

    fun play() {
        mediaSession.playbackRequested()
        lxPlayer.play()
    }
    fun pause() {
        mediaSession.playbackPaused()
        lxPlayer.pause()
    }
    fun seekTo(positionMs: Long) = lxPlayer.seekTo(positionMs)
    fun skipToNext() = lxPlayer.skipToNext()
    fun skipToPrevious() = lxPlayer.skipToPrevious()
    fun skipToQueueItem(queueId: Long) = lxPlayer.skipToNext()
    // The app queue owns repeat, shuffle and autoplay. Each native queue contains
    // one selected track, so enabling a second queue engine would race the app.
    fun playbackSnapshot(): PlaybackSnapshot? = lxPlayer.playbackSnapshot()
    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) =
        lxPlayer.setPlaybackListener(listener)
    fun setSystemPlaybackCallbacks(callbacks: PlaybackMediaSession.Callbacks?) =
        mediaSession.setCallbacks(callbacks)
    fun publishSystemPlayback(snapshot: PlaybackSnapshot, canSkipNext: Boolean, canSkipPrevious: Boolean) =
        mediaSession.publish(snapshot, canSkipNext, canSkipPrevious)
    fun release() {
        songSpectrum.release()
        mediaSession.release()
        lxPlayer.release()
        bridge.release()
    }
}
