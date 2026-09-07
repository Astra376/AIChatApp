package com.example.aichat.feature.chat

enum class ActiveStreamMode {
    SEND,
    CONTINUE,
    REGENERATE
}

enum class ActiveStreamStatus {
    STREAMING,
    STOPPING,
    STOPPED
}

data class ActiveAssistantStream(
    val conversationId: String,
    val draftKey: String,
    val runId: String? = null,
    val mode: ActiveStreamMode,
    val assistantMessageId: String? = null,
    val targetMessageId: String? = null,
    val userMessageId: String? = null,
    val text: String = "",
    val accepted: Boolean = false,
    val status: ActiveStreamStatus = ActiveStreamStatus.STREAMING,
    val remoteOnly: Boolean = false,
    val generationStatus: String = "Replying",
    val modelLabel: String = "Meek Standard"
)
