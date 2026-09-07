package com.example.aichat.feature.activity

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.aichat.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationWorkerDependencies {
    fun notificationRepository(): NotificationRepository
}

class NotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, NotificationWorkerDependencies::class.java).notificationRepository()
        if (repository.appVisible) return Result.success()
        try {
            val userId = repository.currentUserId() ?: return Result.success()
            val settings = repository.refreshSettings()
            if (!settings.pushEnabled || !hasNotificationPermission(applicationContext)) return Result.success()
            createChannels(applicationContext)
            val manager = NotificationManagerCompat.from(applicationContext)
            val startedAt = System.currentTimeMillis()
            val pending = repository.pendingForDevice(userId)
            if (repository.appVisible || repository.currentUserId() != userId) return Result.success()
            pending.items.sortedBy { it.updatedAt }.forEach { item ->
                val enabled = when (item.kind) {
                    "chat", "group" -> settings.chatMessagesEnabled
                    "follower" -> settings.followersEnabled
                    else -> settings.characterUpdatesEnabled
                }
                if (enabled) {
                    val channel = if (item.kind in listOf("chat", "group")) CHAT_CHANNEL else ACTIVITY_CHANNEL
                    val intent = Intent(applicationContext, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = notificationUri(item)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    val click = PendingIntent.getActivity(applicationContext, item.id.hashCode(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(applicationContext, channel)
                        .setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle(item.title)
                        .setContentText(item.body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
                        .setContentIntent(click)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .setWhen(item.createdAt)
                        .setGroup(if (item.kind in listOf("chat", "group")) "meek-chats" else "meek-activity")
                        .setCategory(if (item.kind in listOf("chat", "group")) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .build()
                    // Permission can be revoked between the check and notify; the worker retries safely.
                    if (hasNotificationPermission(applicationContext)) manager.notify(item.id.hashCode(), notification)
                }
                repository.rememberDelivery(userId, item)
            }
            if (pending.complete) repository.finishDeviceCheck(userId, startedAt)
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
    }

    companion object {
        const val CHAT_CHANNEL = "character-messages"
        const val ACTIVITY_CHANNEL = "social-activity"
        private const val WORK_NAME = "meek-notifications"
        fun schedule(context: Context) {
            createChannels(context)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            NotificationManagerCompat.from(context).cancelAll()
        }
        fun hasNotificationPermission(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        fun createChannels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel(CHAT_CHANNEL, "Character messages", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Messages from characters you chat with" },
                NotificationChannel(ACTIVITY_CHANNEL, "Followers and characters", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Followers and new characters you may like" }
            ))
        }
    }
}

fun notificationUri(item: ActivityNotificationDto): Uri = when {
    item.kind == "group" && item.conversationId != null -> Uri.Builder().scheme("meek").authority("group").appendPath(item.conversationId).build()
    item.conversationId != null -> Uri.Builder().scheme("meek").authority("chat").appendPath(item.conversationId).build()
    item.characterId != null -> Uri.Builder().scheme("meek").authority("character").appendPath(item.characterId).build()
    item.actorUserId != null -> Uri.Builder().scheme("meek").authority("profile").appendPath(item.actorUserId).build()
    else -> Uri.parse("meek://activity")
}
