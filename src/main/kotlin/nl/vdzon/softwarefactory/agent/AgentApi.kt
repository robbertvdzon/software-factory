package nl.vdzon.softwarefactory.agent

/**
 * Public API marker of the agent module.
 *
 * The agent module contains the deployable command-line runtime that runs
 * inside agent Docker containers. It prepares target repositories, enriches
 * task context, invokes the selected AI supplier and reports completion back to
 * the factory server.
 */
interface AgentApi

