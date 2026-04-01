package software.medusa.helloworld.shared

import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.helloworld.shared.models.SessionDetail
import software.medusa.helloworld.shared.models.SessionEvent
import software.medusa.helloworld.shared.models.SessionStatus
import software.medusa.helloworld.shared.models.SessionSummary
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

interface SessionRepository {
    suspend fun createQueuedSession(sessionId: String = UUID.randomUUID().toString()): SessionSummary
    suspend fun markRunning(sessionId: String, executionName: String?)
    suspend fun markFailedToStart(sessionId: String, errorMessage: String)
    suspend fun recordProgress(
        sessionId: String,
        step: String,
        progressPercent: Int,
        message: String,
        details: String? = null,
    )

    suspend fun markSucceeded(sessionId: String, resultSummary: String)
    suspend fun markFailed(sessionId: String, errorMessage: String)
    suspend fun listSessions(limit: Int = 20): List<SessionSummary>
    suspend fun getSessionDetail(sessionId: String): SessionDetail?
}

class FirestoreSessionRepository(
    private val firestore: Firestore,
    private val clock: Clock = SystemClock,
) : SessionRepository {
    override suspend fun createQueuedSession(sessionId: String): SessionSummary {
        val now = clock.now()
        val session = SessionSummary(
            id = sessionId,
            status = SessionStatus.QUEUED,
            createdAt = now.isoString(),
            updatedAt = now.isoString(),
            currentStep = "Queued",
            progressPercent = 0,
        )

        writeDocument(sessionDocument(session.id).set(session.toMap()))
        appendEvent(
            sessionId = session.id,
            type = "session-created",
            message = "Session created and queued.",
            step = session.currentStep,
            progressPercent = session.progressPercent,
        )
        return session
    }

    override suspend fun markRunning(sessionId: String, executionName: String?) {
        val now = clock.now().isoString()
        updateSession(
            sessionId = sessionId,
            mapOf(
                "status" to SessionStatus.RUNNING.name,
                "startedAt" to now,
                "updatedAt" to now,
                "executionName" to executionName,
                "currentStep" to "Starting worker",
                "progressPercent" to 5,
            ),
        )
        appendEvent(sessionId, "session-started", "Worker execution started.", "Starting worker", 5, executionName)
    }

    override suspend fun markFailedToStart(sessionId: String, errorMessage: String) {
        val now = clock.now().isoString()
        updateSession(
            sessionId = sessionId,
            mapOf(
                "status" to SessionStatus.FAILED.name,
                "updatedAt" to now,
                "finishedAt" to now,
                "errorMessage" to errorMessage,
                "currentStep" to "Failed to start",
            ),
        )
        appendEvent(sessionId, "session-start-failed", errorMessage, "Failed to start", 0, errorMessage)
    }

    override suspend fun recordProgress(
        sessionId: String,
        step: String,
        progressPercent: Int,
        message: String,
        details: String?,
    ) {
        updateSession(
            sessionId = sessionId,
            mapOf(
                "status" to SessionStatus.RUNNING.name,
                "updatedAt" to clock.now().isoString(),
                "currentStep" to step,
                "progressPercent" to progressPercent,
            ),
        )
        appendEvent(sessionId, "progress", message, step, progressPercent, details)
    }

    override suspend fun markSucceeded(sessionId: String, resultSummary: String) {
        val now = clock.now().isoString()
        updateSession(
            sessionId = sessionId,
            mapOf(
                "status" to SessionStatus.SUCCEEDED.name,
                "updatedAt" to now,
                "finishedAt" to now,
                "currentStep" to "Completed",
                "progressPercent" to 100,
                "resultSummary" to resultSummary,
                "errorMessage" to null,
            ),
        )
        appendEvent(sessionId, "session-succeeded", "Session completed successfully.", "Completed", 100, resultSummary)
    }

    override suspend fun markFailed(sessionId: String, errorMessage: String) {
        val now = clock.now().isoString()
        updateSession(
            sessionId = sessionId,
            mapOf(
                "status" to SessionStatus.FAILED.name,
                "updatedAt" to now,
                "finishedAt" to now,
                "currentStep" to "Failed",
                "errorMessage" to errorMessage,
            ),
        )
        appendEvent(sessionId, "session-failed", errorMessage, "Failed", null, errorMessage)
    }

    override suspend fun listSessions(limit: Int): List<SessionSummary> {
        val snapshot = await(
            firestore.collection(SESSIONS_COLLECTION)
                .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .limit(limit)
                .get(),
        )
        return snapshot.documents.mapNotNull(::toSessionSummary)
    }

    override suspend fun getSessionDetail(sessionId: String): SessionDetail? {
        val sessionSnapshot = await(sessionDocument(sessionId).get())
        val session = toSessionSummary(sessionSnapshot) ?: return null
        val eventsSnapshot = await(
            sessionDocument(sessionId)
                .collection(EVENTS_COLLECTION)
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.ASCENDING)
                .get(),
        )
        val events = eventsSnapshot.documents.mapNotNull(::toSessionEvent)
        return SessionDetail(session = session, events = events)
    }

    private suspend fun updateSession(sessionId: String, fields: Map<String, Any?>) {
        if (fields.isNotEmpty()) {
            writeDocument(sessionDocument(sessionId).set(fields, SetOptions.merge()))
        }
    }

    private suspend fun appendEvent(
        sessionId: String,
        type: String,
        message: String,
        step: String?,
        progressPercent: Int?,
        details: String? = null,
    ) {
        val now = clock.now().isoString()
        val eventId = UUID.randomUUID().toString()
        val payload = linkedMapOf<String, Any?>(
            "id" to eventId,
            "timestamp" to now,
            "type" to type,
            "message" to message,
            "step" to step,
            "progressPercent" to progressPercent,
            "details" to details,
        ).filterValues { it != null }
        writeDocument(
            sessionDocument(sessionId)
                .collection(EVENTS_COLLECTION)
                .document(eventId)
                .set(payload),
        )
    }

    private fun sessionDocument(sessionId: String) = firestore.collection(SESSIONS_COLLECTION).document(sessionId)

    private fun toSessionSummary(snapshot: DocumentSnapshot): SessionSummary? {
        if (!snapshot.exists()) {
            return null
        }
        return SessionSummary(
            id = snapshot.getString("id") ?: snapshot.id,
            status = SessionStatus.valueOf(snapshot.getString("status") ?: SessionStatus.QUEUED.name),
            createdAt = snapshot.getString("createdAt") ?: Instant.EPOCH.isoString(),
            updatedAt = snapshot.getString("updatedAt") ?: Instant.EPOCH.isoString(),
            startedAt = snapshot.getString("startedAt"),
            finishedAt = snapshot.getString("finishedAt"),
            executionName = snapshot.getString("executionName"),
            currentStep = snapshot.getString("currentStep"),
            progressPercent = snapshot.getLong("progressPercent")?.toInt() ?: 0,
            resultSummary = snapshot.getString("resultSummary"),
            errorMessage = snapshot.getString("errorMessage"),
        )
    }

    private fun toSessionEvent(snapshot: DocumentSnapshot): SessionEvent? {
        if (!snapshot.exists()) {
            return null
        }
        return SessionEvent(
            id = snapshot.getString("id") ?: snapshot.id,
            timestamp = snapshot.getString("timestamp") ?: Instant.EPOCH.isoString(),
            type = snapshot.getString("type") ?: "unknown",
            message = snapshot.getString("message") ?: "",
            step = snapshot.getString("step"),
            progressPercent = snapshot.getLong("progressPercent")?.toInt(),
            details = snapshot.getString("details"),
        )
    }

    private suspend fun <T> await(future: ApiFuture<T>): T = withContext(Dispatchers.IO) { future.get() }

    private suspend fun writeDocument(future: ApiFuture<*>) {
        await(future)
    }

    private fun SessionSummary.toMap(): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "status" to status.name,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
            "startedAt" to startedAt,
            "finishedAt" to finishedAt,
            "executionName" to executionName,
            "currentStep" to currentStep,
            "progressPercent" to progressPercent,
            "resultSummary" to resultSummary,
            "errorMessage" to errorMessage,
        ).filterValues { it != null }
    }

    companion object {
        private const val SESSIONS_COLLECTION = "sessions"
        private const val EVENTS_COLLECTION = "events"
    }
}

private fun Instant.isoString(): String = DateTimeFormatter.ISO_INSTANT.format(this)

fun Timestamp.toIsoString(): String = toDate().toInstant().isoString()
