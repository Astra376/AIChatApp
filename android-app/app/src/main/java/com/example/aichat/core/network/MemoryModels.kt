package com.example.aichat.core.network

import kotlinx.serialization.Serializable

@Serializable
data class MemoryLimitsDto(
    val shortTerm: Int = 4_000,
    val midTerm: Int = 8_000,
    val longTerm: Int = 32_000
)

@Serializable
data class MemoryTimelineEventDto(
    val text: String,
    val fictionalTime: String? = null,
    val sourcePosition: Int = 0
)

@Serializable
data class MemorySceneDto(
    val summary: String = "",
    val location: String? = null,
    val fictionalTime: String? = null,
    val timeline: List<MemoryTimelineEventDto> = emptyList()
)

@Serializable
data class CharacterEmotionDto(
    val mood: String = "",
    val trust: Int = 0,
    val affection: Int = 0,
    val stress: Int = 0,
    val energy: Int = 0,
    val openness: Int = 0,
    val reason: String = "",
    val sourcePosition: Int = 0,
    val joy: Int = 50, val sadness: Int = 10, val anger: Int = 5, val fear: Int = 10,
    val curiosity: Int = 55, val jealousy: Int = 5, val hope: Int = 55, val loneliness: Int = 10,
    val shame: Int = 5, val pride: Int = 30, val guilt: Int = 5,
    val momentum: Map<String, Float> = emptyMap()
)

@Serializable
data class CharacterPersonalityDto(
    val warmth: Int = 0,
    val confidence: Int = 0,
    val playfulness: Int = 0,
    val formality: Int = 0,
    val assertiveness: Int = 50,
    val volatility: Int = 35, val resilience: Int = 50, val adaptability: Int = 50,
    val description: String = "",
    val sourcePosition: Int = 0
)

@Serializable
data class CharacterPsychologyDto(
    val cornerstone: String = "",
    val beliefs: List<String> = emptyList(),
    val desires: List<String> = emptyList(),
    val secretDesires: List<String> = emptyList(),
    val lifeStory: String = "",
    val dailyLife: String = "",
    val relationships: String = "",
    val significantEvents: List<String> = emptyList(),
    val sourcePosition: Int = 0
)

@Serializable
data class CharacterDefaultPersonaDto(val name: String = "", val backstory: String = "", val appearance: String = "", val pronouns: String = "")

@Serializable
data class CharacterPsychologyDefaultsDto(
    val advancedDefinition: String = "",
    val emotions: CharacterEmotionDto = CharacterEmotionDto(trust = 50, affection = 30, stress = 15, energy = 50, openness = 50),
    val personality: CharacterPersonalityDto = CharacterPersonalityDto(warmth = 50, confidence = 50, playfulness = 50, formality = 50),
    val psychology: CharacterPsychologyDto = CharacterPsychologyDto(),
    val defaultPersona: CharacterDefaultPersonaDto = CharacterDefaultPersonaDto()
)

@Serializable
data class AutoCreateCharacterRequestDto(val idea: String)

@Serializable
data class AutoCreateCharacterDto(
    val name: String, val tagline: String = "", val appearance: String = "", val greeting: String,
    val bio: String = "", val characterDefinition: String = "",
    val psychologyDefaults: CharacterPsychologyDefaultsDto = CharacterPsychologyDefaultsDto()
)

@Serializable
data class EmotionPortraitsDto(
    val portraits: Map<String, String> = emptyMap(),
    val generating: Boolean = false,
    val failed: Boolean = false,
    val format: String = ""
)
