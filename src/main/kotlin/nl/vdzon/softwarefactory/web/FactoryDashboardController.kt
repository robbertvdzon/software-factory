package nl.vdzon.softwarefactory.web

import jakarta.servlet.http.HttpSession
import nl.vdzon.softwarefactory.tracker.FactoryCommand
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Controller
class FactoryDashboardController(
    private val auth: FactoryDashboardAuth,
    private val service: FactoryDashboardService,
    private val views: FactoryDashboardViews,
) {
    @GetMapping("/login", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun login(
        @RequestParam("error", required = false) error: String?,
        @RequestParam("next", required = false) next: String?,
    ): String =
        views.login(error = error != null, next = next.safeNext())

    @PostMapping("/login")
    fun loginSubmit(
        @RequestParam("username") username: String,
        @RequestParam("password") password: String,
        @RequestParam("next", required = false) next: String?,
        session: HttpSession,
    ): ResponseEntity<Void> =
        if (auth.login(session, username, password)) {
            redirect(next.safeNext())
        } else {
            redirect("/login?error=1&next=${next.safeNext().urlEncoded()}")
        }

    @PostMapping("/logout")
    fun logout(session: HttpSession): ResponseEntity<Void> {
        auth.logout(session)
        return redirect("/login")
    }

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun root(session: HttpSession): String =
        authenticated(session, "/dashboard") { views.dashboard(service.dashboard()) }

    @GetMapping("/dashboard", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun dashboard(session: HttpSession): String =
        authenticated(session, "/dashboard") { views.dashboard(service.dashboard()) }

    @GetMapping("/stories", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun stories(session: HttpSession): String =
        authenticated(session, "/stories") { views.stories(service.stories()) }

    @GetMapping("/stories/{storyKey}", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun storyDetail(@PathVariable storyKey: String, session: HttpSession): String =
        authenticated(session, "/stories/$storyKey") { views.storyDetail(service.storyDetail(storyKey)) }

    @GetMapping("/stories/{storyKey}/briefing", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun briefing(@PathVariable storyKey: String, session: HttpSession): String =
        authenticated(session, "/stories/$storyKey/briefing") { views.briefing(service.storyDetail(storyKey)) }

    @GetMapping("/stories/{storyKey}/screenshots", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun screenshots(@PathVariable storyKey: String, session: HttpSession): String =
        authenticated(session, "/stories/$storyKey/screenshots") { views.screenshots(service.screenshots(storyKey)) }

    @PostMapping("/stories/{storyKey}/commands/{command}")
    fun command(
        @PathVariable storyKey: String,
        @PathVariable command: String,
        session: HttpSession,
    ): ResponseEntity<Void> {
        if (!auth.isAuthenticated(session)) {
            return redirect("/login?next=${"/stories/$storyKey".urlEncoded()}")
        }
        val factoryCommand = FactoryCommand.entries.firstOrNull { it.token == command }
            ?: return redirect("/stories/$storyKey?command=unknown")
        runCatching { service.queueCommand(storyKey, factoryCommand) }
            .onFailure { return redirect("/stories/$storyKey?command=failed") }
        return redirect("/stories/$storyKey?command=queued")
    }

    @GetMapping("/agents", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun agents(session: HttpSession): String =
        authenticated(session, "/agents") { views.agents(service.agents()) }

    @GetMapping("/merged", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun merged(session: HttpSession): String =
        authenticated(session, "/merged") { views.merged(service.merged()) }

    @GetMapping("/downloads", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun downloads(session: HttpSession): String =
        authenticated(session, "/downloads") { views.downloads() }

    @GetMapping("/settings", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun settings(session: HttpSession): String =
        authenticated(session, "/settings") { views.settings(service.settings(auth.username)) }

    private fun authenticated(session: HttpSession, next: String, renderer: () -> String): String =
        if (auth.isAuthenticated(session)) renderer() else views.login(next = next)

    private fun redirect(location: String): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.SEE_OTHER)
            .header(HttpHeaders.LOCATION, location)
            .build()

    private fun String?.safeNext(): String =
        this?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/dashboard"

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8)
}
