package com.example.aichat.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.aichat.core.design.DesignMetrics


fun Modifier.shimmerPlaceholder(
    shape: Shape = RoundedCornerShape(8.dp)
): Modifier = composed {
    val placeholderBase = MaterialTheme.colorScheme.surfaceVariant
    val placeholderShine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val transition = rememberInfiniteTransition(label = "placeholder-shimmer")
    val offset = transition.animateFloat(
        initialValue = -700f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "placeholder-shimmer-offset"
    )
    clip(shape).background(
        Brush.linearGradient(
            colors = listOf(
                placeholderBase,
                placeholderBase,
                placeholderShine,
                placeholderBase,
                placeholderBase
            ),
            start = Offset(offset.value, 0f),
            end = Offset(offset.value + 360f, 360f)
        )
    )
}

@Composable
fun ShimmerBox(
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    Box(modifier = modifier.shimmerPlaceholder(shape))
}

@Composable
fun ShimmerTextLine(
    modifier: Modifier = Modifier,
    width: Dp,
    height: Dp = 14.dp
) {
    ShimmerBox(
        modifier = modifier
            .width(width)
            .height(height),
        shape = RoundedCornerShape(height / 2)
    )
}

/** Uses the same native text measurement as loaded content, including font scale. */
@Composable
fun ShimmerTextBlock(
    style: TextStyle,
    lineWidths: List<Float>,
    modifier: Modifier = Modifier
) {
    val lineHeight = with(LocalDensity.current) { style.fontSize.toDp() * 0.7f }
    Box(modifier = modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Text(
            text = List(lineWidths.size) { "M" }.joinToString("\n"),
            style = style,
            color = Color.Transparent,
            minLines = lineWidths.size,
            maxLines = lineWidths.size
        )
        Column(modifier = Modifier.matchParentSize(), verticalArrangement = Arrangement.SpaceAround) {
            lineWidths.forEach { width ->
                ShimmerBox(Modifier.fillMaxWidth(width).height(lineHeight), RoundedCornerShape(lineHeight / 2))
            }
        }
    }
}

@Composable
fun CharacterSummaryCardPlaceholder(
    modifier: Modifier = Modifier,
    imageAspectRatio: Float = 1.25f
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(imageAspectRatio),
            shape = RoundedCornerShape(DesignMetrics.portraitCorner)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)
        ) {
            ShimmerTextBlock(
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 22.sp),
                lineWidths = listOf(0.68f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            ShimmerTextBlock(
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                lineWidths = listOf(0.86f, 0.62f)
            )
        }
    }
}

@Composable
fun ChatListRowPlaceholder(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        ShimmerBox(modifier = Modifier.size(64.dp), shape = RoundedCornerShape(DesignMetrics.portraitCorner))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                ShimmerTextBlock(
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 21.sp),
                    lineWidths = listOf(0.72f),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                ShimmerTextLine(width = 44.dp, height = 11.dp)
            }
            ShimmerTextBlock(
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                lineWidths = listOf(0.82f)
            )
        }
    }
}

@Composable
fun CircleAvatarPlaceholder(
    modifier: Modifier = Modifier,
    size: Dp
) {
    ShimmerBox(modifier = modifier.size(size), shape = CircleShape)
}
