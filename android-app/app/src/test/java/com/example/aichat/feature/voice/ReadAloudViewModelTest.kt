package com.example.aichat.feature.voice

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadAloudViewModelTest {
    @Test fun mutedMediaVolume_doesNotRequestOrGenerateMessageAudio() = withMutedDevice { model ->
        model.read("conversation", "message")
    }

    @Test fun mutedMediaVolume_doesNotRequestOrGenerateVoicePreview() = withMutedDevice { model ->
        model.preview("official:Ryan")
    }

    private fun withMutedDevice(play: (ReadAloudViewModel) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(client)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
        val model = ReadAloudViewModel(VoiceRepository(retrofit, client, context), context)
        try {
            play(model)
            assertThat(model.state.value.preparing).isFalse()
            assertThat(model.state.value.id).isNull()
            assertThat(model.state.value.error).contains("media volume")
            assertThat(server.requestCount).isEqualTo(0)
        } finally {
            model.stop()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
