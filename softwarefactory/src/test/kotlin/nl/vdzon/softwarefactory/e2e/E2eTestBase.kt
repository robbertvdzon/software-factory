package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.time.Duration

/**
 * Basis voor de end-to-end pipeline-tests. Boot de **echte** Spring-app met alleen de buitenranden
 * vervangen ([E2eTestConfig]: YouTrack-HTTP-mock, scripted agent-runtime, lokale git-remote).
 *
 * De mock-YouTrack-state en de scripted runtime zijn gedeelde statics over de test-JVM (één
 * Spring-context), dus elke test reset ze in [resetSharedState]. Gebruik per test een **unieke
 * story-key** (vandaar de helpers met expliciete keys), zodat workspaces op schijf en story-runs in
 * de DB niet tussen tests vermengen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eTestConfig::class)
abstract class E2eTestBase {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var rest: TestRestTemplate

    protected val youtrack get() = E2eTestConfig.FAKE_YOUTRACK
    protected val state get() = youtrack.state
    protected val runtime get() = E2eTestConfig.TEST_AGENT_RUNTIME

    @BeforeEach
    fun resetSharedState() {
        state.reset()
        runtime.reset()
    }

    /** Ingelogde UI-driver tegen de random server-port. */
    protected fun loginUi(): FactoryUiDriver = FactoryUiDriver(rest, "http://localhost:$port").login()

    /** Awaitility-helper op de mock-YouTrack-state. */
    protected fun awaiter(timeout: Duration = Duration.ofSeconds(60)): AwaitDsl = AwaitDsl(youtrack, timeout)

    /** Wacht tot [role] minstens [count]× is gedispatcht (voor reject-loops). */
    protected fun awaitDispatchCount(role: AgentRole, count: Int, timeout: Duration = Duration.ofSeconds(60)) {
        Awaitility.await("$role ${count}x gedispatcht")
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(100))
            .until { runtime.dispatched.count { it.second == role } >= count }
    }

    /** Maakt een verse story (supplier=mock, label `ai-refinement`); auto-approve aan of uit. */
    protected fun createStory(key: String, autoApprove: Boolean = true) {
        state.createIssue(summary = "E2E story $key", key = key)
        state.setEnumField(key, "Repo", "sample")
        state.setEnumField(key, "AI-supplier", "mock")
        state.setEnumField(key, "Auto-approve", if (autoApprove) "on" else "off")
        state.issue(key)!!.tags += "ai-refinement"
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
}
