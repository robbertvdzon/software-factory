package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import nl.vdzon.softwarefactory.maintenance.MaintenanceCleanupApi
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import nl.vdzon.softwarefactory.maintenance.types.CleanupRunStatus
import nl.vdzon.softwarefactory.runtime.services.CleanupRunNowService
import nl.vdzon.softwarefactory.runtime.services.CleanupRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * De handmatige route van de "Nu draaien"-knop (SF-1929): starten per soort, "alles draaien", en de
 * dubbel-draaien-bescherming — zowel handmatig+handmatig als handmatig+scheduler.
 *
 * Handgeschreven fakes (deze repo heeft geen mock-framework). De executor is bewust injecteerbaar:
 * met een directe executor is de ronde klaar zodra `runNow` terug is, met een wachtrij-executor
 * blijft de bewaking vastzitten en is de "draait al"-situatie deterministisch na te bootsen.
 */
class CleanupRunNowServiceTest {

    private val guard = CleanupRunGuard.inMemory()

    @Test
    fun `een verzoek per soort start dezelfde ronde als de scheduler, met trigger manual`() {
        val runner = FakeRunner(CleanupKinds.AGENT_EVENTS)
        val service = service(listOf(runner))

        val outcome = service.runNow(CleanupKinds.AGENT_EVENTS)

        assertEquals(CleanupRunStatus.STARTED, outcome.status)
        assertEquals(listOf(CleanupTriggers.MANUAL), runner.triggers)
        // Bewaking losgelaten na afloop: een tweede klik mag daarna gewoon weer starten.
        assertEquals(emptyList<String>(), guard.runningKinds())
    }

    @Test
    fun `de github-cleanup loopt via de maintenance-poort, niet via een tweede implementatie`() {
        val gitHub = FakeMaintenanceCleanupApi()
        val service = service(emptyList(), gitHub)

        val outcome = service.runNow(CleanupKinds.GITHUB_RELEASES)

        assertEquals(CleanupRunStatus.STARTED, outcome.status)
        assertEquals(listOf(CleanupTriggers.MANUAL), gitHub.triggers)
    }

    @Test
    fun `alles draaien start elke vrije soort`() {
        val runners = factoryWideRunners()
        val gitHub = FakeMaintenanceCleanupApi()
        val service = service(runners, gitHub)

        val outcome = service.runNow(CleanupKinds.ALL_KINDS)

        assertEquals(CleanupRunStatus.STARTED, outcome.status)
        assertEquals(CleanupKinds.ALL.toSet(), outcome.perKind.keys)
        assertTrue(outcome.perKind.values.all { it == CleanupRunStatus.STARTED }, "niet alles gestart: ${outcome.perKind}")
        assertTrue(runners.all { it.triggers == listOf(CleanupTriggers.MANUAL) }, "niet elke opruimer draaide")
        assertEquals(listOf(CleanupTriggers.MANUAL), gitHub.triggers)
    }

    @Test
    fun `alles draaien slaat de soorten over die al lopen en meldt dat per soort`() {
        val runners = factoryWideRunners()
        val service = service(runners, FakeMaintenanceCleanupApi())
        guard.tryStart(CleanupKinds.AGENT_RUNS)

        val outcome = service.runNow(CleanupKinds.ALL_KINDS)

        assertEquals(CleanupRunStatus.STARTED, outcome.status)
        assertEquals(CleanupRunStatus.ALREADY_RUNNING, outcome.perKind[CleanupKinds.AGENT_RUNS])
        assertEquals(CleanupRunStatus.STARTED, outcome.perKind[CleanupKinds.AGENT_EVENTS])
        assertEquals(emptyList<String>(), runners.single { it.cleanupKind == CleanupKinds.AGENT_RUNS }.triggers)
    }

