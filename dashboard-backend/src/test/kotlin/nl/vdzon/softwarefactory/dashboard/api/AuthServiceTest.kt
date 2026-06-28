package nl.vdzon.softwarefactory.dashboard.api

import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthServiceTest {
    private val secrets = DashboardSecrets(
        youTrackBaseUrl = "https://youtrack.example",
        youTrackToken = "yt",
        youTrackProjects = emptyList(),
        githubToken = "gh",
        databaseUrl = "postgresql://user:pass@localhost:5432/db",
        databaseSchema = "software_factory",
        dashboardUsername = "robbert",
        dashboardPassword = "secret",
        rememberSecret = "robbert:secret",
    )
    private val authService = AuthService(secrets)

    @Test
    fun `login with valid credentials issues an accepted token`() {
        val response = authService.login("robbert", "secret")
        assertEquals("robbert", response.username)

        // Round-trip: het uitgegeven token moet door requireAuthorization geaccepteerd worden.
        authService.requireAuthorization("Bearer ${response.token}")
    }

    @Test
    fun `login rejects wrong password`() {
        assertFailsWith<ResponseStatusException> { authService.login("robbert", "wrong") }
    }

    @Test
    fun `login rejects wrong username`() {
        assertFailsWith<ResponseStatusException> { authService.login("intruder", "secret") }
    }

    @Test
    fun `requireAuthorization rejects a missing header`() {
        assertFailsWith<ResponseStatusException> { authService.requireAuthorization(null) }
    }

    @Test
    fun `requireAuthorization rejects a non-bearer header`() {
        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Basic abc") }
    }

    @Test
    fun `requireAuthorization rejects a token with a tampered signature`() {
        val token = authService.login("robbert", "secret").token
        val raw = String(java.util.Base64.getUrlDecoder().decode(token))
        val tampered = raw.substringBeforeLast(":") + ":deadbeef"
        val tamperedToken = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tampered.toByteArray())

        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer $tamperedToken") }
    }

    @Test
    fun `requireAuthorization rejects an expired token`() {
        val expired = "robbert:1:${"00".repeat(32)}"
        val expiredToken = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(expired.toByteArray())

        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer $expiredToken") }
    }

    @Test
    fun `requireAuthorization rejects garbage`() {
        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer not-base64-@@@") }
    }
}
