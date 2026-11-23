package com.fluxdrop.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdrop.app.ui.theme.Flux
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private fun encodeQr(content: String): BitMatrix? = try {
    val hints = mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
} catch (e: Exception) {
    null
}

/** 圆点风格的二维码（深色渐变点 + 圆角定位框），保证扫码对比度 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { encodeQr(content) }
    Canvas(modifier.aspectRatio(1f).graphicsLayer {}) {
        val m = matrix ?: return@Canvas
        val n = m.width
        val cell = size.minDimension / n
        val brush = Brush.linearGradient(
            listOf(Color(0xFF0F172A), Color(0xFF312E81), Color(0xFF6D28D9)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        fun inFinder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (m.get(x, y) && !inFinder(x, y)) {
                    drawRoundRect(
                        brush,
                        topLeft = Offset(x * cell + cell * 0.07f, y * cell + cell * 0.07f),
                        size = Size(cell * 0.86f, cell * 0.86f),
                        cornerRadius = CornerRadius(cell * 0.36f),
                    )
                }
            }
        }
        for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            val ox = fx * cell
            val oy = fy * cell
            drawRoundRect(
                brush,
                topLeft = Offset(ox + cell / 2f, oy + cell / 2f),
                size = Size(cell * 6f, cell * 6f),
                cornerRadius = CornerRadius(cell * 1.8f),
                style = Stroke(width = cell),
            )
            drawRoundRect(
                brush,
                topLeft = Offset(ox + cell * 2f, oy + cell * 2f),
                size = Size(cell * 3f, cell * 3f),
                cornerRadius = CornerRadius(cell * 0.9f),
            )
        }
    }
}

/** 白底二维码卡片，外圈一道旋转的霓虹光环 */
@Composable
fun GlowingQrCard(content: String, size: Dp, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "qrGlow")
    val angle by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "angle")
    val outer = RoundedCornerShape(30.dp)
    Box(
        modifier
            .size(size)
            .shadow(32.dp, outer, ambientColor = Flux.Violet, spotColor = Flux.Cyan)
            .clip(outer)
            .drawBehind {
                rotate(angle) {
                    drawCircle(
                        Brush.sweepGradient(listOf(Flux.Cyan, Flux.Violet, Flux.Pink, Flux.Amber, Flux.Cyan)),
                        radius = this.size.maxDimension,
                    )
                }
            }
            .padding(4.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White)
            .padding(18.dp),
    ) {
        QrCode(content, Modifier.fillMaxSize())
    }
}
