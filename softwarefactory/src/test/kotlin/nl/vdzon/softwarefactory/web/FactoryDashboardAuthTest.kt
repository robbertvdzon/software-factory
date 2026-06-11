package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.web.services.*
import nl.vdzon.softwarefactory.web.views.*
import nl.vdzon.softwarefactory.web.models.*
import nl.vdzon.softwarefactory.web.repositories.*
import nl.vdzon.softwarefactory.web.controllers.*

import jakarta.servlet.http.Cookie
import nl.vdzon.softwarefactory.config.services.FactoryEnvironmentProvider
import nl.vdzon.softwarefactory.web.services.FactoryDashboardAuth
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactoryDashboardAuthTest {
    private val clock = Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `authenticates configured dashboard user in session`() {
        val auth = auth()
        val session = MockHttpSession()

        assertFalse(auth.login(session, "robbert", "wrong"))
        assertFalse(auth.isAuthenticated(session))

        assertTrue(auth.login(session, "robbert", "secret"))
        assertTrue(auth.isAuthenticated(session))

        auth.logout(session)
        assertFalse(auth.isAuthenticated(MockHttpSession()))
    }

    @Test
    fun `restores session from signed remember cookie`() {
        val auth = auth()
        val loginSession = MockHttpSession()

        assertTrue(auth.login(loginSession, "robbert", "secret"))
        val cookie = auth.loginCookie()
        val requestAfterRestart = MockHttpServletRequest().apply {
            setCookies(Cookie(cookie.name, cookie.value))
        }
        val freshSession = MockHttpSession()

        assertTrue(auth.isAuthenticated(requestAfterRestart, freshSession))
        assertTrue(auth.isAuthenticated(freshSession))
    }

    @Test
    fun `rejects tampered remember cookie`() {
        val auth = auth()
        val cookie = auth.loginCookie()
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie(cookie.name, cookie.value.replaceAfterLast(".", "tampered")))
        }

        assertFalse(auth.isAuthenticated(request, MockHttpSession()))
    }

    @Test
    fun `logout cookie clears remember cookie`() {
        val cookie = auth().logoutCookie()

        assertTrue(cookie.maxAge.isZero)
        assertTrue(cookie.isHttpOnly)
    }

    private fun auth(): FactoryDashboardAuth =
        FactoryDashboardAuth(
            object : FactoryEnvironmentProvider {
                override fun resolvedValues(): Map<String, String> =
                    mapOf(
                        "SF_DASHBOARD_USERNAME" to "robbert",
                        "SF_DASHBOARD_PASSWORD" to "secret",
                    )
            },
            clock,
        )
}
