package nl.vdzon.softwarefactory.web.views

import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.MyActionsPageData
import nl.vdzon.softwarefactory.web.models.NightlyJobsPageData
import nl.vdzon.softwarefactory.web.models.ProjectsPageData
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.views.pages.AgentsView
import nl.vdzon.softwarefactory.web.views.pages.BriefingView
import nl.vdzon.softwarefactory.web.views.pages.DashboardView
import nl.vdzon.softwarefactory.web.views.pages.DownloadsView
import nl.vdzon.softwarefactory.web.views.pages.LoginView
import nl.vdzon.softwarefactory.web.views.pages.MergedView
import nl.vdzon.softwarefactory.web.views.pages.MyActionsView
import nl.vdzon.softwarefactory.web.views.pages.NightlyView
import nl.vdzon.softwarefactory.web.views.pages.ProjectsView
import nl.vdzon.softwarefactory.web.views.pages.ScreenshotsView
import nl.vdzon.softwarefactory.web.views.pages.SettingsView
import nl.vdzon.softwarefactory.web.views.pages.StoriesView
import nl.vdzon.softwarefactory.web.views.pages.StoryDetailView
import nl.vdzon.softwarefactory.web.views.shared.DetailChrome
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.ListComponents
import nl.vdzon.softwarefactory.web.views.shared.StoryStatusPresenter
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Facade voor alle dashboard-pagina's. De daadwerkelijke HTML leeft per pagina in
 * [nl.vdzon.softwarefactory.web.views.pages] met gedeelde bouwstenen (layout, formatters,
 * actiekaarten, statusclassificatie) in [nl.vdzon.softwarefactory.web.views.shared]; deze
 * class bedraadt die onderdelen en delegeert, zodat controllers en tests één stabiel
 * aanspreekpunt houden (zelfde publieke methodes en constructor als vóór de opsplitsing).
 */
@Component
class FactoryDashboardViews(
    private val clock: Clock,
) {
    private val layout = HtmlLayout()
    private val formatters = Formatters(clock)
    private val lists = ListComponents(formatters)
    private val detailChrome = DetailChrome(layout)

    private val loginView = LoginView(layout)
    private val dashboardView = DashboardView(layout, lists)
    private val storiesView = StoriesView(layout, lists)
    private val storyDetailView = StoryDetailView(detailChrome, formatters)
    private val briefingView = BriefingView(detailChrome, formatters)
    private val screenshotsView = ScreenshotsView(detailChrome, formatters)
    private val agentsView = AgentsView(layout, lists)
    private val mergedView = MergedView(layout, formatters)
    private val projectsView = ProjectsView(layout, formatters)
    private val nightlyView = NightlyView(layout, formatters)
    private val myActionsView = MyActionsView(layout)
    private val downloadsView = DownloadsView(layout)
    private val settingsView = SettingsView(layout, formatters)

    fun login(error: Boolean = false, next: String = "/dashboard"): String = loginView.render(error, next)

    fun dashboard(page: DashboardPageData): String = dashboardView.render(page)

    fun stories(page: StoriesPageData): String = storiesView.render(page)

    fun storyDetail(page: StoryDetailPageData): String = storyDetailView.render(page)

    fun briefing(page: StoryDetailPageData): String = briefingView.render(page)

    fun screenshots(page: StoryDetailPageData): String = screenshotsView.render(page)

    fun agents(page: AgentsPageData): String = agentsView.render(page)

    fun merged(page: MergedPageData): String = mergedView.render(page)

    fun projects(page: ProjectsPageData): String = projectsView.render(page)

    fun nightly(page: NightlyJobsPageData): String = nightlyView.render(page)

    fun myActions(page: MyActionsPageData): String = myActionsView.render(page)

    fun downloads(): String = downloadsView.render()

    fun settings(page: SettingsPageData): String = settingsView.render(page)

    fun restarting(): String = settingsView.restarting()

    fun stopped(): String = settingsView.stopped()

    /**
     * Status-buckets voor het classificeren van de YouTrack board-lane (`State`-veld).
     * Het type blijft hier genest — afnemers (waaronder de tests) spreken 'm aan als
     * `FactoryDashboardViews.StatusBucket.X` en een genest type is niet te aliassen;
     * de classificatie-lógica leeft in [StoryStatusPresenter].
     */
    enum class StatusBucket(val attr: String) {
        FINISHED("finished"), IN_PROGRESS("in-progress"), TODO("todo")
    }

    /**
     * Delegaat naar [StoryStatusPresenter.classifyStatus]; blijft op de facade staan zodat
     * unit-tests (en eventuele andere afnemers) 'm kunnen aanroepen zonder de shared-package
     * te kennen. `internal` zodat het buiten de module verborgen blijft.
     */
    internal fun classifyStatus(status: String?): StatusBucket =
        StoryStatusPresenter.classifyStatus(status)

    /** Delegaat naar [StoryStatusPresenter.realStatus]; zie [classifyStatus] voor het waarom. */
    internal fun realStatus(issue: TrackerIssue, subtasks: List<TrackerIssue>, merged: Boolean): StoryStatusPresenter.StoryStatusView =
        StoryStatusPresenter.realStatus(issue, subtasks, merged)
}
