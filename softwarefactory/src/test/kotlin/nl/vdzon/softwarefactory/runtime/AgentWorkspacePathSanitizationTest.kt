package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regressietest voor de audit "docker: invalid spec: too many colons"-bug: een synthetische
 * storyKey als `"AUDIT:project:auditType"` belandt via [AgentWorkspaceFactory.create] als
 * host-pad in `docker run -v <pad>:/work`, waar een `:` de volume-spec breekt.
 */
class AgentWorkspacePathSanitizationTest {
    @Test
    fun `dubbele punten in de storyKey worden vervangen zodat het pad geen docker volume-spec breekt`() {
        assertEquals(
            "AUDIT-personal-feed-documentation",
            AgentWorkspaceFactory.sanitizeForPath("AUDIT:personal-feed:documentation"),
        )
    }

    @Test
    fun `een gewone story-key zonder speciale tekens blijft ongewijzigd`() {
        assertEquals("SF-1234", AgentWorkspaceFactory.sanitizeForPath("SF-1234"))
    }
}
