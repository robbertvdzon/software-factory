package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/** Unit-tests voor de gedeelde Bearer-token-autorisatiehelper (SF-1416). */
class BearerTokenAuthorizerTest {

    private fun envProvider(values: Map<String, String>): ConfigApi = object : ConfigApi {
        override fun resolvedValues(): Map<String, String> = values
    }

    @Test
    fun `isAuthorized returns true for a valid Bearer token`() {
        val configApi = envProvider(mapOf("SF_FACTORY_API_TOKEN" to "expected-token"))
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer expected-token")

        assertTrue(BearerTokenAuthorizer.isAuthorized(configApi, request))
    }

    @Test
    fun `isAuthorized returns false when SF_FACTORY_API_TOKEN is not configured`() {
        val configApi = envProvider(emptyMap())
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer some-token")

        assertFalse(BearerTokenAuthorizer.isAuthorized(configApi, request))
    }

    @Test
    fun `isAuthorized returns false when SF_FACTORY_API_TOKEN is blank`() {
        val configApi = envProvider(mapOf("SF_FACTORY_API_TOKEN" to "   "))
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer   ")

        assertFalse(BearerTokenAuthorizer.isAuthorized(configApi, request))
    }

    @Test
    fun `isAuthorized returns false when the Authorization header is missing`() {
        val configApi = envProvider(mapOf("SF_FACTORY_API_TOKEN" to "expected-token"))
        val request = MockHttpServletRequest()

        assertFalse(BearerTokenAuthorizer.isAuthorized(configApi, request))
    }

    @Test
    fun `isAuthorized returns false when the Authorization header lacks the Bearer prefix`() {
        val configApi = envProvider(mapOf("SF_FACTORY_API_TOKEN" to "expected-token"))
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "expected-token")

        assertFalse(BearerTokenAuthorizer.isAuthorized(configApi, request))
    }

    @Test
    fun `isAuthorized returns false when the token does not match`() {
        val configApi = envProvider(mapOf("SF_FACTORY_API_TOKEN" to "expected-token"))
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer wrong-token")

        assertFalse(BearerTokenAuthorizer.isAuthorized(configApi, request))
    }
}
