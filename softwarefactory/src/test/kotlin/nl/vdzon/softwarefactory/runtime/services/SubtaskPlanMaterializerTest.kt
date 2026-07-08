package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Config-pad-materialisatie (SF-787): materializeFromSpecs maakt EXACT de gedeclareerde subtaken aan. */
class SubtaskPlanMaterializerTest {

    private val specs = listOf(
        SubtaskSpec(SubtaskType.DEVELOPMENT, "Doe het werk", "dev-beschrijving"),
        SubtaskSpec(SubtaskType.REVIEW, "Review", "review-beschrijving"),
        SubtaskSpec(SubtaskType.MERGE, "Merge story-branch"),
        SubtaskSpec(SubtaskType.DEPLOY, "Deploy naar productie"),
    )

    @Test
    fun `materialiseert exact de gedeclareerde subtaken in volgorde zonder auto-append`() {
        val tracker = FakeTracker(parentSupplier = "claude")
        materializer(tracker).materializeFromSpecs("SF-1", specs)

        // Exact 4 subtaken, in de gegeven volgorde; GEEN factory-afgedwongen documentation/merge/
        // deploy/manual-approve extra bovenop de config.
        assertEquals(
            listOf("Doe het werk", "Review", "Merge story-branch", "Deploy naar productie"),
            tracker.created.map { it.title },
        )
        assertEquals(
            listOf(SubtaskType.DEVELOPMENT, SubtaskType.REVIEW, SubtaskType.MERGE, SubtaskType.DEPLOY),
            tracker.created.map { it.type },
        )
    }

    @Test
    fun `erft de AI-supplier van de parent-story`() {
        val tracker = FakeTracker(parentSupplier = "claude")
        materializer(tracker).materializeFromSpecs("SF-1", specs)

        assertEquals(List(4) { "claude" }, tracker.suppliers)
    }

    @Test
    fun `is idempotent op titel bij herhaalde uitvoering`() {
        // Eerste subtaak bestaat al onder de parent → niet opnieuw aanmaken, de rest wel.
        val tracker = FakeTracker(
            parentSupplier = "claude",
            existing = listOf(subtask("SF-2", "Doe het werk")),
        )
        materializer(tracker).materializeFromSpecs("SF-1", specs)

        assertEquals(
            listOf("Review", "Merge story-branch", "Deploy naar productie"),
            tracker.created.map { it.title },
        )
    }

    private fun materializer(tracker: TrackerApi) =
        SubtaskPlanMaterializer(tracker, ProjectRepoResolver(emptyMap()))

    private fun subtask(key: String, title: String): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = title,
            description = null,
            status = "",
            comments = emptyList(),
            fields = fields(),
        )

    private fun fields(supplier: String? = null): TrackerIssueFields =
        TrackerIssueFields(
            targetRepo = null,
            aiSupplier = supplier,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
        )

    private inner class FakeTracker(
        private val parentSupplier: String?,
        private val existing: List<TrackerIssue> = emptyList(),
    ) : TrackerApi {
        val created = mutableListOf<SubtaskSpec>()
        val suppliers = mutableListOf<String?>()

        override fun getIssue(issueKey: String): TrackerIssue =
            TrackerIssue(
                key = issueKey,
                summary = "Story",
                description = null,
                status = "",
                comments = emptyList(),
                fields = fields(supplier = parentSupplier),
            )

        override fun subtasksOf(parentKey: String): List<TrackerIssue> = existing

        override fun createSubtask(parentKey: String, spec: SubtaskSpec, supplier: String?): TrackerIssue {
            created += spec
            suppliers += supplier
            return subtask("SF-new", spec.title)
        }

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = Unit
        override fun transitionIssue(issueKey: String, statusName: String) = Unit
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()
    }
}
