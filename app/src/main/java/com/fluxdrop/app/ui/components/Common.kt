package com.fluxdrop.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxdrop.app.core.DeviceKind
import com.fluxdrop.app.ui.theme.Flux

/** 缓慢流动的极光背景 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tr = rememberInfiniteTransition(label = "aurora")
    val t by tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse),
        label = "t",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Flux.Bg0)
            .drawBehind {
                val w = size.width
                val h = size.height
                val blobs = listOf(
                    Triple(Offset(w * (0.10f + 0.30f * t), h * (0.10f + 0.06f * t)), w * 0.95f, Flux.Violet.copy(alpha = 0.20f)),
                    Triple(Offset(w * (0.95f - 0.30f * t), h * (0.45f + 0.10f * t)), w * 0.80f, Flux.Cyan.copy(alpha = 0.12f)),
                    Triple(Offset(w * (0.25f + 0.45f * t), h * (0.98f - 0.08f * t)), w * 0.90f, Flux.Pink.copy(alpha = 0.11f)),
                )
                for ((c, r, col) in blobs) {
                    drawCircle(
                        brush = Brush.radialGradient(listOf(col, Color.Transparent), center = c, radius = r),
                        radius = r,
                        center = c,
                    )
                }
            },
        content = content,
    )
}

/** 毛玻璃卡片 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Flux.GlassTop, Flux.GlassBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0DFFFFFF))), shape)
            .padding(padding),
        content = content,
    )
}

/** 渐变主按钮，按下有弹性缩放 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    brush: Brush = Flux.accentH,
    height: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, tween(250), label = "alpha")
    val shape = RoundedCornerShape(height / 2.6f)
    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(shape)
            .background(brush)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
        }
    }
}

/** 次要按钮：透明玻璃描边 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Flux.TextHi,
    height: Dp = 48.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(0.5f, Spring.StiffnessMedium), label = "press")
    val shape = RoundedCornerShape(height / 2.6f)
    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, Flux.GlassBorder, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** 圆形玻璃图标按钮 */
@Composable
fun GlassIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, Flux.GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Flux.TextHi, modifier = Modifier.size(size * 0.48f))
    }
}

/** 页面顶部标题 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(brush = Flux.accentH, fontSize = 28.sp, fontWeight = FontWeight.Black),
            )
            Text(subtitle, color = Flux.TextMid, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** 呼吸小圆点 */
@Composable
fun PulseDot(modifier: Modifier = Modifier, color: Color = Flux.Mint, size: Dp = 8.dp) {
    val tr = rememberInfiniteTransition(label = "dot")
    val s by tr.animateFloat(0.55f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "s")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = s
                scaleY = s
                alpha = 0.5f + s * 0.5f
            }
            .clip(CircleShape)
            .background(color),
    )
}

// ------------------------------------------------------------------ 头像与文件图标

private val avatarPalettes = listOf(
    listOf(Color(0xFF22D3EE), Color(0xFF3B82F6)),
    listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
    listOf(Color(0xFF34D399), Color(0xFF0EA5E9)),
    listOf(Color(0xFFFBBF24), Color(0xFFF97316)),
    listOf(Color(0xFFF472B6), Color(0xFFA78BFA)),
    listOf(Color(0xFF60A5FA), Color(0xFF8B5CF6)),
)

fun avatarBrush(seed: String): Brush =
    Brush.linearGradient(avatarPalettes[(seed.hashCode() and 0x7fffffff) % avatarPalettes.size])

fun kindIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.PHONE -> Icons.Rounded.PhoneAndroid
    DeviceKind.TABLET -> Icons.Rounded.TabletAndroid
    DeviceKind.PC -> Icons.Rounded.Laptop
    DeviceKind.WEB -> Icons.Rounded.Language
}

@Composable
fun DeviceAvatar(kind: DeviceKind, seed: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarBrush(seed))
            .border(1.dp, Color(0x40FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kindIcon(kind), null, tint = Color.White, modifier = Modifier.size(size * 0.48f))
    }
}

fun fileIcon(name: String, mime: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp") -> Icons.Rounded.Image
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "flv") -> Icons.Rounded.Movie
        mime.startsWith("audio/") || ext in setOf("mp3", "flac", "wav", "aac", "m4a", "ogg") -> Icons.Rounded.MusicNote
        ext == "apk" -> Icons.Rounded.Android
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Rounded.Archive
        mime.startsWith("text/") || ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md") ->
            Icons.Rounded.Description
        else -> Icons.Rounded.AttachFile
    }
}

fun fileBrush(name: String, mime: String): Brush {
    val icon = fileIcon(name, mime)
    val colors = when (icon) {
        Icons.Rounded.Image -> listOf(Flux.Cyan, Flux.Blue)
        Icons.Rounded.Movie -> listOf(Flux.Pink, Flux.Violet)
        Icons.Rounded.MusicNote -> listOf(Flux.Amber, Color(0xFFF97316))
        Icons.Rounded.Android -> listOf(Flux.Mint, Flux.Cyan)
        Icons.Rounded.Archive -> listOf(Flux.Mint, Color(0xFF059669))
        else -> listOf(Color(0xFF60A5FA), Flux.Violet)
    }
    return Brush.linearGradient(colors)
}

@Composable
fun FileBadge(name: String, mime: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(fileBrush(name, mime)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(fileIcon(name, mime), null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

// ------------------------------------------------------------------ 进度条

/** 渐变进度条，传输中带流光扫过效果 */
@Composable
fun GradientProgress(progress: Float, active: Boolean, brush: Brush, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x1AFFFFFF)),
    ) {
        val fill = Modifier
            .fillMaxHeight()
            .fillMaxWidth(progress.coerceIn(0f, 1f))
            .clip(RoundedCornerShape(50))
            .background(brush)
        if (active) ShimmerFill(fill) else Box(fill)
    }
}

@Composable
private fun ShimmerFill(modifier: Modifier) {
    val tr = rememberInfiniteTransition(label = "shine")
    val shine by tr.animateFloat(
        -0.2f,
        1.2f,
        infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shine",
    )
    Box(
        modifier.drawWithContent {
            drawContent()
            val x = size.width * shine
            val half = 48.dp.toPx()
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                    startX = x - half,
                    endX = x + half,
                ),
            )
        },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = Flux.TextLow,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Flux.TextLow, fontSize = 12.sp)
            Text(value, color = Flux.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
