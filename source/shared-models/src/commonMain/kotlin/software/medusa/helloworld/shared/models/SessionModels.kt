package software.medusa.helloworld.shared.models

import kotlinx.serialization.Serializable

@Serializable
enum class SessionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

@Serializable
data class SessionSummary(
    val id: String,
    val status: SessionStatus,
    val createdAt: String,
    val updatedAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val executionName: String? = null,
    val currentStep: String? = null,
    val progressPercent: Int = 0,
    val resultSummary: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class SessionEvent(
    val id: String,
    val timestamp: String,
    val type: String,
    val message: String,
    val step: String? = null,
    val progressPercent: Int? = null,
    val details: String? = null,
)

@Serializable
data class SessionDetail(
    val session: SessionSummary,
    val events: List<SessionEvent>,
)

@Serializable
data class SessionListResponse(
    val sessions: List<SessionSummary>,
)

@Serializable
data class StartSessionResponse(
    val sessionId: String,
    val executionName: String?,
)
