package nl.vdzon.softwarefactory.e2e

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bouwstap 4 (e2e-plan): borgt dat login + één POST via [FactoryUiDriver] werkt tegen de
 * **echte**, volledig opgestarte Spring-app (met de drie naden vervangen door [E2eTestConfig]).
 *
 * Dit dekt meteen de "app boot schoon"-verificatie uit bouwstap 3: faalt de context-load of
 * de auth-plumbing, dan faalt deze test. De volledige refine→develop-flow volgt in bouwstap 5.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eTestConfig::class)
class FactoryUiDriverLoginTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Test
    fun `login en een POST tegen de echte app werken`() {
        val ui = FactoryUiDriver(rest, "http://localhost:$port").login()

        // Seed een story direct in de fake-YouTrack-state zodat de POST een bestaand issue raakt.
        val state = E2eTestConfig.FAKE_YOUTRACK.state
        val story = state.createIssue(summary = "Login smoke", key = "${state.projectKey}-9001")

        val response = ui.startDeveloping(story.key)

        // 303 (SEE_OTHER) én niet terug naar /login bewijst dat de remember-cookie geaccepteerd is.
        assertEquals(HttpStatus.SEE_OTHER, response.statusCode, "verwachtte een redirect na de POST")
        val location = response.headers.location
        assertNotNull(location, "redirect zonder Location-header")
        assertTrue(
            !location.path.startsWith("/login"),
            "POST werd naar /login geredirect → niet geauthenticeerd (location=$location)",
        )
    }
}
