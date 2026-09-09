package com.example.aichat.feature.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

internal val LocalGenerationLabel = staticCompositionLocalOf { "" }

/** A public generation status, never a representation of private model thoughts. */
@Composable
internal fun ReasoningStatusWord(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reasoning status")
    val distance = with(LocalDensity.current) { 2.dp.toPx() }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val bright = MaterialTheme.colorScheme.onSurface
    Row(modifier.height(24.dp).clearAndSetSemantics { contentDescription = "Thinking" }, verticalAlignment = Alignment.CenterVertically) {
        "Thinking".forEachIndexed { index, character ->
            val wave by transition.animateFloat(
                initialValue = 0f, targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes { durationMillis = 1_400; 0f at 0; 1f at 220; 0f at 500 },
                    initialStartOffset = StartOffset(index * 70)
                ), label = "status letter $index"
            )
            Text(character.toString(), color = lerp(muted, bright, wave),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.graphicsLayer { translationY = -distance * wave })
        }
    }
}
