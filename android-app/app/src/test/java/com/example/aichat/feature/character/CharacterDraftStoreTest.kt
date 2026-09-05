package com.example.aichat.feature.character

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.aichat.core.model.CharacterDraft
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CharacterDraftStoreTest {
    @Test fun draftSurvivesNewStoreInstanceAndIsScopedToAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = SavedCharacterDraft(
            draft = CharacterDraft(name = "Astrid", appearance = "silver hair", greeting = "Hello", characterDefinition = "Reserved"),
            step = CharacterCreateStep.DETAILS,
            portraitOptions = listOf("https://example.test/preview.jpg"),
            selectedPreview = "https://example.test/preview.jpg"
        )
        CharacterDraftStore(context).save("draft-owner", state)
        assertThat(CharacterDraftStore(context).read("draft-owner")).isEqualTo(state)
        assertThat(CharacterDraftStore(context).read("different-owner")).isEqualTo(SavedCharacterDraft())
        CharacterDraftStore(context).save("draft-owner", SavedCharacterDraft())
        assertThat(CharacterDraftStore(context).read("draft-owner")).isEqualTo(SavedCharacterDraft())
    }
}
