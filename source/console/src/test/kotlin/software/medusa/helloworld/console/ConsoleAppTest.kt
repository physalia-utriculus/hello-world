package software.medusa.helloworld.console

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import software.medusa.helloworld.shared.SessionRepository
import software.medusa.helloworld.shared.models.SessionDetail
import software.medusa.helloworld.shared.models.SessionEvent
import software.medusa.helloworld.shared.models.SessionStatus
import software.medusa.helloworld.shared.models.SessionSummary
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleAppTest {
    @Test
    fun rootServesFrontendShell(): Unit = testApplication {
        application {
            module(InMemorySessionRepository(), FakeJobExecutionClient())
        }

        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun startSessionCreatesQueuedThenRunningSession(): Unit = testApplication {
        val repository = InMemorySessionRepository()
        application {
            module(repository, FakeJobExecutionClient())
        }

        client.post("/sessions").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        val sessions = repository.listSessions()
        assertEquals(1, sessions.size)
        assertEquals(SessionStatus.RUNNING, sessions.single().status)
    }
}

private class FakeJobExecutionClient : JobExecutionClient {
    override suspend fun startSessionJob(sessionId: String): String = "executions/$sessionId"
}

private class InMemorySessionRepository : SessionRepository {
    private val sessions = linkedMapOf<String, SessionSummary>()
    private val events = linkedMapOf<String, MutableList<SessionEvent>>()

    override suspend fun createQueuedSession(sessionId: String): SessionSummary {
        val now = Instant.parse("2026-04-01T00:00:00Z").toString()
        return SessionSummary(
            id = sessionId,
            status = SessionStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
            currentStep = "Queued",
            progressPercent = 0,
        ).also {
            sessions[sessionId] = it
            events.getOrPut(sessionId) { mutableListOf() }
        }
    }

    override suspend fun markRunning(sessionId: String, executionName: String?) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(
                status = SessionStatus.RUNNING,
                executionName = executionName,
                currentStep = "Starting worker",
                progressPercent = 5
            )
        }
    }

    override suspend fun markFailedToStart(sessionId: String, errorMessage: String) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(status = SessionStatus.FAILED, errorMessage = errorMessage, currentStep = "Failed to start")
        }
    }

    override suspend fun recordProgress(
        sessionId: String,
        step: String,
        progressPercent: Int,
        message: String,
        details: String?,
    ) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(status = SessionStatus.RUNNING, currentStep = step, progressPercent = progressPercent)
        }
    }

    override suspend fun markSucceeded(sessionId: String, resultSummary: String) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(
                status = SessionStatus.SUCCEEDED,
                resultSummary = resultSummary,
                currentStep = "Completed",
                progressPercent = 100
            )
        }
    }

    override suspend fun markFailed(sessionId: String, errorMessage: String) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(status = SessionStatus.FAILED, errorMessage = errorMessage, currentStep = "Failed")
        }
    }

    override suspend fun listSessions(limit: Int): List<SessionSummary> = sessions.values.take(limit)

    override suspend fun getSessionDetail(sessionId: String): SessionDetail? {
        val session = sessions[sessionId] ?: return null
        return SessionDetail(session, events[sessionId].orEmpty())
    }
}
