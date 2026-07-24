package nl.vdzon.softwarefactory.e2e

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * End-to-end dekking van de **pickup-/fase-gate** van de orchestrator (functional-spec.md §orchestrator):
 * welke issues de factory wél en niet oppakt. Deze paden werden door geen van de bestaande e2e-tests
 * geraakt en zijn met de bestaande harness bereikbaar (geen nieuwe buitenrand-fake nodig).
 *
 * Bewezen gedrag:
 *  - **Lege `AI Phase` (Story Phase) ⇒ niet starten.** De story staat met een geldige supplier in de
 *    poll-set, maar zonder fase pakt de orchestrator 'm niet op (pas `start` triggert de keten).
 *  - **`AI-supplier` leeg/`none` ⇒ ongemoeid.** `findWorkIssues` filtert de story al uit de poll-set.
 *  - **`Paused=true` ⇒ niet verwerken.** De story zit in de poll-set, maar de pipeline skipt 'm.
 *
 * Elke test draait een **controle-story** die wél volledig wordt opgepakt mee als deterministisch
 * synchronisatiepunt: zodra die `planning-approved` bereikt heeft de orchestrator vele poll-cycli
 * gedraaid en is de gated story gegarandeerd meerdere keren geëvalueerd. Daarna assert de test dat de
 * gated story géén enkele agent-dispatch opleverde (gefilterd op de parent-story-key, want de
 * `serializationKey` van elke dispatch is de parent-story-key).
 *
 * Elke test gebruikt unieke story-keys (eigen workspace + story-run) en deelt geen state.
 */
class OrchestratorGateE2eTest : E2eTestBase() {

    @Test
    fun `story zonder AI Phase wordt niet opgepakt`() {
        runtime.script.refinerAsksQuestion = false
        val await = awaiter(Duration.ofSeconds(60))
        val gated = "${state.projectKey}-400"
        val control = "${state.projectKey}-401"

        // Gated: geldige supplier (staat dus in de poll-set), maar GEEN Story Phase → fase-gate skipt 'm.
        state.createIssue(summary = "E2E gated $gated", key = gated)
        state.setEnumField(gated, "Repo", "sample")
        state.setEnumField(gated, "AI-supplier", "mock")
        state.setEnumField(gated, "ApprovalMode", "automatisch")
        // Bewust GEEN "Story Phase" gezet.

        // Controle-story loopt wél (fase `start`) → deterministisch synchronisatiepunt.
        createStory(control)
        await.awaitStoryPhase(control, "planning-approved")

        assertEquals(0, dispatchCountFor(gated), "een story zonder AI Phase mag niet worden opgepakt")
    }

    @Test
    fun `story met AI-supplier none wordt niet opgepakt`() {
        runtime.script.refinerAsksQuestion = false
        val await = awaiter(Duration.ofSeconds(60))
        val gated = "${state.projectKey}-410"
        val control = "${state.projectKey}-411"

        // Gated: fase `start`, maar supplier `none` → findWorkIssues laat 'm uit de poll-set.
        state.createIssue(summary = "E2E gated $gated", key = gated)
        state.setEnumField(gated, "Repo", "sample")
        state.setEnumField(gated, "AI-supplier", "none")
        state.setEnumField(gated, "ApprovalMode", "automatisch")
        state.setEnumField(gated, "Story Phase", "start")

        createStory(control)
        await.awaitStoryPhase(control, "planning-approved")

        assertEquals(0, dispatchCountFor(gated), "een story met AI-supplier none mag niet worden opgepakt")
    }

    @Test
    fun `story op Paused wordt niet verwerkt`() {
        runtime.script.refinerAsksQuestion = false
        val await = awaiter(Duration.ofSeconds(60))
        val gated = "${state.projectKey}-420"
        val control = "${state.projectKey}-421"

        // Gated: volledig opneembaar (supplier + fase `start`), maar Paused → pipeline skipt 'm.
        state.createIssue(summary = "E2E gated $gated", key = gated)
        state.setEnumField(gated, "Repo", "sample")
        state.setEnumField(gated, "AI-supplier", "mock")
        state.setEnumField(gated, "ApprovalMode", "automatisch")
        // Zet de guard vóór de activerende fase. De echte poller draait parallel met de testsetup;
        // `start` eerst zetten creëert een kort maar geldig dispatchvenster vóór `Paused=true`.
        state.setEnumField(gated, "Paused", "true")
        state.setEnumField(gated, "Story Phase", "start")

        createStory(control)
        await.awaitStoryPhase(control, "planning-approved")

        assertEquals(0, dispatchCountFor(gated), "een gepauzeerde story mag niet worden verwerkt")
    }

    /** Aantal agent-dispatches dat onder [storyKey] viel (serializationKey == parent-story-key). */
    private fun dispatchCountFor(storyKey: String): Int =
        runtime.dispatched.count { it.first == storyKey }
}
