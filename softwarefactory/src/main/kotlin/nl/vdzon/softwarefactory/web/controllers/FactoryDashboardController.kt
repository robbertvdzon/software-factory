package nl.vdzon.softwarefactory.web.controllers

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.nightly.NightlyScheduler
import nl.vdzon.softwarefactory.web.services.DashboardEventBus
import nl.vdzon.softwarefactory.web.services.FactoryDashboardAuth
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.web.services.FactoryOperationsService
import nl.vdzon.softwarefactory.web.services.FactoryProcessService
import nl.vdzon.softwarefactory.web.views.FactoryDashboardViews
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Dashboard-routes. Login-afdwinging gebeurt centraal in
 * [nl.vdzon.softwarefactory.web.config.DashboardAuthInterceptor] — handlers hier mogen aannemen
 * dat de gebruiker is ingelogd, behálve de expliciete uitzonderingen (`/login`, `/logout`,
 * `/events` en `/my-actions/count`) die hun eigen afhandeling houden.
 */
@Controller
class FactoryDashboardController(
    private val auth: FactoryDashboardAuth,
    private val service: FactoryDashboardService,
    private val operations: FactoryOperationsService,
    private val views: FactoryDashboardViews,
    private val eventBus: DashboardEventBus,
    private val processService: FactoryProcessService,
    private val nightlyScheduler: NightlyScheduler,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * POST-failures worden naar de gebruiker platgeslagen tot een `?x=failed`-redirect;
     * log hier de oorzaak, anders verdwijnt die spoorloos.
     */
    private fun failed(action: String, key: String, cause: Throwable, target: String): ResponseEntity<Void> {
        logger.warn("Dashboard-actie '{}' voor {} is mislukt", action, key, cause)
        return redirect(target)
    }

    /**
     * Server-Sent Events: pusht een "changed"-signaal naar de browser zodat die z'n data ververst.
     * Eigen auth-afhandeling (géén interceptor/redirect): zonder login een lege, direct gesloten stream.
     */
    @GetMapping("/events")
    @ResponseBody
    fun events(request: HttpServletRequest, session: HttpSession): SseEmitter {
        if (!auth.isAuthenticated(request, session)) {
            return SseEmitter().also { it.complete() }
        }
        return eventBus.register()
    }

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
    fun root(): String = views.dashboard(service.dashboard())

    @GetMapping("/dashboard", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun dashboard(): String = views.dashboard(service.dashboard())

    @GetMapping("/stories", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun stories(): String = views.stories(service.stories())

    @PostMapping("/stories/create")
    fun createStory(
        @RequestParam("project") project: String,
        @RequestParam("title") title: String,
        @RequestParam("description", required = false) description: String?,
        @RequestParam("repo", required = false) repo: String?,
        @RequestParam("aiSupplier", required = false) aiSupplier: String?,
        @RequestParam("aiModel", required = false) aiModel: String?,
        @RequestParam("start", required = false) start: String?,
        @RequestParam("autoApprove", required = false) autoApprove: String?,
    ): ResponseEntity<Void> =
        runCatching {
            service.createStory(project, title, description, repo, aiSupplier, aiModel, start != null, autoApprove != null)
        }.fold(
            onSuccess = { created ->
                eventBus.notifyChanged()
                redirect("/stories/${created.key.urlEncoded()}?created=ok")
            },
            onFailure = { failed("create-story", project, it, "/stories?create=failed") },
        )

    @GetMapping("/stories/{storyKey}", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun storyDetail(@PathVariable storyKey: String): String =
        views.storyDetail(service.storyDetail(storyKey))

    @GetMapping("/stories/{storyKey}/briefing", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun briefing(@PathVariable storyKey: String): String =
        views.briefing(service.storyDetail(storyKey))

    @GetMapping("/stories/{storyKey}/screenshots", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun screenshots(@PathVariable storyKey: String): String =
        views.screenshots(service.screenshots(storyKey))

    @PostMapping("/stories/{storyKey}/commands/{command}")
    fun command(
        @PathVariable storyKey: String,
        @PathVariable command: String,
        @RequestParam("comment", required = false) comment: String?,
        @RequestParam("returnTo", required = false) returnTo: String?,
    ): ResponseEntity<Void> {
        // De manual-approve-poort (SF-192) gebruikt approve/reject vanuit een actiekaart op het
        // story-scherm → terug naar die pagina via returnTo.
        val target = returnTo.safeReturn("/stories/$storyKey")
        val factoryCommand = FactoryCommand.entries.firstOrNull { it.token == command }
            ?: return redirect("$target?command=unknown")
        // De optionele reden (bv. een afkeurreden) wordt als feedback meegegeven aan het commando.
        runCatching { operations.queueCommand(storyKey, factoryCommand, comment) }
            .onFailure { return failed("command:$command", storyKey, it, "$target?command=failed") }
        eventBus.notifyChanged()
        return redirect("$target?command=queued")
    }

    @PostMapping("/stories/{storyKey}/purge")
    fun purge(@PathVariable storyKey: String): ResponseEntity<Void> {
        runCatching { service.purgeStory(storyKey) }
            .onFailure { return failed("purge", storyKey, it, "/stories/$storyKey?purge=failed") }
        eventBus.notifyChanged()
        // De story-detailpagina bestaat niet meer → terug naar de lijst.
        return redirect("/stories?purged=${storyKey.urlEncoded()}")
    }

    @PostMapping("/stories/{storyKey}/story-phase")
    fun storyPhase(
        @PathVariable storyKey: String,
        @RequestParam("phase") phase: String,
        @RequestParam("comment", required = false) comment: String?,
        @RequestParam("returnTo", required = false) returnTo: String?,
    ): ResponseEntity<Void> {
        // Vanuit de "My actions"-inbox geeft het formulier returnTo mee → terug naar de inbox.
        val target = returnTo.safeReturn("/stories/$storyKey")
        runCatching { operations.setStoryPhase(storyKey, phase, comment) }
            .onFailure { return failed("story-phase:$phase", storyKey, it, "$target?phase=failed") }
        eventBus.notifyChanged()
        return redirect("$target?phase=updated")
    }

    @PostMapping("/stories/{storyKey}/start-refining")
    fun startRefining(@PathVariable storyKey: String): ResponseEntity<Void> {
        runCatching { service.startRefining(storyKey) }
            .onFailure { return failed("start-refining", storyKey, it, "/stories/$storyKey?refining=failed") }
        eventBus.notifyChanged()
        return redirect("/stories/$storyKey?refining=started")
    }

    @PostMapping("/stories/{storyKey}/start-developing")
    fun startDeveloping(@PathVariable storyKey: String): ResponseEntity<Void> {
        runCatching { service.startDeveloping(storyKey) }
            .onFailure { return failed("start-developing", storyKey, it, "/stories/$storyKey?developing=failed") }
        eventBus.notifyChanged()
        return redirect("/stories/$storyKey?developing=started")
    }

    @PostMapping("/stories/{storyKey}/set-auto-approve/{state}")
    fun setAutoApprove(
        @PathVariable storyKey: String,
        @PathVariable state: String,
    ): ResponseEntity<Void> {
        val enabled = state.equals("on", ignoreCase = true)
        runCatching { service.setAutoApproveFlag(storyKey, enabled) }
            .onFailure { return failed("set-auto-approve", storyKey, it, "/stories/$storyKey?auto-approve=failed") }
        eventBus.notifyChanged()
        return redirect("/stories/$storyKey?auto-approve=updated")
    }

    @PostMapping("/stories/{storyKey}/subtask-phase")
    fun subtaskPhase(
        @PathVariable storyKey: String,
        @RequestParam("phase") phase: String,
        @RequestParam("comment", required = false) comment: String?,
        @RequestParam("returnTo", required = false) returnTo: String?,
    ): ResponseEntity<Void> {
        // Een gesurfacede subtaak-actie op het story-scherm geeft returnTo mee → terug naar de story.
        val target = returnTo.safeReturn("/stories/$storyKey")
        runCatching { operations.setSubtaskPhase(storyKey, phase, comment) }
            .onFailure { return failed("subtask-phase:$phase", storyKey, it, "$target?phase=failed") }
        eventBus.notifyChanged()
        return redirect("$target?phase=updated")
    }

    @PostMapping("/stories/{storyKey}/open-workspace")
    fun openWorkspace(@PathVariable storyKey: String): ResponseEntity<Void> {
        runCatching { service.openWorkspaceInIntellij(storyKey) }
            .onFailure { return failed("open-workspace", storyKey, it, "/stories/$storyKey?workspace=failed") }
        return redirect("/stories/$storyKey?workspace=opened")
    }

    @GetMapping("/my-actions", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun myActions(): String = views.myActions(service.myActions())

    /**
     * Aantal openstaande acties als platte tekst — voedt het nav-badge-bolletje.
     * Eigen auth-afhandeling (géén interceptor/redirect): zonder login expliciet "0", zodat de
     * badge-poll op de loginpagina geen redirect-loops veroorzaakt.
     */
    @GetMapping("/my-actions/count", produces = [MediaType.TEXT_PLAIN_VALUE])
    @ResponseBody
    fun myActionsCount(request: HttpServletRequest, session: HttpSession): ResponseEntity<String> =
        if (!auth.isAuthenticated(request, session)) {
            ResponseEntity.ok("0")
        } else {
            ResponseEntity.ok(service.myActionsCount().toString())
        }

    @GetMapping("/projects", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun projects(): String = views.projects(service.projectsOverview())

    @PostMapping("/projects/{projectName}/force-deploy")
    fun forceDeploy(@PathVariable projectName: String): ResponseEntity<Void> =
        runCatching { service.forceProjectDeploy(projectName) }
            .fold(
                onSuccess = { redirect("/projects?deployed=ok") },
                onFailure = { failed("force-deploy", projectName, it, "/projects?deploy=failed") },
            )

    @GetMapping("/nightly", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun nightly(@RequestParam("run", required = false) run: String?): String =
        views.nightly(service.nightlyJobs(run))

    /** Start handmatig direct een nieuwe nightly-run (alle enabled jobs). Faalt als er al een run loopt. */
    @PostMapping("/nightly/run-now")
    fun runNightlyNow(): ResponseEntity<Void> {
        val started = runCatching { nightlyScheduler.startManualRun() }.getOrDefault(false)
        if (started) eventBus.notifyChanged()
        return redirect(if (started) "/nightly?run=started" else "/nightly?run=busy")
    }

    /** Onderbreekt de lopende nightly-run (markeert resterende jobs cancelled en sluit de run). */
    @PostMapping("/nightly/stop")
    fun stopNightlyRun(): ResponseEntity<Void> {
        val stopped = runCatching { nightlyScheduler.stopActiveRun() }.getOrDefault(false)
        if (stopped) eventBus.notifyChanged()
        return redirect(if (stopped) "/nightly?run=stopped" else "/nightly?run=stop-none")
    }

    @PostMapping("/nightly/create-story")
    fun createNightlyStory(
        @RequestParam("project") project: String,
        @RequestParam("jobName") jobName: String,
    ): ResponseEntity<Void> =
        runCatching { service.createNightlyStory(project, jobName) }
            .fold(
                onSuccess = { created ->
                    eventBus.notifyChanged()
                    redirect("/stories/${created.key.urlEncoded()}?created=ok")
                },
                onFailure = { failed("nightly-create-story", jobName, it, "/nightly?create=failed") },
            )

    @GetMapping("/agents", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun agents(): String = views.agents(service.agents())

    @GetMapping("/merged", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun merged(): String = views.merged(service.merged())

    @GetMapping("/downloads", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun downloads(): String = views.downloads()

    @GetMapping("/settings", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun settings(@RequestParam("nightly", required = false) nightly: String?): String =
        views.settings(service.settings(auth.username, nightly))

    /** Schrijft de nachtelijke-scheduler-settings weg (master-switch + start-/summary-tijd in NL-tijd). */
    @PostMapping("/settings/nightly")
    fun saveNightlySettings(
        @RequestParam("enabled", required = false) enabled: String?,
        @RequestParam("startTime") startTime: String,
        @RequestParam("summaryTime") summaryTime: String,
    ): ResponseEntity<Void> =
        runCatching {
            service.saveNightlySettings(enabled = enabled != null, startTime = startTime, summaryTime = summaryTime)
        }.fold(
            onSuccess = { redirect("/settings?nightly=saved#nightly") },
            onFailure = { redirect("/settings?nightly=invalid#nightly") },
        )

    /** Stopt de JVM (code 0); de bash-loop start de factory daarna opnieuw met de nieuwste code. */
    @PostMapping("/admin/restart", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun restart(): ResponseEntity<String> {
        processService.requestRestart()
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(views.restarting())
    }

    /** Schrijft het stop-signaal en stopt de JVM; de bash-loop ziet het signaal en stopt zelf ook. */
    @PostMapping("/admin/stop", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun stop(): ResponseEntity<String> {
        processService.requestStop()
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(views.stopped())
    }

    private fun redirect(location: String, cookie: ResponseCookie? = null): ResponseEntity<Void> {
        val builder = ResponseEntity.status(HttpStatus.SEE_OTHER)
            .header(HttpHeaders.LOCATION, location)
        cookie?.let { builder.header(HttpHeaders.SET_COOKIE, it.toString()) }
        return builder.build()
    }

    private fun String?.safeNext(): String =
        SafeRedirect.localPath(this, "/dashboard")

    private fun String?.safeReturn(default: String): String =
        SafeRedirect.localPath(this, default)

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8)
}
