package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ProjectRepoResolverMergeDeployTest {

    @Test
    fun `deployConfigFor returns Skip by default when project has no deploy block`() {
        val resolver = ProjectRepoResolver(mapOf("myproject" to "git@example/r.git"))
        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("myproject"))
    }

    @Test
    fun `deployConfigFor returns Skip for unknown project`() {
        val resolver = ProjectRepoResolver(emptyMap())
        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("unknown"))
    }

    @Test
    fun `parses rest-restart deploy from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: softwarefactory
                repo: https://github.com/robbert/sf.git
                deploy:
                  type: rest-restart
                  restartUrl: http://localhost:8080/api/restart
                  versionUrl: http://localhost:8080/api/version
                  tokenEnvVar: SF_FACTORY_API_TOKEN
                  pollIntervalSeconds: 15
                  timeoutMinutes: 5
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        val deploy = resolver.deployConfigFor("softwarefactory")
        check(deploy is DeployConfig.RestRestart)
        assertEquals("http://localhost:8080/api/restart", deploy.restartUrl)
        assertEquals("http://localhost:8080/api/version", deploy.versionUrl)
        assertEquals("SF_FACTORY_API_TOKEN", deploy.tokenEnvVar)
        assertEquals(15, deploy.pollIntervalSeconds)
        assertEquals(5, deploy.timeoutMinutes)
    }

    @Test
    fun `parses openshift-watch deploy from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: personal-feed
                repo: git@github.com:robbert/personal-feed.git
                deploy:
                  type: openshift-watch
                  namespace: personal-feed
                  deployment: personal-feed-app
                  timeoutMinutes: 10
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        val deploy = resolver.deployConfigFor("personal-feed")
        check(deploy is DeployConfig.OpenshiftWatch)
        assertEquals("personal-feed", deploy.namespace)
        assertEquals("personal-feed-app", deploy.deployment)
        assertEquals(10, deploy.timeoutMinutes)
    }

    @Test
    fun `manualApproveFor defaults to true when not configured`() {
        val resolver = ProjectRepoResolver(mapOf("myproject" to "git@example/r.git"))
        assertEquals(true, resolver.manualApproveFor("myproject"))
        assertEquals(true, resolver.manualApproveFor("unknown"))
        assertEquals(true, resolver.manualApproveFor(null))
    }

    @Test
    fun `manualApprove false in yaml disables the gate, true and absent keep it on`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: gated
                repo: git@example/g.git
              - name: ungated
                repo: git@example/u.git
                manualApprove: false
              - name: explicit-on
                repo: git@example/e.git
                manualApprove: true
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertEquals(true, resolver.manualApproveFor("gated"))
        assertEquals(false, resolver.manualApproveFor("ungated"))
        assertEquals(true, resolver.manualApproveFor("explicit-on"))
    }

    @Test
    fun `missing deploy block defaults to Skip`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: myapp
                repo: git@example/r.git
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("myapp"))
    }
}
