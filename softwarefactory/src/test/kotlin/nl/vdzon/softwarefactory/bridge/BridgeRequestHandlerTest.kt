package nl.vdzon.softwarefactory.bridge

import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dekt [BridgeRequestHandler] per operatie tegen de bestaande fakes (zie
 * docs/ontwerp-bridge-dashboard.md §10): vertalen naar [FactoryDashboardService], nooit nieuwe
 * businesslogica. Wiring in [BridgeTestFixtures].
 */
class BridgeRequestHandlerTest {

    @Test
    fun `stories-list levert de bestaande stories-pagina als JSON-body`() {
        val handler = BridgeRequestHandler(
            BridgeTestFixtures.minimalDashboardService(
                issues = listOf(BridgeTestFixtures.issue("SF-1"), BridgeTestFixtures.issue("SF-2")),
            ),
        )

        val response = handler.handle(BridgeRequest(id = "r-1", operation = "stories.list"))

        assertEquals(true, response.ok)
        assertEquals("r-1", response.id)
        assertEquals(2, response.body?.path("issues")?.size())
    }

    @Test
    fun `myActions-count levert het aantal wachtende taken als JSON-body`() {
        val handler = BridgeRequestHandler(BridgeTestFixtures.minimalDashboardService(issues = emptyList()))

        val response = handler.handle(BridgeRequest(id = "r-2", operation = "myActions.count"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("count")?.asInt())
    }

    @Test
    fun `onbekende operatie geeft een foutresponse met UNKNOWN_OPERATION`() {
        val handler = BridgeRequestHandler(BridgeTestFixtures.minimalDashboardService(issues = emptyList()))

        val response = handler.handle(BridgeRequest(id = "r-3", operation = "does.not.exist"))

        assertEquals(false, response.ok)
        assertEquals("UNKNOWN_OPERATION", response.error?.code)
    }

    @Test
    fun `een YouTrack-fout laat stories-list soft-failen i-p-v de socket te breken`() {
        // FactoryDashboardService.stories() vangt tracker-fouten zelf af (errors-lijst, lege
        // issues) — dit dekt dat de bridge dat gedrag ongewijzigd doorgeeft, niet dat de bridge
        // zelf een exception onderschept (dat pad heeft geen van de fase-B-operaties nog).
        val handler = BridgeRequestHandler(BridgeTestFixtures.minimalDashboardService(issues = null))

        val response = handler.handle(BridgeRequest(id = "r-4", operation = "stories.list"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("issues")?.size())
        assertTrue((response.body?.path("errors")?.size() ?: 0) > 0)
    }
}
