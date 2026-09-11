package nl.vdzon.softwarefactory.runtime.v2

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClient
import java.util.UUID

class AgentRuntimeV2HttpClient(
    private val restClient: RestClient,
) {
    fun createJob(request: RuntimeCreateJobRequest): RuntimeJobView =
        requireNotNull(
            restClient.post()
                .uri("/v2/jobs")
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("create job"))
                .body(RuntimeJobView::class.java),
        ) { "Agent Runtime returned an empty create-job response" }

    fun getJob(jobId: UUID): RuntimeJobView =
        requireNotNull(
            restClient.get()
                .uri("/v2/jobs/{jobId}", jobId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("get job $jobId"))
                .body(RuntimeJobView::class.java),
        ) { "Agent Runtime returned an empty job response for $jobId" }

    fun getResult(jobId: UUID): RuntimeJobResultView =
        requireNotNull(
            restClient.get()
                .uri("/v2/jobs/{jobId}/result", jobId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("get result for $jobId"))
                .body(RuntimeJobResultView::class.java),
        ) { "Agent Runtime returned an empty result response for $jobId" }

    fun downloadJobObject(jobId: UUID, objectId: UUID): ByteArray =
        requireNotNull(
            restClient.get()
                .uri("/v2/jobs/{jobId}/objects/{objectId}/content", jobId, objectId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("download object $objectId for job $jobId"))
                .body(ByteArray::class.java),
        ) { "Agent Runtime returned empty object content for $objectId" }

    fun events(jobId: UUID, afterSequence: Long = 0): RuntimeJobEventPage =
        requireNotNull(
            restClient.get()
                .uri { builder ->
                    builder.path("/v2/jobs/{jobId}/events")
                        .queryParam("afterSequence", afterSequence)
                        .queryParam("limit", 500)
                        .build(jobId)
                }
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("get events for $jobId"))
                .body(RuntimeJobEventPage::class.java),
        ) { "Agent Runtime returned an empty event response for $jobId" }

    fun cancel(jobId: UUID): RuntimeJobView =
        requireNotNull(
            restClient.post()
                .uri("/v2/jobs/{jobId}/cancel", jobId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("cancel job $jobId"))
                .body(RuntimeJobView::class.java),
        ) { "Agent Runtime returned an empty cancel response for $jobId" }

    fun executionOptions(taskType: RuntimeTaskType): List<RuntimeExecutionOption> =
        restClient.get()
            .uri { builder ->
                builder.path("/v2/execution-options")
                    .queryParam("taskType", taskType.name)
                    .build()
            }
            .retrieve()
            .onStatus(HttpStatusCode::isError, runtimeError("get execution options"))
            .body(object : ParameterizedTypeReference<List<RuntimeExecutionOption>>() {})
            .orEmpty()

    fun repositoryAliases(): List<RuntimeRepositoryAliasOption> =
        restClient.get()
            .uri("/v2/repository-aliases")
            .retrieve()
            .onStatus(HttpStatusCode::isError, runtimeError("get repository aliases"))
            .body(object : ParameterizedTypeReference<List<RuntimeRepositoryAliasOption>>() {})
            .orEmpty()
}

internal fun runtimeError(operation: String): RestClient.ResponseSpec.ErrorHandler =
    RestClient.ResponseSpec.ErrorHandler { _, response ->
        val body = response.body.bufferedReader().use { it.readText() }.take(2_000)
        throw AgentRuntimeV2Exception(
            operation = operation,
            statusCode = response.statusCode.value(),
            responseBody = body,
        )
    }

class AgentRuntimeV2Exception(
    val operation: String,
    val statusCode: Int,
    val responseBody: String,
) : RuntimeException("$operation failed with HTTP $statusCode: $responseBody") {
    fun hasErrorCode(errorCode: String): Boolean =
        responseBody.contains("\"code\":\"$errorCode\"") ||
            responseBody.contains("\"code\": \"$errorCode\"")
}
