package com.example.aichat.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.appOutlineSurface

object AppChrome {
    val screenHorizontalPadding = 20.dp
    val screenTopPadding = 16.dp
    val screenBottomPadding = 24.dp
    val sectionSpacing = 14.dp
    val gridSpacing = 12.dp
    val compactControlSize = 42.dp
    val compactControlGap = 10.dp
    val compactHeaderVerticalPadding = 8.dp
    val headerActionIconSize = 24.dp
    val bottomBarHeight = 50.dp
    val bottomBarTapHeight = 50.dp
    val bottomBarHorizontalPadding = 16.dp
    val bottomBarVerticalPadding = 6.dp
    val bottomBarItemHorizontalPadding = 4.dp
    val bottomBarIconSize = 28.dp
    val listRowGap = 20.dp
}

@Composable
fun screenContentPadding(paddingValues: PaddingValues): PaddingValues {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = if (paddingValues.calculateTopPadding() > 0.dp) {
        paddingValues.calculateTopPadding() + AppChrome.screenTopPadding
    } else {
        statusBarPadding + AppChrome.screenTopPadding
    }
    return PaddingValues(
        start = AppChrome.screenHorizontalPadding,
        top = topPadding,
        end = AppChrome.screenHorizontalPadding,
        bottom = paddingValues.calculateBottomPadding() + AppChrome.screenBottomPadding
    )
}

fun Modifier.pageContentFrame(
    paddingValues: PaddingValues = PaddingValues(),
    imeAware: Boolean = false
): Modifier = composed {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = if (paddingValues.calculateTopPadding() > 0.dp) {
        paddingValues.calculateTopPadding() + AppChrome.screenTopPadding
    } else {
        statusBarPadding + AppChrome.screenTopPadding
    }
    var framedModifier = this
        .fillMaxSize()
        .padding(top = topPadding)
    if (imeAware) {
        framedModifier = framedModifier.imePadding()
    }
    framedModifier.padding(
        horizontal = AppChrome.screenHorizontalPadding,
        vertical = AppChrome.screenTopPadding
    )
}

@Composable
fun ScreenBackgroundBox(
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val background = MaterialTheme.colorScheme.background
    Surface(
        modifier = modifier.fillMaxSize(),
        color = background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
            )
            content()
            snackbarHostState?.let {
                TopSnackbarHost(hostState = it)
            }
        }
    }
}

@Composable
fun BoxScope.TopSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        snackbar = { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = modifier
            .align(Alignment.TopCenter)
            .zIndex(100f)
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(
                start = AppChrome.screenHorizontalPadding,
                top = AppChrome.compactHeaderVerticalPadding,
                end = AppChrome.screenHorizontalPadding
            )
    )
}

@Composable
fun AppBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(AppChrome.compactControlSize)
            .appOutlineSurface(shape = CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(
            icon = AppIcons.back,
            contentDescription = "Back",
            size = AppChrome.headerActionIconSize,
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun SimplePageHeader(
    title: String,
    modifier: Modifier = Modifier,
    titlePrefix: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            titlePrefix?.invoke()
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        if (trailing != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing
            )
        }
    }
}

@Composable
fun MainPageHeader(
    title: String,
    onOpenSearch: () -> Unit,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
    titlePrefix: (@Composable () -> Unit)? = null
) {
    SimplePageHeader(title = title, modifier = modifier, titlePrefix = titlePrefix) {
        androidx.compose.material3.IconButton(onClick = onOpenSearch) {
            AppIcon(
                icon = AppIcons.search,
                contentDescription = "Search",
                size = AppChrome.headerActionIconSize,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.IconButton(onClick = onOpenActivity) {
            AppIcon(
                icon = AppIcons.activity,
                contentDescription = "Activity",
                size = AppChrome.headerActionIconSize,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
