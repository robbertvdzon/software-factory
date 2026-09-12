package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.NotificationEvent
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.dashboard.DashboardCommands
import nl.vdzon.softwarefactory.dashboard.DashboardQueries
import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ProductFactoryStoryRequest(
    val productSlug: String,
    val projectKey: String? = null,
    val title: String,
    val description: String,
    val repo: String,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
    val workspaceRunId: String,
    val workspaceCommitSha: String,
    val artifactPath: String,
    val deliveryMode: String = "start-next",
    // Ontbreken (oudere Product Factory-versie) valt terug op de platformbrede defaults
    // (NotificationEvent.DEFAULT, questionsAllowed=true, ApprovalMode.AUTOMATIC) — zie createStory().
    val notificationEvents: Set<String>? = null,
    val questionsAllowed: Boolean? = null,
    val approvalMode: String? = null,
)

data class ProductFactoryStoryResponse(
    val storyKey: String,
    val created: Boolean,
    val deliveryMode: String,
)

data class ProductFactoryAnswerRequest(
    val targetType: String,
    val targetKey: String,
    val phase: String,
    val answer: String,
)

/**
 * Klein machine-tot-machine contract (v1) tussen Product Factory en Software Factory. De
 * dashboard-API blijft Google-auth gebruiken; deze route accepteert alleen het aparte, minimaal
 * gescopeerde integratietoken. Idempotentie leeft duurzaam in de storybeschrijving, zodat ook een
 * retry na een time-out geen dubbele story kan maken.
 */
@RestController
@RequestMapping("/api/integrations/v1")
class ProductFactoryIntegrationController(
    private val dashboard: DashboardQueries,
    private val commands: DashboardCommands,
    private val operations: FactoryOperations,
    private val version: FactoryVersionQuery,
    private val secrets: FactorySecrets,
) {
    private val createLock = Any()

    @GetMapping("/status")
    fun status(@RequestHeader("Authorization", required = false) authorization: String?): Map<String, Any?> {
        authorize(authorization)
        return mapOf("connected" to true, "factoryVersion" to version.commitShort())
    }

    @PostMapping("/stories")
    fun createStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: ProductFactoryStoryRequest,
    ): ResponseEntity<Any> {
        authorize(authorization)
        val key = validateCreateStory(idempotencyKey, body)

        return synchronized(createLock) {
            existingStory(key)?.let { existing ->
                if (body.deliveryMode == "start-next" && existing.fields.storyPhase.isNullOrBlank()) {
                    commands.queueStory(existing.key)
                }
                return@synchronized ResponseEntity.ok(ProductFactoryStoryResponse(existing.key, false, body.deliveryMode))
            }
            val description = """
                ${body.description.trim()}

                Product Factory-context:
                - product: ${body.productSlug}
                - workspace run: ${body.workspaceRunId}
                - workspace commit: ${body.workspaceCommitSha}
                - artefact: ${body.artifactPath}

                ${marker(key)}
            """.trimIndent()
            val created = commands.createStory(
                CreateStoryCommand(
                    projectKey = body.projectKey?.takeIf { it.isNotBlank() },
                    title = body.title.trim(),
                    description = description,
                    repo = body.repo.trim(),
                    aiSupplier = body.aiSupplier?.takeIf { it.isNotBlank() },
                    aiModel = body.aiModel?.takeIf { it.isNotBlank() && it != "default" },
                    start = false,
                    questionsAllowed = body.questionsAllowed ?: true,
                    hotfix = false,
                    approvalMode = body.approvalMode?.takeIf { it.isNotBlank() } ?: "automatisch",
                    notificationEvents = NotificationEvent.parse(
                        body.notificationEvents?.takeIf { it.isNotEmpty() } ?: setOf("DEPLOYED", "QUESTION", "MANUAL_ACTION_REQUIRED", "ERROR"),
                    ),
                ),
            )
            if (body.deliveryMode == "start-next") {
                commands.queueStory(created.key)
            }
            ResponseEntity.status(HttpStatus.CREATED).body(ProductFactoryStoryResponse(created.key, true, body.deliveryMode))
        }
    }

    @GetMapping("/stories/{storyKey}")
    fun story(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): Any {
        authorize(authorization)
        return dashboard.storyDetail(storyKey)
    }

    @PostMapping("/stories/{storyKey}/answers")
    fun answer(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: ProductFactoryAnswerRequest,
    ): Ack {
        authorize(authorization)
        when (validateAnswer(storyKey, body)) {
            AnswerTarget.STORY -> operations.setStoryPhase(body.targetKey, body.phase, body.answer.trim())
            AnswerTarget.SUBTASK -> operations.setSubtaskPhase(body.targetKey, body.phase, body.answer.trim())
        }
        return Ack()
    }

    private fun existingStory(idempotencyKey: String): TrackerIssue? =
        dashboard.stories().issues.firstOrNull { it.description.orEmpty().contains(marker(idempotencyKey)) }

    private fun marker(key: String) = "Product-Factory-Idempotency-Key: $key"

    private fun authorize(header: String?) {
        val expected = secrets.productFactoryToken.orEmpty()
        val supplied = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
        if (expected.isBlank() || !MessageDigest.isEqual(supplied.toByteArray(StandardCharsets.UTF_8), expected.toByteArray(StandardCharsets.UTF_8))) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid integration token")
        }
    }
}

