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
                "SF_YOUTRACK_BASE_URL" to "https://youtrack.example/",
                "SF_YOUTRACK_TOKEN" to "yt",
                "SF_YOUTRACK_PROJECTS" to "SP,KAN",
                "SF_GITHUB_TOKEN" to "gh",
                "SF_DATABASE_URL" to "postgresql://user:pass@localhost:5432/db",
                "SF_DATABASE_SCHEMA" to "software_factory",
                "SF_DASHBOARD_USERNAME" to "robbert",
                "SF_DASHBOARD_PASSWORD" to "secret",
            ),
            secretFiles = emptyList(),
        ).load()

        assertEquals("https://youtrack.example", secrets.youTrackBaseUrl)
        assertEquals(listOf("SP", "KAN"), secrets.youTrackProjects)
        assertEquals("robbert", secrets.dashboardUsername)
    }

    @Test
    fun `startup fails when dashboard password is omitted`() {
        val exception = assertFailsWith<IllegalStateException> {
            DashboardSecretsLoader(
                environment = mapOf(
                    "SF_YOUTRACK_BASE_URL" to "https://youtrack.example/",
                    "SF_YOUTRACK_TOKEN" to "yt",
                    "SF_GITHUB_TOKEN" to "gh",
                    "SF_DATABASE_URL" to "postgresql://user:pass@localhost:5432/db",
                    "SF_DATABASE_SCHEMA" to "software_factory",
                ),
                secretFiles = emptyList(),
            ).load()
        }
        assertContains(exception.message.orEmpty(), "SF_DASHBOARD_PASSWORD")
    }
}
