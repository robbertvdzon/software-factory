package nl.vdzon.softwarefactory.audit

import nl.vdzon.softwarefactory.audit.repositories.AuditJobStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunKind
import nl.vdzon.softwarefactory.audit.services.AuditJob
import nl.vdzon.softwarefactory.audit.services.AuditSeeding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Pure tests voor de seed-regels rond handmatige ("Run now") jobs: die hangen aan dezelfde run als
 * de geplande audits en mogen de geplande ronde van dat project niet stilletjes overslaan — maar
 * mogen de run ook niet eeuwig open houden. Geen DB nodig.
 */
class AuditSeedingTest {

    private var nextId = 1L

    private fun job(auditType: String, kind: String = AuditRunKind.SCHEDULED, status: String = AuditJobStatus.PENDING) =
        AuditRunJobRecord(
            id = nextId++,
            runId = 1,
            project = "A",
            auditType = auditType,
            title = auditType,
            status = status,
            reportId = null,
            containerName = null,
            workspacePath = null,
            storyRunId = null,
            startedAt = null,
            endedAt = null,
            error = null,
            kind = kind,
        )

    private fun audit(name: String) = AuditJob(
        project = "A",
        name = name,
        title = name,
        enabled = true,
        aiSupplier = null,
        aiModel = null,
        priority = null,
    )

    @Test
    fun `a project without jobs is not seeded yet`() {
        assertFalse(AuditSeeding.isSeeded(emptyList(), listOf(audit("quality"))))
    }

    @Test
    fun `a scheduled job means the project is seeded`() {
        assertTrue(AuditSeeding.isSeeded(listOf(job("quality")), listOf(audit("quality"), audit("security"))))
    }

    @Test
    fun `a manual job alone does not count as seeded while other audits remain`() {
        // Anders zou "Run now" op project A de geplande audits van A voor die dag overslaan.
        val manual = job("quality", kind = AuditRunKind.MANUAL)
        assertFalse(AuditSeeding.isSeeded(listOf(manual), listOf(audit("quality"), audit("security"))))
    }

    @Test
    fun `manual jobs covering every enabled audit do count as seeded`() {
        // Valt er niets meer te seeden, dan moet het project wél als geseed tellen — anders blijft
        // het "pending" en eindigt de run nooit.
        val manuals = listOf(job("quality", AuditRunKind.MANUAL), job("security", AuditRunKind.MANUAL))
        assertTrue(AuditSeeding.isSeeded(manuals, listOf(audit("quality"), audit("security"))))
    }

    @Test
    fun `toSeed picks the audits with the oldest report first`() {
        val history = mapOf(
            "quality" to OffsetDateTime.parse("2026-07-20T08:00:00Z"),
            "security" to OffsetDateTime.parse("2026-07-10T08:00:00Z"),
        )
        val chosen = AuditSeeding.toSeed(
            enabledAudits = listOf(audit("quality"), audit("security")),
            projectJobs = emptyList(),
            count = 1,
        ) { history[it.name] }
        assertEquals(listOf("security"), chosen.map { it.name })
    }

    @Test
    fun `toSeed never runs an audit that is already queued in this run`() {
        val chosen = AuditSeeding.toSeed(
            enabledAudits = listOf(audit("quality"), audit("security")),
            projectJobs = listOf(job("quality", kind = AuditRunKind.MANUAL)),
            count = 2,
        ) { null }
        assertEquals(listOf("security"), chosen.map { it.name })
    }

    @Test
    fun `toSeed respects the audit count`() {
        val chosen = AuditSeeding.toSeed(
            enabledAudits = listOf(audit("quality"), audit("security"), audit("adr")),
            projectJobs = emptyList(),
            count = 2,
        ) { null }
        assertEquals(2, chosen.size)
    }
}
