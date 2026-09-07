package com.example.aichat.feature.customization

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import coil.imageLoader

@Serializable
data class AppearanceDto(
    val ultra: Boolean = false,
    val profileFont: String = "default", val frame: String = "none", val bannerId: String = "",
    val profileBackgroundId: String = "", val featuredCharacterId: String = "", val widgets: List<String> = emptyList(),
    val background: String = "default", val backgroundId: String = "", val icon: String = "default",
    val bannerUrl: String? = null, val profileBackgroundUrl: String? = null, val backgroundUrl: String? = null
) {
    fun patch() = JsonObject(mapOf(
        "profileFont" to JsonPrimitive(profileFont), "frame" to JsonPrimitive(frame), "bannerId" to JsonPrimitive(bannerId),
        "profileBackgroundId" to JsonPrimitive(profileBackgroundId), "featuredCharacterId" to JsonPrimitive(featuredCharacterId),
        "widgets" to JsonArray(widgets.map(::JsonPrimitive)), "background" to JsonPrimitive(background),
        "backgroundId" to JsonPrimitive(backgroundId), "icon" to JsonPrimitive(icon)
    ))
}
@Serializable data class ShowcaseCharacter(val id: String, val name: String, val avatarUrl: String? = null, val value: Int = 0)
@Serializable data class ShowcaseStats(val charactersChatted: Int? = null, val longestChat: Int? = null)
@Serializable data class ShowcaseDto(val appearance: AppearanceDto = AppearanceDto(), val stats: ShowcaseStats = ShowcaseStats(),
    val favorites: List<ShowcaseCharacter> = emptyList(), val created: List<ShowcaseCharacter> = emptyList(),
    val recommended: List<ShowcaseCharacter> = emptyList(), val featured: ShowcaseCharacter? = null)
@Serializable data class AppearanceAsset(val id: String, val kind: String, val url: String)
@Serializable data class GenerateAppearanceRequest(val kind: String, val prompt: String, val requestKey: String)
interface AppearanceApi {
    @GET("v1/me/appearance") suspend fun get(): AppearanceDto
    @PATCH("v1/me/appearance") suspend fun update(@Body request: JsonObject): AppearanceDto
    @GET("v1/profiles/{userId}/showcase") suspend fun showcase(@Path("userId") userId: String): ShowcaseDto
    @Multipart @POST("v1/me/appearance/assets") suspend fun upload(@Part("kind") kind: okhttp3.RequestBody, @Part image: MultipartBody.Part): AppearanceAsset
    @POST("v1/me/appearance/generate") suspend fun generate(@Body request: GenerateAppearanceRequest): AppearanceAsset
}
@Singleton
class AppearanceRepository @Inject constructor(retrofit: Retrofit, client: OkHttpClient, @ApplicationContext private val context: Context) {
    private val api = retrofit.create(AppearanceApi::class.java)
    private val imageApi = retrofit.newBuilder().client(client.newBuilder().readTimeout(170,TimeUnit.SECONDS).callTimeout(175,TimeUnit.SECONDS).build()).build().create(AppearanceApi::class.java)
    private val cache = context.getSharedPreferences("account-appearance", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(AppearanceDto())
    val preferences = state.asStateFlow()
    private val readyState = MutableStateFlow(false)
    val ready = readyState.asStateFlow()
    private var accountId: String? = null
    private var sessionRevision = 0
    private val showcases = java.util.concurrent.ConcurrentHashMap<String, ShowcaseDto>()
    suspend fun activate(userId: String) {
        if (accountId == userId && readyState.value) return
        sessionRevision++
        accountId = userId
        readyState.value = false
        val revision = sessionRevision
        val cached = withContext(Dispatchers.IO) { cache.getString(userId, null)?.let { runCatching { json.decodeFromString<AppearanceDto>(it) }.getOrNull() } }
        if (revision != sessionRevision) return
        state.value = cached ?: AppearanceDto()
        readyState.value = cached != null
        try { refresh() } finally { if (revision == sessionRevision) readyState.value = true }
    }
    private fun publish(value: AppearanceDto) {
        state.value = value
        accountId?.let { cache.edit().putString(it, json.encodeToString(AppearanceDto.serializer(), value)).apply() }
        listOf(value.bannerId to value.bannerUrl, value.profileBackgroundId to value.profileBackgroundUrl, value.backgroundId to value.backgroundUrl).forEach { (id, url) ->
            if (url != null) context.imageLoader.enqueue(coil.request.ImageRequest.Builder(context).data(url).memoryCacheKey("appearance:$id").diskCacheKey("appearance:$id").build())
        }
    }
    fun updateUltraAccess(active: Boolean) {
        if (active == state.value.ultra) return
        sessionRevision++ // Discard appearance requests started before the entitlement changed.
        publish(if (active) state.value.copy(ultra = true) else AppearanceDto())
    }
    suspend fun refresh() { val revision=sessionRevision; val result=api.get(); if(revision==sessionRevision) { publish(result); runCatching { LauncherAppearance.select(context,result.icon) } } }
    fun reset() { sessionRevision++; accountId=null; readyState.value=false; state.value=AppearanceDto(); runCatching { LauncherAppearance.select(context,"default") } }
    suspend fun save(value: AppearanceDto) { val revision=sessionRevision; val result=api.update(value.patch()); if(revision==sessionRevision) publish(result) }
    fun cachedShowcase(userId: String) = showcases[userId]
    suspend fun showcase(userId: String) = api.showcase(userId).also { showcases[userId] = it }
    suspend fun upload(kind: String, uri: Uri): AppearanceAsset = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) { val count = stream.read(chunk); if (count < 0) break; require(out.size() + count <= 10_000_000) { "Choose an image under 10 MB." }; out.write(chunk,0,count) }
            out.toByteArray()
         } ?: error("Image could not be opened.")
        require(bytes.size <= 10_000_000) { "Choose an image under 10 MB." }
        imageApi.upload(kind.toRequestBody("text/plain".toMediaType()), MultipartBody.Part.createFormData("image","image",bytes.toRequestBody("application/octet-stream".toMediaType())))
    }
    suspend fun generate(kind: String, prompt: String, requestKey: String = UUID.randomUUID().toString()) = imageApi.generate(GenerateAppearanceRequest(kind,prompt,requestKey))
}
