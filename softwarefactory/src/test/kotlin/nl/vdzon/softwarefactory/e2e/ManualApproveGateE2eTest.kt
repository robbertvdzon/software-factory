package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals

/**
 * End-to-end dekking van de handmatige goedkeur-poort (SF-192), die in [E2eTestBase]/[E2eTestConfig]
 * bewust uit staat en daarom door geen van de andere e2e-tests geraakt wordt. Boot daarom een eigen
 * context met [ManualApproveE2eTestConfig] (poort AAN voor `sample`), maar deelt de buitenrand-dubbels
 * (mock-YouTrack, scripted runtime, Postgres) met de overige e2e-tests.
 *
 * Bewijst de twee poort-eigenschappen die de spec belooft:
 *  - de poort **wacht altijd op een mens**, óók met `Auto-approve=on` (de AI-subtaken lopen vanzelf,
 *    maar de keten stalt op `manual-approve-needed`);
 *  - **afkeuren reset de hele story-keten** (alle subtaken terug naar todo, eerste subtaak weer op
 *    `start`), zodat de developer opnieuw draait.
 *
 * Niet hier gedekt (apart unit-getest): het `@factory:command`-pad in `ManualCommandService` dat de
 * afkeurreden in het gemarkeerde blok in de story-description zet. Deze test stuurt de poort-overgang
 * rechtstreeks via dezelfde `Subtask Phase` die dat commando uiteindelijk zet, en dekt zo het
 * orchestrator-gedrag (gate + reset) test-only af.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ManualApproveE2eTestConfig::class)
class ManualApproveGateE2eTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    private val youtrack get() = E2eTestConfig.FAKE_YOUTRACK
    private val state get() = youtrack.state
    private val runtime get() = E2eTestConfig.TEST_AGENT_RUNTIME

    @BeforeEach
    fun resetSharedState() {
        state.reset()
        runtime.reset()
    }

    @Test
    fun `manual-approve poort wacht ook bij auto-approve en reset bij afkeuren de keten`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = FactoryUiDriver(rest, "http://localhost:$port").login()
        // De keten loopt autonoom (auto-approve aan) via development + de afgedwongen documentation
        // naar de poort; ruime timeout in een koude test-JVM.
        val await = AwaitDsl(youtrack, Duration.ofSeconds(120))
        val story = "${state.projectKey}-300"

        // Auto-approve AAN: alle AI-gates gaan vanzelf — de poort moet desondanks wachten (SF-192).
        state.createIssue(summary = "E2E story $story", key = story)
        state.setEnumField(story, "Repo", "sample")
        state.setEnumField(story, "AI-supplier", "mock")
        state.setEnumField(story, "Auto-approve", "on")
        state.setEnumField(story, "Story Phase", "start")

        // Subtaken (development + afgedwongen documentation/manual-approve/merge/deploy) materialiseren;
        // wacht expliciet tot de poort-subtaak bestaat (ze worden in één keer aangemaakt).
        Awaitility.await("manual-approve-subtaak aangemaakt onder $story")
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(100))
            .until { state.childrenOf(story).any { it.customFields["Subtask Type"]?.path("name")?.asText(null) == "manual-approve" } }
        val gate = manualApproveChild(story)

        // De keten loopt autonoom tot de poort en blijft daar wachten, ondanks auto-approve.
        await.awaitSubtaskPhase(gate.key, "manual-approve-needed")
        assertEquals(1, runtime.dispatched.count { it.second == AgentRole.DEVELOPER }, "developer draait 1x vóór de poort")

        // Afkeuren via de poort → reset van de hele keten → de developer draait opnieuw.
        ui.setSubtaskPhase(gate.key, "manually-not-approved")
        awaitDispatchCount(AgentRole.DEVELOPER, 2)
    }

    /** De factory-afgedwongen `manual-approve`-poort onder [storyKey]. */
    private fun manualApproveChild(storyKey: String) =
        state.childrenOf(storyKey).first {
            it.customFields["Subtask Type"]?.path("name")?.asText(null) == "manual-approve"
        }

    private fun awaitDispatchCount(role: AgentRole, count: Int) {
        Awaitility.await("$role ${count}x gedispatcht")
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(100))
            .until { runtime.dispatched.count { it.second == role } >= count }
    }
}
