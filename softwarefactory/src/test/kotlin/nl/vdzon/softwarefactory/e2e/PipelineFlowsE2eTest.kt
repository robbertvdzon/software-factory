package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-flow end-to-end tests bovenop [E2eTestBase]: voor elke subtaak-soort de **vraag**-flow en de
 * **reject**-flow, plus de **manual**-subtaak en de **story-niveau rejects**. Elke test configureert
 * de scripted agent ([AgentScript]) voor precies dat pad en stuurt de menselijke acties via de UI.
 *
 * - **Vraag**-flows draaien met `Auto-approve=on` (de gates gaan vanzelf), zodat alleen de vraag het
 *   menselijke moment is.
 * - **Reject**-flows draaien met `Auto-approve=off`, zodat de test de approve/reject-gate zelf stuurt.
 *
 * Elke test gebruikt een unieke story-key (eigen workspace + story-run).
 */
class PipelineFlowsE2eTest : E2eTestBase() {

    // ---------------------------------------------------------------------------------------------
    // Vraag-flows per subtaak-soort (auto-approve aan; refiner stelt geen vraag → minder ruis).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `review-subtaak stelt een vraag die de gebruiker beantwoordt`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            reviewerAsksQuestion = true
            plannedSubtasks = AgentScript.subtasks("review")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-10"
        createStory(story)

        await.awaitStoryPhase(story, "planning-approved")
        await.awaitSubtasksCreated(story, 1)
        ui.startDeveloping(story)
        val review = state.childrenOf(story).single()

        await.awaitSubtaskPhase(review.key, "reviewed-with-questions")
        ui.answerSubtask(review.key, "ja, deze aanpak is akkoord", phase = "review-questions-answered")
        await.awaitSubtaskPhase(review.key, "review-approved")

        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.REVIEWER }, "reviewer: vraag + afronden")
    }

    @Test
    fun `test-subtaak stelt een vraag die de gebruiker beantwoordt`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            testerAsksQuestion = true
            plannedSubtasks = AgentScript.subtasks("test")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-20"
        createStory(story)

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)
        val test = state.childrenOf(story).single()

        await.awaitSubtaskPhase(test.key, "tested-with-questions")
        ui.answerSubtask(test.key, "volledige happy-path dekken", phase = "test-questions-answered")
        await.awaitSubtaskPhase(test.key, "test-approved")

        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.TESTER }, "tester: vraag + afronden")
    }

    @Test
    fun `summary-subtaak stelt een vraag die de gebruiker beantwoordt`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            summarizerAsksQuestion = true
            plannedSubtasks = AgentScript.subtasks("summary")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-30"
        createStory(story)

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)
        val summary = state.childrenOf(story).single()

        await.awaitSubtaskPhase(summary.key, "summary-with-questions")
        ui.answerSubtask(summary.key, "ja, neem de risico's mee", phase = "summary-questions-answered")
        await.awaitSubtaskPhase(summary.key, "summary-approved")

        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.SUMMARIZER }, "summarizer: vraag + afronden")
    }

    // ---------------------------------------------------------------------------------------------
    // Reject-flows (auto-approve uit; de test stuurt de approve/reject-gates).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `development-subtaak afgekeurd loopt terug naar de developer`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-40"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val dev = state.childrenOf(story).single()

        // Eerste resultaat → afkeuren → loopback naar developer.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-rejected")
        awaitDispatchCount(AgentRole.DEVELOPER, 2)

        // Tweede resultaat → goedkeuren → reviewer → goedkeuren → klaar.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-approved")
        await.awaitSubtaskPhase(dev.key, "reviewed")
        ui.setSubtaskPhase(dev.key, "review-approved")

        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.DEVELOPER }, "developer: initieel + na reject")
    }

    @Test
    fun `test-subtaak afgekeurd loopt terug naar de developer en dan opnieuw testen`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false // de loopback-developer rondt direct af, stelt geen vraag
            plannedSubtasks = AgentScript.subtasks("test")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-50"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val test = state.childrenOf(story).single()

        await.awaitSubtaskPhase(test.key, "tested")
        ui.setSubtaskPhase(test.key, "test-rejected")
        // test-rejected → developer (loopback) → tester (re-test).
        awaitDispatchCount(AgentRole.DEVELOPER, 1)
        awaitDispatchCount(AgentRole.TESTER, 2)
        await.awaitSubtaskPhase(test.key, "tested")
        ui.setSubtaskPhase(test.key, "test-approved")

        assertEquals(1, runtime.dispatched.count { it.second == AgentRole.DEVELOPER }, "developer fixt na test-reject")
        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.TESTER }, "tester: initieel + re-test")
    }

    @Test
    fun `summary-subtaak afgekeurd laat de summarizer opnieuw draaien`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("summary")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-60"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val summary = state.childrenOf(story).single()

        await.awaitSubtaskPhase(summary.key, "summarized")
        ui.setSubtaskPhase(summary.key, "summary-rejected")
        awaitDispatchCount(AgentRole.SUMMARIZER, 2)
        await.awaitSubtaskPhase(summary.key, "summarized")
        ui.setSubtaskPhase(summary.key, "summary-approved")

        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.SUMMARIZER }, "summarizer: initieel + na reject")
    }

    // ---------------------------------------------------------------------------------------------
    // Manual-subtaak (geen agent; menselijke actie).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `manual-subtaak wacht op de mens en wordt afgerond zonder agent`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("manual")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-70"
        createStory(story)

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)
        val manual = state.childrenOf(story).single()

        await.awaitSubtaskPhase(manual.key, "awaiting-human")
        ui.setSubtaskPhase(manual.key, "manual-action-done")
        await.awaitSubtaskPhase(manual.key, "manual-action-done")

        val subtaskRoles = listOf(AgentRole.DEVELOPER, AgentRole.REVIEWER, AgentRole.TESTER, AgentRole.SUMMARIZER)
        assertTrue(
            runtime.dispatched.none { it.second in subtaskRoles },
            "een manual-subtaak draait geen subtaak-agent, kreeg: ${runtime.dispatched.map { it.second }}",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Story-niveau rejects.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `refinement afgekeurd laat de refiner opnieuw draaien`() {
        runtime.script.apply { refinerAsksQuestion = false }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-80"
        createStory(story, autoApprove = false)

        await.awaitStoryPhase(story, "refined")
        ui.setStoryPhase(story, "refined-rejected")
        awaitDispatchCount(AgentRole.REFINER, 2)
        await.awaitStoryPhase(story, "refined")
        ui.setStoryPhase(story, "refined-approved")
        await.awaitStoryPhase(story, "planned") // planner is doorgegaan

        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.REFINER }, "refiner: initieel + na reject")
    }

    @Test
    fun `planning afgekeurd laat de planner opnieuw draaien`() {
        runtime.script.apply { refinerAsksQuestion = false }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-90"
        createStory(story, autoApprove = false)

        await.awaitStoryPhase(story, "refined")
        ui.setStoryPhase(story, "refined-approved")
        await.awaitStoryPhase(story, "planned")
        ui.setStoryPhase(story, "planning-rejected")
        awaitDispatchCount(AgentRole.PLANNER, 2)
        await.awaitStoryPhase(story, "planned")
        ui.setStoryPhase(story, "planning-approved")
        await.awaitStoryPhase(story, "planning-approved")

        assertEquals(2, runtime.dispatched.count { it.second == AgentRole.PLANNER }, "planner: initieel + na reject")
    }
}
