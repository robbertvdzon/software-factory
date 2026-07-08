package nl.vdzon.softwarefactory.tracker.services

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentCommentContextTest {

    @Test
    fun `summarizer receives the user's plain answer comment so it does not re-ask`() {
        val userAnswer = TrackerComment("user-1", null, "Robbert", "Ja, CI heeft Docker; SF-10/11/12 apart mergen.", null)
        val testerComment = TrackerComment("t-1", null, "Tester", "[TESTER] e2e niet bevestigd.", null)
        val issue = issueWith(userAnswer, testerComment)

        val result = AgentCommentContext.taskComments(issue, AgentRole.SUMMARIZER) { _, _ -> false }

        assertTrue(result.any { it.id == "user-1" }, "Summarizer moet het user-antwoord zien")
    }

    @Test
    fun `tester, reviewer and developer also receive the user's answer`() {
        val userAnswer = TrackerComment("user-1", null, "Robbert", "Mijn antwoord op de vraag.", null)
        val issue = issueWith(userAnswer)

        listOf(AgentRole.TESTER, AgentRole.REVIEWER, AgentRole.DEVELOPER).forEach { role ->
            val result = AgentCommentContext.taskComments(issue, role) { _, _ -> false }
            assertTrue(result.any { it.id == "user-1" }, "$role moet het user-antwoord zien")
        }
    }

    @Test
    fun `a user comment already processed for the role is not re-delivered`() {
        val userAnswer = TrackerComment("user-1", null, "Robbert", "Mijn antwoord.", null)
        val issue = issueWith(userAnswer)

        val result = AgentCommentContext.taskComments(issue, AgentRole.SUMMARIZER) { c, _ -> c.id == "user-1" }

        assertTrue(result.none { it.id == "user-1" }, "Een al verwerkte user-comment mag niet opnieuw geleverd worden")
    }

    private fun issueWith(vararg comments: TrackerComment): TrackerIssue =
        TrackerIssue(
            key = "SF-9",
            summary = "Story-brede test",
            status = "Open",
            fields = TrackerIssueFields(
                targetRepo = "repo",
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
            ),
            comments = comments.toList(),
        )
}
