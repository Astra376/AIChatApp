package com.example.aichat.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/** Material owns drag, fling and spring settling; both bars follow its one offset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberScrollChrome() = TopAppBarDefaults.enterAlwaysScrollBehavior(
    snapAnimationSpec = spring(dampingRatio = .85f, stiffness = Spring.StiffnessMediumLow)
)

/** Adapts our compact header/navigation content to Material's scroll state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollChromeBar(state: TopAppBarState, top: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.clipToBounds().layout { measurable, constraints ->
        val child = measurable.measure(constraints.copy(minHeight = 0))
        if (top) state.heightOffsetLimit = -child.height.toFloat()
        val height = (child.height * (1f - state.collapsedFraction)).roundToInt()
        layout(child.width, height) { child.placeRelative(0, if (top) height - child.height else 0) }
    }) { content() }
}
