package nl.vdzon.softwarefactory.dashboard.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardSecretsLoaderTest {
    private fun baseEnv(vararg extra: Pair<String, String>): Map<String, String> =
        mapOf(
            "SF_DASHBOARD_REMEMBER_SECRET" to "signing-secret",
            "SF_GOOGLE_CLIENT_ID" to "client-id.apps.googleusercontent.com",
        ) + extra

    @Test
    fun `loads dashboard secrets from environment`() {
        val secrets = DashboardSecretsLoader(
            environment = baseEnv("SF_ALLOWED_EMAILS" to "robbert@vdzon.com"),
            secretFiles = emptyList(),
        ).load()

        assertEquals("signing-secret", secrets.rememberSecret)
        assertEquals("client-id.apps.googleusercontent.com", secrets.googleClientId)
        assertEquals(setOf("robbert@vdzon.com"), secrets.allowedEmails)
    }

    @Test
    fun `defaults the allowlist to robbert when omitted`() {
        val secrets = DashboardSecretsLoader(
            environment = baseEnv(),
            secretFiles = emptyList(),
        ).load()

        assertEquals(setOf("robbert@vdzon.com"), secrets.allowedEmails)
    }

    @Test
    fun `parses a comma-separated allowlist and normalises whitespace and casing`() {
        val secrets = DashboardSecretsLoader(
            environment = baseEnv("SF_ALLOWED_EMAILS" to " Robbert@Vdzon.com , second@example.com "),
            secretFiles = emptyList(),
        ).load()

        assertEquals(setOf("robbert@vdzon.com", "second@example.com"), secrets.allowedEmails)
    }

    @Test
    fun `startup fails when google client id is omitted`() {
        val exception = assertFailsWith<IllegalStateException> {
            DashboardSecretsLoader(
                environment = mapOf("SF_DASHBOARD_REMEMBER_SECRET" to "signing-secret"),
                secretFiles = emptyList(),
            ).load()
        }
        assertContains(exception.message.orEmpty(), "SF_GOOGLE_CLIENT_ID")
    }

    @Test
    fun `startup fails when remember secret is omitted`() {
        val exception = assertFailsWith<IllegalStateException> {
            DashboardSecretsLoader(
                environment = mapOf("SF_GOOGLE_CLIENT_ID" to "client-id"),
                secretFiles = emptyList(),
            ).load()
        }
        assertContains(exception.message.orEmpty(), "SF_DASHBOARD_REMEMBER_SECRET")
    }
}
