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
    private val lxPlayer = LxPlayerBridge(appContext, lxPlayback, DecoderMode.load(appContext))
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

    // v0.6 本地资料库：收藏 / 自建歌单 / 最近播放，全部落在 filesDir/local_library.json，
    // 不依赖 Apple 登录。PlaylistImporter 走独立 OkHttp（QQ/网易云/酷狗公开歌单 + 文本列表）。
    val localLibrary = LocalLibraryStore(appContext)
    val localPlaylistImporter = PlaylistImporter(
        http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
        store = localLibrary,
    )

    /** v0.6: phone configuration web server (QR on TV -> HTML on phone -> /playlist-import). */
    val phoneConfigServer = dev.amatv.web.PhoneConfigServer(
        appContext,
        localPlaylistImporter,
        appleMusic = object : dev.amatv.web.PhoneConfigServer.AppleMusicBridge {
            override fun available(): Boolean = isCatalogConfigured

            override fun signedIn(): Boolean = tokenStore.readUserToken() != null

            override suspend fun developerToken(): String? = signInDeveloperToken()

            override suspend fun completeSignIn(userToken: String): Boolean = completePhoneSignIn(userToken)
        },
    ).also { server ->
        server.boot(kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ))
    }

    /**
     * v0.8: 登录链路的开发者令牌解析（打包令牌 → Apple 官网抓取，veil 同款兜底）。
     *
     * 打包/endpoint 令牌不可用时（本地 debug 构建常见），回落到 [AppleDisplayAuth]
     * 从 music.apple.com 官网抓取的匿名 developer JWT——amp-api 接受它配合
     * media-user-token 访问 /v1/me（veil.music 书签方案同款背书）。
     * 都拿不到返回 null，8080 页会展示手动导入令牌的兜底方案。
     *
     * 注意：AppleDisplayAuth.bearer() 的抓取是同步阻塞网络请求，必须在 IO 线程
     * 执行（否则 NetworkOnMainThreadException 会被 runCatching 静默吞掉）。
     */
    suspend fun signInDeveloperToken(): String? = withContext(Dispatchers.IO) {
        runCatching { developerTokens.token() }.getOrNull()
            ?.takeIf { DeveloperTokenPolicy.isUsable(it) }
            ?: dev.amatv.veilsource.AppleDisplayAuth.get(appContext).bearer(forceRefresh = true)
    }

    /** 导入一个链接 / 文本歌单，返回结果（Ok=成功+数量, Err=原因）。 */
    suspend fun importLocalPlaylist(raw: String) = localPlaylistImporter.import(raw)

    /** 切换某首歌是否收藏。 */
    fun toggleLocalFavorite(track: Track) = localLibrary.toggleFavorite(track)

    /**
     * 把一张专辑/歌单整体存进本地资料库（专辑页「添加歌单」按钮）。
     *
     * [originKey] 必须是稳定来源标识（`album:<catalogId>` / `playlist:<id>`），
     * 用标题会在同名专辑之间串台、且改名后重复添加；[coverUrl] 传容器封面，
     * 让资料库以「一个歌单卡片」呈现整张专辑，而不是散成一堆单曲。
     */
    fun addLocalPlaylistFromAlbum(
        name: String,
        originKey: String,
        coverUrl: String?,
        tracks: List<Track>,
    ): LocalLibraryStore.PlaylistUpsert =
        localLibrary.addAsPlaylist(name, originKey, coverUrl, tracks)

    /** 新建一个本地歌单（可预填歌曲）。 */
    fun createLocalPlaylist(name: String, tracks: List<Track> = emptyList()): LocalLibraryStore.LocalPlaylist =
        localLibrary.createPlaylist(name, tracks)

    /** 记录一条最近播放（有效播放 >10s 才应在调用方触发）。 */
    fun recordLocalRecent(track: Track) = localLibrary.recordRecent(track)

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

    /**
     * Phone web sign-in result (8080 page). Validates the token before storing it.
     *
     * Only account metadata is adopted: playback still goes through the LX
     * (落雪) engine and this app never reports play history to Apple Music.
     */
    internal suspend fun completePhoneSignIn(userToken: String): Boolean = runCatching {
        val candidate = validatePhoneAuthentication(userToken)
        completePhoneAuthentication(candidate)
    }.isSuccess

    /**
     * v0.8 上游同款（am_atv phoneAuthentication）：开一个临时区网配对服务器，
     * 手机扫码打开 Apple 登录页（MusicKit JS），授权后送回 Music User Token。
     * developerToken 走 [signInDeveloperToken] 级联（打包 → 官网抓取兜底）。
     */
    internal suspend fun phoneAuthentication(advertisedAuthority: String? = null, port: Int = 0): PhoneMusicAuthorization {
        val developerToken = signInDeveloperToken()
            ?: throw IllegalStateException(AppText.text(R.string.services_credentials_expired_update))
        var created: PhoneMusicAuthorization? = null
        try {
            return withContext(Dispatchers.IO) {
                PhoneMusicAuthorization(developerToken, BuildConfig.VERSION_NAME, "zh-CN",
                    advertisedAuthority = advertisedAuthority, port = port).also { created = it }
            }
        } catch (failure: Throwable) {
            // The return dispatch can be cancelled after IO created the socket.
            created?.close()
            throw failure
        }
    }

    internal class ValidatedPhoneAuthentication internal constructor(
        internal val userToken: String,
        internal val previousToken: String?,
    )

    /** Candidate validation never changes the retained account. */
    internal suspend fun validatePhoneAuthentication(userToken: String): ValidatedPhoneAuthentication {
        require(isPhoneMusicUserToken(userToken)) { AppText.text(R.string.services_auth_invalid_user_token) }
        val previous = tokenStore.readUserToken()
        val developerToken = signInDeveloperToken()
            ?: throw IllegalStateException(AppText.text(R.string.services_credentials_expired_update))
        AppleMusicApi(
            BuildConfig.APPLE_MUSIC_STOREFRONT,
            DeveloperTokenClient(endpoint = "", bundledToken = developerToken),
        ) { userToken }
            .userStorefront()
        return ValidatedPhoneAuthentication(userToken, previous)
    }

    /** Called by the current attempt, immediately before the UI handoff. */
    internal fun completePhoneAuthentication(candidate: ValidatedPhoneAuthentication) {
        // Only account metadata is adopted: playback still goes through the LX
        // (落雪) engine and this app never reports play history to Apple Music.
        tokenStore.writeUserToken(candidate.userToken)
        mediaSession.clear()
        bridge.release()
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
        // v0.6.3: 本地资料库歌单（resourceType=local-playlist）——曲目已存在本地，
        // 直接从 LocalLibraryStore 取，不再走 Apple/veil 网络解析。
        if (collection.resourceType == "local-playlist") {
            val id = collection.resourceId ?: collection.catalogId ?: collection.id
            return localLibrary.playlist(id)?.songs.orEmpty()
        }
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

    /** v0.8: 当前解码方式（软解/FFmpeg、硬解/MediaCodec，veil 同款）。 */
    fun currentDecoderMode(): DecoderMode = DecoderMode.load(appContext)

    /**
     * v0.8: 软解/硬解切换（设置页入口）。持久化后立即重建播放器（同曲同位续播）；
     * 解码方式的取舍与播放源无关——点播仍全走落雪直链。
     */
    fun setDecoderMode(mode: DecoderMode) {
        if (mode == DecoderMode.load(appContext)) return
        DecoderMode.save(appContext, mode)
        lxPlayer.applyDecoderMode(mode)
    }

    /** v0.8: 本机 FFmpeg 软解组件是否可用（64 位设备可能缺失原生库）。 */
    fun ffmpegDecoderAvailable(): Boolean = FfmpegSupport.available

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
