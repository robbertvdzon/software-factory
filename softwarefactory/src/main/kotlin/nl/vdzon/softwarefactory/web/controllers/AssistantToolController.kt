package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.telegram.AssistantToolToken
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Body voor het aanmaken van een story via de assistent. project leeg => default-project. */
data class AssistantCreateStoryRequest(
    val project: String? = null,
    val title: String,
    val description: String? = null,
    val repo: String? = null,
    val start: Boolean = false,
)

/** Body voor het aanpassen van een story/subtaak; alle velden optioneel. */
data class AssistantUpdateStoryRequest(
    val summary: String? = null,
    val description: String? = null,
    val phase: String? = null,
    val comment: String? = null,
)

/**
 * Intern endpoint dat de Telegram-assistent (via het `sf-youtrack`-script) gebruikt om story's te
 * lezen en aan te maken — zo wordt de bestaande, geteste YouTrack-logica hergebruikt i.p.v. die in
 * een shell-script te herbouwen. Afgeschermd met een gedeeld geheim ([AssistantToolToken]); alleen de
 * claude-subprocess krijgt dat geheim via de env, dus willekeurige processen op de host kunnen hier
 * niet bij.
 */
@RestController
@RequestMapping("/internal/assistant")
class AssistantToolController(
    private val issueTrackerClient: YouTrackApi,
    private val dashboardService: FactoryDashboardService,
    private val factorySecrets: FactorySecrets,
    private val token: AssistantToolToken,
) {
    @GetMapping("/projects")
    fun projects(@RequestHeader(HEADER, required = false) auth: String?): ResponseEntity<Any> {
        authorize(auth)?.let { return it }
        val projects = issueTrackerClient.ensureConfiguredProjects().map { mapOf("key" to it.key, "name" to it.name) }
        return ResponseEntity.ok(mapOf("projects" to projects))
    }

    @GetMapping("/story/{key}")
    fun story(@PathVariable key: String, @RequestHeader(HEADER, required = false) auth: String?): ResponseEntity<Any> {
        authorize(auth)?.let { return it }
        val issue = runCatching { issueTrackerClient.getIssue(key) }.getOrNull()
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Onbekende story/subtaak: $key"))
        val isStory = issue.issueType == IssueType.STORY
        val phase = if (isStory) issue.fields.storyPhase else issue.fields.subtaskPhase
        val body = buildMap<String, Any?> {
            put("key", issue.key)
            put("type", if (isStory) "story" else "subtask")
            put("summary", issue.summary)
            put("phase", phase ?: "(leeg)")
            put("repo", issue.fields.repo)
            put("error", issue.fields.error)
            if (isStory) {
                put("whyNotPickedUp", whyNotPickedUp(issue.fields.storyPhase, issue.fields.repo, issue.fields.error))
                val subtasks = runCatching { issueTrackerClient.subtasksOf(key) }.getOrDefault(emptyList())
                put("subtasks", subtasks.map { mapOf("key" to it.key, "type" to it.fields.subtaskType, "phase" to (it.fields.subtaskPhase ?: "(leeg)")) })
            }
        }
        return ResponseEntity.ok(body)
    }

    @PostMapping("/story")
    fun createStory(
        @RequestBody request: AssistantCreateStoryRequest,
        @RequestHeader(HEADER, required = false) auth: String?,
    ): ResponseEntity<Any> {
        authorize(auth)?.let { return it }
        if (request.title.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "title is verplicht"))
        }
        return runCatching {
            val projectKey = resolveProjectKey(request.project)
            val created = dashboardService.createStory(
                projectKey = projectKey,
                title = request.title,
                description = request.description,
                repo = request.repo,
                aiSupplier = null,
                aiModel = null,
                start = request.start,
            )
            ResponseEntity.ok<Any>(
                mapOf(
                    "key" to created.key,
                    "project" to projectKey,
                    "url" to "${factorySecrets.youTrackPublicUrl.trimEnd('/')}/issue/${created.key}",
                    "started" to request.start,
                ),
            )
        }.getOrElse {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to (it.message ?: "aanmaken faalde")))
        }
    }

    @PostMapping("/story/{key}/update")
    fun updateStory(
        @PathVariable key: String,
        @RequestBody request: AssistantUpdateStoryRequest,
        @RequestHeader(HEADER, required = false) auth: String?,
    ): ResponseEntity<Any> {
        authorize(auth)?.let { return it }
        val issue = runCatching { issueTrackerClient.getIssue(key) }.getOrNull()
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Onbekende story/subtaak: $key"))
        return runCatching {
            val updated = mutableListOf<String>()
            request.summary?.takeIf { it.isNotBlank() }?.let { issueTrackerClient.updateIssueSummary(key, it); updated += "summary" }
            request.description?.let { issueTrackerClient.updateIssueDescription(key, it); updated += "description" }
            when {
                request.phase != null -> {
                    if (issue.issueType == IssueType.STORY) {
                        dashboardService.setStoryPhase(key, request.phase, request.comment)
                    } else {
                        dashboardService.setSubtaskPhase(key, request.phase, request.comment)
                    }
                    updated += "phase"
                }
                request.comment != null -> { issueTrackerClient.postComment(key, request.comment); updated += "comment" }
            }
            ResponseEntity.ok<Any>(mapOf("key" to key, "updated" to updated))
        }.getOrElse {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to (it.message ?: "aanpassen faalde")))
        }
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/story/{key}")
    fun deleteStory(@PathVariable key: String, @RequestHeader(HEADER, required = false) auth: String?): ResponseEntity<Any> {
        authorize(auth)?.let { return it }
        val issue = runCatching { issueTrackerClient.getIssue(key) }.getOrNull()
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Onbekende story/subtaak: $key"))
        return runCatching {
            // Een story wordt volledig opgeruimd (incl. subtaken/branch/workfolder); een losse subtaak
            // verwijderen we als YouTrack-issue.
            if (issue.issueType == IssueType.STORY) {
                dashboardService.purgeStory(key)
            } else {
                issueTrackerClient.deleteIssue(key)
            }
            ResponseEntity.ok<Any>(mapOf("key" to key, "deleted" to true))
        }.getOrElse {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to (it.message ?: "verwijderen faalde")))
        }
    }

    /** Default-project = "Software Factory" (key SF), tenzij expliciet een ander project is gevraagd. */
    private fun resolveProjectKey(requested: String?): String {
        requested?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val projects = issueTrackerClient.ensureConfiguredProjects()
        return projects.firstOrNull { it.name.contains("software factory", ignoreCase = true) || it.key.equals("SF", ignoreCase = true) }?.key
            ?: projects.firstOrNull()?.key
            ?: error("Geen YouTrack-project gevonden om in aan te maken.")
    }

    private fun whyNotPickedUp(storyPhase: String?, repo: String?, error: String?): String = when {
        !error.isNullOrBlank() -> "Story staat in error: $error"
        repo.isNullOrBlank() -> "Het `Repo`-veld is leeg → de factory pakt de story niet op. Vul een projectnaam uit projects.yaml in."
        StoryPhase.fromTracker(storyPhase) == null -> "Story Phase is leeg → een lege fase betekent 'niet oppakken'. Zet de fase op 'start' om te beginnen."
        StoryPhase.fromTracker(storyPhase) == StoryPhase.START -> "Fase staat op 'start'; de story wordt bij de eerstvolgende poll opgepakt. Blijft dit hangen, kijk dan of er een agent draait of een fout is."
        else -> "Fase is '$storyPhase'; de story is al in behandeling of wacht op een mens-actie/goedkeuring."
    }

    /** @return een 401-response als het geheim niet klopt, anders null (toegestaan). */
    private fun authorize(auth: String?): ResponseEntity<Any>? =
        if (auth == token.value) {
            null
        } else {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "unauthorized"))
        }

    private companion object {
        private const val HEADER = "X-SF-Assistant-Token"
    }
}
