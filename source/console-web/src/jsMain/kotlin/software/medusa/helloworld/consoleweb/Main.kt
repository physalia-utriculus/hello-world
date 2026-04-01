package software.medusa.helloworld.consoleweb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import software.medusa.helloworld.shared.models.SessionDetail
import software.medusa.helloworld.shared.models.SessionListResponse
import software.medusa.helloworld.shared.models.SessionSummary
import software.medusa.helloworld.shared.models.StartSessionResponse

private val httpClient = HttpClient(Js) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

fun main() {
    renderComposable(rootElementId = "root") {
        ConsoleApp()
    }
}

@Composable
private fun ConsoleApp() {
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var selectedSession by remember { mutableStateOf<SessionDetail?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var startInFlight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshSessions() {
        val response = httpClient.get("/api/sessions").body<SessionListResponse>()
        sessions = response.sessions
        if (selectedSessionId == null) {
            selectedSessionId = response.sessions.firstOrNull()?.id
        }
    }

    suspend fun refreshDetail() {
        val sessionId = selectedSessionId ?: run {
            selectedSession = null
            return
        }
        selectedSession = runCatching {
            httpClient.get("/api/sessions/$sessionId").body<SessionDetail>()
        }.getOrNull()
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshSessions()
            refreshDetail()
            delay(3000)
        }
    }

    Div({ classes("shell") }) {
        Div({ classes("hero") }) {
            Panel {
                H1 { Text("Session Console") }
                MutedText("Start a long-running Cloud Run Job session and watch its Firestore-backed progress refresh live.")
                Button(
                    attrs = {
                        classes("primary-button")
                        type(ButtonType.Button)
                        if (startInFlight) disabled()
                        onClick {
                            startInFlight = true
                            actionError = null
                            scope.launch {
                                runCatching {
                                    val response = httpClient.post("/sessions")
                                    if (response.status.value !in 200..299) {
                                        throw Error(response.bodyAsText())
                                    }
                                    response.body<StartSessionResponse>()
                                }.onSuccess { response ->
                                    selectedSessionId = response.sessionId
                                    refreshSessions()
                                    refreshDetail()
                                }.onFailure { error ->
                                    js("console.error('Session starting failed', error)")
                                    actionError = "Session starting failed"
                                }
                                startInFlight = false
                            }
                        }
                    },
                ) {
                    Text("Start session")
                }
                if (actionError != null) {
                    P({ classes("error") }) { Text(actionError!!) }
                }
            }

            Panel {
                H2 { Text("How it works") }
                MutedText("The console creates a session, triggers a Cloud Run Job execution, and refreshes session state every few seconds.")
            }
        }

        Div({ classes("layout") }) {
            Panel {
                H2 { Text("Sessions") }
                Div({ classes("session-list") }) {
                    if (sessions.isEmpty()) {
                        MutedText("No sessions yet.")
                    } else {
                        sessions.forEach { session ->
                            SessionCard(
                                session = session,
                                selected = session.id == selectedSessionId,
                                onSelect = {
                                    selectedSessionId = session.id
                                    scope.launch { refreshDetail() }
                                },
                            )
                        }
                    }
                }
            }

            Panel {
                H2 { Text("Selected session") }
                if (selectedSession == null) {
                    MutedText("Start or select a session to observe it.")
                } else {
                    SessionDetailPanel(selectedSession!!)
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Div({ classes("panel") }) { content() }
}

@Composable
private fun SessionCard(session: SessionSummary, selected: Boolean, onSelect: () -> Unit) {
    Div(
        attrs = {
            classes("session-card")
            if (selected) classes("selected")
            onClick { onSelect() }
        },
    ) {
        Div({ classes("session-meta") }) {
            P({ classes("strong") }) { Text(session.id) }
            Span({ classes("badge", session.status.name) }) { Text(session.status.name) }
        }
        P { Text(session.currentStep ?: "Waiting for updates") }
        ProgressBar(session.progressPercent)
        Div({ classes("session-meta", "muted") }) {
            Span { Text("${session.progressPercent}%") }
            Span { Text(formatTimestamp(session.updatedAt)) }
        }
    }
}

@Composable
private fun SessionDetailPanel(detail: SessionDetail) {
    val session = detail.session

    Div {
        Div({ classes("session-meta") }) {
            P({ classes("strong") }) { Text(session.id) }
            Span({ classes("badge", session.status.name) }) { Text(session.status.name) }
        }
        P { Text(session.currentStep ?: "Waiting for updates") }
        ProgressBar(session.progressPercent)
        MutedText("Created ${formatTimestamp(session.createdAt)}")
        if (session.executionName != null) {
            MutedText("Execution: ${session.executionName}")
        }
        if (session.resultSummary != null) {
            val resultSummary = session.resultSummary
            if (resultSummary != null) {
                P { Text(resultSummary) }
            }
        }
        if (session.errorMessage != null) {
            val errorMessage = session.errorMessage
            if (errorMessage != null) {
                P({ classes("error") }) { Text(errorMessage) }
            }
        }
        H3 { Text("Events") }
        Div({ classes("events") }) {
            if (detail.events.isEmpty()) {
                MutedText("No events yet.")
            } else {
                detail.events.forEach { event ->
                    Div({ classes("event") }) {
                        P({ classes("strong") }) { Text(event.message) }
                        MutedText(buildString {
                            append(formatTimestamp(event.timestamp))
                            if (event.step != null) append(" - ${event.step}")
                            if (event.progressPercent != null) append(" - ${event.progressPercent}%")
                        })
                        if (event.details != null) {
                            val details = event.details
                            if (details != null) {
                                MutedText(details)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(progressPercent: Int) {
    Div({ classes("progress") }) {
        Div({
            classes("progress-fill")
            attr("style", "width: ${progressPercent}%;")
        })
    }
}

@Composable
private fun MutedText(text: String) {
    P({ classes("muted") }) { Text(text) }
}

private fun formatTimestamp(value: String): String = value.replace("T", " ").removeSuffix("Z")
