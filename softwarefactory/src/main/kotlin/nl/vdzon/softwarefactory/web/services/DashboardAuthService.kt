package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sessie die het dashboard na een geslaagde Google-login krijgt. */
data class DashboardLogin(val token: String, val username: String)

/**
 * Dashboard-authenticatie: ruilt een Google ID-token in voor een eigen, HMAC-ondertekend
 * sessietoken en valideert dat token bij elke aanroep. De allowlist en het signing-geheim komen
 * uit [FactorySecrets]; zonder geheim wordt er nooit een token uitgegeven of geaccepteerd.
 */
@Service
class DashboardAuthService(
    private val secrets: FactorySecrets,
    private val googleVerifier: GoogleIdTokenVerifier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Ruilt een Google ID-token in voor een eigen sessietoken. Het token wordt eerst door de
     * [GoogleIdTokenVerifier]-seam gevalideerd (signature/audience/issuer/expiry); vervolgens moet
     * het e-mailadres geverifieerd én op de allowlist staan. De sessie-identiteit wordt het
     * e-mailadres.
     */
    fun loginWithGoogle(idToken: String): DashboardLogin {
        val signingSecret = signingSecret()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dashboard-login is niet geconfigureerd")
        val identity = try {
            googleVerifier.verify(idToken)
        } catch (ex: ResponseStatusException) {
            logger.warn("Google-login geweigerd: {}", ex.reason)
            throw ex
        }
        if (!identity.emailVerified) {
            logger.warn("Google-login geweigerd voor {}: e-mailadres niet geverifieerd", identity.email)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google-e-mailadres is niet geverifieerd")
        }
        val email = identity.email.lowercase()
        if (email !in secrets.allowedEmails) {
            logger.warn("Google-login geweigerd voor {}: niet op de allowlist", email)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "E-mailadres niet toegestaan")
        }
        val expiresAt = Instant.now().plusSeconds(SESSION_SECONDS).epochSecond
        return DashboardLogin(token = token(signingSecret, email, expiresAt), username = email)
    }

    /**
     * Valideert het Bearer-sessietoken en geeft de identiteit (het allowlisted e-mailadres) terug.
     * Gooit HTTP 401 bij een ontbrekend, ongeldig, verlopen of niet-allowlisted token.
     */
    fun loginForAgent(email: String): DashboardLogin {
        if (email !in secrets.allowedEmails) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Identity is not allowed")
        val expiresAt = Instant.now().plusSeconds(3600).epochSecond
        return DashboardLogin(token = token(signingSecret() ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE), email, expiresAt), username = email)
    }

    fun requireAuthorization(header: String?): String {
        if (header == null || !header.startsWith("Bearer ")) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token")
        }
        val signingSecret = signingSecret() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
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
        val expected = hmac(signingSecret, "$email:$expiresAt")
        if (email !in secrets.allowedEmails || !constantTimeEquals(signature, expected) || expiresAt < Instant.now().epochSecond) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
        }
        return email
    }

    private fun signingSecret(): String? = secrets.dashboardRememberSecret?.takeIf { it.isNotBlank() }

    /**
     * Vergelijkt twee strings in constante tijd om timing-side-channels te voorkomen
     * (een aanvaller mag een HMAC-signature niet byte-voor-byte kunnen raden aan de hand van de
     * responstijd). [MessageDigest.isEqual] is op moderne JDK's timing-safe.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private fun token(secret: String, identity: String, expiresAt: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$identity:$expiresAt:${hmac(secret, "$identity:$expiresAt")}".toByteArray())

    private fun hmac(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SESSION_SECONDS = 60L * 60L * 24L * 30L
    }
}
