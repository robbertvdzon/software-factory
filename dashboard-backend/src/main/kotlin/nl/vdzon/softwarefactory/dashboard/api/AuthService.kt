package nl.vdzon.softwarefactory.dashboard.api

import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class AuthService(
    private val secrets: DashboardSecrets,
    private val googleVerifier: GoogleIdTokenVerifier,
) {
    /**
     * Ruilt een Google ID-token in voor een eigen sessie-token. Het token wordt eerst door de
     * [GoogleIdTokenVerifier]-seam gevalideerd (signature/audience/issuer/expiry); vervolgens moet
     * het e-mailadres geverifieerd én op de allowlist staan. De sessie-identiteit wordt het
     * e-mailadres.
     */
    fun loginWithGoogle(idToken: String): LoginResponse {
        val identity = googleVerifier.verify(idToken)
        if (!identity.emailVerified) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google-e-mailadres is niet geverifieerd")
        }
        val email = identity.email.lowercase()
        if (email !in secrets.allowedEmails) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "E-mailadres niet toegestaan")
        }
        val expiresAt = Instant.now().plusSeconds(60L * 60L * 24L * 30L).epochSecond
        return LoginResponse(token = token(email, expiresAt), username = email)
    }

    /**
     * Valideert het Bearer-sessie-token en geeft de identiteit (het allowlisted e-mailadres) terug.
     * Gooit HTTP 401 bij een ontbrekend, ongeldig, verlopen of niet-allowlisted token.
     */
    fun requireAuthorization(header: String?): String {
        if (header == null || !header.startsWith("Bearer ")) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token")
        }
        val token = header.removePrefix("Bearer ").trim()
        val parts = runCatching {
            String(Base64.getUrlDecoder().decode(token)).split(":", limit = 3)
        }.getOrElse {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
        }
        if (parts.size != 3) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
        }
        val email = parts[0]
        val expiresAt = parts[1].toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
        val signature = parts[2]
        val expected = hmac("$email:$expiresAt")
        if (email !in secrets.allowedEmails || !constantTimeEquals(signature, expected) || expiresAt < Instant.now().epochSecond) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
        }
        return email
    }

    /**
     * Vergelijkt twee strings in constante tijd om timing-side-channels te voorkomen
     * (een aanvaller mag een HMAC-signature niet byte-voor-byte kunnen raden aan de hand van de
     * responstijd). [MessageDigest.isEqual] is op moderne JDK's timing-safe.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private fun token(identity: String, expiresAt: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$identity:$expiresAt:${hmac("$identity:$expiresAt")}".toByteArray())

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secrets.rememberSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
