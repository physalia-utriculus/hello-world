package software.medusa.helloworld.console

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import software.medusa.helloworld.shared.AppConfig

interface JobExecutionClient {
    suspend fun startSessionJob(sessionId: String): String?
}

class CloudRunJobExecutionClient(
    private val httpClient: HttpClient,
    private val credentials: GoogleCredentials,
    private val appConfig: AppConfig,
) : JobExecutionClient {
    override suspend fun startSessionJob(sessionId: String): String? {
        val accessToken = credentials.refreshAccessToken().tokenValue
        val response = httpClient.post(
            "https://run.googleapis.com/v2/projects/${appConfig.gcpProjectId}/locations/${appConfig.gcpRegion}/jobs/${appConfig.workerJobName}:run",
        ) {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            bearerAuth(accessToken)
            setBody(
                RunJobRequestPayload(
                    overrides = RunOverrides(
                        containerOverrides = listOf(
                            ContainerOverride(
                                env = listOf(
                                    EnvVar("SESSION_ID", sessionId),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        if (response.status.value !in 200..299) {
            val responseBody = response.bodyAsText()
            throw JobExecutionException(describeRunJobFailure(response.status.value, responseBody))
        }

        return response.body<RunJobResponse>().name
    }
}

class JobExecutionException(message: String) : RuntimeException(message)

@kotlinx.serialization.Serializable
private data class RunJobRequestPayload(
    val overrides: RunOverrides,
)

@kotlinx.serialization.Serializable
private data class RunOverrides(
    val containerOverrides: List<ContainerOverride>,
)

@kotlinx.serialization.Serializable
private data class ContainerOverride(
    val env: List<EnvVar>,
)

@kotlinx.serialization.Serializable
private data class EnvVar(
    val name: String,
    val value: String,
)

@kotlinx.serialization.Serializable
private data class RunJobResponse(
    val name: String? = null,
)

private fun describeRunJobFailure(statusCode: Int, responseBody: String): String {
    val googleMessage = runCatching {
        Json.parseToJsonElement(responseBody)
            .jsonObject["error"]
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.content
    }.getOrNull()

    val suffix = when {
        googleMessage != null -> googleMessage
        responseBody.isNotBlank() -> responseBody
        else -> "No response body"
    }

    return "Session starting failed. Cloud Run Jobs API returned HTTP $statusCode. $suffix"
}
