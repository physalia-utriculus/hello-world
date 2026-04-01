package software.medusa.helloworld.shared

data class AppConfig(
    val gcpProjectId: String,
    val gcpRegion: String,
    val workerJobName: String,
) {
    companion object {
        fun fromEnvironment(): AppConfig {
            return AppConfig(
                gcpProjectId = requiredEnv("GCP_PROJECT_ID"),
                gcpRegion = requiredEnv("GCP_REGION"),
                workerJobName = requiredEnv("WORKER_JOB_NAME"),
            )
        }
    }
}

fun requiredEnv(name: String): String {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable: $name")
}
