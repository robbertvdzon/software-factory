package nl.vdzon.softwarefactory.web.controllers

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Machine-tot-machine tracker-API voor de Telegram-assistent (§`tools/sf-story`), via [TrackerApi]
 * (de factory's eigen Postgres-tracker). Auth: zelfde Bearer-token-patroon als `POST /api/restart`
 * ([FactoryApiController]), via `SF_FACTORY_API_TOKEN`.
 */
@RestController
@RequestMapping("/api/tracker")
class TrackerStoryApiController(
    private val trackerApi: TrackerApi,
    private val factoryEnvironmentProvider: ConfigApi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/projects")
    fun projects(request: HttpServletRequest): ResponseEntity<Any> {
        authorize(request)?.let { return it }
        val projects = trackerApi.ensureConfiguredProjects().map { mapOf("key" to it.key, "name" to it.name) }
        return ResponseEntity.ok(mapOf("projects" to projects))
    }

    @GetMapping("/stories/{key}")
    fun status(request: HttpServletRequest, @PathVariable key: String): ResponseEntity<Any> {
        authorize(request)?.let { return it }
        val issue = runCatching { trackerApi.getIssue(key) }
            .getOrElse { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "onbekende issue-key '$key'")) }
        val isStory = issue.issueType == IssueType.STORY
        val phase = if (isStory) issue.fields.storyPhase else issue.fields.subtaskPhase
        val body = mutableMapOf<String, Any?>(
            "key" to issue.key,
            "type" to if (isStory) "story" else "subtask",
            "summary" to issue.summary,
            "phase" to (phase ?: "(leeg)"),
            "repo" to issue.fields.repo,
            "error" to issue.fields.error,
        )
        if (isStory) {
            val subtasks = trackerApi.subtasksOf(issue.key).map { it.key }
            body["subtasks"] = subtasks
            body["whyNotPickedUp"] = whyNotPickedUp(phase, issue.fields.repo, issue.fields.error)
        }
        return ResponseEntity.ok(body)
    }

    @PostMapping("/stories")
    fun create(request: HttpServletRequest, @RequestBody body: CreateTrackerStoryRequest): ResponseEntity<Any> {
        authorize(request)?.let { return it }
        if (body.title.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "title is verplicht"))
        }
        val projectKey = body.project?.takeIf { it.isNotBlank() } ?: "SF"
        val issue = trackerApi.createStory(
            projectKey = projectKey,
            title = body.title,
            description = body.description?.takeIf { it.isNotBlank() },
            repo = body.repo?.takeIf { it.isNotBlank() },
            aiSupplier = body.aiSupplier?.takeIf { it.isNotBlank() } ?: "claude",
            aiModel = body.aiModel?.takeIf { it.isNotBlank() },
            start = body.start,
            silent = body.silent,
        )
        logger.info("Story {} aangemaakt via /api/tracker/stories (project={}, start={}).", issue.key, projectKey, body.start)
        return ResponseEntity.ok(
            mapOf(
                "key" to issue.key,
                "project" to projectKey,
                "repo" to issue.fields.repo,
                "aiSupplier" to issue.fields.aiSupplier,
                "started" to body.start,
            ),
        )
    }

    @PostMapping("/stories/{key}")
    fun update(request: HttpServletRequest, @PathVariable key: String, @RequestBody body: UpdateTrackerStoryRequest): ResponseEntity<Any> {
        authorize(request)?.let { return it }
        val issue = runCatching { trackerApi.getIssue(key) }
            .getOrElse { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "onbekende issue-key '$key'")) }
        val updated = mutableListOf<String>()
        body.summary?.let { trackerApi.updateIssueSummary(key, it); updated += "summary" }
        body.description?.let { trackerApi.updateIssueDescription(key, it); updated += "description" }
        body.phase?.let {
            val field = if (issue.issueType == IssueType.STORY) TrackerField.STORY_PHASE else TrackerField.SUBTASK_PHASE
            trackerApi.updateIssueFields(key, TrackerFieldUpdate.of(field to it))
            updated += "phase"
        }
        body.aiSupplier?.let { trackerApi.updateIssueFields(key, TrackerFieldUpdate.of(TrackerField.AI_SUPPLIER to it)); updated += "aiSupplier" }
        body.aiModel?.let { trackerApi.updateIssueFields(key, TrackerFieldUpdate.of(TrackerField.AI_MODEL to it)); updated += "aiModel" }
        body.comment?.let { trackerApi.postComment(key, it); updated += "comment" }
        return ResponseEntity.ok(mapOf("key" to key, "updated" to updated))
    }

    /** DESTRUCTIEF — verwijdert een story (+ subtaken) of losse subtaak onomkeerbaar uit de tracker. */
    @DeleteMapping("/stories/{key}")
    fun delete(request: HttpServletRequest, @PathVariable key: String): ResponseEntity<Any> {
        authorize(request)?.let { return it }
        val issue = runCatching { trackerApi.getIssue(key) }
            .getOrElse { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "onbekende issue-key '$key'")) }
        val subtasks = if (issue.issueType == IssueType.STORY) trackerApi.subtasksOf(key).map { it.key } else emptyList()
        subtasks.forEach { trackerApi.deleteIssue(it) }
        trackerApi.deleteIssue(key)
        return ResponseEntity.ok(mapOf("key" to key, "deleted" to true, "deletedSubtasks" to subtasks))
    }

    private fun whyNotPickedUp(phase: String?, repo: String?, error: String?): String = when {
        !error.isNullOrBlank() -> "Story staat in error: $error"
        repo.isNullOrBlank() -> "Het Repo-veld is leeg -> de factory pakt de story niet op (vul een projectnaam uit projects.yaml in)."
        phase.isNullOrBlank() -> "Story Phase is leeg -> lege fase = niet oppakken. Zet de fase op 'start'."
        phase == "start" -> "Fase staat op 'start'; wordt bij de volgende poll opgepakt."
        else -> "Fase is '$phase'; al in behandeling of wacht op een mens-actie/goedkeuring."
    }

    /** Bearer-token-check, zelfde patroon als [FactoryApiController.restart]. Null = doorgelaten. */
    private fun authorize(request: HttpServletRequest): ResponseEntity<Any>? {
        val expectedToken = factoryEnvironmentProvider.resolvedValues()["SF_FACTORY_API_TOKEN"]?.takeIf { it.isNotBlank() }
            ?: run {
                logger.warn("SF_FACTORY_API_TOKEN niet geconfigureerd; /api/tracker geweigerd.")
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            }
        val authHeader = request.getHeader("Authorization") ?: ""
        val providedToken = if (authHeader.startsWith("Bearer ")) authHeader.removePrefix("Bearer ") else ""
        if (!constantTimeEquals(providedToken, expectedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        return null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}

data class CreateTrackerStoryRequest(
    val project: String? = null,
    val title: String,
    val description: String? = null,
    val repo: String? = null,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
    val start: Boolean = false,
    val silent: Boolean = false,
)

data class UpdateTrackerStoryRequest(
    val summary: String? = null,
    val description: String? = null,
    val phase: String? = null,
    val comment: String? = null,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
)
