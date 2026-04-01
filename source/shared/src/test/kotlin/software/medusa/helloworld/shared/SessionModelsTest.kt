package software.medusa.helloworld.shared

import software.medusa.helloworld.shared.models.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionModelsTest {
    @Test
    fun sessionStatusHasExpectedOrder() {
        assertEquals(
            listOf("QUEUED", "RUNNING", "SUCCEEDED", "FAILED"),
            SessionStatus.entries.map { it.name },
        )
    }
}
