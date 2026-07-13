package nl.vdzon.softwarefactory.runtime.types

import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.types.*

sealed interface CompletionOutcome {
    data class Completed(val agentRunId: Long, val storyRunId: Long) : CompletionOutcome
    data object NoActiveRun : CompletionOutcome
}
