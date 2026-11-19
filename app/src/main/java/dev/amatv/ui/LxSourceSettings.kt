package dev.amatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.luoxue.LxPlayback
import kotlinx.coroutines.launch

/**
 * Account 页「落雪 LX 音源」设置分组（音源接入核对报告 §3.4 / 待办 5）。
 *
 * 覆盖：总开关、音质档位、脚本重载、脚本列表启停、恢复随包脚本。
 * 脚本「导入」沿用落雪本地解决方式：adb push 到 `filesDir/luoxue/` 后按
 * 「重载脚本」即生效（LxScriptStore 目录即事实来源）。
 */
@Composable
internal fun LxSourceSettings(playback: LxPlayback) {
    val scope = rememberCoroutineScope()
    var views by remember { mutableStateOf<List<LxPlayback.ScriptView>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(playback.isEnabled()) }
    var quality by remember { mutableStateOf(playback.quality()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        views = playback.scriptViews()
        status = playback.describe()
    }

    LaunchedEffect(Unit) { refresh() }

    Spacer(Modifier.height(8.dp))
    Text("落雪 LX 音源", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "点播全部走落雪直链播放；未命中时提示换源，不回退 Apple。",
        color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp,
    )
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
        if (busy) {
            AppleLoadingIndicator(Modifier.size(16.dp))
        } else {
            Text(
                status.ifBlank { "脚本未初始化（首次点歌时自动启动）" },
                color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp,
            )
        }
    }

    val toggleFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val reloadFocus = remember { FocusRequester() }
    val resetFocus = remember { FocusRequester() }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsPillButton(
            label = if (enabled) "音源：开" else "音源：关",
            onClick = {
                enabled = !enabled
                playback.setEnabled(enabled)
            },
            modifier = Modifier.width(220.dp)
                .focusRequester(toggleFocus)
                .focusProperties { right = qualityFocus; down = reloadFocus },
        )
        SettingsPillButton(
            label = "音质：${quality.label}",
            onClick = {
                quality = playback.stepQuality(1)
            },
            modifier = Modifier.width(280.dp)
                .focusRequester(qualityFocus)
                .focusProperties { left = toggleFocus; right = FocusRequester.Cancel; down = reloadFocus },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsPillButton(
            label = if (busy) "处理中…" else "重载脚本",
            onClick = {
                if (busy) return@SettingsPillButton
                scope.launch {
                    busy = true
                    message = runCatching { playback.forceReload() }.getOrElse { it.message }
                    refresh()
                    busy = false
                }
            },
            modifier = Modifier.width(220.dp)
                .focusRequester(reloadFocus)
                .focusProperties { up = toggleFocus; right = resetFocus; down = FocusRequester.Cancel },
        )
        SettingsPillButton(
            label = "恢复随包脚本",
            onClick = {
                if (busy) return@SettingsPillButton
                scope.launch {
                    busy = true
                    message = runCatching { playback.resetScripts() }.getOrElse { it.message }
                    refresh()
                    busy = false
                }
            },
            modifier = Modifier.width(280.dp)
                .focusRequester(resetFocus)
                .focusProperties { up = toggleFocus; left = reloadFocus; right = FocusRequester.Cancel; down = FocusRequester.Cancel },
        )
    }

    if (views.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "脚本（${views.count { !it.disabled }}/${views.size} 启用）— adb push 新脚本到 filesDir/luoxue/ 后重载生效",
            color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (view in views) {
                ScriptRow(playback, view) {
                    refresh()
                }
            }
        }
    }
    message?.let {
        Text(it, color = Color(0xFFB9E2B4), fontSize = 14.sp)
    }
}

@Composable
private fun ScriptRow(playback: LxPlayback, view: LxPlayback.ScriptView, onChanged: () -> Unit) {    val label = buildString {
        append(if (view.disabled) "○" else "●")
        append(' ')
        append(view.displayName.ifBlank { view.file })
        if (view.sources.isNotEmpty()) append("（${view.sources.joinToString("/")}）")
        append(if (view.disabled) "  已停用" else "")
    }
    SettingsPillButton(
        label = label,
        onClick = {
            playback.setScriptDisabled(view.file, !view.disabled)
            onChanged()
        },
        modifier = Modifier.width(560.dp),
    )
}

/**
 * Account 页「正在播放」显示设置（v0.2，参照 veil.music 实验室开关）：
 * 开启后导航栏「正在播放」入口用当前歌名替换（超长截断），默认关。
 */
@Composable
internal fun NowPlayingLabelSettings(
    titleAsName: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text("正在播放", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "导航栏「正在播放」入口可以用当前歌名替换文字（参照 veil.music）。",
        color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsPillButton(
            label = if (titleAsName) "正在播放：显示歌名" else "正在播放：固定文字",
            onClick = { onToggle(!titleAsName) },
            modifier = Modifier.width(300.dp),
        )
    }
}

@Composable
/**
 * v0.6 P0：「逐字歌词」设置开关。
 *
 * 打开：活跃歌词行按字逐字点亮（v0.5 的 karaoke word-wipe，LRC 行级时间按 CJK 字符 /
 * 西文单词均分）。关闭：回落整行高亮，即「取消逐字」。与 v0.4/v0.5 行为对齐，默认关闭。
 */
internal fun LyricSettings(
    wordWipeEnabled: Boolean,
    onWordWipeToggle: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text("逐字歌词", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "开启后当前歌词行会按字逐字点亮；关闭则取消逐字、整行高亮。",
        color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsPillButton(
            label = if (wordWipeEnabled) "逐字歌词：开" else "逐字歌词：关",
            onClick = { onWordWipeToggle(!wordWipeEnabled) },
            modifier = Modifier.width(300.dp),
        )
    }
}

@Composable
private fun SettingsPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        cornerRadius = 23.dp,
        background = Color.White.copy(alpha = 0.12f),
        focusedBackground = Color.White.copy(alpha = 0.28f),
        padding = PaddingValues(horizontal = 18.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text(
                label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
