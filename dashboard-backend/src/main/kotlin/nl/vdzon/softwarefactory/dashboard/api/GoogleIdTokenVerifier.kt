package nl.vdzon.softwarefactory.dashboard.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/** Uit een geldig Google ID-token gehaalde identiteit. */
data class GoogleIdentity(val email: String, val emailVerified: Boolean)

/**
 * Seam rond de Google-ID-token-verificatie. De productie-implementatie verifieert de
 * RS256-signature via Google's JWKS; tests injecteren een eigen keyset zodat ze zelf een
 * geldig test-ID-token kunnen ondertekenen zónder netwerkcall naar Google.
 */
fun interface GoogleIdTokenVerifier {
    /**
     * Verifieert signature, audience, issuer en expiry van [idToken] en geeft de identiteit
     * terug. Gooit [ResponseStatusException] (HTTP 401) als het token op één van die punten
     * ongeldig is. De allowlist-check gebeurt bewust in [AuthService], niet hier.
     */
    fun verify(idToken: String): GoogleIdentity
}

/**
 * Nimbus-gebaseerde verificatie van een Google OIDC ID-token. De [jwkSource] is injecteerbaar:
 * productie haalt de sleutels bij Google op, tests leveren een in-memory keyset aan.
 */
class NimbusGoogleIdTokenVerifier(
    private val clientId: String,
    jwkSource: JWKSource<SecurityContext>,
) : GoogleIdTokenVerifier {

    /** Productie: haal de publieke sleutels (met caching) bij Google's JWKS-endpoint op. */
    constructor(clientId: String) : this(
        clientId,
        JWKSourceBuilder.create<SecurityContext>(URI.create(GOOGLE_JWKS_URL).toURL()).build(),
    )

    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
    }

    override fun verify(idToken: String): GoogleIdentity {
        val claims: JWTClaimsSet = try {
            processor.process(idToken, null)
        } catch (ex: Exception) {
            throw unauthorized("Ongeldig Google ID-token")
        }
        if (clientId !in claims.audience.orEmpty()) {
            throw unauthorized("Google ID-token met verkeerde audience")
        }
        if (claims.issuer !in ACCEPTED_ISSUERS) {
            throw unauthorized("Google ID-token met verkeerde issuer")
        }
        val expiry = claims.expirationTime?.toInstant()
        if (expiry == null || expiry.isBefore(Instant.now())) {
            throw unauthorized("Google ID-token is verlopen")
        }
        val email = claims.getStringClaim("email").orEmpty().trim().lowercase()
        if (email.isBlank()) {
            throw unauthorized("Google ID-token bevat geen e-mailadres")
        }
        val emailVerified = claims.getBooleanClaim("email_verified") ?: false
        return GoogleIdentity(email = email, emailVerified = emailVerified)
    }

    private fun unauthorized(reason: String) = ResponseStatusException(HttpStatus.UNAUTHORIZED, reason)

    private companion object {
        const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        val ACCEPTED_ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
