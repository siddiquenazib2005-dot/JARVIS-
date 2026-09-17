package com.jarvis.ai.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<ApiMessage>,
    @SerialName("stream") val stream: Boolean = true
)

@Serializable
data class ApiMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

@Serializable
data class ChatCompletionChunk(
    @SerialName("id") val id: String? = null,
    @SerialName("choices") val choices: List<ChunkChoice> = emptyList()
)

@Serializable
data class ChunkChoice(
    @SerialName("index") val index: Int = 0,
    @SerialName("delta") val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null
)

@Serializable
data class ChatCompletionResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("choices") val choices: List<ResponseChoice> = emptyList(),
    @SerialName("error") val error: ErrorEnvelope? = null
)

@Serializable
data class ResponseChoice(
    @SerialName("index") val index: Int = 0,
    @SerialName("message") val message: ResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ResponseMessage(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null
)

@Serializable
data class ErrorEnvelope(
    @SerialName("message") val message: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("param") val param: String? = null,
    @SerialName("code") val code: String? = null
)

@Serializable
data class VisionResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("choices") val choices: List<VisionChoice> = emptyList(),
    @SerialName("error") val error: ErrorEnvelope? = null
)

@Serializable
data class VisionChoice(
    @SerialName("index") val index: Int = 0,
    @SerialName("message") val message: VisionMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class VisionMessage(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null
)
