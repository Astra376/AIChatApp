package com.example.aichat.core.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.composed
import kotlinx.coroutines.delay

/** Fast cached loads should never flash a spinner or skeleton. */
@Composable
fun rememberDelayedLoading(loading: Boolean, delayMillis: Long = 220L): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) { delay(delayMillis); visible = true } else visible = false
    }
    return loading && visible
}

fun Modifier.delayedLoadingAppearance(): Modifier = composed {
    alpha(if (rememberDelayedLoading(true)) 1f else 0f)
}

@Composable
fun DelayedCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    strokeWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(4f)
) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier.delayedLoadingAppearance(), color = color, strokeWidth = strokeWidth
    )
}
