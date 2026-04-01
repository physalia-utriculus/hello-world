package software.medusa.helloworld.console

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.FirestoreOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import software.medusa.helloworld.shared.AppConfig
import software.medusa.helloworld.shared.FirestoreSessionRepository
import software.medusa.helloworld.shared.SessionRepository
import software.medusa.helloworld.shared.models.SessionListResponse
import software.medusa.helloworld.shared.models.StartSessionResponse
import java.net.URLConnection
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val firestore = FirestoreOptions.getDefaultInstance().service
    val repository = FirestoreSessionRepository(firestore)
    val appConfig = AppConfig.fromEnvironment()
    val credentials = GoogleCredentials.getApplicationDefault()
        .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json()
        }
    }
    val jobExecutionClient = CloudRunJobExecutionClient(httpClient, credentials, appConfig)

    module(repository, jobExecutionClient)
}

fun Application.module(
    repository: SessionRepository,
    jobExecutionClient: JobExecutionClient,
) {
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Unexpected error"))
            )
        }
    }

    routing {
        get("/") {
            respondWebResource(call, "index.html")
        }

        post("/sessions") {
            val session = repository.createQueuedSession()
            try {
                val executionName = jobExecutionClient.startSessionJob(session.id)
                repository.markRunning(session.id, executionName)
                call.respond(StartSessionResponse(session.id, executionName))
            } catch (cause: Throwable) {
                repository.markFailedToStart(session.id, cause.message ?: "Failed to start worker job")
                throw cause
            }
        }

        get("/api/sessions") {
            call.respond(SessionListResponse(repository.listSessions()))
        }

        get("/api/sessions/{sessionId}") {
            val sessionId = call.parameters["sessionId"] ?: error("Missing sessionId")
            val detail = repository.getSessionDetail(sessionId)
            if (detail == null) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            } else {
                call.respond(detail)
            }
        }

        get("/web/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: error("Missing path")
            respondWebResource(call, path)
        }
    }
}

private suspend fun respondWebResource(call: io.ktor.server.application.ApplicationCall, path: String) {
    val resourcePath = "web/$path"
    val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
        ?: error("Missing web resource: $resourcePath")
    val bytes = resourceStream.use { it.readBytes() }
    val contentType = URLConnection.guessContentTypeFromName(call.request.path())
        ?.let(io.ktor.http.ContentType::parse)
        ?: when {
            path.endsWith(".js") -> io.ktor.http.ContentType.Application.JavaScript
            path.endsWith(".css") -> io.ktor.http.ContentType.Text.CSS
            path.endsWith(".html") -> io.ktor.http.ContentType.Text.Html
            else -> io.ktor.http.ContentType.Application.OctetStream
        }

    call.respondBytes(bytes, contentType)
}
