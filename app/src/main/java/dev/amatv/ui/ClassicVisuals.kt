package dev.amatv.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Classic (original Apple Music for TV) card geometry, ported from upstream
 * am_atv `ui/classic/ClassicBrowseVisuals.kt`. Used by the local library
 * classic-style shelves and playlist cards so the look matches the upstream
 * classic interface.
 */
internal object ClassicVisuals {
    val CardWidth = 168.dp
    val CardHeight = 220.dp
    val CardRadius = 14.dp
    val CardTitleSize = 15.sp
    val CardSubtitleSize = 13.sp
    val CardLift = 2.dp
    const val CardFocusScale = 1.035f

    val RailWidth = 156.dp
    val RailRowHeight = 44.dp
    val GridSpacing = 24.dp
}
