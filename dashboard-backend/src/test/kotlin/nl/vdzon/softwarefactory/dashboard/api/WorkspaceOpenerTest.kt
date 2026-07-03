package nl.vdzon.softwarefactory.dashboard.api

import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceOpenerTest {
    @Test
    fun `refuses to open a workspace when local mode is off`() {
        var processStarted = false
        val opener = WorkspaceOpener(secrets(localMode = false)) { _ ->
            processStarted = true
            SucceededProcess()
        }

        val exception = assertFailsWith<ResponseStatusException> {
            opener.openInIntellij("SF-1", "/tmp/some-workspace")
        }

        // Zonder de vlag: een nette 409 met uitleg, en vooral géén ProcessBuilder-poging
        // (die in de k8s-deploy gegarandeerd zou falen).
        assertEquals(HttpStatus.CONFLICT, exception.statusCode)
        assertContains(exception.reason.orEmpty(), "SF_DASHBOARD_LOCAL_MODE")
        assertEquals(false, processStarted)
    }

    @Test
    fun `opens the repo folder in local mode`() {
        val workspace = Files.createTempDirectory("workspace-opener-test")
        val repoRoot = Files.createDirectory(workspace.resolve("repo"))
        var command: List<String>? = null
        val opener = WorkspaceOpener(secrets(localMode = true)) { cmd ->
            command = cmd
            SucceededProcess()
        }

        val path = opener.openInIntellij("SF-1", workspace.toString())

        assertEquals(repoRoot.toAbsolutePath().normalize().toString(), path)
        assertEquals(listOf("open", "-a", "IntelliJ IDEA", path), command)
    }

    @Test
    fun `rejects a missing workspace path even in local mode`() {
        val opener = WorkspaceOpener(secrets(localMode = true)) { _ -> SucceededProcess() }

        val exception = assertFailsWith<IllegalArgumentException> {
            opener.openInIntellij("SF-1", null)
        }
        assertTrue(exception.message.orEmpty().contains("SF-1"))
    }

    private fun secrets(localMode: Boolean): DashboardSecrets =
        DashboardSecrets(
            youTrackBaseUrl = "https://youtrack.example",
            youTrackToken = "yt",
            youTrackProjects = emptyList(),
            githubToken = "gh",
            databaseUrl = "postgresql://user:pass@localhost:5432/db",
            databaseSchema = "software_factory",
            dashboardUsername = "admin",
            dashboardPassword = "secret",
            rememberSecret = "remember",
            localMode = localMode,
        )
}

/** Fake proces dat meteen succesvol klaar is, zodat de test geen echt IntelliJ start. */
private class SucceededProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() {}
}
