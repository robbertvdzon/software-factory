package nl.vdzon.softwarefactory.dashboard.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardSecretsLoaderTest {
    @Test
    fun `loads dashboard secrets from environment`() {
        val secrets = DashboardSecretsLoader(
            environment = mapOf(
                "SF_DASHBOARD_USERNAME" to "robbert",
                "SF_DASHBOARD_PASSWORD" to "secret",
            ),
            secretFiles = emptyList(),
        ).load()

        assertEquals("robbert", secrets.dashboardUsername)
        assertEquals("secret", secrets.dashboardPassword)
    }

    @Test
    fun `defaults username to admin when omitted`() {
        val secrets = DashboardSecretsLoader(
            environment = mapOf("SF_DASHBOARD_PASSWORD" to "secret"),
            secretFiles = emptyList(),
        ).load()

        assertEquals("admin", secrets.dashboardUsername)
    }

    @Test
    fun `defaults remember secret to username colon password when omitted`() {
        val secrets = DashboardSecretsLoader(
            environment = mapOf(
                "SF_DASHBOARD_USERNAME" to "robbert",
                "SF_DASHBOARD_PASSWORD" to "secret",
            ),
            secretFiles = emptyList(),
        ).load()

        assertEquals("robbert:secret", secrets.rememberSecret)
    }

    @Test
    fun `startup fails when dashboard password is omitted`() {
        val exception = assertFailsWith<IllegalStateException> {
            DashboardSecretsLoader(
                environment = emptyMap(),
                secretFiles = emptyList(),
            ).load()
        }
        assertContains(exception.message.orEmpty(), "SF_DASHBOARD_PASSWORD")
    }
}
