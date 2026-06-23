package nl.vdzon.softwarefactory.core

import java.time.OffsetDateTime

enum class AgentRole(val commentPrefix: String) {
    REFINER("[REFINER]"),
    PLANNER("[PLANNER]"),
    DEVELOPER("[DEVELOPER]"),
    REVIEWER("[REVIEWER]"),
    TESTER("[TESTER]"),
    SUMMARIZER("[SUMMARIZER]"),
    ASSISTANT("[ASSISTANT]"),
    COST_MONITOR("[COST-MONITOR]"),
    ORCHESTRATOR("[ORCHESTRATOR]");

    val markerKeyPart: String = name.lowercase().replace("_", "-")
}

enum class TrackerField(val displayName: String) {
    REPO("Repo"),
    AI_SUPPLIER("AI-supplier"),
    AUTO_APPROVE("Auto-approve"),
    AI_PHASE("AI Phase"),
    AI_LEVEL("AI Level"),
    AI_MODEL("AI Model"),
    AI_REASONING_EFFORT("AI Reasoning Effort"),
    STORY_PHASE("Story Phase"),
    SUBTASK_PHASE("Subtask Phase"),
    SUBTASK_TYPE("Subtask Type"),
    AI_MAX_DEVELOPER_LOOPBACKS("AI Max Developer Loopbacks"),
    AI_TOKEN_BUDGET("AI Token Budget"),
    AI_TOKENS_USED("AI Tokens Used"),
    AGENT_STARTED_AT("AgentStartedAt"),
    PAUSED("Paused"),
    ERROR("Error"),
}

/**
 * Story vs subtask. Afgeleid uit het standaard YouTrack `Type`-veld:
 * `Task` -> SUBTASK, al het andere (m.n. `User Story`) -> STORY.
 * Het `Subtask Type`-veld bepaalt de rol van een subtask, niet dit onderscheid.
 */
enum class IssueType { STORY, SUBTASK }

/** Rol/pipeline van een subtask (waarde van het `Subtask Type`-veld). */
enum class SubtaskType(val trackerValue: String) {
    DEVELOPMENT("development"),
    REVIEW("review"),
    TEST("test"),
    MANUAL("manual"),
    SUMMARY("summary"),
    MERGE("merge"),
    DEPLOY("deploy");

    companion object {
        fun fromTracker(value: String?): SubtaskType? =
            value?.trim()?.lowercase()?.let { v -> entries.firstOrNull { it.trackerValue == v } }
    }
}

enum class FactoryCommand(val token: String) {
    PAUSE("pause"),
    RESUME("resume"),
    KILL("kill"),
    RE_IMPLEMENT("re-implement"),
    CLEAR_ERROR("clear-error"),
    RETRY_CURRENT_STEP("retry-current-step"),
    DELETE("delete"),
    MERGE("merge"),
}

sealed interface TrackerCommentInstruction {
    val sourceText: String
}

data class TrackerCommandInstruction(
    val command: FactoryCommand,
    override val sourceText: String,
) : TrackerCommentInstruction

sealed interface TrackerTriggerInstruction : TrackerCommentInstruction

data class AiLevelTrigger(
    val level: Int,
    override val sourceText: String,
) : TrackerTriggerInstruction

data class AiSupplierTrigger(
    val supplier: String,
    override val sourceText: String,
) : TrackerTriggerInstruction

data class AutoApproveTrigger(
    val enabled: Boolean,
    override val sourceText: String,
) : TrackerTriggerInstruction

data class BudgetTrigger(
    val budget: Long,
    override val sourceText: String,
) : TrackerTriggerInstruction

data class ContinueTrigger(
    override val sourceText: String,
) : TrackerTriggerInstruction

data class TrackerFieldMetadata(
    val id: String,
    val name: String,
    val schemaType: String?,
)

data class TrackerFieldMapping(
    private val fields: Map<TrackerField, TrackerFieldMetadata>,
) {
    fun id(field: TrackerField): String =
        requireNotNull(fields[field]) { "issue tracker field '${field.displayName}' is not mapped." }.id

    fun schemaType(field: TrackerField): String? =
        fields[field]?.schemaType

    fun searchableFieldIds(): List<String> =
        fields.values.map { it.id }

    companion object {
        fun fromDefinitions(definitions: List<TrackerFieldMetadata>): TrackerFieldMapping {
            val byName = definitions.associateBy { it.name }
            val mapped = TrackerField.entries.associateWith { field ->
                byName[field.displayName]
                    ?: throw MissingTrackerFieldException("Missing required issue tracker custom field: ${field.displayName}")
            }
            return TrackerFieldMapping(mapped)
        }
    }
}

class MissingTrackerFieldException(message: String) : RuntimeException(message)

class YouTrackApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class TrackerIssue(
    val key: String,
    val summary: String,
    val description: String? = null,
    val status: String,
    val fields: TrackerIssueFields,
    val comments: List<TrackerComment>,
    val tags: List<String> = emptyList(),
    val id: String = key,
    val projectKey: String = key.substringBefore('-', missingDelimiterValue = key),
) {
    val issueType: IssueType get() = fields.issueType
}

data class TrackerIssueFields(
    val targetRepo: String?,
    /**
     * Waarde van het vrije tekstveld `Repo`: een projectnaam uit projects.yaml (→ bijbehorende repo)
     * óf rechtstreeks een repo-URL. Zie ProjectRepoResolver.resolve.
     */
    val repo: String? = null,
    val aiSupplier: String? = null,
    val autoApprove: Boolean = false,
    val aiPhase: String?,
    val aiLevel: Int?,
    val aiMaxDeveloperLoopbacks: Int? = null,
    val aiTokenBudget: Long?,
    val aiTokensUsed: Long?,
    val agentStartedAt: OffsetDateTime?,
    val paused: Boolean,
    val error: String?,
    val type: String? = null,
    val subtaskType: String? = null,
    val aiModel: String? = null,
    val aiReasoningEffort: String? = null,
    val storyPhase: String? = null,
    val subtaskPhase: String? = null,
) {
    fun developerLoopbackLimit(default: Int): Int =
        (aiMaxDeveloperLoopbacks ?: default).coerceAtLeast(0)

    /** STORY tenzij het `Type`-veld `Task` is. */
    val issueType: IssueType
        get() = if (type?.trim().equals("Task", ignoreCase = true)) IssueType.SUBTASK else IssueType.STORY
}

/**
 * Declaratie van een aan te maken subtask (door de planner gedeclareerd, door de
 * orchestrator gematerialiseerd via [YouTrackApi.createSubtask]).
 */
data class SubtaskSpec(
    val type: SubtaskType,
    val title: String,
    val description: String? = null,
    val model: String? = null,
    val effort: String? = null,
)

data class TrackerComment(
    val id: String,
    val authorAccountId: String?,
    val authorDisplayName: String?,
    val body: String,
    val created: OffsetDateTime?,
) {
    val isAgentComment: Boolean = TrackerCommentParser.isAgentComment(body)
}

data class TrackerFieldUpdate(
    val values: Map<TrackerField, Any?>,
) {
    companion object {
        fun of(vararg values: Pair<TrackerField, Any?>): TrackerFieldUpdate =
            TrackerFieldUpdate(values.toMap())
    }
}
