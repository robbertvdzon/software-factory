package nl.vdzon.softwarefactory.maintenance.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.PackageCleanupRule
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.config.ReleaseCleanupConfig
import nl.vdzon.softwarefactory.config.ReleasePrefixRule
import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRecord
import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRepository
import nl.vdzon.softwarefactory.maintenance.repositories.NewMaintenanceCleanupRun
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dekt de historie-kant van [MaintenanceCleanupScheduler] (SF-1913): per project per tick precies
 * één rij — ook bij 0 verwijderingen, bij dry-run en bij een gefaalde ronde — plus de retentie en
 * het definitief verdwijnen van de Telegram-melding. Het opruim-algoritme zelf is elders gedekt
 * ([ReleaseRetentionPlannerTest], [PackageVersionRetentionPlannerTest]).
 */
class MaintenanceCleanupSchedulerTest {

    @Test
    fun `een run met verwijderingen legt de aantallen en de verwijderde namen vast`() {
        val repository = InMemoryRunRepository()
        val scheduler = scheduler(repository)

        scheduler.tick()

        val run = repository.runs.single()
        assertEquals("sf", run.project)
        assertEquals(CleanupKinds.GITHUB_RELEASES, run.kind)
        // Generieke tellers = releases + package-versions samen; de uitsplitsing zit in `details`.
        assertEquals(3, run.itemsDeleted)
        assertEquals(2, run.itemsKept)
        assertEquals(2, run.details.releasesDeleted)
        assertEquals(1, run.details.releasesKept)
        assertEquals(1, run.details.packagesDeleted)
        assertEquals(1, run.details.packagesKept)
        assertEquals(false, run.dryRun)
        assertNull(run.error)
        assertEquals(listOf("v1.0.1", "v1.0.0"), run.details.releaseTags)
        assertEquals(listOf("app #11 (sha-oldold)"), run.details.packageVersions)
        assertTrue(run.finishedAt >= run.startedAt)
    }

    @Test
    fun `de daadwerkelijke deletes gaan naar GitHub`() {
        val releases = FakeReleaseClient()
        val packages = FakePackageClient()
        val scheduler = scheduler(InMemoryRunRepository(), releaseClient = releases, packageClient = packages)

        scheduler.tick()

        assertEquals(listOf("v1.0.1", "v1.0.0"), releases.deleted)
        assertEquals(listOf(11L), packages.deleted)
    }

    @Test
    fun `een run zonder verwijderingen levert tóch een rij op — bewijs dat de scheduler gedraaid heeft`() {
        val repository = InMemoryRunRepository()
        val scheduler = scheduler(
            repository,
            releaseClient = FakeReleaseClient(releases = listOf(release(3, "v1.0.2", "2026-03-01"))),
            packageClient = FakePackageClient(versions = listOf(version(13, "2026-03-01", listOf("sha-new")))),
        )

        scheduler.tick()

        val run = repository.runs.single()
        assertEquals(0, run.itemsDeleted)
        assertEquals(2, run.itemsKept)
        assertEquals(0, run.details.releasesDeleted)
        assertEquals(0, run.details.packagesDeleted)
        assertEquals(1, run.details.releasesKept)
        assertEquals(1, run.details.packagesKept)
        assertEquals(emptyList<String>(), run.details.releaseTags)
        assertEquals(emptyList<String>(), run.details.packageVersions)
        assertNull(run.error)
    }

    @Test
    fun `dry-run verwijdert niets maar legt de geplande aantallen vast`() {
        val repository = InMemoryRunRepository()
        val releases = FakeReleaseClient()
        val packages = FakePackageClient()
        val scheduler = scheduler(repository, releaseClient = releases, packageClient = packages, dryRun = true)

        scheduler.tick()

        val run = repository.runs.single()
        assertEquals(true, run.dryRun)
        assertEquals(2, run.details.releasesDeleted)
        assertEquals(1, run.details.packagesDeleted)
        assertEquals(listOf("v1.0.1", "v1.0.0"), run.details.releaseTags)
        assertEquals(emptyList<String>(), releases.deleted)
        assertEquals(emptyList<Long>(), packages.deleted)
    }

