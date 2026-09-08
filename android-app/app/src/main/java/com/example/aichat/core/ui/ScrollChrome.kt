package com.example.aichat.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import kotlin.math.abs
import kotlin.math.roundToInt

class ScrollChromeState : NestedScrollConnection {
    var visible by mutableStateOf(true)
    private var distance = 0f
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && abs(available.y) > abs(available.x)) {
            if (distance * available.y < 0) distance = 0f
            distance += available.y
            if (abs(distance) > 16f) { visible = distance > 0; distance = 0f }
        }
        return Offset.Zero
    }
}

@Composable
fun rememberScrollChrome(): ScrollChromeState = remember { ScrollChromeState() }

/** The bar is measured at its natural size, then slides while releasing its space. */
@Composable
fun ScrollChromeBar(state: ScrollChromeState, top: Boolean, content: @Composable () -> Unit) {
    val fraction by animateFloatAsState(if (state.visible) 1f else 0f, tween(180), label = "scroll-chrome")
    Box(Modifier.clipToBounds().layout { measurable, constraints ->
        val child = measurable.measure(constraints.copy(minHeight = 0))
        val height = (child.height * fraction).roundToInt()
        layout(child.width, height) { child.placeRelative(0, if (top) height - child.height else 0) }
    }) { content() }
}