    @Test
    fun `twee keer snel klikken start maar één ronde`() {
        val queue = mutableListOf<Runnable>()
        val runner = FakeRunner(CleanupKinds.WORKSPACES)
        val service = service(listOf(runner), executor = Executor { queue += it })

        val first = service.runNow(CleanupKinds.WORKSPACES)
        val second = service.runNow(CleanupKinds.WORKSPACES)

        assertEquals(CleanupRunStatus.STARTED, first.status)
        assertEquals(CleanupRunStatus.ALREADY_RUNNING, second.status)
        assertEquals(1, queue.size)
        // Zodra de eerste ronde klaar is, is de bewaking weer vrij.
        queue.single().run()
        assertEquals(emptyList<String>(), guard.runningKinds())
        assertEquals(CleanupRunStatus.STARTED, service.runNow(CleanupKinds.WORKSPACES).status)
    }

    @Test
    fun `draait de scheduler die soort net, dan start de knop geen tweede ronde`() {
        val runner = FakeRunner(CleanupKinds.COMPLETION_PAYLOADS)
        val service = service(listOf(runner))
        // De scheduler houdt dezelfde in-memory bewaking vast tijdens zijn tick.
        guard.tryStart(CleanupKinds.COMPLETION_PAYLOADS)

        val outcome = service.runNow(CleanupKinds.COMPLETION_PAYLOADS)

        assertEquals(CleanupRunStatus.ALREADY_RUNNING, outcome.status)
        assertEquals(emptyList<String>(), runner.triggers)
    }

    @Test
    fun `een uitgezette opruimer start niet en meldt dat`() {
        val runner = FakeRunner(CleanupKinds.AGENT_EVENTS, enabled = false)
        val service = service(listOf(runner))

        val outcome = service.runNow(CleanupKinds.AGENT_EVENTS)

        assertEquals(CleanupRunStatus.DISABLED, outcome.status)
        assertEquals(emptyList<String>(), runner.triggers)
        assertEquals(emptyList<String>(), guard.runningKinds())
    }

    @Test
    fun `een onbekende soort levert unknown_kind op`() {
        val outcome = service(factoryWideRunners()).runNow("bestaat-niet")

        assertEquals(CleanupRunStatus.UNKNOWN_KIND, outcome.status)
        assertEquals("unknown_kind", outcome.status.wireValue)
    }

    @Test
    fun `een falende ronde laat de bewaking niet hangen`() {
        val runner = FakeRunner(CleanupKinds.AGENT_RUNS, fails = true)
        val service = service(listOf(runner))

        val outcome = service.runNow(CleanupKinds.AGENT_RUNS)

        assertEquals(CleanupRunStatus.STARTED, outcome.status)
        assertEquals(emptyList<String>(), guard.runningKinds())
    }

    // --- wiring ------------------------------------------------------------------------------

    private fun service(
        runners: List<CleanupRunner>,
        maintenanceCleanupApi: MaintenanceCleanupApi = FakeMaintenanceCleanupApi(),
        executor: Executor = Executor { it.run() },
    ) = CleanupRunNowService(runners, maintenanceCleanupApi, guard, executor)

    private fun factoryWideRunners(): List<FakeRunner> =
        listOf(
            CleanupKinds.AGENT_EVENTS,
            CleanupKinds.AGENT_RUNS,
            CleanupKinds.COMPLETION_PAYLOADS,
            CleanupKinds.WORKSPACES,
        ).map { FakeRunner(it) }

    private class FakeRunner(
        override val cleanupKind: String,
        private val enabled: Boolean = true,
        private val fails: Boolean = false,
    ) : CleanupRunner {
        val triggers = mutableListOf<String>()

        override fun cleanupEnabled(): Boolean = enabled

        override fun runCleanupRoundLocked(trigger: String) {
            triggers += trigger
            if (fails) error("opruimen mislukt")
        }
    }

    private class FakeMaintenanceCleanupApi : MaintenanceCleanupApi {
        val triggers = mutableListOf<String>()

        override fun runCleanupRoundLocked(trigger: String) {
            triggers += trigger
        }
    }
}
