package dev.amatv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.amatv.R

/**
 * v0.8.1: 全局苹方（PingFang SC）字体。
 *
 * 子集化版本（GB2312 全集 7445 字 + ASCII/拉丁扩展/通用标点/CJK 标点/假名/全角），
 * 每字重约 1.55MB——原始 OTF 约 13MB/字重，全量嵌入会让 APK 膨胀近 40MB。
 * 极生僻字（扩展区汉字）不在子集内，渲染时回退系统字体。
 *
 * 字重映射：苹方没有独立 Bold 字重（Semibold 为最重），Bold(700) 由 Semibold 承担。
 */
val PingFangFontFamily = FontFamily(
    Font(R.font.pingfang_regular, FontWeight.Normal),
    Font(R.font.pingfang_regular, FontWeight.Light),
    Font(R.font.pingfang_medium, FontWeight.Medium),
    Font(R.font.pingfang_semibold, FontWeight.SemiBold),
    Font(R.font.pingfang_semibold, FontWeight.Bold),
    Font(R.font.pingfang_semibold, FontWeight.ExtraBold),
    Font(R.font.pingfang_semibold, FontWeight.Black),
)

private val TvColors = darkColorScheme(
    primary = Color(0xFFFA2D55),
    onPrimary = Color.White,
    background = Color(0xFF08070A),
    onBackground = Color.White,
    surface = Color(0xFF17151B),
    onSurface = Color.White,
)

private val BaseTypography = Typography()

/** 全部 M3 文字样式挂上苹方：未显式指定 fontFamily 的 Text 全局生效。 */
private val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = PingFangFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = PingFangFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = PingFangFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = PingFangFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = PingFangFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = PingFangFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = PingFangFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = PingFangFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = PingFangFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = PingFangFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = PingFangFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = PingFangFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = PingFangFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = PingFangFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = PingFangFontFamily),
)

@Composable
fun AmAtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColors, typography = AppTypography) {
        // 双保险：显式覆盖 LocalTextStyle，未显式指定 fontFamily 的 Text（项目内
        // 绝大多数只传 fontSize/fontWeight）全部继承苹方，不依赖 material3 版本的
        // LocalTextStyle 提供行为。
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides AppTypography.bodyLarge,
        ) {
            content()
        }
    }
}
