package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceFactory
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.nio.file.Files
import java.time.Duration

/**
 * Basis voor de end-to-end pipeline-tests. Boot de **echte** Spring-app met alleen de buitenranden
 * vervangen ([E2eTestConfig]: Postgres-tracker-teststate, scripted agent-runtime, lokale git-remote).
 *
 * De tracker-teststate en de scripted runtime zijn gedeelde statics over de test-JVM (één
 * Spring-context), dus elke test reset ze in [resetSharedState]. Gebruik per test een **unieke
 * story-key** (vandaar de helpers met expliciete keys), zodat workspaces op schijf en story-runs in
 * de DB niet tussen tests vermengen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eTestConfig::class)
abstract class E2eTestBase {

    protected val state get() = E2eTestConfig.TRACKER_STATE
    protected val runtime get() = E2eTestConfig.TEST_AGENT_RUNTIME

    @BeforeEach
    fun resetSharedState() {
        state.reset()
        runtime.reset()
        E2eTestConfig.FAKE_GITHUB.reset()
        // Eénmalig per test-JVM: workspaces van VORIGE runs verwijzen naar een `origin` in een
        // inmiddels verwijderde temp-remote; hergebruik laat elke git-stap (en dus de hele
        // pipeline) stranden. Niet per test: workspaces van eerdere tests in deze run worden
        // door de asynchrone poller nog verwerkt (hun story-runs staan open in de DB) en
        // verwijderen-onder-de-poller-vandaan laat hele polls falen.
        if (staleWorkspacesCleaned.compareAndSet(false, true)) {
            deleteStaleStoryWorkspaces()
        }
    }

    private fun deleteStaleStoryWorkspaces() {
        val storiesRoot = AgentWorkspaceFactory.projectRoot().resolve("work").resolve("stories")
        if (!Files.isDirectory(storiesRoot)) return
        Files.list(storiesRoot).use { entries ->
            entries
                .filter { it.fileName.toString().startsWith("${state.projectKey}-") }
                .forEach { dir ->
                    Files.walk(dir).use { walk ->
                        walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
        }
    }

    /** UI-driver die direct in de tracker-teststate schrijft (geen HTTP-calls meer). */
    protected fun loginUi(): FactoryUiDriver = FactoryUiDriver(state)

    /** Awaitility-helper op de tracker-teststate. */
    protected fun awaiter(timeout: Duration = Duration.ofSeconds(60)): AwaitDsl = AwaitDsl(state, timeout)

    /**
     * Aantal dispatches van [role] voor déze story. Bewust story-gebonden (de runtime registreert
     * de serializationKey = parent-story-key): een vorige test die z'n pipeline nog heeft lopen
     * (bv. na een timeout) kan ná de reset nog dispatches voor zíjn story loggen — een globale
     * telling raakt daardoor besmet en flaket ("developer 3x i.p.v. 2x").
     */
    protected fun dispatchCount(storyKey: String, role: AgentRole): Int =
        runtime.dispatched.count { it.first == storyKey && it.second == role }

    /** Wacht tot [role] voor [storyKey] minstens [count]× is gedispatcht (voor reject-loops). */
    protected fun awaitDispatchCount(storyKey: String, role: AgentRole, count: Int, timeout: Duration = Duration.ofSeconds(60)) {
        Awaitility.await("$role ${count}x gedispatcht voor $storyKey")
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(100))
            .until { dispatchCount(storyKey, role) >= count }
    }

    /**
     * De door de planner geplande (AI-)subtaak onder [storyKey]. Filtert de factory-afgedwongen
     * afsluit-subtaken (documentation/merge/deploy/manual-approve) eruit, zodat de per-flow-tests die
     * met precies één geplande subtaak werken die met `.single()` terugvinden.
     */
    protected fun plannedChild(storyKey: String) =
        state.childrenOf(storyKey).single { it.fields.subtaskType !in ENFORCED_SUBTASK_TYPES }

    /** Maakt een verse story (supplier=mock, Story Phase=start); auto-approve aan of uit. */
    protected fun createStory(key: String, autoApprove: Boolean = true) {
        state.createIssue(summary = "E2E story $key", key = key)
        state.setEnumField(key, "Repo", "sample")
        state.setEnumField(key, "AI-supplier", "mock")
        state.setEnumField(key, "Auto-approve", if (autoApprove) "on" else "off")
        // Geen label meer: de story wordt opgepakt zodra de Story Phase op `start` staat.
        state.setEnumField(key, "Story Phase", "start")
    }

    /**
     * Drijft refine→plan met **auto-approve aan** en een refiner-vraag (de default): wacht op de
     * vraag, beantwoordt 'm via de UI, en wacht tot de subtaken zijn aangemaakt + planning-approved.
     * Daarna pakt de test de eerste subtaak op via `startDeveloping`.
     */
    protected fun refineAndPlan(ui: FactoryUiDriver, await: AwaitDsl, storyKey: String, expectedSubtasks: Int) {
        await.awaitStoryPhase(storyKey, "refined-with-questions")
        ui.answerStory(storyKey, "ja, ga door")
        await.awaitSubtasksCreated(storyKey, expectedSubtasks)
        await.awaitStoryPhase(storyKey, "planning-approved")
    }

    /**
     * Refine→plan met **auto-approve uit** en zonder refiner-vraag (`refinerAsksQuestion=false`):
     * keurt de story-gates (`refined`/`planned`) handmatig goed via de UI tot de subtaken er staan.
     * Voor de reject-tests, die de approve/reject-gate zelf willen sturen.
     */
    protected fun approveRefineAndPlan(ui: FactoryUiDriver, await: AwaitDsl, storyKey: String, expectedSubtasks: Int) {
        await.awaitStoryPhase(storyKey, "refined")
        ui.setStoryPhase(storyKey, "refined-approved")
        await.awaitStoryPhase(storyKey, "planned")
        ui.setStoryPhase(storyKey, "planning-approved")
        await.awaitSubtasksCreated(storyKey, expectedSubtasks)
    }

    companion object {
        /** Factory-afgedwongen afsluit-subtaken (SF-154/SF-213/SF-192), niet door de planner geleverd. */
        private val ENFORCED_SUBTASK_TYPES = setOf("documentation", "merge", "deploy", "manual-approve")

        /** Eénmalig per test-JVM (elke testklasse forkt z'n eigen JVM, zie surefire-config). */
        private val staleWorkspacesCleaned = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
