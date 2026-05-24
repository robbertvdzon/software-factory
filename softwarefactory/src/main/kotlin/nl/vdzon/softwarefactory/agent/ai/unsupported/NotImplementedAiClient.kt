package nl.vdzon.softwarefactory.agent.ai.unsupported

import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AiClient

class NotImplementedAiClient(
    override val supplier: String,
) : AiClient {
    override fun run(context: AgentContext): AgentOutcome =
        AgentOutcome(
            phase = null,
            comment = "AI supplier '$supplier' is nog niet geimplementeerd.",
            outcome = "error-supplier-not-implemented",
            exitCode = 1,
        )
}