private enum class AnswerTarget { STORY, SUBTASK }

private val STORY_ANSWER_PHASES = setOf("questions-answered", "planning-questions-answered")
private val SUBTASK_ANSWER_PHASES = setOf(
    "development-questions-answered", "review-questions-answered", "test-questions-answered",
    "summary-questions-answered", "documentation-questions-answered", "manual-action-done",
)

/**
 * Ongeldige invoer is een clientfout: die moet 400 geven en niet 500, want een retry met hetzelfde
 * verzoek faalt opnieuw. De validatie staat top-level zodat de controllerklasse klein blijft.
 */
private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

private fun badRequestUnless(condition: Boolean, message: String) {
    if (!condition) badRequest(message)
}

/** Valideert het aanmaakverzoek en geeft de genormaliseerde idempotentiesleutel terug. */
private fun validateCreateStory(idempotencyKey: String?, body: ProductFactoryStoryRequest): String {
    val key = idempotencyKey?.trim().orEmpty()
    badRequestUnless(key.matches(Regex("[A-Za-z0-9._:-]{8,160}")), "Ongeldige of ontbrekende Idempotency-Key")
    badRequestUnless(body.productSlug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")), "Ongeldige productslug")
    badRequestUnless(
        body.title.isNotBlank() && body.description.isNotBlank() && body.repo.isNotBlank(),
        "Titel, omschrijving en repo zijn verplicht",
    )
    badRequestUnless(body.workspaceCommitSha.matches(Regex("[a-fA-F0-9]{7,64}")), "Ongeldige workspace commit-SHA")
    badRequestUnless(body.deliveryMode in setOf("draft", "start-next"), "deliveryMode moet draft of start-next zijn")
    return key
}

/** Valideert het antwoordverzoek en geeft terug of het een story- of subtaakovergang is. */
private fun validateAnswer(storyKey: String, body: ProductFactoryAnswerRequest): AnswerTarget {
    badRequestUnless(body.answer.isNotBlank(), "Antwoord is verplicht")
    return when (body.targetType) {
        "story" -> {
            badRequestUnless(body.targetKey == storyKey, "Story target hoort niet bij het pad")
            badRequestUnless(body.phase in STORY_ANSWER_PHASES, "Ongeldige antwoordfase voor story")
            AnswerTarget.STORY
        }
        "subtask" -> {
            badRequestUnless(body.phase in SUBTASK_ANSWER_PHASES, "Ongeldige antwoordfase voor subtask")
            AnswerTarget.SUBTASK
        }
        else -> badRequest("targetType moet story of subtask zijn")
    }
}
