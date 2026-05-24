package nl.vdzon.softwarefactory.agentworker

/**
 * Public API marker of the agent worker module.
 *
 * The agent worker module owns the standalone container process that executes
 * one assigned agent run: it prepares the target repository, builds the prompt,
 * invokes the AI client, publishes developer PRs and reports completion back to
 * the factory server.
 */
interface AgentWorkerApi
