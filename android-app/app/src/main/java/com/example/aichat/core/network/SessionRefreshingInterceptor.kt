package com.example.aichat.core.network

import com.example.aichat.BuildConfig
import com.example.aichat.core.auth.SessionStorage
import com.example.aichat.core.model.AppSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Singleton
class SessionRefreshingInterceptor @Inject constructor(
    private val sessionStorage: SessionStorage,
    private val json: Json
) : Interceptor {
    private val refreshLock = Any()
    private val jsonMediaType = "application/json".toMediaType()
    private val baseUrl = if (BuildConfig.API_BASE_URL.endsWith("/")) {
        BuildConfig.API_BASE_URL
    } else {
        "${BuildConfig.API_BASE_URL}/"
    }
    private val refreshUrl = "${baseUrl}v1/auth/refresh"
    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (isRefreshRequest(originalRequest)) {
            return chain.proceed(originalRequest)
        }

        val sessionBeforeRequest = sessionStorage.read()
        val activeSession = if (sessionBeforeRequest != null && shouldRefresh(sessionBeforeRequest)) {
            refreshSessionIfNeeded(sessionBeforeRequest)
        } else {
            sessionBeforeRequest
        }

        val authenticatedRequest = originalRequest.newBuilder().apply {
            if (activeSession != null) {
                header("Authorization", "Bearer ${activeSession.accessToken}")
            }
        }.build()

        val response = chain.proceed(authenticatedRequest)
        if (response.code != 401 || activeSession == null || authenticatedRequest.header(RETRY_HEADER) == "true") {
            return response
        }

        val refreshedSession = refreshSessionIfNeeded(activeSession) ?: return response

        response.close()
        return chain.proceed(
            authenticatedRequest.newBuilder()
                .header("Authorization", "Bearer ${refreshedSession.accessToken}")
                .header(RETRY_HEADER, "true")
                .build()
        )
    }

    private fun refreshSessionIfNeeded(previousSession: AppSession): AppSession? {
        synchronized(refreshLock) {
            val latestSession = sessionStorage.read() ?: return null
            if (latestSession.accessToken != previousSession.accessToken && !shouldRefresh(latestSession)) {
                return latestSession
            }

            val requestBody = json.encodeToString(
                RefreshSessionRequestDto.serializer(),
                RefreshSessionRequestDto(latestSession.refreshToken)
            ).toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(refreshUrl)
                .post(requestBody)
                .build()

            val response = refreshClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    return null
                }

                val body = it.body?.string() ?: return null
                val payload = json.decodeFromString(SessionResponseDto.serializer(), body)
                val refreshedSession = AppSession(
                    accessToken = payload.accessToken,
                    refreshToken = payload.refreshToken,
                    expiresAtEpochMillis = payload.expiresAt
                )
                sessionStorage.write(refreshedSession)
                return refreshedSession
            }
        }
    }

    private fun shouldRefresh(session: AppSession): Boolean {
        return session.expiresAtEpochMillis <= System.currentTimeMillis() + REFRESH_BUFFER_MILLIS
    }

    private fun isRefreshRequest(request: Request): Boolean {
        return request.url.toString() == refreshUrl
    }

    private companion object {
        const val RETRY_HEADER = "X-Auth-Retry"
        const val REFRESH_BUFFER_MILLIS = 30_000L
    }
}
