package nl.vdzon.softwarefactory.e2e

import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

/**
 * Speelt "de gebruiker" in de end-to-end integratietest (bouwstap 4 uit het e2e-plan).
 *
 * Praat via een [TestRestTemplate] op de random server-port tegen de **echte**
 * controller-endpoints van [nl.vdzon.softwarefactory.web.controllers.FactoryDashboardController].
 * Eerst `POST /login` (admin/admin); de auth-laag geeft een stateless remember-cookie
 * (`sf-dashboard-login`, HMAC-getekend) terug die we vasthouden en op elke vervolg-POST
 * meesturen, zodat [nl.vdzon.softwarefactory.web.services.FactoryDashboardAuth.isAuthenticated]
 * de calls accepteert.
 *
 * TestRestTemplate volgt standaard geen redirects, dus de 302 die de controllers teruggeven
 * blijft zichtbaar — handig om login en de POSTs te verifiëren.
 */
class FactoryUiDriver(
    private val rest: TestRestTemplate,
    private val baseUrl: String,
    private val username: String = "admin",
    private val password: String = "admin",
) {
    /** De remember-cookie (`name=value`) die we na login meesturen. */
    private var sessionCookie: String? = null

    /** Logt in als dashboard-gebruiker en bewaart de remember-cookie. Idempotent. */
    fun login(): FactoryUiDriver {
        val response = post("/login", form("username" to username, "password" to password))
        val setCookie = response.headers[HttpHeaders.SET_COOKIE].orEmpty()
        sessionCookie = setCookie
            .map { it.substringBefore(';') }
            .firstOrNull { it.startsWith("$REMEMBER_COOKIE=") && it.substringAfter('=').isNotBlank() }
            ?: error("Login leverde geen geldige $REMEMBER_COOKIE-cookie op (status=${response.statusCode}, set-cookie=$setCookie)")
        check(response.statusCode == HttpStatus.SEE_OTHER) {
            "Verwachtte 303 na login, kreeg ${response.statusCode}"
        }
        return this
    }

    /** `POST /stories/{key}/story-phase` met de gegeven phase + (optioneel) antwoord-comment. */
    fun answerStory(key: String, answer: String, phase: String = "questions-answered") =
        authedPost("/stories/$key/story-phase", form("phase" to phase, "comment" to answer))

    /** `POST /stories/{key}/start-developing`. */
    fun startDeveloping(key: String) =
        authedPost("/stories/$key/start-developing", form())

    /** `POST /stories/{key}/subtask-phase` met de gegeven phase + (optioneel) antwoord-comment. */
    fun answerSubtask(key: String, answer: String, phase: String = "development-questions-answered") =
        authedPost("/stories/$key/subtask-phase", form("phase" to phase, "comment" to answer))

    /** Generieke story-phase-overgang zonder comment (bijv. approve-stappen). */
    fun setStoryPhase(key: String, phase: String) =
        authedPost("/stories/$key/story-phase", form("phase" to phase))

    /** Generieke subtask-phase-overgang zonder comment (bijv. approve-stappen). */
    fun setSubtaskPhase(key: String, phase: String) =
        authedPost("/stories/$key/subtask-phase", form("phase" to phase))

    private fun authedPost(path: String, body: MultiValueMap<String, String>) =
        post(path, body, requireAuth = true)

    private fun post(
        path: String,
        body: MultiValueMap<String, String>,
        requireAuth: Boolean = false,
    ) = run {
        if (requireAuth) checkNotNull(sessionCookie) { "Niet ingelogd: roep eerst login() aan." }
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            sessionCookie?.let { set(HttpHeaders.COOKIE, it) }
        }
        rest.postForEntity(baseUrl + path, HttpEntity(body, headers), Void::class.java)
    }

    private fun form(vararg pairs: Pair<String, String>): MultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply { pairs.forEach { (k, v) -> add(k, v) } }

    companion object {
        private const val REMEMBER_COOKIE = "sf-dashboard-login"
    }
}
