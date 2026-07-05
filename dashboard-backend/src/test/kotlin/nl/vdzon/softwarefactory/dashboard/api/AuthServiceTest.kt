package nl.vdzon.softwarefactory.dashboard.api

import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthServiceTest {
    private val clientId = "test-client-id.apps.googleusercontent.com"
    private val tokens = TestGoogleTokens()
    private val secrets = DashboardSecrets(
        rememberSecret = "super-secret-signing-key",
        googleClientId = clientId,
        allowedEmails = setOf("robbert@vdzon.com"),
    )
    private val authService = AuthService(secrets, tokens.verifier(clientId))

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
    fun `Google login rejects a wrong audience`() {
        val idToken = tokens.idToken(email = "robbert@vdzon.com", audience = "some-other-client")

        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(idToken) }
    }

    @Test
    fun `Google login rejects a wrong issuer`() {
        val idToken = tokens.idToken(email = "robbert@vdzon.com", audience = clientId, issuer = "https://evil.example.com")

        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(idToken) }
    }

    @Test
    fun `Google login rejects an expired token`() {
        val idToken = tokens.idToken(
            email = "robbert@vdzon.com",
            audience = clientId,
            expiresAt = Date(System.currentTimeMillis() - 60_000L),
        )

        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(idToken) }
    }

    @Test
    fun `Google login rejects a token signed with an untrusted key`() {
        val idToken = tokens.idToken(email = "robbert@vdzon.com", audience = clientId, signWithForeignKey = true)

        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle(idToken) }
    }

    @Test
    fun `Google login rejects garbage that is not a JWT`() {
        assertFailsWith<ResponseStatusException> { authService.loginWithGoogle("not-a-jwt") }
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
        val other = AuthService(
            secrets.copy(allowedEmails = setOf("intruder@example.com")),
            tokens.verifier(clientId),
        )
        val intruderToken = other.loginWithGoogle(tokens.idToken("intruder@example.com", clientId)).token

        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer $intruderToken") }
    }

    @Test
    fun `requireAuthorization rejects garbage`() {
        assertFailsWith<ResponseStatusException> { authService.requireAuthorization("Bearer not-base64-@@@") }
    }
}
