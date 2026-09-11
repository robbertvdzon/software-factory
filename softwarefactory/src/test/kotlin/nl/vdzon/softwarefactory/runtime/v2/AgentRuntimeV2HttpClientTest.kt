package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRuntimeV2HttpClientTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules().registerKotlinModule()

    @Test
    fun `create serialiseert het branchgerichte contract zonder repository-url of input-sha`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = AgentRuntimeV2HttpClient(builder.build())
        val jobId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val request = repositoryRequest()

        server.expect(requestTo("/v2/jobs"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect { exchange ->
                val json = mapper.readTree((exchange as MockClientHttpRequest).bodyAsBytes)
                assertEquals("software-factory", json.at("/repositoryCheckout/alias").asText())
                assertEquals("software-factory/SF-42", json.at("/repositoryCheckout/branch").asText())
                assertEquals("COMMIT_AND_PUSH", json.at("/repositoryCheckout/publicationMode").asText())
                assertEquals("REPOSITORY_CONFIG", json.at("/verification/mode").asText())
                assertTrue(json.path("repositorySnapshot").isMissingNode)
                assertTrue(json.path("baseBranch").isMissingNode)
                assertTrue(json.path("commitSha").isMissingNode)
            }
            .andRespond(withSuccess(jobJson(jobId, "QUEUED"), MediaType.APPLICATION_JSON))

        val created = client.createJob(request)

        assertEquals(jobId, created.id)
        assertEquals(RuntimeJobStatus.QUEUED, created.status)
        server.verify()
    }

    @Test
    fun `worker-wachtstatus uit het productiecontract blijft polbaar`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = AgentRuntimeV2HttpClient(builder.build())
        val jobId = UUID.fromString("55555555-5555-5555-5555-555555555555")
        server.expect(requestTo("/v2/jobs/$jobId"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(jobJson(jobId, "WAITING_FOR_WORKER"), MediaType.APPLICATION_JSON))

        val job = client.getJob(jobId)

        assertEquals(RuntimeJobStatus.WAITING_FOR_WORKER, job.status)
        assertEquals(false, job.terminal)
        server.verify()
    }

    @Test
    fun `resultaat houdt agent repository en verificatiegegevens gescheiden`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = AgentRuntimeV2HttpClient(builder.build())
        val jobId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        server.expect(requestTo("/v2/jobs/$jobId/result"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {
                      "jobId":"$jobId",
                      "result":{"phase":"developed","summaryText":"klaar"},
                      "repositoryResult":{
                        "alias":"software-factory",
                        "branch":"software-factory/SF-42",
                        "checkoutCommitSha":"1111111111111111111111111111111111111111",
                        "publicationStatus":"PUSHED",
                        "commitSha":"2222222222222222222222222222222222222222"
                      },
                      "verificationResult":{
                        "status":"PASSED",
                        "configVersion":1,
                        "agentRounds":2,
                        "commands":[{
                          "id":"backend",
                          "argv":["mvn","verify"],
                          "status":"PASSED",
                          "exitCode":0,
                          "durationMillis":1200,
                          "outputTail":"BUILD SUCCESS"
                        }]
                      },
                      "artifacts":[],
                      "usageSummary":{"attemptCount":1,"usageQuality":"MEASURED","metrics":[],"costs":[]},
                      "completedAt":"2026-09-11T07:00:00Z"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.getResult(jobId)

        assertEquals("developed", result.result.path("phase").asText())
        assertEquals(RuntimePublicationStatus.PUSHED, result.repositoryResult?.publicationStatus)
        assertEquals(RuntimeVerificationStatus.PASSED, result.verificationResult?.status)
        assertEquals(2, result.verificationResult?.agentRounds)
        server.verify()
    }

    @Test
    fun `inputupload gebruikt create head patch en complete met offsets`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = AgentRuntimeV2UploadClient(builder.build())
        val uploadId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val objectId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val sha = "a".repeat(64)
        server.expect(requestTo("/v2/uploads"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"uploadId":"$uploadId","objectId":"$objectId","state":"UPLOADING","protocol":"RESUMABLE_PATCH","chunkSizeBytes":1048576,"uploadUrl":"/v2/uploads/$uploadId","offset":0,"sizeBytes":3,"expiresAt":"2026-09-12T07:00:00Z"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("/v2/uploads/$uploadId"))
            .andExpect(method(HttpMethod.HEAD))
            .andRespond(
                withStatus(HttpStatus.NO_CONTENT)
                    .header("Upload-Offset", "0")
                    .header("Upload-Length", "3")
                    .header("Upload-State", "UPLOADING"),
            )
        server.expect(requestTo("/v2/uploads/$uploadId"))
            .andExpect(method(HttpMethod.PATCH))
            .andExpect { exchange ->
                assertEquals("0", exchange.headers.getFirst("Upload-Offset"))
                assertEquals("abc", String((exchange as MockClientHttpRequest).bodyAsBytes))
            }
            .andRespond(withStatus(HttpStatus.NO_CONTENT).header("Upload-Offset", "3"))
        server.expect(requestTo("/v2/uploads/$uploadId/complete"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"objectId":"$objectId","filename":"source.txt","mimeType":"text/plain","sizeBytes":3,"sha256":"$sha","state":"READY","createdAt":"2026-09-11T07:00:00Z","readyAt":"2026-09-11T07:01:00Z"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val upload = client.createUpload(RuntimeCreateUploadRequest("source.txt", "text/plain", 3, sha))
        val head = client.uploadHead(upload.uploadId)
        val offset = client.appendUploadChunk(upload.uploadId, head.offset, "abc".toByteArray())
        val completed = client.completeUpload(upload.uploadId)

        assertEquals(3, offset)
        assertEquals(RuntimeUploadState.READY, completed.state)
        assertEquals(objectId, completed.objectId)
        server.verify()
    }

    private fun repositoryRequest() = RuntimeCreateJobRequest(
        idempotencyKey = "story-SF-42-development-1",
        jobKind = RuntimeJobKind.REPOSITORY_WORK,
        taskType = RuntimeTaskType.REPOSITORY_AGENT,
        execution = RuntimeExecution("openai", "gpt-5.6-sol", RuntimeExecutionMode.SUBSCRIPTION),
        input = RuntimeJobInput("Voer de story uit."),
        output = RuntimeOutputContract(
            resultSchema = mapper.readTree(
                """{"type":"object","required":["phase"],"properties":{"phase":{"type":"string"}}}""",
            ),
        ),
        repositoryCheckout = RuntimeRepositoryCheckout(
            alias = "software-factory",
            branch = "software-factory/SF-42",
            publicationMode = RuntimePublicationMode.COMMIT_AND_PUSH,
        ),
        verification = RuntimeJobVerification(RuntimeVerificationMode.REPOSITORY_CONFIG),
        executionTimeoutSeconds = 3_600,
    )

    private fun jobJson(jobId: UUID, status: String) =
        """
        {
          "id":"$jobId",
          "tenantId":"software-factory",
          "idempotencyKey":"story-SF-42-development-1",
          "jobKind":"REPOSITORY_WORK",
          "taskType":"REPOSITORY_AGENT",
          "execution":{"vendorId":"openai","model":"gpt-5.6-sol","mode":"SUBSCRIPTION"},
          "status":"$status",
          "phase":"QUEUED",
          "attemptCount":0,
          "maxAttempts":3,
          "createdAt":"2026-09-11T07:00:00Z",
          "updatedAt":"2026-09-11T07:00:00Z"
        }
        """.trimIndent()
}
