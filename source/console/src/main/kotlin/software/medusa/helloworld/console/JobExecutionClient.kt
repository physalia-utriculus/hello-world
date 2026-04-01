package software.medusa.helloworld.console

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

        return response.body<RunJobResponse>().name
    }
}

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
