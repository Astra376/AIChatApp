package com.example.aichat.core.network

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

private val errorResponseJson = Json { ignoreUnknownKeys = true }
private val genericHttpMessage = Regex("^HTTP\\s+\\d+.*", RegexOption.IGNORE_CASE)

fun Throwable.userFacingMessage(fallback: String): String {
    val causes = generateSequence(this as Throwable?) { it.cause }.toList()
    val httpError = causes.filterIsInstance<HttpException>().firstOrNull()
    if (httpError != null) {
        val backendMessage = httpError.response()
            ?.errorBody()
            ?.string()
            ?.let { body ->
                runCatching {
                    errorResponseJson.decodeFromString(ErrorResponseDto.serializer(), body).message.trim()
                }.getOrNull()
            }
            ?.takeIf { it.isNotBlank() }
        if (backendMessage != null) return backendMessage

        return httpStatusMessage(httpError.code(), fallback)
    }

    if (causes.any { it is kotlinx.serialization.SerializationException || it.message?.contains("stream", ignoreCase = true) == true && it.message?.contains("terminal", ignoreCase = true) == true }) {
        return "The reply was interrupted. Please retry."
    }
    if (causes.any { it is SocketTimeoutException }) {
        return "The request timed out. Check your connection and retry."
    }
    if (causes.any { it is UnknownHostException || it is ConnectException }) {
        return "Couldn't connect. Check your internet connection and retry."
    }

    return causes
        .asSequence()
        .mapNotNull { it.message?.trim() }
        .firstOrNull { it.isNotBlank() && !genericHttpMessage.matches(it) }
        ?: fallback
}

fun httpStatusMessage(statusCode: Int, fallback: String): String = when (statusCode) {
    401 -> "Your session expired. Sign in again and retry."
    409 -> "A reply is still finishing. Stop it or try again shortly."
    408, 504 -> "The request timed out. Check your connection and retry."
    429 -> "The service is busy right now. Please retry in a moment."
    in 500..599 -> "The service is temporarily unavailable. Please try again."
    else -> fallback
}
