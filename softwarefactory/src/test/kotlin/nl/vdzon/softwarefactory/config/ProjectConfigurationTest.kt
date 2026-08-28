package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class ProjectConfigurationTest {

    @Test
    fun `matches case-insensitively and trims whitespace`() {
        val resolver = ProjectConfiguration(mapOf("Personal-Feed" to "git@example/pf.git"))

        assertEquals("git@example/pf.git", resolver.repoFor("personal-feed"))
        assertEquals("git@example/pf.git", resolver.repoFor("  PERSONAL-FEED  "))
        assertEquals("git@example/pf.git", resolver.repoFor("Personal-Feed"))
    }

    @Test
    fun `resolve uses the config repo for a known name`() {
        val resolver = ProjectConfiguration(mapOf("personal-feed" to "git@example/pf.git"))

        assertEquals("git@example/pf.git", resolver.resolve("personal-feed"))
        assertEquals("git@example/pf.git", resolver.resolve("  PERSONAL-FEED "))
    }

    @Test
    fun `resolve treats an unknown value as a literal repo url`() {
        val resolver = ProjectConfiguration(mapOf("pf" to "git@example/pf.git"))

        assertEquals("git@github.com:robbert/direct.git", resolver.resolve("git@github.com:robbert/direct.git"))
        assertEquals("https://host/x.git", resolver.resolve("  https://host/x.git  "))
    }

    @Test
    fun `resolve returns null for blank input`() {
        val resolver = ProjectConfiguration(mapOf("pf" to "git@example/pf.git"))

        assertNull(resolver.resolve(null))
        assertNull(resolver.resolve("   "))
    }

    @Test
    fun `returns null for blank or unknown names`() {
        val resolver = ProjectConfiguration(mapOf("pf" to "git@example/pf.git"))

        assertNull(resolver.repoFor(null))
        assertNull(resolver.repoFor(""))
        assertNull(resolver.repoFor("   "))
        assertNull(resolver.repoFor("unknown"))
    }

    @Test
    fun `parses a yaml file with multiple projects`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: personal-feed
                repo: git@github.com:robbert/personal-feed.git
              - name: SoftwareFactory
                repo: https://github.com/robbert/softwarefactory.git
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertEquals("git@github.com:robbert/personal-feed.git", resolver.repoFor("personal-feed"))
        assertEquals("https://github.com/robbert/softwarefactory.git", resolver.repoFor("softwarefactory"))
        assertEquals(setOf("personal-feed", "softwarefactory"), resolver.configuredNames())
        // projectNames behoudt de originele schrijfwijze (voor keuzelijsten in de UI).
        assertEquals(listOf("personal-feed", "SoftwareFactory"), resolver.projectNames())
    }

    @Test
    fun `missing file yields an empty resolver`(@TempDir dir: Path) {
        val resolver = ProjectConfiguration.fromYaml(dir.resolve("does-not-exist.yaml"))

        assertNull(resolver.repoFor("anything"))
        assertEquals(emptySet<String>(), resolver.configuredNames())
    }

    @Test
    fun `malformed file yields an empty resolver instead of crashing`(@TempDir dir: Path) {
        val file = dir.resolve("bad.yaml")
        Files.writeString(file, "this is: not the expected shape")

        val resolver = ProjectConfiguration.fromYaml(file)

        assertNull(resolver.repoFor("anything"))
    }

    @Test
    fun `entries missing name or repo are skipped`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: ok
                repo: git@example/ok.git
              - name: no-repo
              - repo: git@example/no-name.git
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertEquals("git@example/ok.git", resolver.repoFor("ok"))
        assertNull(resolver.repoFor("no-repo"))
        assertEquals(setOf("ok"), resolver.configuredNames())
    }

    @Test
    fun `a yaml type-instantiation tag is refused and yields an empty resolver`(@TempDir dir: Path) {
        // SafeConstructor mag geen willekeurige Java-typen instantiëren via een expliciete YAML-tag.
        // Een ScriptEngineManager-tag is de klassieke SnakeYAML-deserialisatie-gadget; SafeConstructor
        // gooit hierop, fromYaml vangt dat en levert (net als bij ander malformed YAML) een lege resolver.
        val file = dir.resolve("evil.yaml")
        file.writeText(
            """
            projects: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL ["http://127.0.0.1/"]]]]
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertNull(resolver.repoFor("anything"))
        assertEquals(emptySet<String>(), resolver.configuredNames())
    }

    @Test
    fun `a non-object project entry yields an empty resolver instead of crashing`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - just-a-string
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertNull(resolver.repoFor("anything"))
        assertEquals(emptySet<String>(), resolver.configuredNames())
    }

    @Test
    fun `parses a releaseCleanup block`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: personal-feed
                repo: git@github.com:robbert/personal-feed.git
                releaseCleanup:
                  releases:
                    - prefix: "apk-"
                      keep: 3
                    - prefix: "reader-apk-"
                      keep: 3
                  packages:
                    - name: personal-news-feed-backend
                      keep: 15
                  protectedManifestPaths:
                    - deploy/base/kustomization.yaml
                  alwaysKeepTags: [main, latest]
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)
        val config = resolver.releaseCleanupFor("personal-feed")

        assertEquals(
            listOf(ReleasePrefixRule("apk-", 3), ReleasePrefixRule("reader-apk-", 3)),
            config?.releases,
        )
        assertEquals(listOf(PackageCleanupRule("personal-news-feed-backend", 15)), config?.packages)
        assertEquals(listOf("deploy/base/kustomization.yaml"), config?.protectedManifestPaths)
        assertEquals(setOf("main", "latest"), config?.alwaysKeepTags)
    }

    @Test
    fun `missing releaseCleanup block yields null`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: softwarefactory
                repo: https://github.com/robbert/softwarefactory.git
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertNull(resolver.releaseCleanupFor("softwarefactory"))
        assertNull(resolver.releaseCleanupFor(null))
        assertNull(resolver.releaseCleanupFor("unknown"))
    }

    @Test
    fun `releaseCleanup items missing required fields are skipped instead of crashing`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: personal-feed
                repo: git@github.com:robbert/personal-feed.git
                releaseCleanup:
                  releases:
                    - prefix: "apk-"
                      keep: 3
                    - keep: 5
                    - prefix: "no-keep-"
                  packages:
                    - name: ok-package
                      keep: 10
                    - keep: 2
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)
        val config = resolver.releaseCleanupFor("personal-feed")

        assertEquals(listOf(ReleasePrefixRule("apk-", 3)), config?.releases)
        assertEquals(listOf(PackageCleanupRule("ok-package", 10)), config?.packages)
        // Default als alwaysKeepTags niet is opgegeven.
        assertEquals(setOf("main"), config?.alwaysKeepTags)
    }

    @Test
    fun `parses an apkPackages block`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: robberts-assistent
                repo: git@github.com:robbert/robberts-assistent.git
                apkPackages:
                  - tagPrefix: wind-latest
                    packageName: nl.vdzon.wind
                  - tagPrefix: notities-latest
                    packageName: nl.vdzon.notities
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertEquals(
            listOf(
                ApkPackageMapping("wind-latest", "nl.vdzon.wind"),
                ApkPackageMapping("notities-latest", "nl.vdzon.notities"),
            ),
            resolver.apkPackagesFor("robberts-assistent"),
        )
    }

    @Test
    fun `apkPackages items keep an optional workflowName for per-app sync-status scoping`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: robberts-assistent
                repo: git@github.com:robbert/robberts-assistent.git
                apkPackages:
                  - tagPrefix: wind-latest
                    packageName: nl.vdzon.wind
                    workflowName: Build Wind APK
                  - tagPrefix: notities-latest
                    packageName: nl.vdzon.notities
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)
        val mappings = resolver.apkPackagesFor("robberts-assistent")

        assertEquals("Build Wind APK", mappings.single { it.tagPrefix == "wind-latest" }.workflowName)
        assertNull(mappings.single { it.tagPrefix == "notities-latest" }.workflowName)
    }

    @Test
    fun `missing apkPackages block yields an empty list`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: softwarefactory
                repo: https://github.com/robbert/softwarefactory.git
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertEquals(emptyList<ApkPackageMapping>(), resolver.apkPackagesFor("softwarefactory"))
        assertEquals(emptyList<ApkPackageMapping>(), resolver.apkPackagesFor(null))
        assertEquals(emptyList<ApkPackageMapping>(), resolver.apkPackagesFor("unknown"))
    }

    @Test
    fun `apkPackages items missing required fields are skipped instead of crashing`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: robberts-assistent
                repo: git@github.com:robbert/robberts-assistent.git
                apkPackages:
                  - tagPrefix: wind-latest
                    packageName: nl.vdzon.wind
                  - tagPrefix: notities-latest
                  - packageName: nl.vdzon.groentetuin
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertEquals(listOf(ApkPackageMapping("wind-latest", "nl.vdzon.wind")), resolver.apkPackagesFor("robberts-assistent"))
    }

    @Test
    fun `requiredChecksFor matcht ook op een https-URL terwijl projects-yaml de ssh-vorm gebruikt`(@TempDir dir: Path) {
        // Reproduceert de Product Factory-dispatch: die stuurt altijd de publieke HTTPS-URL
        // (targetRepositoryUrl), ongeacht welke vorm projects.yaml zelf gebruikt.
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: hkh-autopilot
                repo: git@github.com:robbertvdzon/hkh-autopilot.git
                merge:
                  requiredChecks: [Repository verification]
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        assertEquals(setOf("Repository verification"), resolver.requiredChecksFor("hkh-autopilot"))
        assertEquals(
            setOf("Repository verification"),
            resolver.requiredChecksFor("https://github.com/robbertvdzon/hkh-autopilot.git"),
        )
        assertEquals(
            setOf("Repository verification"),
            resolver.requiredChecksForRepo("https://github.com/robbertvdzon/hkh-autopilot.git"),
        )
    }

    @Test
    fun `deployTargetsFor levert de echte config i-p-v-Skip als het Repo-veld een andere URL-vorm is`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: hkh-autopilot
                repo: git@github.com:robbertvdzon/hkh-autopilot.git
                deploy:
                  type: openshift-watch
                  namespace: hkh-autopilot
                  deployment: backend
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)

        val target = resolver.deployTargetsFor("https://github.com/robbertvdzon/hkh-autopilot.git").single()
        val config = target.config
        assertTrue(config is DeployConfig.OpenshiftWatch, "verwacht de echte OpenshiftWatch-config, geen Skip")
        assertEquals("backend", (config as DeployConfig.OpenshiftWatch).deployment)
    }

    @Test
    fun `URL-matching negeert https-vs-ssh, -git-suffix, trailing slash en hoofdlettergebruik`() {
        val resolver = ProjectConfiguration(mapOf("hkh-autopilot" to "git@github.com:robbertvdzon/hkh-autopilot.git"))

        assertEquals("git@github.com:robbertvdzon/hkh-autopilot.git", resolver.repoFor("HTTPS://GitHub.com/RobbertVdzon/hkh-autopilot.GIT"))
        assertEquals("git@github.com:robbertvdzon/hkh-autopilot.git", resolver.repoFor("https://github.com/robbertvdzon/hkh-autopilot"))
        assertEquals("git@github.com:robbertvdzon/hkh-autopilot.git", resolver.repoFor("https://github.com/robbertvdzon/hkh-autopilot.git/"))
    }

    @Test
    fun `URL-matching levert geen false positive voor een andere repo`() {
        val resolver = ProjectConfiguration(mapOf("hkh-autopilot" to "git@github.com:robbertvdzon/hkh-autopilot.git"))

        assertNull(resolver.repoFor("https://github.com/robbertvdzon/hkh.git"))
        assertNull(resolver.repoFor("https://github.com/someoneelse/hkh-autopilot.git"))
    }

    @Test
    fun `projectNameFor levert de originele schrijfwijze terug voor een naam of een matchende URL`() {
        val resolver = ProjectConfiguration(mapOf("HKH-Autopilot" to "git@github.com:robbertvdzon/hkh-autopilot.git"))

        assertEquals("HKH-Autopilot", resolver.projectNameFor("hkh-autopilot"))
        assertEquals("HKH-Autopilot", resolver.projectNameFor("HKH-AUTOPILOT"))
        assertEquals("HKH-Autopilot", resolver.projectNameFor("https://github.com/robbertvdzon/hkh-autopilot.git"))
        assertEquals("HKH-Autopilot", resolver.projectNameFor("git@github.com:robbertvdzon/hkh-autopilot.git"))
    }

    @Test
    fun `projectNameFor levert null voor onbekende namen of repos`() {
        val resolver = ProjectConfiguration(mapOf("hkh-autopilot" to "git@github.com:robbertvdzon/hkh-autopilot.git"))

        assertNull(resolver.projectNameFor(null))
        assertNull(resolver.projectNameFor(""))
        assertNull(resolver.projectNameFor("onbekend"))
        assertNull(resolver.projectNameFor("https://github.com/robbertvdzon/een-ander-project.git"))
    }

    @Test
    fun `liveComponents items keep an optional workflowName for per-component sync-status scoping`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: robberts-assistent
                repo: git@github.com:robbert/robberts-assistent.git
                liveComponents:
                  - label: backend
                    namespace: robberts-assistent
                    deployment: robberts-assistent-backend
                    workflowName: Build robberts-assistent-backend image
                  - label: frontend
                    namespace: robberts-assistent
                    deployment: robberts-assistent-frontend
            """.trimIndent(),
        )

        val resolver = ProjectConfiguration.fromYaml(file)
        val components = resolver.liveComponentsFor("robberts-assistent")

        assertEquals("Build robberts-assistent-backend image", components.single { it.label == "backend" }.workflowName)
        assertNull(components.single { it.label == "frontend" }.workflowName)
    }
}
