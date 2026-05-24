package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.config.FactoryEnvironmentProvider
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactoryDashboardAuthTest {
    @Test
    fun `authenticates configured dashboard user in session`() {
        val auth = FactoryDashboardAuth(
            object : FactoryEnvironmentProvider {
                override fun resolvedValues(): Map<String, String> =
                    mapOf(
                        "SF_DASHBOARD_USERNAME" to "robbert",
                        "SF_DASHBOARD_PASSWORD" to "secret",
                    )
            },
        )
        val session = MockHttpSession()

        assertFalse(auth.login(session, "robbert", "wrong"))
        assertFalse(auth.isAuthenticated(session))

        assertTrue(auth.login(session, "robbert", "secret"))
        assertTrue(auth.isAuthenticated(session))

        auth.logout(session)
        assertFalse(auth.isAuthenticated(MockHttpSession()))
    }
}
