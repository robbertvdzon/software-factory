package nl.vdzon.softwarefactory.config

import jakarta.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Gedeelde Bearer-token-autorisatie tegen `SF_FACTORY_API_TOKEN`, gebruikt door de
 * machine-tot-machine-controllers (TrackerStoryApiController, CompletionOperationsController).
 * Het dashboard zelf gebruikt Google-SSO via `web.services.DashboardAuthService` (andere flow).
 *
 * `internal`: mag binnen de softwarefactory-app-compilatie-eenheid vanuit elk pakket
 * (incl. `web.controllers`) aangeroepen worden, maar telt niet als publiek root-API
 * voor de Modulith-grens (config blijft alleen [ConfigApi] als publiek contract exposen).
 */
internal object BearerTokenAuthorizer {
    private const val TOKEN_ENV_KEY = "SF_FACTORY_API_TOKEN"

    fun isAuthorized(configApi: ConfigApi, request: HttpServletRequest): Boolean {
        val expectedToken = configApi.resolvedValues()[TOKEN_ENV_KEY]?.takeIf { it.isNotBlank() } ?: return false
        val authHeader = request.getHeader("Authorization") ?: ""
        val providedToken = if (authHeader.startsWith("Bearer ")) authHeader.removePrefix("Bearer ") else ""
        return constantTimeEquals(providedToken, expectedToken)
    }

    /** Constante-tijd tokenvergelijking om timing-side-channels te voorkomen. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}
