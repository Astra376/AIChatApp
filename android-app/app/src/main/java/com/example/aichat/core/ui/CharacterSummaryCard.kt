package com.example.aichat.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.example.aichat.core.design.DesignMetrics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.CharacterPortrait
import com.example.aichat.core.model.CharacterSummary

private object CharacterCardMetrics {
    val titleGap = 4.dp
}

@Composable
fun CharacterSummaryCard(
    character: CharacterSummary,
    modifier: Modifier = Modifier,
    imageAspectRatio: Float = 1.25f,
    isOpening: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val teaser = character.tagline.ifBlank { character.greeting }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(imageAspectRatio)
        ) {
            CharacterPortrait(
                name = character.name,
                avatarUrl = character.avatarUrl,
                modifier = Modifier.fillMaxSize()
            )
            if (rememberDelayedLoading(isOpening)) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape).padding(6.dp),
                    strokeWidth = 2.dp
                )
            }
            CharacterCountBadge(
                icon = AppIcons.chats,
                count = character.publicChatCount,
                label = "interactions",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 6.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text = character.name,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (compact) 15.sp else 20.sp,
                    lineHeight = if (compact) 18.sp else 22.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(CharacterCardMetrics.titleGap))
            Text(
                text = teaser,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.94f),
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
