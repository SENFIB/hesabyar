package com.hesabyar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GL_SUN = Color(0xFFFBBF24)
private val GL_CLOUD = Color(0xFFF3F4F6)
private val GL_DARK_CLOUD = Color(0xFFB0B7C3)
private val GL_RAIN = Color(0xFF93C5FD)
private val GL_SNOW = Color(0xFFE0F2FE)
private val GL_BOLT = Color(0xFFFCD34D)
private val GL_FOG = Color(0xFFD1D5DB)

// آیکون کشیده‌شده وضعیت هوا بر اساس کد Open-Meteo
@Composable
fun WeatherGlyph(code: Int, size: Dp = 36.dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawGlyph(code)
    }
}

private fun DrawScope.drawGlyph(code: Int) {
    val s = size.minDimension
    when (code) {
        0 -> glyphSun(s, 0.5f, 0.5f, 0.24f)
        1, 2 -> {
            glyphSun(s, 0.36f, 0.36f, 0.15f)
            glyphCloud(s, 0.1f, 0.1f, GL_CLOUD)
        }
        3 -> glyphCloud(s, 0f, 0.04f, GL_CLOUD)
        45, 48 -> glyphFog(s)
        in 51..67, in 80..82 -> {
            glyphCloud(s, 0f, -0.1f, GL_CLOUD)
            glyphDrops(s)
        }
        in 71..77, in 85..86 -> {
            glyphCloud(s, 0f, -0.1f, GL_CLOUD)
            glyphSnow(s)
        }
        in 95..99 -> {
            glyphCloud(s, 0f, -0.12f, GL_DARK_CLOUD)
            glyphBolt(s)
        }
        else -> glyphCloud(s, 0f, 0f, GL_CLOUD)
    }
}

private fun DrawScope.glyphSun(s: Float, cx: Float, cy: Float, r: Float) {
    val c = Offset(cx * s, cy * s)
    drawCircle(GL_SUN, radius = r * s, center = c)
    for (i in 0 until 8) {
        val a = i * PI / 4
        val inner = (r + 0.08f) * s
        val outer = (r + 0.2f) * s
        drawLine(
            color = GL_SUN,
            start = Offset(c.x + (cos(a) * inner).toFloat(), c.y + (sin(a) * inner).toFloat()),
            end = Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()),
            strokeWidth = 0.05f * s,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.glyphCloud(s: Float, ox: Float, oy: Float, color: Color) {
    drawCircle(color, radius = 0.17f * s, center = Offset((ox + 0.30f) * s, (oy + 0.50f) * s))
    drawCircle(color, radius = 0.22f * s, center = Offset((ox + 0.50f) * s, (oy + 0.40f) * s))
    drawCircle(color, radius = 0.15f * s, center = Offset((ox + 0.68f) * s, (oy + 0.50f) * s))
    drawRoundRect(
        color = color,
        topLeft = Offset((ox + 0.13f) * s, (oy + 0.50f) * s),
        size = Size(0.72f * s, 0.2f * s),
        cornerRadius = CornerRadius(0.1f * s)
    )
}

private fun DrawScope.glyphDrops(s: Float) {
    for (x in listOf(0.3f, 0.5f, 0.7f)) {
        drawLine(
            color = GL_RAIN,
            start = Offset(x * s, 0.74f * s),
            end = Offset((x - 0.05f) * s, 0.92f * s),
            strokeWidth = 0.06f * s,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.glyphSnow(s: Float) {
    drawCircle(GL_SNOW, radius = 0.055f * s, center = Offset(0.32f * s, 0.84f * s))
    drawCircle(GL_SNOW, radius = 0.055f * s, center = Offset(0.68f * s, 0.84f * s))
    drawCircle(GL_SNOW, radius = 0.055f * s, center = Offset(0.5f * s, 0.95f * s))
}

private fun DrawScope.glyphBolt(s: Float) {
    val p = Path().apply {
        moveTo(0.52f * s, 0.66f * s)
        lineTo(0.40f * s, 0.84f * s)
        lineTo(0.52f * s, 0.84f * s)
        lineTo(0.44f * s, 1.0f * s)
        lineTo(0.64f * s, 0.78f * s)
        lineTo(0.52f * s, 0.78f * s)
        close()
    }
    drawPath(p, color = GL_BOLT)
}

private fun DrawScope.glyphFog(s: Float) {
    for (y in listOf(0.38f, 0.56f, 0.74f)) {
        drawLine(
            color = GL_FOG,
            start = Offset(0.18f * s, y * s),
            end = Offset(0.82f * s, y * s),
            strokeWidth = 0.07f * s,
            cap = StrokeCap.Round
        )
    }
}