    @Test
    fun `een mislukte projectrun legt de fout vast en laat de andere projecten doorlopen`() {
        val repository = InMemoryRunRepository()
        val scheduler = MaintenanceCleanupScheduler(
            projects = projects("kapot" to "https://github.com/robbert/kapot", "sf" to SF_REPO),
            releaseClient = object : FakeReleaseClient() {
                override fun listReleases(slug: String): List<ReleaseInfo> =
                    if (slug == "robbert/kapot") error("GitHub gaf 500") else super.listReleases(slug)
            },
            packageClient = FakePackageClient(),
            protectedShaSource = FakeProtectedShaSource(),
            runRepository = repository,
            settings = MaintenanceCleanupSettings(dryRun = false, retentionDays = 90),
        )

        scheduler.tick()

        assertEquals(listOf("kapot", "sf"), repository.runs.map { it.project })
        assertEquals("GitHub gaf 500", repository.runs.first().error)
        assertEquals(0, repository.runs.first().itemsDeleted)
        assertNull(repository.runs.last().error)
        assertEquals(2, repository.runs.last().details.releasesDeleted)
    }

    @Test
    fun `een project zonder GitHub-slug wordt overgeslagen en levert geen rij op`() {
        val repository = InMemoryRunRepository()
        val scheduler = scheduler(repository, projects = projects("sf" to "niet-eens-een-url"))

        scheduler.tick()

        assertEquals(emptyList<MaintenanceCleanupRunRecord>(), repository.runs)
    }

    @Test
    fun `een falende repository laat de cleanup zelf niet omvallen`() {
        val releases = FakeReleaseClient()
        val scheduler = scheduler(ExplodingRunRepository(), releaseClient = releases)

        scheduler.tick()

        assertEquals(listOf("v1.0.1", "v1.0.0"), releases.deleted)
    }

    @Test
    fun `aan het eind van de tick worden runs ouder dan de retentiegrens opgeruimd`() {
        val repository = InMemoryRunRepository()
        val before = OffsetDateTime.now()

        scheduler(repository, retentionDays = 30).tick()

        val cutoff = assertNotNull(repository.cutoffs.single())
        assertTrue(cutoff >= before.minusDays(30).minusMinutes(1), "cutoff $cutoff ligt te ver in het verleden")
        assertTrue(cutoff <= OffsetDateTime.now().minusDays(30), "cutoff $cutoff ligt niet 30 dagen terug")
    }

    @Test
    fun `een geplande ronde markeert de rijen als scheduled`() {
        val repository = InMemoryRunRepository()

        scheduler(repository).tick()

        assertEquals(CleanupTriggers.SCHEDULED, repository.runs.single().trigger)
    }

    @Test
    fun `een handmatige ronde draait dezelfde opruiming en markeert de rijen als manual`() {
        val repository = InMemoryRunRepository()
        val releases = FakeReleaseClient()

        scheduler(repository, releaseClient = releases).runCleanupRoundLocked(CleanupTriggers.MANUAL)

        assertEquals(CleanupTriggers.MANUAL, repository.runs.single().trigger)
        // Exact dezelfde ronde als de cron: dezelfde deletes, geen tweede implementatie.
        assertEquals(listOf("v1.0.1", "v1.0.0"), releases.deleted)
        // De log-retentie hoort bij de cron, niet bij de knop.
        assertEquals(emptyList<OffsetDateTime>(), repository.cutoffs)
    }

    @Test
    fun `draait er al een github-ronde, dan slaat de tick over (en ruimt alleen de log op)`() {
        val repository = InMemoryRunRepository()
        val guard = CleanupRunGuard.inMemory()
        val releases = FakeReleaseClient()
        val scheduler = scheduler(repository, releaseClient = releases).apply { configureRunGuard(guard) }
        assertTrue(guard.tryStart(CleanupKinds.GITHUB_RELEASES), "de bewaking hoort vrij te zijn")

        scheduler.tick()

        assertEquals(emptyList<MaintenanceCleanupRunRecord>(), repository.runs)
        assertEquals(emptyList<String>(), releases.deleted)
        assertEquals(1, repository.cutoffs.size)
    }

