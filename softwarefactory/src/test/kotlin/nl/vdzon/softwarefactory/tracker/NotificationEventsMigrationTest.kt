package nl.vdzon.softwarefactory.tracker

import kotlin.test.Test
import kotlin.test.assertContains

class NotificationEventsMigrationTest {
    private val sql = requireNotNull(
        javaClass.classLoader.getResource("db/migration/V34__notification_events.sql"),
    ).readText().replace(Regex("\\s+"), " ")

    @Test
    fun `V34 converteert alle vier legacy modes naar concrete events en verwijdert notify_mode`() {
        assertContains(sql, "WHEN 'geen' THEN ARRAY['QUESTION']::TEXT[]")
        assertContains(sql, "WHEN 'na-elke-stap' THEN ARRAY[ 'QUESTION', 'APPROVAL_REQUIRED', 'MANUAL_ACTION_REQUIRED', 'QUOTA_WAIT', 'ERROR', 'STEP_COMPLETED', 'WORKFLOW_COMPLETED' ]::TEXT[]")
        assertContains(sql, "WHEN 'als-klaar' THEN ARRAY['QUESTION', 'ERROR', 'WORKFLOW_COMPLETED']::TEXT[]")
        assertContains(sql, "WHEN 'als-klaar-en-gedeployed' THEN ARRAY['QUESTION', 'ERROR', 'DEPLOYED']::TEXT[]")
        assertContains(sql, "ALTER TABLE \${schema}.issues DROP COLUMN notify_mode;")
    }

    @Test
    fun `V34 is veilig opnieuw uitvoerbaar`() {
        assertContains(sql, "ADD COLUMN IF NOT EXISTS notification_events")
        assertContains(sql, "IF EXISTS (")
        assertContains(sql, "AND column_name = 'notify_mode'")
    }
}
