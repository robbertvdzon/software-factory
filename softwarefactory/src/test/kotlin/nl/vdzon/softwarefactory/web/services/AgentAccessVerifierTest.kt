package nl.vdzon.softwarefactory.web.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.web.server.ResponseStatusException

class AgentAccessVerifierTest {
    private val token = "a".repeat(40)
    @Test fun `numbered preview origin cannot match another host`() {
        val access = AgentAccessVerifier(token, "tester@example.invalid", "https://app-pr-{pr}.example.invalid")
        assertEquals("tester@example.invalid", access.verify(token, "tester@example.invalid", "https://app-pr-12.example.invalid"))
        for (origin in listOf("https://app-pr-x.example.invalid", "https://app-pr-12.example.invalid.evil.test", "http://app-pr-12.example.invalid")) {
            assertThrows(ResponseStatusException::class.java) { access.verify(token, "tester@example.invalid", origin) }
        }
    }
    @Test fun `rejects disabled wrong token unknown identity and wrong origin`() {
        val access = AgentAccessVerifier(token, "tester@example.invalid", "https://acceptance.example.invalid")
        assertEquals("tester@example.invalid", access.verify(token,"Tester@example.invalid", "https://acceptance.example.invalid"))
        for (provided in listOf(null, "wrong")) assertThrows(ResponseStatusException::class.java) { access.verify(provided,"tester@example.invalid",null) }
        assertThrows(ResponseStatusException::class.java) { access.verify(token,"owner@example.invalid",null) }
        assertThrows(ResponseStatusException::class.java) { access.verify(token,"tester@example.invalid","https://other.invalid") }
        assertThrows(ResponseStatusException::class.java) { AgentAccessVerifier("", "tester@example.invalid", "").verify(token,"tester@example.invalid",null) }
        assertThrows(IllegalArgumentException::class.java) { AgentAccessVerifier("short", "tester@example.invalid", "") }
    }
}