    /**
     * Mutatiebestendige vorm van "geen Telegram-melding meer": geen enkele constructorparameter is
     * nog een telegram-type. Een test op "er is geen bericht verstuurd" zou vacuüm groen zijn zodra
     * de gateway terugkeert met een andere aanroepvoorwaarde.
     */
    @Test
    fun `de scheduler kent geen Telegram-afhankelijkheid meer`() {
        val parameterTypes = MaintenanceCleanupScheduler::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }

        assertTrue(parameterTypes.none { it.contains("telegram", ignoreCase = true) }, "Telegram zit nog in $parameterTypes")
        assertTrue(
            parameterTypes.any { it == MaintenanceCleanupRunRepository::class.java.name },
            "De run-historie-repository hoort juist wél in de constructor te staan: $parameterTypes",
        )
    }

    /**
     * SF-1938: vóór de paginatie zag de scheduler maar één GitHub-pagina, waardoor één ronde
     * hooguit ~85 versies opruimde en de achterstand dagen bleef staan. Met een client die alle
     * 350 versies teruggeeft, moet één ronde de volledige achterstand wegwerken.
     */
    @Test
    fun `één ronde ruimt de volledige achterstand op - 350 versions met keep 15`() {
        val repository = InMemoryRunRepository()
        val packages = FakePackageClient(versions = (1..350).map { version(it.toLong(), createdAt(it), listOf("sha-$it")) })
        val scheduler = scheduler(
            repository,
            projects = projects(listOf("sf" to SF_REPO), packageKeep = 15),
            packageClient = packages,
        )

        scheduler.tick()

        assertEquals(335, packages.deleted.size)
        assertEquals(335, repository.runs.single().details.packagesDeleted)
        assertEquals(15, repository.runs.single().details.packagesKept)
    }

    /**
     * De beschermingslijst (open PR-head-sha's) is een *veiligheids*lijst: is die onvolledig, dan
     * mag er deze ronde niets weg — anders verliest een lopende preview-PR z'n image.
     */
    @Test
    fun `een onvolledige beschermingslijst slaat de package-cleanup over en logt dat als fout`() {
        val repository = InMemoryRunRepository()
        val packages = FakePackageClient()
        val releases = FakeReleaseClient()

        scheduler(
            repository,
            releaseClient = releases,
            packageClient = packages,
            protectedShaSource = FakeProtectedShaSource(complete = false),
        ).tick()

        assertEquals(emptyList<Long>(), packages.deleted)
        val run = repository.runs.single()
        assertEquals(0, run.details.packagesDeleted)
        assertTrue(run.error!!.contains("package-cleanup"), "onverwachte foutmelding: ${run.error}")
        // De release-cleanup van diezelfde ronde blijft gewoon staan.
        assertEquals(listOf("v1.0.1", "v1.0.0"), releases.deleted)
        assertEquals(2, run.details.releasesDeleted)
    }

    // --- wiring ------------------------------------------------------------------------------

    private fun scheduler(
        repository: MaintenanceCleanupRunRepository,
        projects: ProjectConfiguration = projects("sf" to SF_REPO),
        releaseClient: GitHubReleaseCleanupClient = FakeReleaseClient(),
        packageClient: GitHubPackageCleanupClient = FakePackageClient(),
        dryRun: Boolean = false,
        retentionDays: Long = 90,
        protectedShaSource: GitHubProtectedShaSource = FakeProtectedShaSource(),
    ) = MaintenanceCleanupScheduler(
        projects = projects,
        releaseClient = releaseClient,
        packageClient = packageClient,
        protectedShaSource = protectedShaSource,
        runRepository = repository,
        settings = MaintenanceCleanupSettings(dryRun = dryRun, retentionDays = retentionDays),
    )

    private fun projects(vararg entries: Pair<String, String>) = projects(entries.toList())

    private fun projects(entries: List<Pair<String, String>>, packageKeep: Int = 1) = ProjectConfiguration(
        repos = entries.toMap(),
        releaseCleanupConfigs = entries.associate {
            it.first to ReleaseCleanupConfig(
                releases = listOf(ReleasePrefixRule(prefix = "v", keep = 1)),
                packages = listOf(PackageCleanupRule(name = "app", keep = packageKeep)),
            )
        },
    )

    /** Verzamelt wat de scheduler wil vastleggen; vervangt de JDBC-kant volledig. */
    private open class InMemoryRunRepository : MaintenanceCleanupRunRepository(JdbcTemplate(), fakeSecrets()) {
        val runs = mutableListOf<MaintenanceCleanupRunRecord>()
        val cutoffs = mutableListOf<OffsetDateTime>()

        override fun add(run: NewMaintenanceCleanupRun): MaintenanceCleanupRunRecord {
            val record = MaintenanceCleanupRunRecord(
                id = runs.size + 1L,
                kind = run.kind,
                project = run.project,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                itemsDeleted = run.itemsDeleted,
                itemsKept = run.itemsKept,
                dryRun = run.dryRun,
                error = run.error,
                details = run.details,
                trigger = run.trigger,
            )
            runs += record
            return record
        }

        override fun get(id: Long) = runs.firstOrNull { it.id == id }

        override fun recent(project: String?, kind: String?, limit: Int) = runs.reversed()

        override fun deleteOlderThan(cutoff: OffsetDateTime): Int {
            cutoffs += cutoff
            return 0
        }
    }

    /** Bewijst dat het wegschrijven fail-soft is: elke schrijfactie klapt. */
    private class ExplodingRunRepository : InMemoryRunRepository() {
        override fun add(run: NewMaintenanceCleanupRun): MaintenanceCleanupRunRecord = error("database weg")

        override fun deleteOlderThan(cutoff: OffsetDateTime): Int = error("database weg")
    }

    private open class FakeReleaseClient(
        private val releases: List<ReleaseInfo> = DEFAULT_RELEASES,
    ) : GitHubReleaseCleanupClient(fakeSecrets()) {
        val deleted = mutableListOf<String>()

        override fun listReleases(slug: String): List<ReleaseInfo> = releases

        override fun deleteRelease(slug: String, id: Long, tagName: String) {
            deleted += tagName
        }
    }

    private class FakePackageClient(
        private val versions: List<PackageVersionInfo> = DEFAULT_VERSIONS,
    ) : GitHubPackageCleanupClient(fakeSecrets()) {
        val deleted = mutableListOf<Long>()

        override fun listVersions(owner: String, packageName: String): List<PackageVersionInfo> = versions

        override fun deleteVersion(owner: String, packageName: String, id: Long) {
            deleted += id
        }
    }

    private class FakeProtectedShaSource(
        private val tags: Set<String> = emptySet(),
        private val complete: Boolean = true,
    ) : GitHubProtectedShaSource(fakeSecrets()) {
        override fun protectedTags(slug: String, manifestPaths: List<String>) = ProtectedTags(tags, complete)
    }

    private companion object {
        const val SF_REPO = "https://github.com/robbert/software-factory"

        /** Nieuwste eerst weggeschreven zodat de verwachte volgorde van de deletes vastligt. */
        val DEFAULT_RELEASES = listOf(
            release(3, "v1.0.2", "2026-03-01"),
            release(2, "v1.0.1", "2026-02-01"),
            release(1, "v1.0.0", "2026-01-01"),
        )

        val DEFAULT_VERSIONS = listOf(
            version(12, "2026-03-01", listOf("sha-newnew")),
            version(11, "2026-01-01", listOf("sha-oldold")),
        )

        fun release(id: Long, tag: String, publishedAt: String) = ReleaseInfo(id, tag, publishedAt)

        fun version(id: Long, createdAt: String, tags: List<String>) = PackageVersionInfo(id, createdAt, tags)

        /** Oplopende, lexicografisch sorteerbare tijdstempel — hoger volgnummer = nieuwer. */
        fun createdAt(index: Int) = "2026-01-01T00:00:00.%04dZ".format(index)

        fun fakeSecrets() = FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "token",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "test",
        )
    }
}
