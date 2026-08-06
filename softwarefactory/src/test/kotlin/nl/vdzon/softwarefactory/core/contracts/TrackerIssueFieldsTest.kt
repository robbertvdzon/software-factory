package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.TrackerField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SF-1959 — de hotfix-as in [TrackerIssueFields.applying]: de lokale spiegel van wat
 * `updateIssueFields` naar de tracker-DB schrijft. Default UIT, zodat een onbekende of ontbrekende
 * waarde van een story nooit stilzwijgend een hotfix maakt.
 */
class TrackerIssueFieldsTest {

    private val fields = TrackerIssueFields(
        targetRepo = null,
        aiPhase = null,
        aiLevel = null,
        aiTokenBudget = null,
        aiTokensUsed = null,
        agentStartedAt = null,
        paused = false,
        error = null,
    )

    @Test
    fun `hotfix staat standaard uit`() {
        assertFalse(fields.hotfix)
    }

    @Test
    fun `applying accepteert zowel Boolean als String voor hotfix`() {
        assertTrue(fields.applying(TrackerField.HOTFIX, true).hotfix)
        assertTrue(fields.applying(TrackerField.HOTFIX, "true").hotfix)
        assertTrue(fields.applying(TrackerField.HOTFIX, "TRUE").hotfix)
        assertFalse(fields.applying(TrackerField.HOTFIX, false).hotfix)
        assertFalse(fields.applying(TrackerField.HOTFIX, "false").hotfix)
    }

    @Test
    fun `applying valt bij een onbekende of lege hotfix-waarde terug op uit`() {
        val aan = fields.copy(hotfix = true)
        assertFalse(aan.applying(TrackerField.HOTFIX, null).hotfix)
        assertFalse(aan.applying(TrackerField.HOTFIX, "").hotfix)
        assertFalse(aan.applying(TrackerField.HOTFIX, 1).hotfix)
    }

    @Test
    fun `applying van hotfix laat de andere story-assen ongemoeid`() {
        val updated = fields.applying(TrackerField.HOTFIX, true)

        assertEquals(fields.questionsAllowed, updated.questionsAllowed)
        assertEquals(fields.approvalMode, updated.approvalMode)
        assertEquals(fields.notificationEvents, updated.notificationEvents)
    }

    @Test
    fun `auditstories gebruiken exact de vereiste eventset`() {
        assertEquals(
            setOf(
                NotificationEvent.QUESTION,
                NotificationEvent.MANUAL_ACTION_REQUIRED,
                NotificationEvent.ERROR,
            ),
            NotificationEvent.AUDIT,
        )
    }

    @Test
    fun `notification-events parser accepteert alleen exacte publieke namen`() {
        assertEquals(NotificationEvent.entries.toSet(), NotificationEvent.parse(NotificationEvent.entries.map { it.name }))
        assertFailsWith<IllegalArgumentException> { NotificationEvent.parse(setOf("EROR")) }
        assertFailsWith<IllegalArgumentException> { NotificationEvent.parse(setOf("error")) }
    }
}
