package com.example.aichat.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

internal fun formatRoleplayText(
    value: String,
    narrationColor: Color = Color.Unspecified,
    speechColor: Color = Color.Unspecified
): AnnotatedString = buildAnnotatedString {
    fun closing(text: String, from: Int, marker: String): Int {
        var index = from
        while (index < text.length) {
            if (text[index] == '\\') { index += 2; continue }
            if (text.startsWith(marker, index)) {
                if (marker.first() != '*' || text.drop(index).takeWhile { it == '*' }.length == marker.length) return index
                index += text.drop(index).takeWhile { it == '*' }.length
            } else index++
        }
        return -1
    }

    fun parse(text: String, depth: Int = 0) {
        if (depth > 16) { append(text); return }
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length && text[index + 1] in "*`\\\"") {
                append(text[index + 1]); index += 2; continue
            }
            val marker = when {
                text.startsWith("***", index) -> "***"
                text.startsWith("**", index) -> "**"
                text[index] == '*' -> "*"
                text.startsWith("```", index) -> "```"
                text[index] == '`' -> "`"
                text[index] == '"' -> "\""
                text[index] == '“' -> "“"
                else -> null
            }
            if (marker == null) { append(text[index++]); continue }
            val endMarker = if (marker == "“") "”" else marker
            val end = closing(text, index + marker.length, endMarker)
            if (end < 0) { append(marker); index += marker.length; continue }
            val quoted = marker == "\"" || marker == "“"
            val code = marker.startsWith('`')
            val style = when (marker) {
                "***" -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = narrationColor)
                "**" -> SpanStyle(fontWeight = FontWeight.Bold)
                "*" -> SpanStyle(fontStyle = FontStyle.Italic, color = narrationColor)
                "`", "```" -> SpanStyle(fontFamily = FontFamily.Monospace)
                else -> SpanStyle(color = speechColor, fontWeight = FontWeight.Medium)
            }
            pushStyle(style)
            if (quoted) append(marker)
            val content = text.substring(index + marker.length, end)
            if (code) append(content) else parse(content, depth + 1)
            if (quoted) append(endMarker)
            pop()
            index = end + endMarker.length
        }
    }
    parse(value)
}
