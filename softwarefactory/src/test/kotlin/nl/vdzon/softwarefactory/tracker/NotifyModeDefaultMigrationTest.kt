package nl.vdzon.softwarefactory.tracker

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class NotifyModeDefaultMigrationTest {
    private val sql = requireNotNull(
        javaClass.classLoader.getResource("db/migration/V29__notify_mode_creation_default.sql"),
    ).readText()

    @Test
    fun `V29 zet de notify_mode-kolomdefault op als-klaar-en-gedeployed`() {
        assertContains(
            sql.replace(Regex("\\s+"), " "),
            "ALTER TABLE \${schema}.issues ALTER COLUMN notify_mode SET DEFAULT 'als-klaar-en-gedeployed';",
        )
    }

    @Test
    fun `V29 bevat geen backfill van bestaande rijen`() {
        assertFalse(Regex("(?im)^\\s*UPDATE\\s+").containsMatchIn(sql))
    }
}
