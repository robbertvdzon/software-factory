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
) {
    fun login(username: String, password: String): LoginResponse {
        if (username != secrets.dashboardUsername || !constantTimeEquals(password, secrets.dashboardPassword)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        val expiresAt = Instant.now().plusSeconds(60L * 60L * 24L * 30L).epochSecond
        return LoginResponse(token = token(username, expiresAt), username = username)
    }

    fun requireAuthorization(header: String?) {
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
        val username = parts[0]
        val expiresAt = parts[1].toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
        val signature = parts[2]
        val expected = hmac("$username:$expiresAt")
        if (username != secrets.dashboardUsername || !constantTimeEquals(signature, expected) || expiresAt < Instant.now().epochSecond) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token")
        }
    }

    /**
     * Vergelijkt twee strings in constante tijd om timing-side-channels te voorkomen
     * (een aanvaller mag een HMAC-signature of wachtwoord niet byte-voor-byte kunnen raden
     * aan de hand van de responstijd). [MessageDigest.isEqual] is op moderne JDK's timing-safe.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private fun token(username: String, expiresAt: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$username:$expiresAt:${hmac("$username:$expiresAt")}".toByteArray())

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secrets.rememberSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
