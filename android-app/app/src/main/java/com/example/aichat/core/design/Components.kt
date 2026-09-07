package com.example.aichat.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.aichat.core.util.avatarPalette

internal object DesignMetrics {
    val cardCorner = 22.dp
    val buttonCorner = 999.dp
    val primaryButtonHeight = 54.dp
    val secondaryButtonHeight = 50.dp
    val iconGap = 8.dp
    val fieldCorner = 22.dp
    val fieldSingleLineHeight = 50.dp
    val fieldMultiLineHeight = 58.dp
    val fieldHorizontalPadding = 16.dp
    val fieldSingleLineVerticalPadding = 10.dp
    val fieldMultiLineVerticalPadding = 12.dp
    val fieldIconGap = 10.dp
    val iconCircleSize = 36.dp
    val iconPillHeight = 36.dp
    val iconPillHorizontalPadding = 16.dp
    val squareIconButtonSize = 50.dp
    val squareButtonCorner = 22.dp
    val selectionDotSize = 20.dp
    val selectionDotInnerSize = 8.dp
    val portraitCorner = 12.dp
    val chipHorizontalPadding = 12.dp
    val chipVerticalPadding = 6.dp
    val portraitBadgeGap = 12.dp
}

@Composable
private fun elevatedSurfaceColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return scheme.surface.copy(alpha = 0.98f).compositeOver(scheme.background)
}

@Composable
private fun controlSurfaceColor(selected: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    return if (selected) {
        scheme.onSurface.copy(alpha = 0.09f).compositeOver(scheme.surface)
    } else {
        scheme.surfaceVariant.copy(alpha = 0.9f).compositeOver(scheme.background)
    }
}

fun Modifier.appOutlineSurface(
    shape: Shape,
    enabled: Boolean = true,
    selected: Boolean = false
): Modifier = composed {
    this
        .alpha(if (enabled) 1f else 0.52f)
        .clip(shape)
        .background(controlSurfaceColor(selected = selected))
}

