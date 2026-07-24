package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ProjectConfigurationMergeDeployTest {

    @Test
    fun `parses project-specific required checks and validates complete startup policy`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: backend
                repo: git@example/backend.git
                merge:
                  requiredChecks: [Backend verification]
              - name: frontend
                repo: git@example/frontend.git
                merge:
                  requiredChecks: [Flutter verification]
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        resolver.requireCompleteMergePolicies()
        assertEquals(setOf("Backend verification"), resolver.requiredChecksFor("BACKEND"))
        assertEquals(setOf("Flutter verification"), resolver.requiredChecksFor("frontend"))
        assertEquals(setOf("Backend verification"), resolver.requiredChecksForRepo("git@example/backend.git"))
    }

    @Test
    fun `startup validation names every project with a missing policy`() {
        val resolver = ProjectConfiguration(
            repos = mapOf("backend" to "repo-b", "frontend" to "repo-f"),
            requiredChecks = mapOf("backend" to setOf("Backend verification")),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            resolver.requireCompleteMergePolicies()
        }

        assertTrue(exception.message.orEmpty().contains("frontend"))
    }

    @Test
    fun `deployConfigFor returns Skip by default when project has no deploy block`() {
        val resolver = ProjectConfiguration(mapOf("myproject" to "git@example/r.git"))
        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("myproject"))
    }

    @Test
    fun `deployConfigFor returns Skip for unknown project`() {
        val resolver = ProjectConfiguration(emptyMap())
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

        val resolver = ProjectConfiguration.fromYaml(file)

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

        val resolver = ProjectConfiguration.fromYaml(file)

        val deploy = resolver.deployConfigFor("personal-feed")
        check(deploy is DeployConfig.OpenshiftWatch)
        assertEquals("personal-feed", deploy.namespace)
        assertEquals("personal-feed-app", deploy.deployment)
        assertEquals(10, deploy.timeoutMinutes)
    }

    @Test
    fun `deploy timeout defaults to 20 minutes when omitted`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: sf
                repo: https://github.com/robbert/sf.git
                deploy:
                  type: rest-restart
                  restartUrl: http://localhost:8080/api/restart
                  versionUrl: http://localhost:8080/api/version
                  tokenEnvVar: SF_FACTORY_API_TOKEN
              - name: os
                repo: git@example/os.git
                deploy:
                  type: openshift-watch
                  namespace: ns
                  deployment: app
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        val rest = resolver.deployConfigFor("sf")
        check(rest is DeployConfig.RestRestart)
        assertEquals(ProjectConfiguration.DEFAULT_DEPLOY_TIMEOUT_MINUTES, rest.timeoutMinutes)
        assertEquals(20, rest.timeoutMinutes)
        val os = resolver.deployConfigFor("os")
        check(os is DeployConfig.OpenshiftWatch)
        assertEquals(20, os.timeoutMinutes)
    }

    @Test
    fun `parses argocd fields on openshift-watch deploy`(@TempDir dir: Path) {
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
                  argocdApp: personal-feed
                  argocdNamespace: openshift-gitops
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        val deploy = resolver.deployConfigFor("personal-feed")
        check(deploy is DeployConfig.OpenshiftWatch)
        assertEquals("personal-feed", deploy.argocdApp)
        assertEquals("openshift-gitops", deploy.argocdNamespace)
    }

    @Test
    fun `argocd fields absent stay null`(@TempDir dir: Path) {
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
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        val deploy = resolver.deployConfigFor("personal-feed")
        check(deploy is DeployConfig.OpenshiftWatch)
        assertEquals(null, deploy.argocdApp)
        assertEquals(null, deploy.argocdNamespace)
    }

    @Test
    fun `manualApproveFor defaults to true when not configured`() {
        val resolver = ProjectConfiguration(mapOf("myproject" to "git@example/r.git"))
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

        val resolver = ProjectConfiguration.fromYaml(file)

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

        val resolver = ProjectConfiguration.fromYaml(file)

        assertEquals(DeployConfig.Skip, resolver.deployConfigFor("myapp"))
    }

    @Test
    fun `deployTargetsFor derives one target with empty matchPaths from the legacy single deploy block`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: sf
                repo: https://github.com/robbert/sf.git
                deploy:
                  type: rest-restart
                  restartUrl: http://localhost:8080/api/restart
                  versionUrl: http://localhost:8080/api/version
                  tokenEnvVar: SF_FACTORY_API_TOKEN
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        val targets = resolver.deployTargetsFor("sf")
        assertEquals(1, targets.size)
        assertEquals(emptyList<String>(), targets.single().matchPaths)
        assertEquals(resolver.deployConfigFor("sf"), targets.single().config)
    }

    @Test
    fun `deployTargetsFor for unconfigured project returns a single Skip target, never empty`() {
        val resolver = ProjectConfiguration(mapOf("myapp" to "git@example/r.git"))

        val targets = resolver.deployTargetsFor("myapp")

        assertEquals(1, targets.size)
        assertEquals(DeployConfig.Skip, targets.single().config)
        assertEquals(emptyList<String>(), targets.single().matchPaths)
    }

    @Test
    fun `deploy as a list parses multiple targets with their own matchPaths`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: robberts-assistent
                repo: git@github.com:robbert/robberts-assistent.git
                deploy:
                  - name: backend
                    matchPaths: [robbert_assistent/backend/]
                    type: openshift-watch
                    namespace: robberts-assistent
                    deployment: robberts-assistent-backend
                  - name: frontend
                    matchPaths: [robbert_assistent/frontend/]
                    type: openshift-watch
                    namespace: robberts-assistent
                    deployment: robberts-assistent-frontend
                  - name: notities-apk
                    matchPaths: [notities/]
                    type: skip
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        val targets = resolver.deployTargetsFor("robberts-assistent")
        assertEquals(3, targets.size)
        assertEquals(listOf("backend", "frontend", "notities-apk"), targets.map { it.name })
        assertEquals(listOf("robbert_assistent/backend/"), targets[0].matchPaths)
        check(targets[0].config is DeployConfig.OpenshiftWatch)
        assertEquals("robberts-assistent-backend", (targets[0].config as DeployConfig.OpenshiftWatch).deployment)
        assertEquals(listOf("notities/"), targets[2].matchPaths)
        assertEquals(DeployConfig.Skip, targets[2].config)
        // Backward-compat voor aanroepers die de lijst nog niet kennen: deployConfigFor geeft het
        // eerste doel als representatieve config.
        assertEquals(targets.first().config, resolver.deployConfigFor("robberts-assistent"))
    }

    @Test
    fun `projects yaml example parses and shows the robberts-assistent multi-deployment illustration`() {
        // Regressietest voor het gedocumenteerde voorbeeld in projects.yaml.example (SF-1): moet
        // syntactisch geldig blijven en de 5 aparte onderdelen (backend/frontend/groentetuin/2 APK's)
        // met hun eigen matchPaths opleveren.
        val exampleFile = Path.of("..", "projects.yaml.example")
        assertTrue(exampleFile.toFile().isFile, "verwacht projects.yaml.example op $exampleFile (repo-root)")

        val resolver = ProjectConfiguration.fromYaml(exampleFile)

        val targets = resolver.deployTargetsFor("robberts-assistent")
        assertEquals(
            listOf("backend", "frontend", "groentetuin-frontend", "notities-apk", "wind-apk"),
            targets.map { it.name },
        )
        assertEquals(listOf("robbert_assistent/backend/"), targets[0].matchPaths)
        assertEquals(listOf("notities/"), targets[3].matchPaths)
        assertEquals(DeployConfig.Skip, targets[3].config)
        assertEquals(DeployConfig.Skip, targets[4].config)
    }

    @Test
    fun `deploy list item with unknown type is skipped but the rest of the list still parses`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: myproj
                repo: git@example/r.git
                deploy:
                  - name: bogus
                    matchPaths: [bogus/]
                    type: not-a-real-type
                  - name: backend
                    matchPaths: [backend/]
                    type: openshift-watch
                    namespace: ns
                    deployment: dep
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        val targets = resolver.deployTargetsFor("myproj")
        assertEquals(listOf("backend"), targets.map { it.name })
    }
}
