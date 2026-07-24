package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.node.IntNode
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Aanvullende end-to-end pipeline-tests bovenop [PipelineFlowsE2eTest], gericht op scenario's die de
 * productiecode ondersteunt maar die nog niet door de bestaande e2e-suite werden gedekt:
 *
 *  - **afkeur-/reset-loops voorbij één iteratie** (developer-loopback 2x, test-chain reset 2x);
 *  - de **review-rejected → developer-loopback** binnen een development-subtaak (eigen review-poort);
 *  - de **developer-loopback-cap** die bij overschrijding naar `Error` gaat (geen oneindige loop);
 *  - een **silent** subtaak waarbij een agent-vraag niet op een mens wacht maar in een
 *    clarification-`Error` belandt.
 *
 * Elke test gebruikt een unieke story-key (eigen workspace + story-run) en stuurt de approve/reject-
 * gates zelf via de UI (auto-approve uit), tenzij anders nodig. Geen productiegedrag gewijzigd:
 * alleen testcode + de [AwaitDsl.awaitErrorContains]-helper zijn toegevoegd.
 */
class PipelineLoopbackE2eTest : E2eTestBase() {

    @Test
    fun `development-subtaak twee keer afgekeurd loopt elke keer terug naar de developer`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-100"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val dev = plannedChild(story)

        // Eerste resultaat → afkeuren → loopback 1.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-rejected")
        awaitDispatchCount(story, AgentRole.DEVELOPER, 2)

        // Tweede resultaat → opnieuw afkeuren → loopback 2 (voorbij één iteratie).
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-rejected")
        awaitDispatchCount(story, AgentRole.DEVELOPER, 3)

        // Derde resultaat → goedkeuren → reviewer → klaar.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-approved")
        await.awaitSubtaskPhase(dev.key, "reviewed")
        ui.setSubtaskPhase(dev.key, "review-approved")

        assertEquals(3, dispatchCount(story, AgentRole.DEVELOPER), "developer: initieel + 2x na reject")
    }

    @Test
    fun `review afgekeurd binnen de development-subtaak loopt terug naar de developer`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            reviewerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-110"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val dev = plannedChild(story)

        // Develop → goedkeuren → reviewer draait → review afkeuren → developer-loopback.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-approved")
        await.awaitSubtaskPhase(dev.key, "reviewed")
        ui.setSubtaskPhase(dev.key, "review-rejected")
        awaitDispatchCount(story, AgentRole.DEVELOPER, 2)

        // Na de fix opnieuw develop → goedkeuren → re-review → goedkeuren → klaar.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-approved")
        await.awaitSubtaskPhase(dev.key, "reviewed")
        ui.setSubtaskPhase(dev.key, "review-approved")

        assertEquals(2, dispatchCount(story, AgentRole.DEVELOPER), "developer: initieel + na review-reject")
        assertEquals(2, dispatchCount(story, AgentRole.REVIEWER), "reviewer: initieel + re-review")
    }

    @Test
    fun `developer-loopback boven de cap zet de subtaak in Error en stopt de loop`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-120"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val dev = plannedChild(story)
        // Cap = 1 op de subtaak (de loopback-cap leest het veld van de subtaak zelf, niet de parent):
        // de eerste loopback mag nog, de tweede knalt door de cap.
        state.setRawField(dev.key, "AI Max Developer Loopbacks", IntNode.valueOf(1))

        // Run 1 → afkeuren → loopback (run 2) mag nog.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-rejected")
        awaitDispatchCount(story, AgentRole.DEVELOPER, 2)

        // Run 2 → opnieuw afkeuren → cap bereikt → geen run 3, subtaak in Error.
        await.awaitSubtaskPhase(dev.key, "developed")
        ui.setSubtaskPhase(dev.key, "development-rejected")
        await.awaitErrorContains(dev.key, "Developer-loopback cap bereikt")

        assertEquals(2, dispatchCount(story, AgentRole.DEVELOPER), "developer mag de cap (1) niet overschrijden: 2 runs")
    }

    @Test
    fun `test-bevinding reset de keten twee keer en laat telkens opnieuw testen`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("test")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-130"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val test = plannedChild(story)

        // Bevinding 1 → reset → re-test.
        await.awaitSubtaskPhase(test.key, "tested")
        ui.setSubtaskPhase(test.key, "test-rejected")
        awaitDispatchCount(story, AgentRole.TESTER, 2)

        // Bevinding 2 → reset → re-test (voorbij één iteratie, ruim onder de cap van 3).
        await.awaitSubtaskPhase(test.key, "tested")
        ui.setSubtaskPhase(test.key, "test-rejected")
        awaitDispatchCount(story, AgentRole.TESTER, 3)

        await.awaitSubtaskPhase(test.key, "tested")
        ui.setSubtaskPhase(test.key, "test-approved")

        assertEquals(0, dispatchCount(story, AgentRole.DEVELOPER), "tester doet geen eigen developer-fix")
        assertEquals(3, dispatchCount(story, AgentRole.TESTER), "tester: initieel + 2x re-test na reset")
    }

    @Test
    fun `silent story zet een agent-vraag in een clarification-Error in plaats van te wachten`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = true // stelt op attempt 1 een vraag
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-140"
        // Goedkeuring automatisch (gates lopen vanzelf) + vragen UIT: een subtaak-vraag wacht niet op een mens.
        createStory(story, autoApprove = true)
        state.setEnumField(story, "QuestionsAllowed", "false")

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)
        val dev = plannedChild(story)

        // Developer stelt een vraag → silent → clarification-Error op de subtaak (geen wachtstand).
        await.awaitErrorContains(dev.key, "[CLARIFICATION]")

        assertEquals(1, dispatchCount(story, AgentRole.DEVELOPER), "developer draait niet opnieuw: de vraag eindigt in Error")
    }
}
