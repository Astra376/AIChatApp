package com.example.aichat.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles LIMIT 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles LIMIT 1")
    suspend fun getProfile(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles")
    suspend fun clear()
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters WHERE visibility = 'PUBLIC'")
    suspend fun getPublicCharacters(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE id = :characterId LIMIT 1")
    fun observeById(characterId: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE ownerUserId = :ownerUserId ORDER BY updatedAt DESC")
    fun observeOwnedCharacters(ownerUserId: String): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE likedByMe = 1 AND visibility = 'PUBLIC' ORDER BY updatedAt DESC")
    fun observeLikedCharacters(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :characterId LIMIT 1")
    suspend fun getById(characterId: String): CharacterEntity?

    @Query(
        """
        SELECT * FROM characters
        WHERE visibility = 'PUBLIC' AND (
            name LIKE '%' || :query || '%'
            OR tagline LIKE '%' || :query || '%'
            OR bio LIKE '%' || :query || '%'
        )
        """
    )
    suspend fun searchPublicCharacters(query: String): List<CharacterEntity>

    @Upsert
    suspend fun upsert(character: CharacterEntity)

    @Upsert
    suspend fun upsertAll(characters: List<CharacterEntity>)

    @Query(
        """
        UPDATE characters
        SET likedByMe = :likedByMe, likeCount = :likeCount
        WHERE id = :characterId
        """
    )
    suspend fun updateLikeState(
        characterId: String,
        likedByMe: Boolean,
        likeCount: Int
    ): Int

    @Query("DELETE FROM characters")
    suspend fun clear()
}

@Dao
interface ConversationSceneDao {
    @Query("SELECT * FROM conversation_scenes WHERE conversationId = :conversationId LIMIT 1")
    fun observeByConversation(conversationId: String): Flow<ConversationSceneEntity?>

    @Query("SELECT * FROM conversation_scenes WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getByConversation(conversationId: String): ConversationSceneEntity?

    @Upsert
    suspend fun upsert(scene: ConversationSceneEntity)

    @Query("DELETE FROM conversation_scenes WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM conversation_scenes")
    suspend fun clear()
}

@Dao
interface ConversationMemoryDao {
    @Query("SELECT * FROM conversation_memories WHERE conversationId = :conversationId LIMIT 1")
    fun observeByConversation(conversationId: String): Flow<ConversationMemoryEntity?>

    @Query("SELECT * FROM conversation_memories WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getByConversation(conversationId: String): ConversationMemoryEntity?

    @Upsert
    suspend fun upsert(memory: ConversationMemoryEntity)

    @Query("DELETE FROM conversation_memories")
    suspend fun clear()
}

data class ConversationSummaryRow(
    val id: String,
    val characterId: String,
    val characterName: String,
    val characterAvatarUrl: String?,
    val updatedAt: Long,
    val startedAt: Long,
    val lastMessageAt: Long?,
    val lastPreview: String,
    val unreadCount: Int,
    val hasUnreadBadge: Boolean
)

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT
            conversations.id AS id,
            conversations.characterId AS characterId,
            characters.name AS characterName,
            characters.avatarUrl AS characterAvatarUrl,
            conversations.updatedAt AS updatedAt,
            conversations.startedAt AS startedAt,
            conversations.lastMessageAt AS lastMessageAt,
            COALESCE(selectedRegenerations.content, latestAssistantMessages.content, conversations.previewText, '') AS lastPreview,
            conversations.unreadCount AS unreadCount,
            conversations.hasUnreadBadge AS hasUnreadBadge
        FROM conversations
        INNER JOIN characters ON characters.id = conversations.characterId
        LEFT JOIN messages AS latestAssistantMessages
            ON latestAssistantMessages.id = (
                SELECT messages.id
                FROM messages
                WHERE messages.conversationId = conversations.id
                    AND messages.sendState = 'SENT'
                    AND messages.role = 'ASSISTANT'
                ORDER BY messages.position DESC, messages.createdAt DESC, messages.updatedAt DESC, messages.id DESC
                LIMIT 1
            )
        LEFT JOIN assistant_regenerations AS selectedRegenerations
            ON selectedRegenerations.id = latestAssistantMessages.selectedRegenerationId
        WHERE conversations.ownerUserId = :ownerUserId
        ORDER BY conversations.updatedAt DESC
        """
    )
    fun observeConversationSummaries(ownerUserId: String): Flow<List<ConversationSummaryRow>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    fun observeById(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getById(conversationId: String): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversations
        WHERE ownerUserId = :ownerUserId AND characterId = :characterId
        ORDER BY updatedAt DESC, startedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findByOwnerAndCharacter(ownerUserId: String, characterId: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY
            CASE WHEN sendState = 'SENT' THEN 0 ELSE 1 END ASC,
            CASE WHEN sendState = 'SENT' THEN position END ASC,
            CASE WHEN sendState != 'SENT' THEN createdAt END ASC,
            CASE WHEN sendState != 'SENT' THEN updatedAt END ASC,
            id ASC
        """
    )
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY
            CASE WHEN sendState != 'SENT' THEN 0 ELSE 1 END ASC,
            CASE WHEN sendState != 'SENT' THEN createdAt END DESC,
            CASE WHEN sendState != 'SENT' THEN updatedAt END DESC,
            CASE WHEN sendState = 'SENT' THEN position END DESC,
            id DESC
        LIMIT :limit
        """
    )
    fun observeNewestMessages(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun observeMessageCount(conversationId: String): Flow<Int>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY
            CASE WHEN sendState = 'SENT' THEN 0 ELSE 1 END ASC,
            CASE WHEN sendState = 'SENT' THEN position END ASC,
            CASE WHEN sendState != 'SENT' THEN createdAt END ASC,
            CASE WHEN sendState != 'SENT' THEN updatedAt END ASC,
            id ASC
        """
    )
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND sendState = 'SENT' ORDER BY position ASC")
    suspend fun getCommittedMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND sendState = 'SENT' ORDER BY position DESC LIMIT :limit")
    suspend fun getRecentCommittedMessages(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND (position > :position OR sendState != 'SENT')")
    suspend fun getMessagesRemovedByRewind(conversationId: String, position: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND sendState = 'SENT' ORDER BY position DESC LIMIT 1")
    suspend fun getLatestMessage(conversationId: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND sendState = 'SENT' AND role = 'ASSISTANT'
        ORDER BY position DESC, createdAt DESC, updatedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestAssistantMessage(conversationId: String): MessageEntity?

    @Query("SELECT COALESCE(MIN(position), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMinimumPosition(conversationId: String): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND sendState != 'SENT' ORDER BY createdAt ASC")
    suspend fun getLocalOnlyMessages(conversationId: String): List<MessageEntity>

    @Upsert
    suspend fun insert(message: MessageEntity)

    @Upsert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Update
    suspend fun updateAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND position > :position")
    suspend fun deleteAfter(conversationId: String, position: Int)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND sendState = 'SENT'")
    suspend fun deleteCommittedByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface AssistantRegenerationDao {
    @Query(
        """
        SELECT assistant_regenerations.* FROM assistant_regenerations
        INNER JOIN messages ON messages.id = assistant_regenerations.messageId
        WHERE messages.conversationId = :conversationId
        ORDER BY assistant_regenerations.createdAt ASC
        """
    )
    fun observeConversationRegenerations(conversationId: String): Flow<List<AssistantRegenerationEntity>>

    @Query("SELECT * FROM assistant_regenerations WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getByMessage(messageId: String): List<AssistantRegenerationEntity>

    @Query("SELECT * FROM assistant_regenerations WHERE messageId IN (:messageIds) ORDER BY createdAt ASC")
    suspend fun getByMessages(messageIds: List<String>): List<AssistantRegenerationEntity>

    @Query("SELECT r.* FROM assistant_regenerations r INNER JOIN messages m ON m.id=r.messageId WHERE m.conversationId=:conversationId AND (m.position>:position OR m.sendState!='SENT')")
    suspend fun getRemovedByRewind(conversationId: String, position: Int): List<AssistantRegenerationEntity>

    @Query("""
        SELECT * FROM assistant_regenerations WHERE messageId IN (
            SELECT id FROM messages WHERE conversationId = :conversationId
            ORDER BY CASE WHEN sendState != 'SENT' THEN 0 ELSE 1 END ASC,
                CASE WHEN sendState != 'SENT' THEN createdAt END DESC,
                CASE WHEN sendState != 'SENT' THEN updatedAt END DESC,
                CASE WHEN sendState = 'SENT' THEN position END DESC, id DESC LIMIT :limit
        ) ORDER BY createdAt ASC
    """)
    fun observeNewestRegenerations(conversationId: String, limit: Int): Flow<List<AssistantRegenerationEntity>>

    @Query(
        """
        SELECT assistant_regenerations.* FROM assistant_regenerations
        INNER JOIN messages ON messages.id = assistant_regenerations.messageId
        WHERE messages.conversationId = :conversationId
        ORDER BY assistant_regenerations.createdAt ASC
        """
    )
    suspend fun getConversationRegenerations(conversationId: String): List<AssistantRegenerationEntity>

    @Query("SELECT * FROM assistant_regenerations WHERE id = :regenerationId LIMIT 1")
    suspend fun getById(regenerationId: String): AssistantRegenerationEntity?

    @Upsert
    suspend fun insert(regeneration: AssistantRegenerationEntity)

    @Upsert
    suspend fun insertAll(regenerations: List<AssistantRegenerationEntity>)

    @Query(
        """
        DELETE FROM assistant_regenerations
        WHERE messageId IN (
            SELECT id FROM messages
            WHERE conversationId = :conversationId AND sendState = 'SENT'
        )
        """
    )
    suspend fun deleteForCommittedMessages(conversationId: String)

    @Query("DELETE FROM assistant_regenerations WHERE id = :regenerationId")
    suspend fun deleteById(regenerationId: String)

    @Query("DELETE FROM assistant_regenerations")
    suspend fun clear()
}
