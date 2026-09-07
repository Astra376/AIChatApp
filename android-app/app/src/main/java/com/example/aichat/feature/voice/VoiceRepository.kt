package com.example.aichat.feature.voice

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.*

@Serializable data class VoiceDto(val id: String, val name: String, val description: String = "", val official: Boolean = false, val mine: Boolean = false, val public: Boolean = false)
@Serializable data class VoiceListDto(val available: Boolean = false, val items: List<VoiceDto> = emptyList())
@Serializable data class AudioDto(val audioUrl: String)
@Serializable data class SpeakDto(val conversationId: String, val messageId: String)
@Serializable data class VoiceChoiceDto(val voiceId: String)
interface VoiceApi {
    @GET("v1/voices") suspend fun list(): VoiceListDto
    @Multipart @POST("v1/voices") suspend fun create(@Part("name") name: RequestBody, @Part("description") description: RequestBody,
        @Part("public") public: RequestBody, @Part("requestKey") requestKey: RequestBody, @Part sample: MultipartBody.Part?): VoiceDto
    @POST("v1/voices/speak") suspend fun speak(@Body request: SpeakDto): AudioDto
    @POST("v1/voices/{id}/preview") suspend fun preview(@Path("id") id: String): AudioDto
    @PATCH("v1/characters/{id}/voice") suspend fun choose(@Path("id") characterId: String, @Body request: VoiceChoiceDto): VoiceChoiceDto
}
@Singleton class VoiceRepository @Inject constructor(retrofit: Retrofit, client: OkHttpClient, @ApplicationContext private val context: Context) {
    private val api = retrofit.newBuilder().client(client.newBuilder().readTimeout(205, TimeUnit.SECONDS).callTimeout(220, TimeUnit.SECONDS).build()).build().create(VoiceApi::class.java)
    suspend fun list() = api.list()
    suspend fun speak(conversationId: String, messageId: String) = api.speak(SpeakDto(conversationId, messageId)).audioUrl
    suspend fun preview(id: String) = api.preview(id).audioUrl
    suspend fun choose(characterId: String, voiceId: String) { api.choose(characterId, VoiceChoiceDto(voiceId)) }
    suspend fun create(name: String, description: String, public: Boolean, sample: Uri?, requestKey: String): VoiceDto = withContext(Dispatchers.IO) {
        val prepared = sample?.let { VoiceSample.prepare(context, it) }
        val plain = "text/plain".toMediaType()
        try {
            api.create(name.toRequestBody(plain), description.toRequestBody(plain), public.toString().toRequestBody(plain),
                requestKey.toRequestBody(plain), prepared?.let { MultipartBody.Part.createFormData("sample", it.file.name, it.file.asRequestBody(it.mime.toMediaType())) })
        } finally { prepared?.file?.delete() }
    }
}
