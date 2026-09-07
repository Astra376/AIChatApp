package com.example.aichat.feature.home

import com.example.aichat.core.db.CharacterDao
import com.example.aichat.core.db.toEntity
import com.example.aichat.core.db.toModel
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.CursorPage
import com.example.aichat.core.network.HomeApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRepository @Inject constructor(
    private val characterDao: CharacterDao,
    private val homeApi: HomeApi
) {
    suspend fun loadFeed(cursor: String?): CursorPage<CharacterSummary> {
        val page = homeApi.getFeed(cursor = cursor)
        characterDao.upsertAll(page.items.map { it.toEntity() })
        return CursorPage(
            items = page.items.map { it.toEntity().toModel() },
            nextCursor = page.nextCursor
        )
    }

    suspend fun trendingSearches(): List<String> = homeApi.trendingSearches().queries

    suspend fun search(query: String, cursor: String?): CursorPage<CharacterSummary> {
        if (query.isBlank()) return CursorPage(emptyList(), null)
        val page = homeApi.search(query = query.trim(), cursor = cursor)
        characterDao.upsertAll(page.items.map { it.toEntity() })
        return CursorPage(
            items = page.items.map { it.toEntity().toModel() },
            nextCursor = page.nextCursor
        )
    }
}
