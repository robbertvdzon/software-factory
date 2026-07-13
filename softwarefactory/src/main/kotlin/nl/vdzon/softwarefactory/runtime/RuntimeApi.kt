package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.types.*

import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.types.CompletionOutcome
import nl.vdzon.softwarefactory.runtime.types.CompletionStep

/**
 * Public API of the runtime module.
 *
 * The runtime module owns execution state around agent containers: workspaces,
 * logs, run events and completion handling. Web adapters call this API when an
 * agent run has finished; the runtime also uses it after reading agent result
 * files from completed container workspaces.
 */
interface RuntimeApi {
    fun complete(request: AgentRunCompleteRequest): CompletionOutcome

    fun requeueCompletion(completionId: Long, step: CompletionStep, requestedBy: String, reason: String): Boolean = false
}