/**
 * Standard app subpage presented as a Material bottom sheet. Material owns the
 * drag, settling, accessibility, and dismissal behavior; callers only supply
 * page content and dismissal state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraggableSubpage(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(999.dp)
                        )
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
        ) {
            content()
        }
    }
}

@Composable
private fun OutlineTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(DesignMetrics.buttonCorner),
    height: Dp = DesignMetrics.secondaryButtonHeight,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DesignMetrics.iconPillHorizontalPadding
    ),
    selected: Boolean = false,
    contentColor: Color? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val resolvedContentColor = contentColor ?: if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .height(height)
            .appOutlineSurface(
                shape = shape,
                enabled = enabled,
                selected = selected
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides resolvedContentColor) {
            leadingIcon?.let {
                it()
                Spacer(modifier = Modifier.width(DesignMetrics.iconGap))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(DesignMetrics.cardCorner),
        colors = CardDefaults.cardColors(containerColor = elevatedSurfaceColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier.height(DesignMetrics.primaryButtonHeight),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(DesignMetrics.buttonCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                it()
                Spacer(modifier = Modifier.width(DesignMetrics.iconGap))
            }
            Text(text = text)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    OutlineTextButton(
        text = text,
        enabled = enabled,
        modifier = modifier,
        contentColor = contentColor,
        leadingIcon = leadingIcon,
        onClick = onClick
    )
}

@Composable
fun SelectionButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = DesignMetrics.secondaryButtonHeight,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    OutlineTextButton(
        text = text,
        modifier = modifier,
        height = height,
        selected = selected,
        leadingIcon = leadingIcon,
        onClick = onClick
    )
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    shape: RoundedCornerShape = RoundedCornerShape(DesignMetrics.fieldCorner),
    containerMinHeight: Dp? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val containerColor = controlSurfaceColor(selected = false)
    val alignTextToTop = !singleLine && minLines > 1
    // Keep selection and IME composing text together. Feed keyboard edits back
    // immediately rather than waiting for a ViewModel/Flow recomposition.
    var editor by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value)) }
    var lastExternalValue by remember { mutableStateOf(value) }
    if (value != lastExternalValue) {
        lastExternalValue = value
        if (value != editor.text) editor = TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    }
    BasicTextField(
        value = editor,
        onValueChange = { next ->
            editor = next
            onValueChange(next.text)
        },
        modifier = modifier,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (enabled) {
                            containerColor
                        } else {
                            containerColor.copy(alpha = 0.64f)
                        }
                    )
                    .defaultMinSize(
                        minHeight = containerMinHeight ?: if (singleLine) {
                            DesignMetrics.fieldSingleLineHeight
                        } else {
                            DesignMetrics.fieldMultiLineHeight
                        }
                    )
                    .padding(
                        horizontal = DesignMetrics.fieldHorizontalPadding,
                        vertical = if (singleLine) {
                            DesignMetrics.fieldSingleLineVerticalPadding
                        } else {
                            DesignMetrics.fieldMultiLineVerticalPadding
                        }
                    ),
                horizontalArrangement = Arrangement.spacedBy(DesignMetrics.fieldIconGap),
                verticalAlignment = if (alignTextToTop) Alignment.Top else Alignment.CenterVertically
            ) {
                leadingIcon?.let {
                    CompositionLocalProvider(
                        LocalContentColor provides if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            it()
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .align(if (alignTextToTop) Alignment.Top else Alignment.CenterVertically),
                    contentAlignment = if (alignTextToTop) Alignment.TopStart else Alignment.CenterStart
                ) {
                    if (editor.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (enabled) 0.84f else 0.48f
                            )
                        )
                    }
                    innerTextField()
                }
            }
        }
    )
}

@Composable
fun IconCircleButton(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    containerSize: Dp = DesignMetrics.iconCircleSize,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    androidx.compose.material3.IconButton(
        onClick = onClick, enabled = enabled, modifier = modifier.size(containerSize),
        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
            contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) { icon() }
}

@Composable
fun IconPillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    OutlineTextButton(
        text = text,
        modifier = modifier,
        enabled = enabled,
        selected = selected,
        shape = RoundedCornerShape(999.dp),
        height = DesignMetrics.iconPillHeight,
        contentPadding = PaddingValues(
            horizontal = DesignMetrics.iconPillHorizontalPadding
        ),
        leadingIcon = leadingIcon,
        onClick = onClick
    )
}

@Composable
fun SquareIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(DesignMetrics.squareIconButtonSize)
            .appOutlineSurface(
                shape = RoundedCornerShape(DesignMetrics.squareButtonCorner),
                enabled = enabled
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            icon()
        }
    }
}

@Composable
fun SelectionDot(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(DesignMetrics.selectionDotSize)
            .clip(CircleShape)
            .background(controlSurfaceColor(selected = selected)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) DesignMetrics.selectionDotInnerSize else 6.dp)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                )
        )
    }
}

@Composable
fun CharacterPortrait(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(DesignMetrics.portraitCorner),
    alignment: Alignment = Alignment.Center
) {
    val palette = avatarPalette(avatarUrl ?: name)
    when {
        avatarUrl.isNullOrBlank().not() -> {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = modifier
                    .clip(shape)
                    .background(controlSurfaceColor(selected = false))
            )
        }

        else -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(brush = Brush.linearGradient(palette)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CircleAvatar(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier
) {
    val palette = avatarPalette(avatarUrl ?: name)
    when {
        avatarUrl.isNullOrBlank().not() -> {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .clip(CircleShape)
                    .background(controlSurfaceColor(selected = false))
            )
        }

        else -> {
            Box(
                modifier = modifier
                    .clip(CircleShape)
                    .background(brush = Brush.linearGradient(palette)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun PillChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = controlSurfaceColor(selected = false)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = DesignMetrics.chipHorizontalPadding,
                vertical = DesignMetrics.chipVerticalPadding
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatRow(
    left: String,
    right: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(left, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(right, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PortraitBadge(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CharacterPortrait(
            name = name,
            avatarUrl = avatarUrl,
            modifier = Modifier
                .size(52.dp)
                .aspectRatio(1f)
        )
        Spacer(modifier = Modifier.size(DesignMetrics.portraitBadgeGap))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
