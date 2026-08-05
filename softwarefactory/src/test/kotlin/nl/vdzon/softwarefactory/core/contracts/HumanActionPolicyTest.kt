package nl.vdzon.softwarefactory.core.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** SF-1261 — as 2 (Goedkeuring): [HumanActionPolicy.autoApproveActive] op de nieuwe [ApprovalMode]-as. */
class HumanActionPolicyTest {

    @Test
    fun `story gebruikt het eigen approvalMode-veld`() {
        val story = story(approvalMode = ApprovalMode.EVERY_STEP.trackerValue)

        assertFalse(HumanActionPolicy.autoApproveActive(story) { fail("parent-lookup mag niet gebeuren voor een story") })
    }

    @Test
    fun `subtaak erft approvalMode van de parent-story`() {
        val subtask = subtask()
        val parentFields = fields(approvalMode = ApprovalMode.AUTOMATIC.trackerValue)

        assertTrue(HumanActionPolicy.autoApproveActive(subtask) { parentFields })
    }

    @Test
    fun `subtaak met elke-stap-parent wacht op een mens`() {
        val subtask = subtask()
        val parentFields = fields(approvalMode = ApprovalMode.EVERY_STEP.trackerValue)

        assertFalse(HumanActionPolicy.autoApproveActive(subtask) { parentFields })
    }

    @Test
    fun `subtaak met falende parent-lookup is fail-safe (geen auto-approve)`() {
        // Regressietest: een lookup-falen mag NIET terugvallen op het eigen (subtaak-)veld, want
        // dat is altijd de class-default AUTOMATIC en zou de goedkeuringsgate fail-open maken.
        val subtask = subtask()

        assertFalse(HumanActionPolicy.autoApproveActive(subtask) { null })
    }

    @Test
    fun `hotfix-subtaak op developed wacht nooit op een mens`() {
        // SF-1959 — de DEVELOPED-gate geldt alleen voor subtaskType 'development'. Zou dat ook voor
        // 'hotfix' gelden, dan bleef een hotfix bij goedkeuring=elke-stap hangen op een mens-poort.
        val hotfix = subtask(
            subtaskType = SubtaskType.HOTFIX.trackerValue,
            subtaskPhase = SubtaskPhase.DEVELOPED.trackerValue,
        )

        assertNull(HumanActionPolicy.gateFor(hotfix))
        assertFalse(HumanActionPolicy.awaitsHuman(hotfix, autoApproveActive = false))
    }

    @Test
    fun `development-subtaak op developed wacht wel op goedkeuring`() {
        val development = subtask(
            subtaskType = SubtaskType.DEVELOPMENT.trackerValue,
            subtaskPhase = SubtaskPhase.DEVELOPED.trackerValue,
        )

        assertEquals(HumanGate.APPROVAL, HumanActionPolicy.gateFor(development))
        assertTrue(HumanActionPolicy.awaitsHuman(development, autoApproveActive = false))
    }

    @Test
    fun `hotfix-subtaak met een vraag wacht wel op een mens`() {
        val hotfix = subtask(
            subtaskType = SubtaskType.HOTFIX.trackerValue,
            subtaskPhase = SubtaskPhase.DEVELOPED_WITH_QUESTIONS.trackerValue,
        )

        assertEquals(HumanGate.QUESTION, HumanActionPolicy.gateFor(hotfix))
    }

    private fun story(approvalMode: String): TrackerIssue =
        TrackerIssue(
            key = "SF-1",
            summary = "Story",
            status = "",
            comments = emptyList(),
            fields = fields(approvalMode = approvalMode, type = "User Story"),
        )

    private fun subtask(subtaskType: String? = null, subtaskPhase: String? = null): TrackerIssue =
        TrackerIssue(
            key = "SF-2",
            summary = "Subtaak",
            status = "",
            comments = emptyList(),
            fields = fields(type = "Task").copy(subtaskType = subtaskType, subtaskPhase = subtaskPhase),
        )

    private fun fields(approvalMode: String = ApprovalMode.AUTOMATIC.trackerValue, type: String? = null): TrackerIssueFields =
        TrackerIssueFields(
            targetRepo = null,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            approvalMode = approvalMode,
            error = null,
            type = type,
        )
}
