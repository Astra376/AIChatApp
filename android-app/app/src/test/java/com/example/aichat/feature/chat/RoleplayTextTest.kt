package com.example.aichat.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoleplayTextTest {
    @Test fun emphasisAndQuotedSpeechHaveDistinctStyles() {
        val text = formatRoleplayText("*action* **bold** ***both*** “Hello”", Color.Gray, Color.White)
        assertThat(text.text).isEqualTo("action bold both “Hello”")
        assertThat(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.start == 0 && it.end == 6 }).isTrue()
        assertThat(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 7 }).isTrue()
        assertThat(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic }).isTrue()
        assertThat(text.spanStyles.any { it.item.color == Color.White && it.start == 17 }).isTrue()
    }
    @Test fun nestedEmphasisEscapesAndUnfinishedStreamRemainReadable() {
        val text = formatRoleplayText("*walks **very** slowly* \\*literal\\*\n*unfinished")
        assertThat(text.text).isEqualTo("walks very slowly *literal*\n*unfinished")
        assertThat(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 6 && it.end == 10 }).isTrue()
        assertThat(formatRoleplayText("`*code*`").text).isEqualTo("*code*")
    }
}
