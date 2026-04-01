package software.medusa.helloworld.worker

import software.medusa.helloworld.shared.SessionDetail
import software.medusa.helloworld.shared.SessionEvent
import software.medusa.helloworld.shared.SessionRepository
import software.medusa.helloworld.shared.SessionStatus
import software.medusa.helloworld.shared.SessionSummary
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class WorkerAppTest {
    @Test
    fun workerMarksSessionSuccessful() = runTest {
        val repository = RecordingSessionRepository()
        repository.createQueuedSession("session-1")

        runWorkerSession("session-1", repository, FakeDemoClient())

        assertEquals(SessionStatus.SUCCEEDED, repository.session.status)
        assertEquals(100, repository.session.progressPercent)
    }
}

private class FakeDemoClient : DemoClient {
    override suspend fun fetchTodo(): String = "todo:test"
    override suspend fun fetchAdvice(): String = "advice:test"
    override suspend fun fetchAge(): String = "age:10"
    override suspend fun fetchNationality(): String = "country:PL"
    override suspend fun fetchCatFact(): String = "fact:test"
}

private class RecordingSessionRepository : SessionRepository {
    var session = SessionSummary(
        id = "session-1",
        status = SessionStatus.QUEUED,
        createdAt = Instant.parse("2026-04-01T00:00:00Z").toString(),
        updatedAt = Instant.parse("2026-04-01T00:00:00Z").toString(),
    )

    override suspend fun createQueuedSession(sessionId: String): SessionSummary = session.copy(id = sessionId).also { session = it }
    override suspend fun markRunning(sessionId: String, executionName: String?) = Unit
    override suspend fun markFailedToStart(sessionId: String, errorMessage: String) = Unit

    override suspend fun recordProgress(sessionId: String, step: String, progressPercent: Int, message: String, details: String?) {
        session = session.copy(status = SessionStatus.RUNNING, currentStep = step, progressPercent = progressPercent)
    }

    override suspend fun markSucceeded(sessionId: String, resultSummary: String) {
        session = session.copy(status = SessionStatus.SUCCEEDED, currentStep = "Completed", progressPercent = 100, resultSummary = resultSummary)
    }

    override suspend fun markFailed(sessionId: String, errorMessage: String) {
        session = session.copy(status = SessionStatus.FAILED, errorMessage = errorMessage)
    }

    override suspend fun listSessions(limit: Int): List<SessionSummary> = listOf(session)
    override suspend fun getSessionDetail(sessionId: String): SessionDetail = SessionDetail(session, emptyList<SessionEvent>())
}
