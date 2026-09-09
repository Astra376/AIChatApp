package com.example.aichat.core.ui

import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.composed
import kotlinx.coroutines.delay

/** Fast cached loads should never flash a spinner or skeleton. */
@Composable
fun rememberDelayedLoading(loading: Boolean, delayMillis: Long = 400L): Boolean {
    var visible by remember(loading) { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) { delay(delayMillis); visible = true } else visible = false
    }
    return loading && visible
}

fun Modifier.delayedLoadingAppearance(): Modifier = composed {
    val opacity by animateFloatAsState(if (rememberDelayedLoading(true)) 1f else 0f, tween(180), label = "loading-appearance")
    alpha(opacity)
}

@Composable
fun DelayedCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    strokeWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(4f)
) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = Modifier.delayedLoadingAppearance().then(modifier), color = color, strokeWidth = strokeWidth
    )
}
