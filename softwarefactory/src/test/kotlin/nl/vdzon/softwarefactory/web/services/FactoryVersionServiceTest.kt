package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.dashboard.services.FactoryVersionService
import kotlin.test.Test
import kotlin.test.assertEquals

class FactoryVersionServiceTest {
    @Test
    fun `leest in een checkout de versie uit git`() {
        // De testrun draait vanuit de module-map van een echte checkout; de sha is dan bekend.
        val info = FactoryVersionService(environment = emptyMap()).info()

        assert(info.commitShort != "onbekend") { "verwachtte een git-sha, kreeg ${info.commitShort}" }
    }

    @Test
    fun `build-info wordt afgekapt tot een korte sha`() {
        val environment = mapOf(
            "SF_BUILD_COMMIT" to "0123456789abcdef0123456789abcdef01234567",
            "SF_BUILD_BRANCH" to "main",
            "SF_BUILD_COMMIT_SUBJECT" to "feat: iets",
            "SF_BUILD_COMMIT_DATE" to "2026-09-12T10:00:00Z",
        )

        // Zonder .git valt de service op deze waarden terug; hier toetsen we alleen de vertaling.
        val service = FactoryVersionService(environment)
        val fromBuild = service.javaClass.getDeclaredMethod("captureFromBuildInfo").apply { isAccessible = true }
            .invoke(service) as nl.vdzon.softwarefactory.dashboard.models.FactoryVersionInfo

        assertEquals("0123456", fromBuild.commitShort)
        assertEquals("main", fromBuild.branch)
        assertEquals("feat: iets", fromBuild.commitSubject)
        assertEquals(false, fromBuild.dirty)
    }
}
