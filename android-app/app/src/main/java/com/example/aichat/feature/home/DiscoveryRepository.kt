package com.example.aichat.feature.home

import android.content.Context
import com.example.aichat.core.network.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DiscoveryDto(val version: String, val categories: List<DiscoveryCategoryDto>)
@Serializable
data class DiscoveryCategoryDto(val id: String, val title: String, val total: Int, val items: List<CharacterDto>)

@Singleton
class DiscoveryRepository @Inject constructor(@ApplicationContext context: Context, private val api: HomeApi) {
    private val preferences = context.getSharedPreferences("discovery-previews", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun cached(userId: String): DiscoveryDto? = runCatching {
        preferences.getString(userId, null)?.let { json.decodeFromString<DiscoveryDto>(it) }
    }.getOrNull()
    suspend fun refresh(userId: String): DiscoveryDto = withContext(Dispatchers.IO) {
        api.discover().also { preferences.edit().putString(userId, json.encodeToString(it)).apply() }
    }
    suspend fun category(id: String, version: String, cursor: String?) = api.discoverCategory(id, version, cursor)
}
