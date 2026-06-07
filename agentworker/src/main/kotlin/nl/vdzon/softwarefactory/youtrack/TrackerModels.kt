package nl.vdzon.softwarefactory.youtrack

import java.time.OffsetDateTime

enum class AgentRole(val commentPrefix: String) {
    REFINER("[REFINER]"),
    PLANNER("[PLANNER]"),
    DEVELOPER("[DEVELOPER]"),
    REVIEWER("[REVIEWER]"),
    TESTER("[TESTER]"),
    SUMMARIZER("[SUMMARIZER]"),
    COST_MONITOR("[COST-MONITOR]"),
    ORCHESTRATOR("[ORCHESTRATOR]");

    val markerKeyPart: String = name.lowercase().replace("_", "-")
}

enum class TrackerField(val displayName: String) {
    AI_SUPPLIER("AI-supplier"),
    AI_PHASE("AI Phase"),
    AI_LEVEL("AI Level"),
    AI_TOKEN_BUDGET("AI Token Budget"),
    AI_TOKENS_USED("AI Tokens Used"),
    AGENT_STARTED_AT("AgentStartedAt"),
    PAUSED("Paused"),
    ERROR("Error"),
}

enum class FactoryCommand(val token: String) {
    PAUSE("pause"),
    RESUME("resume"),
    KILL("kill"),
    RE_IMPLEMENT("re-implement"),
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
    val id: String = key,
    val projectKey: String = key.substringBefore('-', missingDelimiterValue = key),
)

data class TrackerIssueFields(
    val targetRepo: String?,
    val aiSupplier: String? = null,
    val aiPhase: String?,
    val aiLevel: Int?,
    val aiTokenBudget: Long?,
    val aiTokensUsed: Long?,
    val agentStartedAt: OffsetDateTime?,
    val paused: Boolean,
    val error: String?,
)

data class TrackerComment(
    val id: String,
    val authorAccountId: String?,
    val authorDisplayName: String?,
    val body: String,
    val created: OffsetDateTime?,
) {
    val isAgentComment: Boolean = AgentRole.entries.any { body.trimStart().startsWith(it.commentPrefix) }
}

data class TrackerFieldUpdate(
    val values: Map<TrackerField, Any?>,
) {
    companion object {
        fun of(vararg values: Pair<TrackerField, Any?>): TrackerFieldUpdate =
            TrackerFieldUpdate(values.toMap())
    }
}
