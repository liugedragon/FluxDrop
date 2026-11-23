package com.fluxdrop.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 闪渡配色：深空底色 + 青→紫→粉 霓虹渐变 */
object Flux {
    val Bg0 = Color(0xFF060914)
    val Bg1 = Color(0xFF0B1024)
    val Bg2 = Color(0xFF121A3A)
    val Panel = Color(0xFF10172F)

    val Cyan = Color(0xFF22D3EE)
    val Blue = Color(0xFF3B82F6)
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFEC4899)
    val Mint = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)

    val TextHi = Color(0xFFF1F5FF)
    val TextMid = Color(0xFFA9B4D6)
    val TextLow = Color(0xFF6B7699)

    val GlassTop = Color(0x1CFFFFFF)
    val GlassBottom = Color(0x0AFFFFFF)
    val GlassBorder = Color(0x26FFFFFF)

    val accent: Brush = Brush.linearGradient(listOf(Cyan, Violet, Pink))
    val accentH: Brush = Brush.horizontalGradient(listOf(Cyan, Violet, Pink))
    val coolH: Brush = Brush.horizontalGradient(listOf(Cyan, Blue, Violet))
    val mintH: Brush = Brush.horizontalGradient(listOf(Mint, Cyan))
}

private val scheme = darkColorScheme(
    primary = Flux.Cyan,
    onPrimary = Color(0xFF00141A),
    secondary = Flux.Violet,
    onSecondary = Color.White,
    tertiary = Flux.Pink,
    background = Flux.Bg0,
    onBackground = Flux.TextHi,
    surface = Flux.Bg1,
    onSurface = Flux.TextHi,
    surfaceVariant = Flux.Bg2,
    onSurfaceVariant = Flux.TextMid,
    surfaceContainerLowest = Flux.Bg0,
    surfaceContainerLow = Color(0xFF0E1530),
    surfaceContainer = Color(0xFF111833),
    surfaceContainerHigh = Color(0xFF151D3C),
    surfaceContainerHighest = Color(0xFF1A2347),
    outline = Flux.GlassBorder,
    outlineVariant = Color(0x1AFFFFFF),
    error = Flux.Red,
)

@Composable
fun FluxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
