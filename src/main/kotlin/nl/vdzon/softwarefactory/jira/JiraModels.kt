package nl.vdzon.softwarefactory.jira

import java.time.OffsetDateTime

enum class AgentRole(val commentPrefix: String) {
    REFINER("[REFINER]"),
    DEVELOPER("[DEVELOPER]"),
    REVIEWER("[REVIEWER]"),
    TESTER("[TESTER]"),
    COST_MONITOR("[COST-MONITOR]"),
    ORCHESTRATOR("[ORCHESTRATOR]");

    val markerKeyPart: String = name.lowercase().replace("_", "-")
}

enum class JiraKnownField(val displayName: String) {
    TARGET_REPO("Target Repo"),
    AI_PHASE("AI Phase"),
    AI_LEVEL("AI Level"),
    AI_TOKEN_BUDGET("AI Token Budget"),
    AI_TOKENS_USED("AI Tokens Used"),
    AGENT_STARTED_AT("AgentStartedAt"),
    PAUSED("Paused"),
    ERROR("Error"),
}

data class JiraFieldMetadata(
    val id: String,
    val name: String,
    val schemaType: String?,
)

data class JiraFieldMapping(
    private val fields: Map<JiraKnownField, JiraFieldMetadata>,
) {
    fun id(field: JiraKnownField): String =
        requireNotNull(fields[field]) { "Jira field '${field.displayName}' is not mapped." }.id

    fun schemaType(field: JiraKnownField): String? =
        fields[field]?.schemaType

    fun searchableFieldIds(): List<String> =
        fields.values.map { it.id }

    companion object {
        fun fromDefinitions(definitions: List<JiraFieldMetadata>): JiraFieldMapping {
            val byName = definitions.associateBy { it.name }
            val mapped = JiraKnownField.entries.associateWith { field ->
                byName[field.displayName]
                    ?: throw MissingJiraFieldException("Missing required Jira custom field: ${field.displayName}")
            }
            return JiraFieldMapping(mapped)
        }
    }
}

class MissingJiraFieldException(message: String) : RuntimeException(message)

class JiraClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class JiraIssue(
    val key: String,
    val summary: String,
    val status: String,
    val fields: JiraIssueFields,
    val comments: List<JiraComment>,
)

data class JiraIssueFields(
    val targetRepo: String?,
    val aiPhase: String?,
    val aiLevel: Int?,
    val aiTokenBudget: Long?,
    val aiTokensUsed: Long?,
    val agentStartedAt: OffsetDateTime?,
    val paused: Boolean,
    val error: String?,
)

data class JiraComment(
    val id: String,
    val authorAccountId: String?,
    val authorDisplayName: String?,
    val body: String,
    val created: OffsetDateTime?,
) {
    val isAgentComment: Boolean = JiraCommentParser.isAgentComment(body)
}

data class JiraFieldUpdate(
    val values: Map<JiraKnownField, Any?>,
) {
    companion object {
        fun of(vararg values: Pair<JiraKnownField, Any?>): JiraFieldUpdate =
            JiraFieldUpdate(values.toMap())
    }
}
