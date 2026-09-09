package com.example.aichat.feature.customization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/** Fixed-size repeat units keep full-screen backgrounds quiet at every aspect ratio. */
@Composable
fun BackgroundPattern(key: String, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val accent = when (key) {
        "aurora" -> if (dark) Color(0xFF80BAA7) else Color(0xFF326C59)
        "midnight" -> if (dark) Color(0xFFABAADF) else Color(0xFF535281)
        "rose" -> if (dark) Color(0xFFD6A0AE) else Color(0xFF97576C)
        "paper" -> if (dark) Color(0xFFC4B69A) else Color(0xFF8A7658)
        else -> MaterialTheme.colorScheme.onBackground
    }
    val base = MaterialTheme.colorScheme.background
    Canvas(modifier.background(base)) {
        drawRect(accent.copy(alpha = if (dark) .025f else .035f))
        val ink = accent.copy(alpha = if (dark) .075f else .085f)
        val stroke = Stroke(.7.dp.toPx())
        val tile = (if (key == "paper") 32 else 56).dp.toPx()
        if (key == "paper") {
            var x = 0f
            while (x < size.width) {
                drawLine(ink, Offset(x,0f), Offset(x,size.height), .5.dp.toPx())
                x += tile
            }
            var y = 0f
            while (y < size.height) {
                drawLine(ink, Offset(0f,y), Offset(size.width,y), .5.dp.toPx())
                y += tile
            }
            return@Canvas
        }
        var y = -tile
        var row = -1
        while (y < size.height + tile) {
            if (key == "aurora") {
                val path = Path()
                var x = 0f
                while (x <= size.width + 4.dp.toPx()) {
                    val wave = y + sin(x / (tile * 2) * (Math.PI * 2)).toFloat() * 7.dp.toPx()
                    if (x == 0f) path.moveTo(x, wave) else path.lineTo(x, wave)
                    x += 4.dp.toPx()
                }
                drawPath(path, ink, style = stroke)
            } else {
                var x = if (row % 2 == 0) 0f else tile / 2
                while (x < size.width + tile) {
                    when (key) {
                        "midnight" -> {
                            val r = 7.dp.toPx()
                            val diamond = Path().apply {
                                moveTo(x, y-r); lineTo(x+r, y); lineTo(x, y+r); lineTo(x-r, y); close()
                            }
                            drawPath(diamond, ink, style = stroke)
                            drawCircle(ink, .8.dp.toPx(), Offset(x + tile/2, y + tile/2))
                        }
                        "rose" -> {
                            val r = 7.dp.toPx()
                            for (center in listOf(Offset(x-r/2,y), Offset(x+r/2,y), Offset(x,y-r/2), Offset(x,y+r/2)))
                                drawCircle(ink, r, center, style = stroke)
                        }
                        else -> drawCircle(ink, 1.dp.toPx(), Offset(x,y))
                    }
                    x += tile
                }
            }
            y += tile
            row++
        }
    }
}
