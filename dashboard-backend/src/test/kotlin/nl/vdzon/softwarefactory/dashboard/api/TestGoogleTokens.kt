package nl.vdzon.softwarefactory.dashboard.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date

/**
 * Test-dubbel voor Google-ID-tokens: een eigen RSA-keypair + in-memory JWKS zodat tests
 * netwerkloos een geldig (of bewust ongeldig) ID-token kunnen ondertekenen. Zo dekt de test
 * exact het productiepad ([NimbusGoogleIdTokenVerifier]) zonder live Google-call.
 */
class TestGoogleTokens {
    private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()

    /** Tweede, niet-vertrouwde sleutel (zelfde kid) om een tampered/verkeerd-getekend token te maken. */
    private val foreignKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()

    fun jwkSource(): ImmutableJWKSet<SecurityContext> = ImmutableJWKSet(JWKSet(signingKey.toPublicJWK()))

    fun verifier(clientId: String): NimbusGoogleIdTokenVerifier =
        NimbusGoogleIdTokenVerifier(clientId, jwkSource())

    fun idToken(
        email: String,
        audience: String,
        issuer: String = "https://accounts.google.com",
        emailVerified: Boolean = true,
        expiresAt: Date = Date(System.currentTimeMillis() + 3_600_000L),
        signWithForeignKey: Boolean = false,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("123456789")
            .expirationTime(expiresAt)
            .issueTime(Date())
            .claim("email", email)
            .claim("email_verified", emailVerified)
            .build()
        val key = if (signWithForeignKey) foreignKey else signingKey
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }
}
