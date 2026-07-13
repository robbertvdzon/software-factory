package nl.vdzon.softwarefactory.core

import java.time.OffsetDateTime

// AgentRole is verhuisd naar factory-common (core/AgentRole.kt): de agentworker heeft 'm ook
// nodig en had er een eigen, gedivergeerde kopie van. Zelfde package, dus imports bleven gelijk.
// TrackerField is om dezelfde reden verhuisd naar factory-common (core/TrackerField.kt):
// dashboard-backend leest dezelfde tracker-velden en mag de namen niet zelf dupliceren.

/**
 * SF-335 — categorie van een gezette [TrackerField.ERROR]. Onderscheidt een inhoudelijke
 * **clarification**-fout (uit een `*_WITH_QUESTIONS`-uitkomst bij een silent story; niet retrybaar,
 * vraagt om een mens/aangepaste story) van een **technische** fout (flaky test/deploy/netwerk; wél
 * retrybaar). De markering is een leesbare prefix op de error-tekst; verdere afhandeling
 * (retry/digest/monitor) valt buiten deze story.
 */
enum class ErrorCategory(val marker: String) {
    CLARIFICATION("[CLARIFICATION]"),
    TECHNICAL("[TECHNICAL]");

    companion object {
        /** Leidt de categorie af uit opgeslagen error-tekst: bevat 'ie de clarification-marker dan CLARIFICATION. */
        fun of(errorText: String?): ErrorCategory =
            if (errorText != null && errorText.contains(CLARIFICATION.marker)) CLARIFICATION else TECHNICAL

        /**
         * Bouwt de error-tekst voor een `*_WITH_QUESTIONS`-uitkomst bij een silent story: de
         * clarification-marker gevolgd door de vragen van de agent (best-effort; valt terug op een
         * generieke tekst als er geen vraagtekst beschikbaar is).
         */
        fun clarificationText(questions: String?): String =
            "${CLARIFICATION.marker} Silent: de agent stelde vragen die niet automatisch beantwoord kunnen " +
                "worden. Beantwoord de story handmatig of pas 'm aan.\n\n" +
                (questions?.takeIf { it.isNotBlank() } ?: "(geen vraagtekst beschikbaar)")
    }
}

/**
 * Story vs subtask. Afgeleid uit het standaard tracker `Type`-veld:
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
    // Vaste, niet-AI poort vlak vóór de merge: wacht op een handmatige goedkeuring (SF-192).
    MANUAL_APPROVE("manual-approve"),
    SUMMARY("summary"),
    // Vaste, factory-afgedwongen documentatie-stap (SF-213): ná summary, vóór de manual-approve-poort.
    DOCUMENTATION("documentation"),
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
    // Handmatige goedkeur-poort (SF-192): approve laat de keten door, reject reset 'm met een reden.
    APPROVE("approve"),
    REJECT("reject"),
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

open class TrackerApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TrackerIssueNotFoundException(val issueKey: String) :
    TrackerApiException("Issue tracker: onbekende issue-key '$issueKey'.")

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
    /**
     * Key van de parent-story als dit een subtaak is (uit de inward "Subtask"-link), anders null.
     * Wordt al bij het inlezen van het issue meegehaald, zodat callers (bv. `myActions()`)
     * geen aparte call per subtaak nodig hebben om de owner-story te bepalen.
     */
    val parentKey: String? = null,
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
    // SF-335 — enum-boolean (analoog aan [paused]); default false = bestaand gedrag.
    val silent: Boolean = false,
    val error: String?,
    val type: String? = null,
    val subtaskType: String? = null,
    val aiModel: String? = null,
    val aiReasoningEffort: String? = null,
    val storyPhase: String? = null,
    val subtaskPhase: String? = null,
    // SF-818 — aanmaakmoment / laatste wijziging van het issue (tracker-DB `created_at`/`updated_at`).
    // Puur informatief voor de UI (tijdstempel per story-regel): geen [TrackerField], dus niet in
    // [applying]. Default null zodat backends die deze metadata niet leveren blijven
    // compileren.
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
) {
    fun developerLoopbackLimit(default: Int): Int =
        (aiMaxDeveloperLoopbacks ?: default).coerceAtLeast(0)

    /** STORY tenzij het `Type`-veld `Task` is. */
    val issueType: IssueType
        get() = if (type?.trim().equals("Task", ignoreCase = true)) IssueType.SUBTASK else IssueType.STORY

    /**
     * Past één veld-update toe op deze fields — de lokale spiegel van wat een
     * [TrackerFieldUpdate] naar de tracker schrijft. Dit is bewust de enige plek met deze
     * mapping: een nieuw [TrackerField] dwingt hier een compilerfout af i.p.v. dat callers
     * elk hun eigen (verouderende) when-blok bijhouden.
     */
    fun applying(field: TrackerField, value: Any?): TrackerIssueFields = when (field) {
        TrackerField.AI_PHASE -> copy(aiPhase = value as String?)
        TrackerField.AI_LEVEL -> copy(aiLevel = value as Int?)
        TrackerField.AI_MAX_DEVELOPER_LOOPBACKS -> copy(aiMaxDeveloperLoopbacks = value as Int?)
        TrackerField.AI_TOKEN_BUDGET -> copy(aiTokenBudget = value as Long?)
        TrackerField.AI_TOKENS_USED -> copy(aiTokensUsed = value as Long?)
        TrackerField.AGENT_STARTED_AT -> copy(agentStartedAt = value as OffsetDateTime?)
        TrackerField.PAUSED -> copy(paused = value as Boolean)
        // Enum-boolean in de tracker: accepteert zowel de string-representatie als een Boolean.
        TrackerField.SILENT -> copy(silent = (value as? String)?.equals("true", ignoreCase = true) ?: (value as? Boolean ?: false))
        TrackerField.ERROR -> copy(error = value as String?)
        TrackerField.AI_SUPPLIER -> copy(aiSupplier = value as String?)
        TrackerField.AUTO_APPROVE -> copy(autoApprove = (value as? String)?.equals("on", ignoreCase = true) ?: false)
        TrackerField.AI_MODEL -> copy(aiModel = value as String?)
        TrackerField.AI_REASONING_EFFORT -> copy(aiReasoningEffort = value as String?)
        TrackerField.STORY_PHASE -> copy(storyPhase = value as String?)
        TrackerField.SUBTASK_PHASE -> copy(subtaskPhase = value as String?)
        TrackerField.SUBTASK_TYPE -> copy(subtaskType = value as String?)
        TrackerField.REPO -> copy(repo = value as String?)
    }
}

/**
 * Declaratie van een aan te maken subtask (door de planner gedeclareerd, door de
 * orchestrator gematerialiseerd via [TrackerCapabilities.createSubtask]).
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
