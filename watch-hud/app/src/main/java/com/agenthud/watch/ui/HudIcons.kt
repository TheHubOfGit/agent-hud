package com.agenthud.watch.ui

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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MicIcon(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Capsule head
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, h * 0.12f),
            size = Size(w * 0.32f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
            style = stroke
        )
        // U-cradle arc
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.28f),
            size = Size(w * 0.60f, h * 0.44f),
            style = stroke
        )
        // Stem & base
        drawLine(color, Offset(w * 0.50f, h * 0.72f), Offset(w * 0.50f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.88f), Offset(w * 0.66f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

@Composable
fun CheckIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.76f)
            lineTo(w * 0.84f, h * 0.26f)
        }
        drawPath(path, color = color, style = Stroke(width = w * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CloseIcon(color: Color, size: Dp = 12.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.15f
        drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun BellIcon(color: Color, muted: Boolean, size: Dp = 15.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.70f)
            lineTo(w * 0.28f, h * 0.44f)
            cubicTo(w * 0.28f, h * 0.18f, w * 0.72f, h * 0.18f, w * 0.72f, h * 0.44f)
            lineTo(w * 0.76f, h * 0.70f)
            close()
        }
        drawPath(path, color = color, style = stroke)
        drawLine(color, Offset(w * 0.42f, h * 0.84f), Offset(w * 0.58f, h * 0.84f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        if (muted) {
            drawLine(color, Offset(w * 0.18f, h * 0.18f), Offset(w * 0.82f, h * 0.82f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun MoonIcon(color: Color, size: Dp = 15.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(
            color = color,
            startAngle = 45f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(w * 0.18f, h * 0.18f),
            size = Size(w * 0.64f, h * 0.64f),
            style = stroke
        )
        drawArc(
            color = color,
            startAngle = 95f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(w * 0.36f, h * 0.20f),
            size = Size(w * 0.48f, h * 0.48f),
            style = stroke
        )
    }
}

@Composable
fun SunIcon(color: Color, size: Dp = 15.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val sw = w * 0.11f
        drawCircle(color, radius = w * 0.20f, center = Offset(cx, cy), style = Stroke(width = sw))
        val rays = listOf(
            Offset(cx, h * 0.12f) to Offset(cx, h * 0.22f),
            Offset(cx, h * 0.78f) to Offset(cx, h * 0.88f),
            Offset(w * 0.12f, cy) to Offset(w * 0.22f, cy),
            Offset(w * 0.78f, cy) to Offset(w * 0.88f, cy)
        )
        rays.forEach { (a, b) -> drawLine(color, a, b, strokeWidth = sw, cap = StrokeCap.Round) }
    }
}

@Composable
fun RefreshIcon(color: Color, size: Dp = 15.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.20f),
            size = Size(w * 0.60f, h * 0.60f),
            style = stroke
        )
        drawLine(color, Offset(w * 0.68f, h * 0.16f), Offset(w * 0.80f, h * 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.80f, h * 0.32f), Offset(w * 0.62f, h * 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}
