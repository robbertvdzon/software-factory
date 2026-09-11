package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.types.*

import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.types.CompletionOutcome
import nl.vdzon.softwarefactory.runtime.types.CompletionStep

/**
 * Public API of the runtime module.
 *
 * The runtime module owns durable correlation, run events and completion handling around Agent
 * Runtime jobs. Web adapters and the Runtime reconciler call this API with validated completions.
 */
interface RuntimeApi {
    fun complete(request: AgentRunCompleteRequest): CompletionOutcome

    fun requeueCompletion(completionId: Long, step: CompletionStep, requestedBy: String, reason: String): Boolean = false
}
