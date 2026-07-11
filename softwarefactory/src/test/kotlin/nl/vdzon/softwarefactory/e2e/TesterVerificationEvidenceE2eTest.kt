package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Productie-loopback via wire-file, completionvalidatie, tracker en orchestrator. */
class TesterVerificationEvidenceE2eTest : E2eTestBase() {

    @Test
    fun `red and revision-mismatched evidence reset the full chain before exact green evidence passes`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("development", "review", "test")
            testerEvidenceModes = listOf("failed", "mismatch", "green")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-927"
        createStory(story, autoApprove = true)

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)
        awaitDispatchCount(story, AgentRole.TESTER, 3)
        val test = state.childrenOf(story).single { it.fields.subtaskType == "test" }
        await.awaitSubtaskPhase(test.key, "test-approved")

        assertEquals(3, dispatchCount(story, AgentRole.DEVELOPER), "iedere evidence-reject reset naar development")
        assertEquals(6, dispatchCount(story, AgentRole.REVIEWER), "development-review en reviewsubtaak draaien beide per cyclus")
        assertEquals(3, dispatchCount(story, AgentRole.TESTER), "rood + mismatch + exact groen")
    }
}
