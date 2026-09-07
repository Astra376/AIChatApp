package com.example.aichat.feature.activity

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.example.aichat.core.auth.SessionStorage
import com.example.aichat.core.db.ProfileDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Retrofit

data class DeviceNotificationBatch(val items: List<ActivityNotificationDto>, val complete: Boolean)

@Singleton
class NotificationRepository @Inject constructor(
    retrofit: Retrofit,
    private val sessionStorage: SessionStorage,
    private val profileDao: ProfileDao,
    @ApplicationContext private val context: Context
) {
    private val api = retrofit.create(NotificationApi::class.java)
    private val preferences = context.getSharedPreferences("notification-delivery", Context.MODE_PRIVATE)
    private val settingsMutex = Mutex()
    private val _settings = MutableStateFlow(NotificationSettingsDto())
    val settings = _settings.asStateFlow()
    @Volatile var appVisible: Boolean = false
        private set

    fun setAppVisible(visible: Boolean) { appVisible = visible }
    suspend fun currentUserId(): String? = if (sessionStorage.read() == null) null else profileDao.getProfile()?.userId
    suspend fun refreshSettings(): NotificationSettingsDto = api.settings().also { _settings.value = it }
    suspend fun updateSettings(value: NotificationSettingsDto): NotificationSettingsDto = settingsMutex.withLock {
        api.updateSettings(mapOf(
            "pushEnabled" to value.pushEnabled,
            "emailEnabled" to value.emailEnabled,
            "chatMessagesEnabled" to value.chatMessagesEnabled,
            "followersEnabled" to value.followersEnabled,
            "characterUpdatesEnabled" to value.characterUpdatesEnabled
        )).also {
            _settings.value = it
            if (!it.pushEnabled) NotificationManagerCompat.from(context).cancelAll()
        }
    }
    suspend fun presence(conversationId: String? = null) { api.presence(PresenceRequestDto(conversationId)) }
    suspend fun page(cursor: String? = null): NotificationPageDto = api.notifications(cursor = cursor)
    suspend fun markRead(notification: ActivityNotificationDto) {
        api.markRead(notification.id)
        NotificationManagerCompat.from(context).cancel(notification.id.hashCode())
    }
    suspend fun clear(notification: ActivityNotificationDto) {
        api.clear(notification.id)
        NotificationManagerCompat.from(context).cancel(notification.id.hashCode())
    }
    suspend fun clearAll() {
        api.clearAll()
        NotificationManagerCompat.from(context).cancelAll()
    }
    suspend fun followState(userId: String): FollowStateDto = api.followState(userId)
    suspend fun setFollow(userId: String, following: Boolean): FollowStateDto = if (following) api.follow(userId) else api.unfollow(userId)

    suspend fun pendingForDevice(userId: String): DeviceNotificationBatch {
        val since = preferences.getLong("last-check:$userId", System.currentTimeMillis() - 24 * 60 * 60_000)
        val pages = mutableListOf<ActivityNotificationDto>()
        var cursor: String? = null
        repeat(5) {
            val page = api.notifications(cursor, since)
            pages += page.items
            cursor = page.nextCursor
            if (cursor == null) {
                return DeviceNotificationBatch(pages.filter { notification ->
                    preferences.getLong("shown:$userId:${notification.id}", 0) < notification.updatedAt
                }, complete = true)
            }
        }
        return DeviceNotificationBatch(pages.filter { preferences.getLong("shown:$userId:${it.id}", 0) < it.updatedAt }, complete = false)
    }
    fun finishDeviceCheck(userId: String, startedAt: Long) {
        preferences.edit { putLong("last-check:$userId", startedAt - 1_000) }
    }
    fun rememberDelivery(userId: String, notification: ActivityNotificationDto) {
        preferences.edit {
            putLong("shown:$userId:${notification.id}", notification.updatedAt)
            val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60_000
            preferences.all.filter { (key, value) -> key.startsWith("shown:") && value is Long && value < cutoff }
                .keys.forEach(::remove)
        }
    }
}
