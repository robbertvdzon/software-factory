package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ProjectRepoResolverMergeDeployTest {

    @Test
    fun `mergeConfigFor returns Manual by default when project has no merge block`() {
        val resolver = ProjectRepoResolver(mapOf("myproject" to "git@example/r.git"))
        assertEquals(MergeConfig.Manual, resolver.mergeConfigFor("myproject"))
    }

    @Test
    fun `deployConfigFor returns Skip by default when project has no deploy block`() {
        val resolver = ProjectRepoResolver(mapOf("myproject" to "git@example/r.git"))
        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("myproject"))
    }

    @Test
    fun `mergeConfigFor returns Manual for unknown project`() {
        val resolver = ProjectRepoResolver(emptyMap())
        assertEquals(MergeConfig.Manual, resolver.mergeConfigFor("unknown"))
    }

    @Test
    fun `deployConfigFor returns Skip for unknown project`() {
        val resolver = ProjectRepoResolver(emptyMap())
        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("unknown"))
    }

    @Test
    fun `parses automatic merge and rest-restart deploy from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: softwarefactory
                repo: https://github.com/robbert/sf.git
                merge:
                  mode: automatic
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

        assertEquals(MergeConfig.Automatic, resolver.mergeConfigFor("softwarefactory"))
        val deploy = resolver.deployConfigFor("softwarefactory")
        check(deploy is DeployConfig.RestRestart)
        assertEquals("http://localhost:8080/api/restart", deploy.restartUrl)
        assertEquals("http://localhost:8080/api/version", deploy.versionUrl)
        assertEquals("SF_FACTORY_API_TOKEN", deploy.tokenEnvVar)
        assertEquals(15, deploy.pollIntervalSeconds)
        assertEquals(5, deploy.timeoutMinutes)
    }

    @Test
    fun `parses manual merge and openshift-watch deploy from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: personal-feed
                repo: git@github.com:robbert/personal-feed.git
                merge:
                  mode: manual
                deploy:
                  type: openshift-watch
                  namespace: personal-feed
                  deployment: personal-feed-app
                  timeoutMinutes: 10
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertEquals(MergeConfig.Manual, resolver.mergeConfigFor("personal-feed"))
        val deploy = resolver.deployConfigFor("personal-feed")
        check(deploy is DeployConfig.OpenshiftWatch)
        assertEquals("personal-feed", deploy.namespace)
        assertEquals("personal-feed-app", deploy.deployment)
        assertEquals(10, deploy.timeoutMinutes)
    }

    @Test
    fun `missing merge block defaults to Manual`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: myapp
                repo: git@example/r.git
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertEquals(MergeConfig.Manual, resolver.mergeConfigFor("myapp"))
        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("myapp"))
    }
}
