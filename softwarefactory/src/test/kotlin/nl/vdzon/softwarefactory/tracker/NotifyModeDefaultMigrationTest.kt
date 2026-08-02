package nl.vdzon.softwarefactory.tracker

import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SF-1776 — pint de vorm van `V27__notify_mode_default_deployed.sql` vast: die verschuift alleen de
 * KOLOMDEFAULT en raakt geen enkele bestaande rij aan (AC 6). Een backfill zou de meldingen-stand
 * van lopende story's stilzwijgend omzetten.
 */
class NotifyModeDefaultMigrationTest {

    private val sql: String = requireNotNull(
        javaClass.classLoader.getResourceAsStream("db/migration/V27__notify_mode_default_deployed.sql"),
    ) { "migratie V27 ontbreekt op het classpath" }.bufferedReader().use { it.readText() }

    @Test
    fun `V27 zet de kolomdefault van notify_mode op als-klaar-en-gedeployed`() {
        val statements = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString(" ").trim()

        assertNotNull(statements)
        assertTrue(
            statements.contains("ALTER TABLE \${schema}.issues ALTER COLUMN notify_mode SET DEFAULT 'als-klaar-en-gedeployed';"),
            "V27 moet exact de kolomdefault verschuiven, gevonden: $statements",
        )
        assertEquals("als-klaar-en-gedeployed", NotifyMode.WHEN_DONE_AND_DEPLOYED.trackerValue)
    }

    @Test
    fun `V27 bevat geen backfill van bestaande rijen`() {
        val statements = sql.lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString(" ")
            .uppercase()

        assertFalse(statements.contains("UPDATE "), "V27 mag geen UPDATE bevatten (AC 6): $statements")
        assertFalse(statements.contains("DELETE "), "V27 mag geen DELETE bevatten (AC 6): $statements")
        assertEquals(1, statements.count { it == ';' }, "V27 hoort uit precies één statement te bestaan")
    }
}
