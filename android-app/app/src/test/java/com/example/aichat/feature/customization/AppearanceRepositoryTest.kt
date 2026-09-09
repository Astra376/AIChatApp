package com.example.aichat.feature.customization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppearanceRepositoryTest {
    @Test fun cachedStyleSurvivesOfflineRestart_andTestUltraToggle_withoutLeakingAccounts() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("account-appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient()
            val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build()
            val initial = AppearanceRepository(retrofit, client, context)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ultra":true,"frame":"halo","profileFont":"serif","bannerId":"preset:aurora"}"""))
            initial.activate("owner")
            assertEquals("halo", initial.preferences.value.frame)
            initial.updateUltraAccess(false)
            assertFalse(initial.preferences.value.ultra)
            assertEquals("none", initial.preferences.value.frame)
            initial.updateUltraAccess(true)
            assertEquals("halo", initial.preferences.value.frame)
            assertEquals("preset:aurora", initial.preferences.value.bannerId)
            val restarted = AppearanceRepository(retrofit, client, context)
            server.enqueue(MockResponse().setResponseCode(503))
            runCatching { restarted.activate("owner") }
            assertTrue(restarted.ready.value)
            assertTrue(restarted.preferences.value.ultra)
            assertEquals("serif", restarted.preferences.value.profileFont)
            restarted.reset()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ultra":false}"""))
            restarted.activate("other")
            restarted.updateUltraAccess(true)
            assertEquals("none", restarted.preferences.value.frame)
            assertEquals("", restarted.preferences.value.bannerId)
        } finally { server.shutdown() }
    }
}
