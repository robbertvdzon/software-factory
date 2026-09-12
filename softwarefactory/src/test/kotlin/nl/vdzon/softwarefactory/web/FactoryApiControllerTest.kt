package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.dashboard.services.FactoryVersionService
import nl.vdzon.softwarefactory.web.controllers.FactoryApiController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class FactoryApiControllerTest {

    @Test
    fun `version endpoint returns 200 with the version fields`() {
        val controller = FactoryApiController(FactoryVersionService())

        val response = controller.version()

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assert(body!!.containsKey("commitHash")) { "commitHash moet aanwezig zijn" }
        assert(body.containsKey("commitDate")) { "commitDate moet aanwezig zijn" }
        assert(body.containsKey("branch")) { "branch moet aanwezig zijn" }
    }

    @Test
    fun `version falls back to build info when the working directory is not a git checkout`() {
        val controller = FactoryApiController(StubVersion)

        val body = controller.version().body!!

        assertEquals("abc1234", body["commitHash"])
        assertEquals("main", body["branch"])
    }
}
