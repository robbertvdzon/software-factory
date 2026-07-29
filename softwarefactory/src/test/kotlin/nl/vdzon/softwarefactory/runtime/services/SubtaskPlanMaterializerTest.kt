package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunSubtaskPayload
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    /** Bij `elke-stap` hoort altijd de vaste manual-approve-poort vóór de merge. */
    @Test
    fun `materialiseert de manual-approve-poort als de parent elke-stap is`() {
        val tracker = FakeTracker(parentSupplier = "claude", parentApprovalMode = ApprovalMode.EVERY_STEP)

        materializer(tracker).materializeIfPlanned(plannerRequest(), AgentRole.PLANNER)

        assertTrue(tracker.created.any { it.type == SubtaskType.MANUAL_APPROVE })
    }

    /** `automatisch` slaat de poort altijd over. */
    @Test
    fun `slaat de manual-approve-poort over als de parent automatisch is`() {
        val tracker = FakeTracker(parentSupplier = "claude", parentApprovalMode = ApprovalMode.AUTOMATIC)

        materializer(tracker).materializeIfPlanned(plannerRequest(), AgentRole.PLANNER)

        assertTrue(tracker.created.none { it.type == SubtaskType.MANUAL_APPROVE })
    }

    /** Bij `alleen-manual-poort` lopen AI-stappen automatisch, maar blijft de vaste poort staan. */
    @Test
    fun `materialiseert de manual-approve-poort als de parent alleen-manual-poort is`() {
        val tracker = FakeTracker(parentSupplier = "claude", parentApprovalMode = ApprovalMode.MANUAL_GATE_ONLY)

        materializer(tracker).materializeIfPlanned(plannerRequest(), AgentRole.PLANNER)

        assertTrue(tracker.created.any { it.type == SubtaskType.MANUAL_APPROVE })
    }

    /**
     * Regressietest voor de reviewbevinding: als het ophalen van de parent-story faalt (transient
     * tracker/DB-fout), mag de manual-approve-poort NIET stilzwijgend overgeslagen worden.
     */
    @Test
    fun `laat de manual-approve-poort staan als de parent-lookup faalt`() {
        val tracker = FakeTracker(parentSupplier = "claude", parentLookupFails = true)

        materializer(tracker).materializeIfPlanned(plannerRequest(), AgentRole.PLANNER)

        assertTrue(tracker.created.any { it.type == SubtaskType.MANUAL_APPROVE })
    }

    private fun plannerRequest(): AgentRunCompleteRequest =
        AgentRunCompleteRequest(
            storyKey = "SF-1",
            role = AgentRole.PLANNER.name,
            containerName = "c",
            phase = "planned",
            outcome = "planned",
            subtasks = listOf(
                AgentRunSubtaskPayload(type = SubtaskType.DEVELOPMENT.trackerValue, title = "Doe het werk"),
            ),
        )

    private fun materializer(tracker: TrackerApi) =
        SubtaskPlanMaterializer(tracker)

    private fun subtask(key: String, title: String): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = title,
            description = null,
            status = "",
            comments = emptyList(),
            fields = fields(),
        )

    private fun fields(supplier: String? = null, approvalMode: String = ApprovalMode.AUTOMATIC.trackerValue): TrackerIssueFields =
        TrackerIssueFields(
            targetRepo = null,
            aiSupplier = supplier,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            approvalMode = approvalMode,
            error = null,
        )

    private inner class FakeTracker(
        private val parentSupplier: String?,
        private val existing: List<TrackerIssue> = emptyList(),
        private val parentApprovalMode: ApprovalMode = ApprovalMode.AUTOMATIC,
        private val parentLookupFails: Boolean = false,
    ) : TrackerApi {
        val created = mutableListOf<SubtaskSpec>()
        val suppliers = mutableListOf<String?>()

        override fun getIssue(issueKey: String): TrackerIssue {
            if (parentLookupFails) {
                throw IllegalStateException("transient tracker failure")
            }
            return TrackerIssue(
                key = issueKey,
                summary = "Story",
                description = null,
                status = "",
                comments = emptyList(),
                fields = fields(supplier = parentSupplier, approvalMode = parentApprovalMode.trackerValue),
            )
        }

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
