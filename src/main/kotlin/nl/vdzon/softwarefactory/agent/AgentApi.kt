package nl.vdzon.softwarefactory.agent

/**
 * Public API marker of the agent module.
 *
 * The agent module owns the AI-client contract and supplier selection used by
 * the standalone agent worker. Concrete AI supplier implementations live in
 * subpackages and are exposed only through the root-level contract.
 */
interface AgentApi
