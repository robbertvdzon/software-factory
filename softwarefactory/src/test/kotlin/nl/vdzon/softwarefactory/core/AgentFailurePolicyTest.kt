package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.contracts.*
import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.core.contracts.*

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentFailurePolicyTest {

    @Test
    fun `infra-fouten in de summary zijn retryable`() {
        assertTrue(AgentFailurePolicy.isRetryable("error", "API gaf HTTP 429 terug"))
        assertTrue(AgentFailurePolicy.isRetryable("error", "api error 500: internal server error"))
        assertTrue(AgentFailurePolicy.isRetryable("error", "Rate limit exceeded, try again later"))
        assertTrue(AgentFailurePolicy.isRetryable("error", "request timeout na 60s"))
        assertTrue(AgentFailurePolicy.isRetryable("error", "agent exited without writing /work/agent-result.json"))
        assertTrue(AgentFailurePolicy.isRetryable("error", "container stopped without writing a result"))
    }

    @Test
    fun `token in de outcome zelf telt ook`() {
        assertTrue(AgentFailurePolicy.isRetryable("timeout", null))
        assertTrue(AgentFailurePolicy.isRetryable("http 429", ""))
    }

    @Test
    fun `matching is hoofdletter-ongevoelig`() {
        assertTrue(AgentFailurePolicy.isRetryable("error", "HTTP 429 Too Many Requests"))
        assertTrue(AgentFailurePolicy.isRetryable("ERROR", "RATE LIMIT bereikt"))
    }

    @Test
    fun `inhoudelijke fouten zijn terminal`() {
        assertFalse(AgentFailurePolicy.isRetryable("error", "compilatie faalt: unresolved reference"))
        assertFalse(AgentFailurePolicy.isRetryable("review-rejected", "edge cases niet afgedekt"))
    }

    @Test
    fun `null of lege invoer is terminal`() {
        assertFalse(AgentFailurePolicy.isRetryable(null, null))
        assertFalse(AgentFailurePolicy.isRetryable("", ""))
    }
}
