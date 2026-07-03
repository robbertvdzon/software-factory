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
        // Zonder SF_DASHBOARD_LOCAL_MODE staat local mode uit (veilige default voor k8s).
        assertEquals(false, secrets.localMode)
    }

    @Test
    fun `local mode is only enabled on an explicit true`() {
        fun loadWith(localMode: String?): DashboardSecrets =
            DashboardSecretsLoader(
                environment = buildMap {
                    put("SF_YOUTRACK_BASE_URL", "https://youtrack.example/")
                    put("SF_YOUTRACK_TOKEN", "yt")
                    put("SF_GITHUB_TOKEN", "gh")
                    put("SF_DATABASE_URL", "postgresql://user:pass@localhost:5432/db")
                    put("SF_DATABASE_SCHEMA", "software_factory")
                    put("SF_DASHBOARD_PASSWORD", "secret")
                    localMode?.let { put("SF_DASHBOARD_LOCAL_MODE", it) }
                },
                secretFiles = emptyList(),
            ).load()

        assertEquals(true, loadWith("true").localMode)
        assertEquals(true, loadWith("TRUE").localMode)
        // Elke andere waarde (of afwezigheid) betekent: geen machine-lokale acties.
        assertEquals(false, loadWith("false").localMode)
        assertEquals(false, loadWith("1").localMode)
        assertEquals(false, loadWith(null).localMode)
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
