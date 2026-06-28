package nl.vdzon.softwarefactory.web.services

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import nl.vdzon.softwarefactory.config.ConfigApi
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class FactoryDashboardAuth(
    environmentProvider: ConfigApi,
    private val clock: Clock,
) {
    private val environment = environmentProvider.resolvedValues()
    val username: String = environment["SF_DASHBOARD_USERNAME"]?.takeIf { it.isNotBlank() } ?: "admin"
    private val password: String = environment["SF_DASHBOARD_PASSWORD"]?.takeIf { it.isNotBlank() } ?: "admin"
    private val rememberSecret: String = "$username:$password"
    private val rememberMaxAge: Duration =
        Duration.ofDays(environment["SF_DASHBOARD_REMEMBER_DAYS"]?.toLongOrNull()?.coerceIn(1, 365) ?: 30)
    private val secureCookie: Boolean =
        environment["SF_DASHBOARD_COOKIE_SECURE"]?.toBooleanStrictOrNull() ?: false

    fun isAuthenticated(session: HttpSession): Boolean =
        session.getAttribute(SESSION_USER) == username

    fun isAuthenticated(request: HttpServletRequest, session: HttpSession): Boolean {
        if (isAuthenticated(session)) {
            return true
        }
        val cookie = request.cookies
            ?.firstOrNull { it.name == REMEMBER_COOKIE }
            ?.value
            ?: return false
        if (!isValidRememberToken(cookie)) {
            return false
        }
        session.setAttribute(SESSION_USER, username)
        return true
    }

    fun login(session: HttpSession, username: String, password: String): Boolean {
        if (username == this.username && constantTimeEquals(password, this.password)) {
            session.setAttribute(SESSION_USER, username)
            return true
        }
        return false
    }

    /**
     * Constante-tijd wachtwoordvergelijking om timing-side-channels te voorkomen, net als de
     * remember-cookie-signature al via [MessageDigest.isEqual] vergeleken wordt.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    fun loginCookie(): ResponseCookie =
        ResponseCookie.from(REMEMBER_COOKIE, rememberToken())
            .path("/")
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .maxAge(rememberMaxAge)
            .build()

    fun logout(session: HttpSession) {
        session.invalidate()
    }

    fun logoutCookie(): ResponseCookie =
        ResponseCookie.from(REMEMBER_COOKIE, "")
            .path("/")
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .maxAge(Duration.ZERO)
            .build()

    private fun rememberToken(): String {
        val expiresAt = clock.instant().plus(rememberMaxAge).epochSecond
        val payload = "$username:$expiresAt"
        return "${payload.base64Url()}.${signature(payload).base64Url()}"
    }

    private fun isValidRememberToken(token: String): Boolean {
        val parts = token.split(".")
        if (parts.size != 2) {
            return false
        }
        val payload = parts[0].base64UrlDecoded() ?: return false
        val expectedSignature = signature(payload)
        val actualSignature = parts[1].base64UrlDecodedBytes() ?: return false
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            return false
        }

        val tokenUsername = payload.substringBeforeLast(":", missingDelimiterValue = "")
        val expiresAt = payload.substringAfterLast(":", missingDelimiterValue = "").toLongOrNull() ?: return false
        return tokenUsername == username && expiresAt > clock.instant().epochSecond
    }

    private fun signature(payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(rememberSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun String.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(StandardCharsets.UTF_8))

    private fun ByteArray.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.base64UrlDecoded(): String? =
        base64UrlDecodedBytes()?.let { String(it, StandardCharsets.UTF_8) }

    private fun String.base64UrlDecodedBytes(): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(this) }.getOrNull()

    companion object {
        private const val SESSION_USER = "software-factory-user"
        private const val REMEMBER_COOKIE = "sf-dashboard-login"
    }
}
