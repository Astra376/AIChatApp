package com.example.aichat.feature.activity

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aichat.core.design.CircleAvatar
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.core.ui.screenContentPadding

@Composable
fun ActivityRoute(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onOpenConversation: (String, String) -> Unit = { _, _ -> },
    onOpenCharacter: (String) -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenGroup: (String) -> Unit = {},
    viewModel: ActivityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    val showLoading = com.example.aichat.core.ui.rememberDelayedLoading(state.loading)
    ScreenBackgroundBox {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = screenContentPadding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap)) {
                        AppBackButton(onClick = onBack)
                        Text("Activity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (state.items.isNotEmpty()) TextButton(onClick = viewModel::clearAll) { Text("Clear all") }
                    }
                }
                if (showLoading && state.items.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
                if (!state.loading && state.items.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(bottom = 22.dp).size(112.dp), contentAlignment = Alignment.Center) {
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .09f), modifier = Modifier.fillMaxSize()) {}
                            com.example.aichat.core.design.AppIcon(com.example.aichat.core.design.AppIcons.activity, null, size = 54.dp, tint = MaterialTheme.colorScheme.primary)
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).size(34.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge) }
                            }
                        }
                        Text("You're all caught up", style = MaterialTheme.typography.titleMedium)
                        Text("Character messages and updates will appear here.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = { viewModel.load() }) { Text("Refresh") }
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    Surface(shape = RoundedCornerShape(16.dp), color = if (item.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                            viewModel.markRead(item)
                            when {
                                item.kind == "group" && item.conversationId != null -> onOpenGroup(item.conversationId)
                                item.conversationId != null -> onOpenConversation(item.conversationId, item.characterId.orEmpty())
                                item.characterId != null -> onOpenCharacter(item.characterId)
                                item.actorUserId != null -> onOpenProfile(item.actorUserId)
                            }
                        }.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircleAvatar(name = item.title, avatarUrl = item.avatarUrl, modifier = Modifier.size(44.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold)
                                Text(item.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(DateUtils.getRelativeTimeSpanString(item.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { viewModel.clear(item) }, contentPadding = PaddingValues(8.dp)) { Text("Clear") }
                        }
                    }
                }
                if (state.nextCursor != null) item {
                    TextButton(onClick = { viewModel.load(more = true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.loading) "Loading…" else "Load more")
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
