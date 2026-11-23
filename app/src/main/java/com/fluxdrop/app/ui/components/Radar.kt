package com.fluxdrop.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxdrop.app.core.Device
import com.fluxdrop.app.ui.theme.Flux
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 雷达：同心环 + 旋转扫描扇区 + 向外扩散的波纹，
 * 发现的设备以气泡形式弹出并均匀分布在环上，点击即可选中。
 */
@Composable
fun RadarView(
    devices: List<Device>,
    selected: Set<String>,
    onToggle: (Device) -> Unit,
    onCenterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tr = rememberInfiniteTransition(label = "radar")
    val sweep by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "sweep")
    val pulse by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "pulse")

    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val half = constraints.maxWidth / 2f * 0.96f

        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f * 0.96f

            drawCircle(
                Brush.radialGradient(listOf(Flux.Violet.copy(alpha = 0.16f), Color.Transparent), center = c, radius = r),
                radius = r,
                center = c,
            )
            val ring = Stroke(width = 1.dp.toPx())
            for (i in 1..4) {
                drawCircle(Flux.Cyan.copy(alpha = 0.08f + 0.04f * i), radius = r * i / 4f, center = c, style = ring)
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx()))
            val lineColor = Flux.Cyan.copy(alpha = 0.12f)
            drawLine(lineColor, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawLine(lineColor, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1.dp.toPx(), pathEffect = dash)

            for (k in 0 until 3) {
                val p = (pulse + k / 3f) % 1f
                drawCircle(
                    Flux.Cyan.copy(alpha = (1f - p) * 0.30f),
                    radius = r * (0.14f + 0.86f * p),
                    center = c,
                    style = Stroke(width = (2.5f * (1f - p) + 0.6f).dp.toPx()),
                )
            }

            rotate(sweep, pivot = c) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Flux.Cyan.copy(alpha = 0.34f),
                        center = c,
                    ),
                    radius = r,
                    center = c,
                )
                drawLine(
                    Brush.linearGradient(listOf(Flux.Cyan.copy(alpha = 0f), Flux.Cyan), start = c, end = Offset(c.x + r, c.y)),
                    start = c,
                    end = Offset(c.x + r, c.y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // 中心：本机
        Box(
            Modifier
                .size(72.dp)
                .shadow(24.dp, CircleShape, ambientColor = Flux.Violet, spotColor = Flux.Violet)
                .clip(CircleShape)
                .background(Flux.accent)
                .clickable(onClick = onCenterClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Bolt, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }

        val sorted = remember(devices) { devices.sortedBy { it.id } }
        val n = sorted.size
        sorted.forEachIndexed { i, d ->
            key(d.id) {
                val ringFrac = when {
                    n <= 5 -> 0.70f
                    i % 2 == 0 -> 0.58f
                    else -> 0.86f
                }
                val targetAngle = -90f + 360f / max(n, 1) * i + (if (n > 5 && i % 2 == 1) 18f else 0f)
                val angle by animateFloatAsState(targetAngle, spring(0.8f, Spring.StiffnessVeryLow), label = "angle")
                val rr by animateFloatAsState(ringFrac, spring(0.8f, Spring.StiffnessVeryLow), label = "ring")
                DeviceBubble(
                    device = d,
                    selected = d.id in selected,
                    onClick = { onToggle(d) },
                    modifier = Modifier.offset {
                        val rad = Math.toRadians(angle.toDouble())
                        val radius = half * rr
                        IntOffset((cos(rad) * radius).roundToInt(), (sin(rad) * radius).roundToInt())
                    },
                )
            }
        }
    }
}

@Composable
fun DeviceBubble(device: Device, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
    val selScale by animateFloatAsState(if (selected) 1.1f else 1f, spring(0.45f, Spring.StiffnessMedium), label = "sel")
    val ringAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "ringAlpha")

    Column(
        modifier
            .width(86.dp)
            .graphicsLayer {
                val s = appear.value * selScale
                scaleX = s
                scaleY = s
                alpha = appear.value.coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer { alpha = ringAlpha }
                    .border(2.5.dp, Flux.accent, CircleShape),
            )
            DeviceAvatar(device.kind, device.id, 52.dp)
            SelectedBadge(visible = selected, modifier = Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            device.name,
            color = Flux.TextHi,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            if (device.viaWeb) "网页版 · ${device.ip}" else device.ip,
            color = Flux.TextLow,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 选中角标。单独成函数，避免在 Column 里被解析成 ColumnScope.AnimatedVisibility 导致编译错误 */
@Composable
private fun SelectedBadge(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Flux.Mint)
                .border(2.dp, Flux.Bg0, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}
