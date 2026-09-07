package com.example.aichat.feature.persona

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable data class PersonaDto(
    val id: String = "", val name: String = "", val backstory: String = "",
    val appearance: String = "", val pronouns: String = "",
    val createdAt: Long = 0, val updatedAt: Long = 0
)
@Serializable data class PersonaDetailsDto(
    val name: String = "", val backstory: String = "", val appearance: String = "", val pronouns: String = ""
)
@Serializable data class PersonaLibraryDto(
    val items: List<PersonaDto> = emptyList(), val defaultPersonaId: String? = null, val accountName: String = "You"
)
@Serializable data class ConversationPersonaDto(
    val conversationId: String = "", val mode: String = "auto", val personaId: String? = null,
    val effectiveName: String = "You", val characterDefault: PersonaDetailsDto? = null,
    val accountName: String = "You", val defaultPersonaId: String? = null, val groupId: String = ""
)
interface PersonaApi {
    @GET("v1/personas") suspend fun list(): PersonaLibraryDto
    @POST("v1/personas") suspend fun create(@Body input: Map<String, String>): PersonaDto
    @PATCH("v1/personas/{id}") suspend fun update(@Path("id") id: String, @Body input: Map<String, String>): PersonaDto
    @DELETE("v1/personas/{id}") suspend fun delete(@Path("id") id: String)
    @PATCH("v1/personas/default") suspend fun setDefault(@Body input: Map<String, JsonElement>)
    @GET("v1/conversations/{id}/persona") suspend fun selection(@Path("id") id: String): ConversationPersonaDto
    @PATCH("v1/conversations/{id}/persona") suspend fun select(
        @Path("id") id: String, @Body input: Map<String, String>
    ): ConversationPersonaDto
    @GET("v1/groups/{id}/persona") suspend fun groupSelection(@Path("id") id: String): ConversationPersonaDto
    @PATCH("v1/groups/{id}/persona") suspend fun selectGroup(
        @Path("id") id: String, @Body input: Map<String, String>
    ): ConversationPersonaDto
}
