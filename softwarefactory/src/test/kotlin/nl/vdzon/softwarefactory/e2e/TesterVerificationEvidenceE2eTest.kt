package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Productie-loopback via getypeerde Runtime-completion, verificatiebewijs, tracker en orchestrator. */
class TesterVerificationEvidenceE2eTest : E2eTestBase() {

    @Test
    fun `red developer verification retries before green evidence lets the chain continue`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development", "review", "test")
            developerVerificationFailures = listOf(true, true, false)
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-927"
        createStory(story, autoApprove = true)

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)
        awaitDispatchCount(story, AgentRole.DEVELOPER, 3)
        val test = state.childrenOf(story).single { it.fields.subtaskType == "test" }
        await.awaitSubtaskPhase(test.key, "test-approved")

        assertEquals(3, dispatchCount(story, AgentRole.DEVELOPER), "twee rode jobs lopen terug naar development")
        assertEquals(2, dispatchCount(story, AgentRole.REVIEWER), "review start pas na de groene developerjob")
        assertEquals(1, dispatchCount(story, AgentRole.TESTER), "tester draait eenmaal op de gepubliceerde branchstand")
    }
}
