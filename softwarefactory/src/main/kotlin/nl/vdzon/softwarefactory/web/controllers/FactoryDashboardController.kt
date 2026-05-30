package nl.vdzon.softwarefactory.web.controllers

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import nl.vdzon.softwarefactory.web.services.FactoryDashboardAuth
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.web.views.FactoryDashboardViews
import nl.vdzon.softwarefactory.youtrack.FactoryCommand
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
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
    fun login(
        @RequestParam("error", required = false) error: String?,
        @RequestParam("next", required = false) next: String?,
        request: HttpServletRequest,
        session: HttpSession,
    ): ResponseEntity<String> =
        if (auth.isAuthenticated(request, session)) {
            ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, next.safeNext())
                .body("")
        } else {
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(views.login(error = error != null, next = next.safeNext()))
        }

    @PostMapping("/login")
    fun loginSubmit(
        @RequestParam("username") username: String,
        @RequestParam("password") password: String,
        @RequestParam("next", required = false) next: String?,
        session: HttpSession,
    ): ResponseEntity<Void> =
        if (auth.login(session, username, password)) {
            redirect(next.safeNext(), auth.loginCookie())
        } else {
            redirect("/login?error=1&next=${next.safeNext().urlEncoded()}", auth.logoutCookie())
        }

    @PostMapping("/logout")
    fun logout(session: HttpSession): ResponseEntity<Void> {
        auth.logout(session)
        return redirect("/login", auth.logoutCookie())
    }

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun root(request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/dashboard") { views.dashboard(service.dashboard()) }

    @GetMapping("/dashboard", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun dashboard(request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/dashboard") { views.dashboard(service.dashboard()) }

    @GetMapping("/stories", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun stories(request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/stories") { views.stories(service.stories()) }

    @GetMapping("/stories/{storyKey}", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun storyDetail(@PathVariable storyKey: String, request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/stories/$storyKey") { views.storyDetail(service.storyDetail(storyKey)) }

    @GetMapping("/stories/{storyKey}/briefing", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun briefing(@PathVariable storyKey: String, request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/stories/$storyKey/briefing") { views.briefing(service.storyDetail(storyKey)) }

    @GetMapping("/stories/{storyKey}/screenshots", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun screenshots(@PathVariable storyKey: String, request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/stories/$storyKey/screenshots") { views.screenshots(service.screenshots(storyKey)) }

    @PostMapping("/stories/{storyKey}/commands/{command}")
    fun command(
        @PathVariable storyKey: String,
        @PathVariable command: String,
        request: HttpServletRequest,
        session: HttpSession,
    ): ResponseEntity<Void> {
        if (!auth.isAuthenticated(request, session)) {
            return redirect("/login?next=${"/stories/$storyKey".urlEncoded()}")
        }
        val factoryCommand = FactoryCommand.entries.firstOrNull { it.token == command }
            ?: return redirect("/stories/$storyKey?command=unknown")
        runCatching { service.queueCommand(storyKey, factoryCommand) }
            .onFailure { return redirect("/stories/$storyKey?command=failed") }
        return redirect("/stories/$storyKey?command=queued")
    }

    @PostMapping("/stories/{storyKey}/open-workspace")
    fun openWorkspace(
        @PathVariable storyKey: String,
        request: HttpServletRequest,
        session: HttpSession,
    ): ResponseEntity<Void> {
        if (!auth.isAuthenticated(request, session)) {
            return redirect("/login?next=${"/stories/$storyKey".urlEncoded()}")
        }
        runCatching { service.openWorkspaceInIntellij(storyKey) }
            .onFailure { return redirect("/stories/$storyKey?workspace=failed") }
        return redirect("/stories/$storyKey?workspace=opened")
    }

    @GetMapping("/agents", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun agents(request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/agents") { views.agents(service.agents()) }

    @GetMapping("/merged", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun merged(request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/merged") { views.merged(service.merged()) }

    @GetMapping("/downloads", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun downloads(request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/downloads") { views.downloads() }

    @GetMapping("/settings", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun settings(request: HttpServletRequest, session: HttpSession): String =
        authenticated(request, session, "/settings") { views.settings(service.settings(auth.username)) }

    private fun authenticated(request: HttpServletRequest, session: HttpSession, next: String, renderer: () -> String): String =
        if (auth.isAuthenticated(request, session)) renderer() else views.login(next = next)

    private fun redirect(location: String, cookie: ResponseCookie? = null): ResponseEntity<Void> {
        val builder = ResponseEntity.status(HttpStatus.SEE_OTHER)
            .header(HttpHeaders.LOCATION, location)
        cookie?.let { builder.header(HttpHeaders.SET_COOKIE, it.toString()) }
        return builder.build()
    }

    private fun String?.safeNext(): String =
        this?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/dashboard"

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8)
}
