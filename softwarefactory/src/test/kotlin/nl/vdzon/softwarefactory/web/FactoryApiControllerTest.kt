package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.web.controllers.FactoryApiController
import nl.vdzon.softwarefactory.web.models.FactoryVersionInfo
import nl.vdzon.softwarefactory.web.services.FactoryProcessService
import nl.vdzon.softwarefactory.web.services.FactoryVersionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import java.time.OffsetDateTime

/**
 * Unit-tests voor FactoryApiController.
 * FactoryVersionService en FactoryProcessService zijn final classes; we wikkelen ze in
 * lichte delegate-objecten die we van buitenaf kunnen sturen.
 */
class FactoryApiControllerTest {

    private val sampleInfo = FactoryVersionInfo(
        startedAt = OffsetDateTime.parse("2026-01-01T10:00:00Z"),
        branch = "main",
        commitShort = "abc1234",
        commitSubject = "Test commit",
        commitDate = "2026-01-01T09:00:00Z",
        dirty = false,
    )

    /** Minimalistische wrapper omdat FactoryVersionService niet open is. */
    private inner class StubVersionService : FactoryVersionService() {
        fun stubInfo(): FactoryVersionInfo = sampleInfo
    }

    /** Minimalistische wrapper omdat FactoryProcessService niet open is. */
    private inner class StubProcessService : FactoryProcessService() {
        var restartCalled = false
    }

    /** ConfigApi-stub met een instelbare resolved-config (i.p.v. de echte secrets.env). */
    private fun envProvider(values: Map<String, String>): ConfigApi = object : ConfigApi {
        override fun resolvedValues(): Map<String, String> = values
    }

    private val emptyEnvProvider = envProvider(emptyMap())

    @Test
    fun `version endpoint returns 200 with correct fields`() {
        // We testen de controleflow van de controller via een directe aanroep.
        // StubVersionService erft de logica maar we kunnen niet eenvoudig de info() overschrijven
        // zonder open classes. Gebruik de echte service (geeft onbekende git-waarden terug in CI).
        // We valideren alleen dat het endpoint 200 teruggeeft en de velden aanwezig zijn.
        val versionService = FactoryVersionService()
        val processService = FactoryProcessService()
        val controller = FactoryApiController(versionService, processService, emptyEnvProvider)

        val response = controller.version()

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assert(body!!.containsKey("commitHash")) { "commitHash moet aanwezig zijn" }
        assert(body.containsKey("commitDate")) { "commitDate moet aanwezig zijn" }
        assert(body.containsKey("branch")) { "branch moet aanwezig zijn" }
    }

    @Test
    fun `restart returns 401 when SF_FACTORY_API_TOKEN is not set`() {
        val versionService = FactoryVersionService()
        val processService = FactoryProcessService()
        val controller = FactoryApiController(versionService, processService, emptyEnvProvider)

        val request = MockHttpServletRequest("POST", "/api/restart")
        request.addHeader("Authorization", "Bearer some-token")

        // In de test-omgeving is SF_FACTORY_API_TOKEN niet gezet → verwacht 401.
        val response = controller.restart(request)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `restart accepts token resolved from factory config not process env`() {
        val versionService = FactoryVersionService()
        val processService = FactoryProcessService()
        // Token komt uit de factory-config (zoals secrets.env), niet uit System.getenv.
        val controller = FactoryApiController(
            versionService,
            processService,
            envProvider(mapOf("SF_FACTORY_API_TOKEN" to "secret-from-file")),
        )

        val request = MockHttpServletRequest("POST", "/api/restart")
        request.addHeader("Authorization", "Bearer secret-from-file")

        val response = controller.restart(request)
        assertEquals(HttpStatus.OK, response.statusCode)
    }
}
