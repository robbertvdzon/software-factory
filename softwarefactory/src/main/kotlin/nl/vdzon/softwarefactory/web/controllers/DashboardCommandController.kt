package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.NotificationEvent
import nl.vdzon.softwarefactory.dashboard.DashboardCommands
import nl.vdzon.softwarefactory.dashboard.models.AgentExecutionConfigSaveInput
import nl.vdzon.softwarefactory.dashboard.models.AuditProjectSettingsSaveInput
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import nl.vdzon.softwarefactory.web.services.DashboardAuthInterceptor
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Schrijfendpoints van het dashboard: story-acties, audits, instellingen en onderhoud. Uitsluitend
 * vertalen en delegeren naar [DashboardCommands] en [FactoryOperations] — geen businesslogica.
 */
@RestController
@RequestMapping("/api/v1")
class DashboardCommandController(
    private val commands: DashboardCommands,
    private val operations: FactoryOperations,
) {
    @PostMapping("/stories")
    fun createStory(@RequestBody body: CreateStoryRequest) =
        commands.createStory(
            CreateStoryCommand(
                // SF-818 — projectKey is optioneel: het dialoog stuurt 'm niet meer mee en de service
                // valt terug op het enige geconfigureerde project.
                projectKey = body.projectKey,
                title = body.title,
                description = body.description,
                repo = body.repo,
                aiSupplier = body.aiSupplier,
                aiModel = body.aiModel,
                start = body.start,
                questionsAllowed = body.questionsAllowed,
                hotfix = body.hotfix,
                approvalMode = body.approvalMode,
                notificationEvents = NotificationEvent.parse(body.notificationEvents),
            ),
        )

    @PostMapping("/stories/{storyKey}/story-phase")
    fun setStoryPhase(@PathVariable storyKey: String, @RequestBody body: PhaseRequest): Ack {
        operations.setStoryPhase(storyKey, body.phase, body.comment)
        return Ack()
    }

    @PostMapping("/subtasks/{subtaskKey}/phase")
    fun setSubtaskPhase(@PathVariable subtaskKey: String, @RequestBody body: PhaseRequest): Ack {
        operations.setSubtaskPhase(subtaskKey, body.phase, body.comment)
        return Ack()
    }

    @PostMapping("/stories/{storyKey}/questions-allowed")
    fun setQuestionsAllowed(@PathVariable storyKey: String, @RequestBody body: QuestionsAllowedRequest): Ack {
        commands.setQuestionsAllowedFlag(storyKey, body.enabled)
        return Ack()
    }

    @PostMapping("/stories/{storyKey}/approval-mode")
    fun setApprovalMode(@PathVariable storyKey: String, @RequestBody body: ModeRequest): Ack {
        commands.setApprovalMode(storyKey, body.mode)
        return Ack()
    }

    @PostMapping("/stories/{storyKey}/notification-events")
    fun setNotificationEvents(@PathVariable storyKey: String, @RequestBody body: NotificationEventsRequest): Ack {
        commands.setNotificationEvents(storyKey, body.notificationEvents)
        return Ack()
    }

    /** Partial update: alleen de meegegeven (niet-null) velden worden gewijzigd. */
    @PostMapping("/stories/{storyKey}/edit")
    fun editStory(@PathVariable storyKey: String, @RequestBody body: EditStoryRequest): Ack {
        commands.editStory(
            storyKey,
            description = body.description,
            descriptionSummary = body.descriptionSummary,
            aiSupplier = body.aiSupplier,
            aiModel = body.aiModel,
        )
        return Ack()
    }

    /** `command`: pause/resume/kill/re-implement/clear-error/retry-current-step/delete/merge/approve/reject. */
    @PostMapping("/stories/{storyKey}/command/{command}")
    fun command(
        @PathVariable storyKey: String,
        @PathVariable command: String,
        @RequestBody(required = false) body: CommandRequest?,
    ): Ack {
        val factoryCommand = FactoryCommand.entries.firstOrNull { it.token == command }
            ?: throw IllegalArgumentException("Onbekend command: $command")
        operations.queueCommand(storyKey, factoryCommand, body?.reason)
        return Ack()
    }

    /** DESTRUCTIEF — de frontend vraagt bevestiging vóór deze aanroep. */
    @PostMapping("/stories/{storyKey}/purge")
    fun purgeStory(@PathVariable storyKey: String): Ack {
        commands.purgeStory(storyKey)
        return Ack()
    }

    @PostMapping("/stories/{storyKey}/start-refining")
    fun startRefining(@PathVariable storyKey: String): Ack {
        commands.startRefining(storyKey)
        return Ack()
    }

    /** "Queue story": wacht op de per-repo-wachtrij i.p.v. meteen te starten (zie `start-refining` voor de override). */
    @PostMapping("/stories/{storyKey}/queue")
    fun queueStory(@PathVariable storyKey: String): Ack {
        commands.queueStory(storyKey)
        return Ack()
    }

    @PostMapping("/stories/{storyKey}/start-developing")
    fun startDeveloping(@PathVariable storyKey: String): Ack {
        commands.startDeveloping(storyKey)
        return Ack()
    }

    /**
     * "Nu draaien" op het Opruimen-scherm. Een geweigerde ronde (draait al, uitgezet, onbekende
     * soort) komt als HTTP 200 met een statusveld terug, niet als foutcode.
     */
    @PostMapping("/maintenance/run")
    fun maintenanceRunNow(@RequestBody body: MaintenanceRunNowRequest): CleanupRunNowBody {
        val result = commands.runCleanupNow(body.kind)
        return CleanupRunNowBody(result.accepted, result.status, result.kinds)
    }

    @PostMapping("/audits/run-now")
    fun auditRunNow(@RequestBody body: AuditRunNowRequest): AuditRunNowBody {
        val result = commands.runAuditNow(body.project, body.auditType)
        return AuditRunNowBody(result.accepted, result.status)
    }

    @PostMapping("/audits/questions/answer")
    fun answerAuditQuestion(@RequestBody body: AuditAnswerRequest) =
        AuditAnswerBody(commands.answerAuditQuestion(body.questionId, body.answer))

    @PostMapping("/audits/settings")
    fun saveAuditSettings(@RequestBody body: AuditSettingsSaveRequest): Ack {
        commands.saveAuditSettings(
            body.enabled,
            body.projects.map { AuditProjectSettingsSaveInput(it.project, it.startTime, it.auditCount) },
        )
        return Ack()
    }

    @PostMapping("/settings/agent-execution")
    fun saveAgentExecutionConfig(
        @RequestAttribute(DashboardAuthInterceptor.USER_ATTRIBUTE) user: String,
        @RequestBody body: AgentExecutionConfigSaveRequest,
    ): Ack {
        commands.saveAgentExecutionConfig(
            AgentExecutionConfigSaveInput(
                role = body.role,
                projectKey = body.projectKey?.takeIf(String::isNotBlank),
                vendorId = body.vendorId,
                model = body.model,
                mode = body.mode,
                updatedBy = user,
            ),
        )
        return Ack()
    }

    /** Valideert en bewaart de projectcatalogus; een ongeldig document geeft 400 met de reden. */
    @PostMapping("/settings/project-catalog")
    fun saveProjectCatalog(
        @RequestAttribute(DashboardAuthInterceptor.USER_ATTRIBUTE) user: String,
        @RequestBody body: ProjectCatalogSaveRequest,
    ): Ack {
        commands.saveProjectCatalog(body.yaml, user)
        return Ack()
    }

    @PostMapping("/audit-memory/update")
    fun auditMemoryUpdate(@RequestBody body: AuditMemoryNoteRequest): Ack {
        commands.updateAuditMemoryNote(body.project, body.auditType, body.key, body.content)
        return Ack()
    }

    @PostMapping("/audit-memory/delete")
    fun auditMemoryDelete(@RequestBody body: AuditMemoryNoteKeyRequest): Ack {
        commands.deleteAuditMemoryNote(body.project, body.auditType, body.key)
        return Ack()
    }

    @PostMapping("/projects/{name}/force-deploy")
    fun forceDeploy(@PathVariable name: String): Ack {
        commands.forceProjectDeploy(name)
        return Ack()
    }
}

/**
 * [started] betekent "verzoek geaccepteerd" (gestart óf in de wachtrij) en blijft zo staan voor
 * oudere frontends; [status] onderscheidt "gestart", "in de wachtrij" en de weigeringsredenen.
 */
data class AuditRunNowBody(val started: Boolean, val status: String)

/** [started] = er draait nu een ronde, [status] de samenvattende reden, [kinds] per soort wat er gebeurde. */
data class CleanupRunNowBody(val started: Boolean, val status: String, val kinds: Map<String, String>)

/** `false` als de vraag niet meer openstond — dubbele submit, of al via Telegram beantwoord. */
data class AuditAnswerBody(val answered: Boolean)
