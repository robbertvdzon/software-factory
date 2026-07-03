package nl.vdzon.softwarefactory.agentworker

/**
 * Public API marker of the agent worker module.
 *
 * The agent worker module owns the standalone container process that executes
 * one assigned agent run: it prepares the target repository, builds the prompt,
 * invokes the AI client, publishes developer PRs and writes the run result to
 * the mounted workspace. It must not call factory server internals directly.
 *
 * Het resultaat-bestand (`/work/agent-result.json`) wordt geschreven in het
 * gedeelde wire-formaat [nl.vdzon.softwarefactory.contract.AgentResultFile]
 * uit factory-common; de factory-server leest datzelfde type.
 */
interface AgentWorkerApi
