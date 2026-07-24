package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals

/**
 * End-to-end dekking van de handmatige goedkeur-poort (SF-192), die in [E2eTestBase]/[E2eTestConfig]
 * bewust uit staat en daarom door geen van de andere e2e-tests geraakt wordt. Boot daarom een eigen
 * context met [ManualApproveE2eTestConfig] (poort AAN voor `sample`), maar deelt de buitenrand-dubbels
 * (Postgres-tracker-teststate, scripted runtime) met de overige e2e-tests.
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

    private val state get() = E2eTestConfig.TRACKER_STATE
    private val runtime get() = E2eTestConfig.TEST_AGENT_RUNTIME

    @BeforeEach
    fun resetSharedState() {
        state.reset()
        runtime.reset()
        E2eTestConfig.FAKE_GITHUB.reset()
    }

    @Test
    fun `manual-approve poort wacht ook bij auto-approve en reset bij afkeuren de keten`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = FactoryUiDriver(state)
        // De keten loopt autonoom (auto-approve aan) via development + de afgedwongen documentation
        // naar de poort; ruime timeout in een koude test-JVM.
        val await = AwaitDsl(state, Duration.ofSeconds(120))
        val story = "${state.projectKey}-300"

        // Auto-approve AAN: alle AI-gates gaan vanzelf — de poort moet desondanks wachten (SF-192).
        state.createIssue(summary = "E2E story $story", key = story)
        state.setEnumField(story, "Repo", "sample")
        state.setEnumField(story, "AI-supplier", "mock")
        state.setEnumField(story, "ApprovalMode", "alleen-manual-poort")
        state.setEnumField(story, "Story Phase", "start")

        // Subtaken (development + afgedwongen documentation/manual-approve/merge/deploy) materialiseren;
        // wacht expliciet tot de poort-subtaak bestaat (ze worden in één keer aangemaakt).
        Awaitility.await("manual-approve-subtaak aangemaakt onder $story")
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(100))
            .until { state.childrenOf(story).any { it.fields.subtaskType == "manual-approve" } }
        val gate = manualApproveChild(story)

        // De keten loopt autonoom tot de poort en blijft daar wachten, ondanks auto-approve.
        await.awaitSubtaskPhase(gate.key, "manual-approve-needed")
        assertEquals(1, dispatchCount(story, AgentRole.DEVELOPER), "developer draait 1x vóór de poort")

        // Afkeuren via de poort → reset van de hele keten → de developer draait opnieuw.
        ui.setSubtaskPhase(gate.key, "manually-not-approved")
        awaitDispatchCount(story, AgentRole.DEVELOPER, 2)
    }

    @Test
    fun `manual-approve poort goedgekeurd zet de keten door naar de merge-subtaak`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = FactoryUiDriver(state)
        val await = AwaitDsl(state, Duration.ofSeconds(120))
        val story = "${state.projectKey}-310"

        // Auto-approve AAN: de AI-subtaken lopen vanzelf tot de poort, die desondanks wacht (SF-192).
        state.createIssue(summary = "E2E story $story", key = story)
        state.setEnumField(story, "Repo", "sample")
        state.setEnumField(story, "AI-supplier", "mock")
        state.setEnumField(story, "ApprovalMode", "alleen-manual-poort")
        state.setEnumField(story, "Story Phase", "start")

        // Wacht tot de afgedwongen subtaken (incl. manual-approve + merge + deploy) bestaan en de
        // keten op de poort stalt.
        Awaitility.await("manual-approve-subtaak aangemaakt onder $story")
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(100))
            .until { state.childrenOf(story).any { it.fields.subtaskType == "manual-approve" } }
        val gate = manualApproveChild(story)
        val merge = enforcedChild(story, "merge")
        val deploy = enforcedChild(story, "deploy")

        await.awaitSubtaskPhase(gate.key, "manual-approve-needed")
        // De poort houdt de keten tegen: de merge-subtaak is nog niet opgepakt (fase leeg).
        assertEquals(
            null,
            merge.fields.subtaskPhase,
            "merge mag nog niet starten zolang de poort op een mens wacht",
        )

        // Goedkeuren via de poort → de keten zet door naar de merge-subtaak (SF-192 approve-pad).
        ui.setSubtaskPhase(gate.key, "manually-approved")

        // SF-244: zodra de merge-subtaak aan de beurt is, probeert de factory automatisch te mergen.
        // In de e2e-harness (lokale git-remote, geen GitHub-PR) faalt die merge → Error op de
        // merge-subtaak. Dat bewijst (a) de poort-approve heeft de keten doorgezet naar merge en
        // (b) SF-244 foutpad: een merge-fout zet de merge-subtaak op Error en stopt de keten.
        await.awaitErrorContains(merge.key, "automatische merge")

        // De keten stopt op de merge-fout: de deploy-subtaak wordt nooit gestart.
        assertEquals(
            null,
            state.issue(deploy.key)?.fields?.subtaskPhase,
            "deploy mag niet starten als de merge faalt en de keten stopt",
        )
        // De keten is niet gereset: de developer draaide precies één keer (vóór de poort).
        assertEquals(1, dispatchCount(story, AgentRole.DEVELOPER), "developer draait 1x; approve reset de keten niet")
    }

    /** De factory-afgedwongen `manual-approve`-poort onder [storyKey]. */
    private fun manualApproveChild(storyKey: String) = enforcedChild(storyKey, "manual-approve")

    /** De factory-afgedwongen subtaak van [type] onder [storyKey]. */
    private fun enforcedChild(storyKey: String, type: String) =
        state.childrenOf(storyKey).first { it.fields.subtaskType == type }

    /** Story-gebonden telling: zie E2eTestBase.dispatchCount voor het waarom (cross-test-besmetting). */
    private fun dispatchCount(storyKey: String, role: AgentRole): Int =
        runtime.dispatched.count { it.first == storyKey && it.second == role }

    private fun awaitDispatchCount(storyKey: String, role: AgentRole, count: Int) {
        Awaitility.await("$role ${count}x gedispatcht voor $storyKey")
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(100))
            .until { dispatchCount(storyKey, role) >= count }
    }
}
