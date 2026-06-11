package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Het volledige happy-path-scenario (e2e-plan §4): een verse story met label `ai-refinement` tot
 * álle subtaken afgerond, met de **echte** Spring-app en alleen de buitenranden vervangen
 * ([E2eTestConfig]). `Auto-approve=on` laat de orchestrator de `*-ed → *-approved`-gates zelf zetten,
 * zodat de test enkel de écht menselijke acties stuurt (twee vragen beantwoorden + "start developing").
 *
 * De per-rol vraag/reject-flows staan in [PipelineFlowsE2eTest]; dit is de end-to-end keten in één keer.
 */
class FullRefineToDevelopE2eTest : E2eTestBase() {

    @Test
    fun `story doorloopt refine tot alle subtaken afgerond`() {
        val ui = loginUi()
        val await = awaiter()

        // Story (default: alle 4 subtaak-typen, refiner + developer stellen een vraag).
        val storyKey = "${state.projectKey}-1"
        createStory(storyKey)

        // Refine → (antwoord) → plan → 4 subtaken → planning-approved.
        refineAndPlan(ui, await, storyKey, expectedSubtasks = 4)

        // Mens drukt op "start developing" → eerste subtask krijgt de ai-development-tag.
        ui.startDeveloping(storyKey)
        val devSubtask = state.childrenOf(storyKey).first()

        // Developer stelt een vraag → beantwoord via de UI → de hele keten loopt door.
        await.awaitSubtaskPhase(devSubtask.key, "developed-with-questions")
        ui.answerSubtask(devSubtask.key, "variant A, graag")
        await.awaitAllSubtasksApproved(storyKey)

        // --- Eindtoestand ---
        assertEquals(4, state.childrenOf(storyKey).size, "verwachtte 4 subtaken onder $storyKey")

        // --- Dispatch-volgorde van de scripted agents ---
        val roles = runtime.dispatched.map { it.second }
        assertOrderedSubsequence(
            roles,
            listOf(
                AgentRole.REFINER,    // attempt 1: vraag
                AgentRole.REFINER,    // attempt 2: refined
                AgentRole.PLANNER,    // planned + 4 subtaken
                AgentRole.DEVELOPER,  // attempt 1: vraag
                AgentRole.DEVELOPER,  // attempt 2: developed
                AgentRole.REVIEWER,   // dev-subtask review
                AgentRole.REVIEWER,   // review-subtask
                AgentRole.TESTER,     // test-subtask
                AgentRole.SUMMARIZER, // summary-subtask
            ),
        )
        assertEquals(2, roles.count { it == AgentRole.REFINER }, "refiner moet 2x draaien (vraag + afronden)")
        assertEquals(2, roles.count { it == AgentRole.DEVELOPER }, "developer moet 2x draaien (vraag + afronden)")
        assertEquals(1, roles.count { it == AgentRole.PLANNER }, "planner draait precies 1x")
    }

    /** Borgt dat [expected] als geordende deelreeks (subsequence) in [actual] voorkomt. */
    private fun <T> assertOrderedSubsequence(actual: List<T>, expected: List<T>) {
        var idx = 0
        for (item in actual) {
            if (idx < expected.size && item == expected[idx]) idx++
        }
        assertTrue(
            idx == expected.size,
            "verwachtte $expected als geordende deelreeks van de dispatch-volgorde, maar kreeg $actual",
        )
    }
}
