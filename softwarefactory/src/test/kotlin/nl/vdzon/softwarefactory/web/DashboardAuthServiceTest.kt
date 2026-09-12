package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.web.services.DashboardAuthService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardAuthServiceTest {
    private val clientId = "test-client-id.apps.googleusercontent.com"
    private val tokens = TestGoogleTokens()
    private val secrets = DashboardApiFixtures.fakeSecrets()
    private val authService = DashboardAuthService(secrets, tokens.verifier(clientId))

    @Test
    fun `allowlisted Google login issues a session token accepted by requireAuthorization`() {
        // Hoofdlettergevoeligheid mag niet uitmaken: het adres wordt genormaliseerd.
        val idToken = tokens.idToken(email = "Robbert@Vdzon.com", audience = clientId)

        val response = authService.loginWithGoogle(idToken)
        assertEquals("robbert@vdzon.com", response.username)

        // Round-trip: het uitgegeven sessie-token wordt geaccepteerd en geeft de identiteit terug.
        assertEquals("robbert@vdzon.com", authService.requireAuthorization("Bearer ${response.token}"))
    }

    @Test
    fun `Google login rejects an email that is not on the allowlist`() {
        val idToken = tokens.idToken(email = "intruder@example.com", audience = clientId)

        val exception = assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(idToken) }
        assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
    }

    @Test
    fun `Google login rejects an unverified email even when allowlisted`() {
        val idToken = tokens.idToken(email = "robbert@vdzon.com", audience = clientId, emailVerified = false)

        val exception = assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(idToken) }
        assertEquals(HttpStatus.UNAUTHORIZED, exception.statusCode)
    }

    @Test
    fun `Google login rejects a wrong audience, issuer, expiry, key and garbage`() {
        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(tokens.idToken("robbert@vdzon.com", "some-other-client")) }
        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(tokens.idToken("robbert@vdzon.com", clientId, issuer = "https://evil.example.com")) }
        assertFailsWith<ResponseStatusException> {
            authService.loginWithGoogle(tokens.idToken("robbert@vdzon.com", clientId, expiresAt = Date(System.currentTimeMillis() - 60_000L)))
        }
        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(tokens.idToken("robbert@vdzon.com", clientId, signWithForeignKey = true)) }
        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle("not-a-jwt") }
    }

    @Test
    fun `login is unavailable without a signing secret`() {
        val unconfigured = DashboardAuthService(DashboardApiFixtures.fakeSecrets(dashboardRememberSecret = null), tokens.verifier(clientId))

        val exception = assertFailsWith<ResponseStatusException> { unconfigured.loginWithGoogle(tokens.idToken("robbert@vdzon.com", clientId)) }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.statusCode)
        assertFailsWith<ResponseStatusException> { unconfigured.requireAuthorization("Bearer ${authService.loginWithGoogle(tokens.idToken("robbert@vdzon.com", clientId)).token}") }
    }

    @Test
    fun `requireAuthorization rejects a missing, non-bearer or garbage header`() {
        assertFailsWith<ResponseStatusException> { authService.requireAuthorization(null) }
        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Basic abc") }
        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer not-base64-@@@") }
    }

    @Test
    fun `requireAuthorization rejects a token with a tampered signature`() {
        val token = authService.loginWithGoogle(tokens.idToken("robbert@vdzon.com", clientId)).token
        val raw = String(java.util.Base64.getUrlDecoder().decode(token))
        val tampered = raw.substringBeforeLast(":") + ":deadbeef"
        val tamperedToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.toByteArray())

        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer $tamperedToken") }
    }

    @Test
    fun `requireAuthorization rejects an expired session token`() {
        val expired = "robbert@vdzon.com:1:${"00".repeat(32)}"
        val expiredToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(expired.toByteArray())

        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer $expiredToken") }
    }

    @Test
    fun `requireAuthorization rejects a token for a non-allowlisted identity`() {
        // Zelfs met een geldige HMAC-signature mag een niet-allowlisted identiteit niet door.
        val other = DashboardAuthService(DashboardApiFixtures.fakeSecrets(allowedEmails = setOf("intruder@example.com")), tokens.verifier(clientId))
        val intruderToken = other.loginWithGoogle(tokens.idToken("intruder@example.com", clientId)).token

        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer $intruderToken") }
    }
}
