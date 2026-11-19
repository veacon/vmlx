package dev.amatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.integration.LocalLibraryStore
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Local library rendered with the SAME horizontal shelf layout and card visuals
 * as "Now Listening" (HomeScreen/HomeShelves): vertical list of shelves, each a
 * horizontal row of 168x220 cards. No left/right split.
 *
 * - Playlist cards carry catalogKind=Playlist so tapping them opens the shared
 *   CollectionScreen detail page (identical to tapping a playlist on Home).
 * - The square "+" import tile sits at the END of the first shelf ("我的歌单"),
 *   so real playlists come first and the add action is last.
 * - v0.8: signed-in Apple user library shelves (资料库歌单/歌曲/最近添加/最近播放)
 *   are rendered FIRST via [appleShelves]; local shelves follow. Catalog playlist
 *   items open the shared CollectionScreen, songs start playback directly.
 * - Focus: the tab keeps focus on tab switch; DPAD DOWN from the tab lands on
 *   the first content card via [focusRequester].
 */
@Composable
internal fun LocalLibraryScreen(
    store: LocalLibraryStore,
    serverUrlFlow: StateFlow<String>,
    onOpenTrack: (Track, List<Track>) -> Unit,
    onOpenCollection: (Track) -> Unit,
    appleShelves: List<ContentShelf> = emptyList(),
    onLoadMoreShelf: (String) -> Unit = {},
    shelfPagination: Map<String, LibraryShelfPagination> = emptyMap(),
    focusRequester: FocusRequester? = null,
    navigationFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val playlists by store.playlists.collectAsState()

    var showImport by remember { mutableStateOf(false) }

    // v0.8：资料库只保留一个「资料库播放列表」分区——Apple 账号歌单在前，
    // 本地导入歌单在后，「添加歌单」（扫码）固定在最末（v0.6.4 语义）。
    // 分区有下一页时在添加入口前追加「载入更多」。
    val shelves = remember(playlists, appleShelves, shelfPagination) {
        buildList {
            val applePlaylists = appleShelves.firstOrNull { it.id == "lib-playlists" }
            add(LibShelf(
                id = "lib-playlists",
                title = dev.amatv.i18n.AppText.text(dev.amatv.R.string.app_library_playlists),
                cards = buildList {
                    applePlaylists?.items?.forEach { add(LibCard.Catalog(it)) }
                    playlists.forEach { add(LibCard.Playlist(it)) }
                    val page = shelfPagination["lib-playlists"]
                    if (page != null && page.showAction) {
                        add(LibCard.LoadMore("lib-playlists"))
                    }
                    add(LibCard.Add)
                },
            ))
        }
    }

    BackHandler(enabled = showImport) { showImport = false }

    // Row states and per-card focus requesters follow the shelf list and are
    // rebuilt whenever shelves change (a playlist import does exactly that).
    // A DPAD move evaluates a focusProperties closure that may still have been
    // created against the PREVIOUS, shorter list, so every lookup below must be
    // bounds-safe: getOrNull + coerceIn instead of a raw index access.
    val rowStates = remember(shelves) { shelves.map { androidx.compose.foundation.lazy.LazyListState() } }
    val fallbackRowState = remember { androidx.compose.foundation.lazy.LazyListState() }
    val cardFocus = remember(shelves) { shelves.map { shelf -> List(shelf.cards.size) { FocusRequester() } } }

    /** Returns null instead of throwing when the shelf row or card is out of range. */
    fun requesterAt(shelfIndex: Int, cardIndex: Int): FocusRequester? {
        val row = cardFocus.getOrNull(shelfIndex)?.takeIf { it.isNotEmpty() } ?: return null
        return row.getOrNull(cardIndex.coerceIn(0, row.lastIndex))
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
                Column {
                    ShelfHeading(shelf.title)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        state = rowStates.getOrNull(shelfIndex) ?: fallbackRowState,
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                    ) {
                        itemsIndexed(
                            shelf.cards,
                            key = { index, card -> "${shelf.id}:${cardKey(card, index)}" },
                        ) { index, card ->
                            // Per-card requester owned by the item itself, so it stays
                            // valid even while the shelf list is being swapped.
                            val ownFocus = remember(shelf.id, index) { FocusRequester() }
                            val selfFocus = requesterAt(shelfIndex, index) ?: ownFocus
                            val focusModifier = Modifier
                                .focusRequester(selfFocus)
                                .then(
                                    if (shelfIndex == 0 && index == 0 && focusRequester != null) {
                                        Modifier.focusRequester(focusRequester)
                                    } else Modifier
                                )
                                .focusProperties {
                                    left = if (index == 0) FocusRequester.Cancel
                                    else requesterAt(shelfIndex, index - 1) ?: FocusRequester.Cancel
                                    right = if (index == shelf.cards.lastIndex) FocusRequester.Cancel
                                    else requesterAt(shelfIndex, index + 1) ?: FocusRequester.Cancel
                                    up = if (shelfIndex == 0) {
                                        navigationFocusRequester ?: FocusRequester.Cancel
                                    } else {
                                        requesterAt(shelfIndex - 1, index) ?: FocusRequester.Cancel
                                    }
                                    down = if (shelfIndex == shelves.lastIndex) FocusRequester.Cancel
                                    else requesterAt(shelfIndex + 1, index) ?: FocusRequester.Cancel
                                }
                            when (card) {
                                LibCard.Add -> AddPlaylistTile(
                                    onClick = { showImport = true },
                                    modifier = focusModifier,
                                )
                                is LibCard.Playlist -> PlaylistCard(
                                    playlist = card.playlist,
                                    modifier = focusModifier,
                                    onClick = { onOpenCollection(card.playlist.toCatalogTrack()) },
                                )
                                is LibCard.Catalog -> SongCard(
                                    track = card.track,
                                    modifier = focusModifier,
                                    onClick = {
                                        // Apple 歌单打开详情页（资料库分区只含歌单类目）。
                                        onOpenCollection(card.track)
                                    },
                                )
                                is LibCard.LoadMore -> LoadMoreTile(
                                    page = shelfPagination[card.shelfId],
                                    modifier = focusModifier,
                                    onClick = { onLoadMoreShelf(card.shelfId) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showImport) {
            ImportPlaylistDialog(serverUrlFlow = serverUrlFlow, onDismiss = { showImport = false })
        }
    }
}

private data class LibShelf(val id: String, val title: String, val cards: List<LibCard>)

private sealed interface LibCard {
    object Add : LibCard
    data class Playlist(val playlist: LocalLibraryStore.LocalPlaylist) : LibCard

    /** v0.8: Apple user-library playlist item rendered as a song card, opens CollectionScreen. */
    data class Catalog(val track: Track) : LibCard

    /** v0.8: "load more" tile appended to a paginated Apple shelf. */
    data class LoadMore(val shelfId: String) : LibCard
}

private fun cardKey(card: LibCard, index: Int): String = when (card) {
    LibCard.Add -> "add"
    is LibCard.Playlist -> "pl-" + card.playlist.id
    is LibCard.Catalog -> "cat-" + card.track.id + "-" + index
    is LibCard.LoadMore -> "more-" + card.shelfId
}

/** A local playlist becomes a catalog Playlist track so clicking it opens CollectionScreen. */
private fun LocalLibraryStore.LocalPlaylist.toCatalogTrack(): Track {
    val first = songs.firstOrNull()
    return Track(
        id = id,
        title = name,
        artist = sourceLabel,
        album = "",
        durationMs = songs.sumOf { it.durationMs },
        palette = first?.palette ?: listOf(Color(0xFF2A2A2E)),
        lyrics = emptyList(),
        catalogId = id,
        catalogKind = CatalogKind.Playlist,
        artworkUrl = coverUrl ?: first?.artworkUrl,
        resourceId = id,
        resourceType = "local-playlist",
    )
}

@Composable
private fun ShelfHeading(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.66f),
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** v0.8: "load more" tile for a paginated Apple shelf (168x220, same geometry). */
@Composable
private fun LoadMoreTile(
    page: LibraryShelfPagination?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(168.dp).height(220.dp),
        cornerRadius = 14.dp,
        focusedScale = 1.035f,
        focusedLift = 2.dp,
        showBorder = true,
        background = Color.White.copy(alpha = 0.05f),
        focusedBackground = Color.White.copy(alpha = 0.16f),
    ) { focused ->
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                when {
                    page?.loading == true -> "载入中…"
                    page?.failed == true -> "重试载入"
                    else -> "载入更多"
                },
                color = if (page?.failed == true) Color(0xFFFFB0BA) else Color.White.copy(alpha = 0.85f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    page?.loading == true -> "正在读取下一页"
                    page?.failed == true -> "上次载入失败"
                    else -> "还有更多内容"
                },
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Square-with-plus import card, matching Home's 168x220 card geometry. */
@Composable
private fun AddPlaylistTile(onClick: () -> Unit, modifier: Modifier = Modifier) {    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(168.dp).height(220.dp),
        cornerRadius = 14.dp,
        focusedScale = 1.035f,
        focusedLift = 2.dp,
        showBorder = true,
        background = Color.White.copy(alpha = 0.05f),
        focusedBackground = Color.White.copy(alpha = 0.16f),
    ) { focused ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = if (focused) 0.10f else 0.04f))
                        .border(
                            width = if (focused) 2.dp else 1.5.dp,
                            color = Color(0xFFFA233B).copy(alpha = if (focused) 1f else 0.7f),
                            shape = RoundedCornerShape(16.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color(0xFFFF6B7E), fontSize = 54.sp, fontWeight = FontWeight.Light)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text("添加歌单", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("扫码 · QQ / 网易云 / 酷狗", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** Playlist card: cover + name + song count, same geometry as Home's RecentCard. */
@Composable
private fun PlaylistCard(
    playlist: LocalLibraryStore.LocalPlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cover = playlist.songs.firstOrNull()
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(168.dp).height(220.dp),
        cornerRadius = 14.dp,
        focusedScale = 1.035f,
        focusedLift = 2.dp,
        background = Color.Transparent,
        focusedBackground = Color.Transparent,
    ) { focused ->
        Column {
            if (cover != null) {
                MotionArtwork(track = cover, modifier = Modifier.size(168.dp), animate = focused, cornerRadius = 14.dp)
            } else {
                Box(
                    Modifier.size(168.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                )
            }
            Text(
                playlist.name, color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 9.dp),
            )
            Text(
                "${playlist.sourceLabel} · ${playlist.trackCount} 首",
                color = Color.White.copy(alpha = 0.56f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** Single song card, identical to Home's RecentCard. */
@Composable
private fun SongCard(
    track: Track,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(168.dp).height(220.dp),
        cornerRadius = 14.dp,
        focusedScale = 1.035f,
        focusedLift = 2.dp,
        background = Color.Transparent,
        focusedBackground = Color.Transparent,
    ) { focused ->
        Column {
            MotionArtwork(track = track, modifier = Modifier.size(168.dp), animate = focused, cornerRadius = 14.dp)
            Text(
                track.title, color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 9.dp),
            )
            Text(
                track.artist, color = Color.White.copy(alpha = 0.56f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** QR dialog: TV shows QR, phone opens the web page. Close button is default focus. */
@Composable
private fun ImportPlaylistDialog(serverUrlFlow: StateFlow<String>, onDismiss: () -> Unit) {
    val serverUrl by serverUrlFlow.collectAsState()
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(760.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C1E))
                .padding(horizontal = 34.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("用手机添加歌单", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("遥控器输入长链接太费劲。扫码到手机上粘贴，整张歌单会作为一个歌单加入资料库。", color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                        if (serverUrl.isNotBlank()) {
                            QrCode(content = serverUrl, size = 208.dp, cornerRadius = 12.dp)
                        } else {
                            Box(Modifier.size(208.dp), contentAlignment = Alignment.Center) {
                                Text("配置网页启动中…", color = Color(0xFF666666), fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(serverUrl.ifBlank { "-" }, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StepLine(1, "手机连同一个 Wi-Fi", "电视和手机要在同一个网络下。")
                    StepLine(2, "扫描左侧二维码", "相机或微信扫一扫都可以。")
                    StepLine(3, "粘贴歌单链接", "QQ 音乐 / 网易云 / 酷狗，或「歌名 - 歌手」多行文本。")
                    Text("导入完成后会作为一个歌单出现在「我的歌单」。", color = Color(0xFF9BE3A2), fontSize = 13.sp)
                }
            }
            FocusSurface(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(closeFocus),
                cornerRadius = 23.dp,
                background = Color.White.copy(alpha = 0.12f),
                focusedBackground = Color.White.copy(alpha = 0.28f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("关闭", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun StepLine(number: Int, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(number.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}
