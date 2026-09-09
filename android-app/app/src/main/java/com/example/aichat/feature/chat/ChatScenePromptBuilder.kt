package com.example.aichat.feature.chat

import com.example.aichat.core.db.CharacterEntity
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.ChatMessage
import com.example.aichat.core.model.MessageSendState
import java.security.MessageDigest
import java.util.Locale

object ChatScenePromptBuilder {
    private val sceneCuePatterns = listOf(
        Regex("\\b(scene|setting|background)\\s*[:\\-]\\s*([^.!?\\n]{4,140})", RegexOption.IGNORE_CASE),
        Regex("\\b(?:arrive(?:s|d)? at|enter(?:s|ed)?|walk(?:s|ed)? into|step(?:s|ped)? into|go(?:es)? to|travel(?:s|ed)? to|move(?:s|d)? to)\\s+([^.!?\\n]{4,140})", RegexOption.IGNORE_CASE),
        Regex("\\b(?:inside|outside|within|beneath|aboard|at|in|on)\\s+(?:the|a|an)\\s+([^.!?\\n]{4,140})", RegexOption.IGNORE_CASE),
        Regex("\\b(?:later|that night|the next morning|at dawn|at dusk|meanwhile)\\b([^.!?\\n]{0,140})", RegexOption.IGNORE_CASE)
    )

    fun initialScene(character: CharacterSummary): ScenePrompt {
        val source = listOf(character.name, character.tagline, character.bio, character.greeting)
            .joinToString(" ")
            .trim()
        val key = "initial:${stableHash(source.lowercase(Locale.US))}"
        return ScenePrompt(key = key, prompt = backgroundPrompt(character.name, source))
    }

    fun initialScene(character: CharacterEntity): ScenePrompt {
        val source = listOf(character.name, character.tagline, character.bio, character.greeting)
            .joinToString(" ")
            .trim()
        val key = "initial:${stableHash(source.lowercase(Locale.US))}"
        return ScenePrompt(key = key, prompt = backgroundPrompt(character.name, source))
    }

    fun changedScene(character: CharacterSummary, messages: List<ChatMessage>): ScenePrompt? {
        val committed = messages
            .filter { it.sendState == MessageSendState.SENT }
            .takeLast(6)
            .asReversed()

        for (message in committed) {
            val cue = extractSceneCue(message.visibleContent) ?: continue
            val source = "${character.name}: $cue"
            return ScenePrompt(
                key = "scene:${stableHash("${character.id}:${cue.lowercase(Locale.US)}")}",
                prompt = backgroundPrompt(character.name, source)
            )
        }

        return null
    }

    private fun extractSceneCue(text: String): String? {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length < 8) return null

        for (pattern in sceneCuePatterns) {
            val match = pattern.find(compact) ?: continue
            val cue = match.groupValues.drop(1).lastOrNull { it.isNotBlank() }?.trim() ?: continue
            return cue
                .trim(' ', ',', ';', ':', '-', '.')
                .take(180)
                .takeIf { it.length >= 4 }
        }

        return null
    }

    private fun backgroundPrompt(characterName: String, source: String): String {
        val visualSource = source.take(900)
        return """
            Clean cinematic roleplay chat background for $characterName.
            Visual setting: $visualSource
            Wide immersive environment, no people, no characters, no text, no logos, no speech bubbles.
            Soft atmospheric lighting, readable dark and light areas for chat bubbles, tasteful depth, polished concept art.
        """.trimIndent()
    }

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

data class ScenePrompt(
    val key: String,
    val prompt: String
)
