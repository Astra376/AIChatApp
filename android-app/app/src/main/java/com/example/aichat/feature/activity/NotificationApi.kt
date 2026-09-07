package com.example.aichat.feature.activity

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class NotificationSettingsDto(
    val pushEnabled: Boolean = true,
    val emailEnabled: Boolean = true,
    val chatMessagesEnabled: Boolean = true,
    val followersEnabled: Boolean = true,
    val characterUpdatesEnabled: Boolean = true
)

@Serializable
data class ActivityNotificationDto(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val characterId: String? = null,
    val conversationId: String? = null,
    val actorUserId: String? = null,
    val avatarUrl: String? = null,
    val count: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val read: Boolean = false
)

@Serializable
data class NotificationPageDto(val items: List<ActivityNotificationDto>, val nextCursor: String? = null)

@Serializable
data class PresenceRequestDto(val conversationId: String? = null)

@Serializable
data class FollowStateDto(val following: Boolean = false, val followerCount: Int = 0)

interface NotificationApi {
    @GET("v1/notifications")
    suspend fun notifications(@Query("cursor") cursor: String? = null, @Query("since") since: Long? = null, @Query("pageSize") pageSize: Int = 50): NotificationPageDto
    @DELETE("v1/notifications") suspend fun clearAll()
    @DELETE("v1/notifications/{id}") suspend fun clear(@Path("id") id: String)
    @POST("v1/notifications/{id}/read") suspend fun markRead(@Path("id") id: String)
    @GET("v1/notification-settings") suspend fun settings(): NotificationSettingsDto
    @PATCH("v1/notification-settings") suspend fun updateSettings(@Body settings: Map<String, Boolean>): NotificationSettingsDto
    @POST("v1/presence") suspend fun presence(@Body body: PresenceRequestDto)
    @GET("v1/profiles/{userId}/follow") suspend fun followState(@Path("userId") userId: String): FollowStateDto
    @POST("v1/profiles/{userId}/follow") suspend fun follow(@Path("userId") userId: String): FollowStateDto
    @DELETE("v1/profiles/{userId}/follow") suspend fun unfollow(@Path("userId") userId: String): FollowStateDto
}
