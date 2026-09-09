package com.example.aichat.feature.character

import com.example.aichat.core.model.CharacterDraft
import com.example.aichat.core.network.*
import com.example.aichat.feature.chat.CharacterMemoryUiState
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class CharacterPsychologyTest {
    @Test fun characterDraft_roundTripsTheEntireEditableMind() {
        val original=SavedCharacterDraft(draft=CharacterDraft(name="Astrid",psychologyDefaults=CharacterPsychologyDefaultsDto(
            psychology=CharacterPsychologyDto(cornerstone="A promise",secretDesires=listOf("Travel"),lifeStory="An astronomer"),
            emotions=CharacterEmotionDto(joy=73,momentum=mapOf("joy" to 3f)),
            defaultPersona=CharacterDefaultPersonaDto(name="Scout",backstory="A cartographer"))),step=CharacterCreateStep.PSYCHOLOGY)
        val json=Json { ignoreUnknownKeys=true }
        assertThat(json.decodeFromString(SavedCharacterDraft.serializer(),json.encodeToString(SavedCharacterDraft.serializer(),original))).isEqualTo(original)
    }
    @Test fun editingAnEmotion_preservesOtherAxesAndClampsToTheSliderRange() {
        val original=CharacterEmotionDto(joy=40,anger=10,trust=61)
        val changed=original.withScore("Joy",150)
        assertThat(changed.joy).isEqualTo(100)
        assertThat(changed.anger).isEqualTo(10)
        assertThat(changed.trust).isEqualTo(61)
        assertThat(original.joy).isEqualTo(40)
    }
    @Test fun incomingState_doesNotOverwriteUnsavedPsychology() {
        val state=CharacterMemoryUiState(psychology=CharacterPsychologyDto(cornerstone="My edit"),psychologyEdited=true)
        val next=state.withMemory(CharacterMemoryDto("chat","","",1,psychology=CharacterPsychologyDto(cornerstone="Server update")),true)
        assertThat(next.psychology?.cornerstone).isEqualTo("My edit")
        assertThat(next.hasChanges).isTrue()
        val saved=next.withMemory(CharacterMemoryDto("chat","","",2,psychology=CharacterPsychologyDto(cornerstone="My edit")),true,true)
        assertThat(saved.hasChanges).isFalse()
    }
}
