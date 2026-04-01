package software.medusa.helloworld.worker

import com.google.cloud.firestore.FirestoreOptions
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import software.medusa.helloworld.shared.FirestoreSessionRepository
import software.medusa.helloworld.shared.SessionRepository
import software.medusa.helloworld.shared.requiredEnv

suspend fun main() {
    val sessionId = requiredEnv("SESSION_ID")
    val firestore = FirestoreOptions.getDefaultInstance().service
    val repository = FirestoreSessionRepository(firestore)
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    runWorkerSession(sessionId, repository, PublicApiDemoClient(httpClient))
}

suspend fun runWorkerSession(
    sessionId: String,
    repository: SessionRepository,
    demoClient: DemoClient,
) {
    try {
        repository.recordProgress(sessionId, "Preparing requests", 10, "Preparing public API calls.")
        delay(1000)

        repository.recordProgress(sessionId, "Calling APIs", 25, "Calling multiple public APIs concurrently.")
        val batchOne = coroutineScope {
            awaitAll(
                async { demoClient.fetchTodo() },
                async { demoClient.fetchAdvice() },
                async { demoClient.fetchAge() },
            )
        }
        repository.recordProgress(
            sessionId,
            "Processing first batch",
            50,
            "First batch finished.",
            batchOne.joinToString(" | ")
        )

        delay(1500)

        repository.recordProgress(sessionId, "Calling more APIs", 70, "Running another I/O batch.")
        val batchTwo = coroutineScope {
            awaitAll(
                async { demoClient.fetchNationality() },
                async { demoClient.fetchCatFact() },
            )
        }
        repository.recordProgress(
            sessionId,
            "Aggregating results",
            90,
            "Summarising responses.",
            batchTwo.joinToString(" | ")
        )

        delay(1000)

        repository.markSucceeded(
            sessionId,
            (batchOne + batchTwo).joinToString(separator = " | "),
        )
    } catch (cause: Throwable) {
        repository.markFailed(sessionId, cause.message ?: "Session failed")
        throw cause
    }
}

interface DemoClient {
    suspend fun fetchTodo(): String
    suspend fun fetchAdvice(): String
    suspend fun fetchAge(): String
    suspend fun fetchNationality(): String
    suspend fun fetchCatFact(): String
}

class PublicApiDemoClient(
    private val httpClient: HttpClient,
) : DemoClient {
    override suspend fun fetchTodo(): String {
        val todo = httpClient.get("https://jsonplaceholder.typicode.com/todos/1").body<TodoResponse>()
        return "todo:${todo.title}"
    }

    override suspend fun fetchAdvice(): String {
        val advice = httpClient.get("https://api.adviceslip.com/advice").body<AdviceEnvelope>()
        return "advice:${advice.slip.advice}"
    }

    override suspend fun fetchAge(): String {
        val age = httpClient.get("https://api.agify.io/?name=medusa").body<AgeResponse>()
        return "age:${age.age ?: -1}"
    }

    override suspend fun fetchNationality(): String {
        val nationality = httpClient.get("https://api.nationalize.io/?name=medusa").body<NationalityResponse>()
        return "country:${nationality.country.firstOrNull()?.countryId ?: "unknown"}"
    }

    override suspend fun fetchCatFact(): String {
        val fact = httpClient.get("https://catfact.ninja/fact").body<CatFactResponse>()
        return "fact:${fact.fact}"
    }
}

@Serializable
private data class TodoResponse(
    val title: String,
)

@Serializable
private data class AdviceEnvelope(
    val slip: AdviceSlip,
)

@Serializable
private data class AdviceSlip(
    val advice: String,
)

@Serializable
private data class AgeResponse(
    val age: Int? = null,
)

@Serializable
private data class NationalityResponse(
    val country: List<CountryProbability>,
)

@Serializable
private data class CountryProbability(
    @SerialName("country_id")
    val countryId: String,
)

@Serializable
private data class CatFactResponse(
    val fact: String,
)
