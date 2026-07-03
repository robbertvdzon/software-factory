package nl.vdzon.softwarefactory.contract

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * HET wire-contract van `/work/agent-result.json` — het koppelvlak tussen de
 * agentworker-container en de factory-server.
 *
 * - **Schrijver**: de agentworker-CLI (`nl.vdzon.softwarefactory.agentworker.cli.AgentCli`)
 *   serialiseert dit type aan het einde van een agent-run naar het resultaat-bestand
 *   in de gemounte workspace (pad via `SF_AGENT_RESULT_FILE`, default `/work/agent-result.json`).
 * - **Lezer**: de factory-server
 *   (`nl.vdzon.softwarefactory.runtime.services.AgentResultFileCompletionPoller`)
 *   deserialiseert dit type zodra de container gestopt is en mapt het daarna naar
 *   z'n interne `AgentRunCompleteRequest`.
 *
 * Veldnamen zijn het JSON-formaat en mogen dus NIET hernoemd worden zonder
 * migratiepad: een lopende agent-container kan een oude versie draaien terwijl de
 * factory al nieuw is (en andersom). Nieuwe velden mogen alleen met default worden
 * toegevoegd; onbekende velden worden bij het lezen genegeerd
 * (`@JsonIgnoreProperties(ignoreUnknown = true)`), zodat een nieuwere schrijver een
 * oudere lezer niet breekt. De contract-test
 * (`AgentResultFileContractTest`) pint het bestaande formaat vast.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentResultFile(
    val storyKey: String,
    val role: String,
    val containerName: String,
    /** Doel-fase van de story na deze run; `null` als de agent geen fase-overgang rapporteert. */
    val phase: String? = null,
    val outcome: String,
    /** Samenvatting/commentaar van de agent. De agentworker schrijft dit altijd; lezers tolereren `null`. */
    val summaryText: String? = null,
    val exitCode: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    val numTurns: Int = 0,
    val durationMs: Int = 0,
    val costUsdEst: Double = 0.0,
    val events: List<AgentResultEvent> = emptyList(),
    val knowledgeUpdates: List<AgentResultKnowledgeUpdate> = emptyList(),
    /** Door de planner gedeclareerde subtaken (fase 3); `type` = trackerValue. */
    val subtasks: List<AgentResultSubtask> = emptyList(),
)

/** Eén run-event in [AgentResultFile.events]; `payload` is meestal een JSON-string. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentResultEvent(
    val kind: String,
    val payload: String,
)

/** Kennis-tip die de agent wil opslaan ([AgentResultFile.knowledgeUpdates]); de factory upsert deze. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentResultKnowledgeUpdate(
    val category: String,
    val key: String,
    val content: String,
)

/** Door de planner gedeclareerde subtask-spec; `type` als trackerValue ("development"/"review"/"test"/...). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentResultSubtask(
    val type: String,
    val title: String,
    val description: String? = null,
    val model: String? = null,
    val effort: String? = null,
)
