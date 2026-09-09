package com.example.aichat.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test
import retrofit2.Retrofit

class ChatRequestQueueTest {
    @Test
    fun transcriptControlsAndRecovery_doNotWaitBehindBackgroundRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        val base = OkHttpClient.Builder().dispatcher(Dispatcher().apply {
            maxRequests = 1
            maxRequestsPerHost = 1
        }).build()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(base).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val background = base.newCall(Request.Builder().url(server.url("/slow-artwork")).build())
        background.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
        try {
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)?.path).isEqualTo("/slow-artwork")
            server.enqueue(MockResponse().setResponseCode(204))
            withTimeout(3_000) { NetworkModule.provideChatApi(retrofit, base).rewind("message-1") }
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)?.path).isEqualTo("/v1/messages/message-1/rewind")
            server.enqueue(MockResponse().setResponseCode(204))
            withTimeout(3_000) { NetworkModule.provideConversationApi(retrofit, base).markConversationRead("chat-1") }
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)?.path).isEqualTo("/v1/conversations/chat-1/read")
        } finally {
            background.cancel()
            base.dispatcher.cancelAll()
            base.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
