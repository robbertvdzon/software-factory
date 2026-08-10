package nl.vdzon.softwarefactory.pipeline

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.DeployTarget
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseInfo
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.core.contracts.ArgoApplicationStatus
import nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler
import nl.vdzon.softwarefactory.testsupport.FakeGitHubApi
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class DeploySubtaskHandlerTest {

    private val subtaskKey = "SF-102"
    private val parentKey = "SF-100"
    private val targetRepo = "git@github.com:robbert/sf.git"
    private val now = OffsetDateTime.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    // advanceChain zit niet meer in de handler-constructor maar gaat per process-aanroep mee.
    private val defaultAdvance: (TrackerIssue) -> IssueProcessResult = { IssueProcessResult.Chained(subtaskKey, null) }

    private fun subtask(phase: SubtaskPhase?, agentStartedAt: OffsetDateTime? = null) = TrackerIssue(
        key = subtaskKey,
        summary = "Deploy subtask",
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = targetRepo,
            repo = "softwarefactory",
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = agentStartedAt,
            paused = false,
            error = null,
            subtaskPhase = phase?.trackerValue,
        ),
        comments = emptyList(),
    )

    private fun parentIssue(projectName: String = "softwarefactory") = TrackerIssue(
        key = parentKey,
        summary = "Parent story",
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = targetRepo,
            repo = projectName,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
        ),
        comments = emptyList(),
    )

    private fun buildHandler(
        deployConfig: DeployConfig,
        capturedUpdates: MutableList<Pair<String, TrackerFieldUpdate>> = mutableListOf(),
        // Secrets zoals ConfigApi.resolvedValues() ze zou leveren (secrets.env e.d.).
        secrets: Map<String, String> = emptyMap(),
        probe: DeploymentStatusProbe = DeploymentStatusProbe { _, _ -> null },
        // Default: geen verwachte SHA bepaalbaar (latestSha=null) → verificatie valt terug op het
        // oude startedAt-/image-gedrag, zodat de bestaande scenario's ongewijzigd blijven.
        expectedSha: String? = null,
        // Multi-deployment-routing (SF-1): expliciete lijst-vorm deploy-doelen voor "softwarefactory".
        // Default null → alleen het enkelvoudige [deployConfig] hierboven telt (backward-compat pad).
        deployTargets: List<DeployTarget>? = null,
        // Story-diff-fake: de bestandspaden die de PR van [parentKey] zou wijzigen. Default null
        // simuleert "diff niet bepaalbaar" (fail-open: elk matchPaths-doel telt dan mee).
        changedFiles: List<String>? = null,
        prNumber: Int = 1,
        // SF-2: fake voor Skip-doelen met apkCheck: true. Default null → nooit gevonden, zodat
        // bestaande scenario's (geen apkCheck) ongewijzigd blijven.
        apkReleaseProbe: ApkReleaseProbe = ApkReleaseProbe { _, _, _ -> null },
        // Simuleert een tijdelijke trackerstoring op het lezen van de parent-story: getIssue gooit.
        parentReadFails: Boolean = false,
        // Regressie SF-1710/SF-1717: simuleert dat ManualCommandService de story-run al heeft
        // afgesloten (zoals na een échte merge gebeurt, vóórdat de DEPLOY-subtaak begint te pollen).
        // Default false = bestaande scenario's ongewijzigd (run blijft "open").
        closeStoryRunAfterSetup: Boolean = false,
    ): DeploySubtaskHandler {
        val tracker = object : TrackerApi {
            override fun getIssue(issueKey: String) =
                if (parentReadFails) error("tracker onbereikbaar voor $issueKey") else parentIssue()
            override fun parentStoryKey(subtaskKey: String) = parentKey
            override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
                capturedUpdates.add(issueKey to update)
            }

            override fun updateIssueDescription(issueKey: String, description: String) {}

            override fun updateIssueDescriptionSummary(issueKey: String, descriptionSummary: String) {}

            override fun transitionIssue(issueKey: String, statusName: String) {}
            override fun postAgentComment(issueKey: String, role: nl.vdzon.softwarefactory.core.AgentRole, message: String) = error("unused")
        }
        val resolver = ProjectConfiguration(
            mapOf("softwarefactory" to targetRepo),
            deployConfigs = mapOf("softwarefactory" to deployConfig),
            deployTargets = deployTargets?.let { mapOf("softwarefactory" to it) } ?: emptyMap(),
        )
        val configApi = object : ConfigApi {
            override fun resolvedValues(): Map<String, String> = secrets
        }
        val storyRuns = InMemoryStoryRunRepository()
        // Seed een run met targetRepo + base-branch main zodat expectedSha() een repo/branch heeft.
        val run = storyRuns.openOrCreate(parentKey, targetRepo)
        storyRuns.updatePullRequest(run.id, "feature", prNumber, null, "main", null, null, null, null)
        if (closeStoryRunAfterSetup) {
            storyRuns.close(run.id, "merged", now)
        }
        val gitHub = FakeGitHubApi(
            latestSha = expectedSha,
            changedFilesByPr = changedFiles?.let { mapOf(prNumber to it) } ?: emptyMap(),
        )
        return DeploySubtaskHandler(tracker, resolver, clock, configApi, probe, storyRuns, gitHub, apkReleaseProbe)
    }

    @Test
    fun `null phase returns Skipped`() {
        val handler = buildHandler(DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "dep", timeoutMinutes = 5))
        val result = handler.process(subtask(null), null, defaultAdvance)
        assertTrue(result is IssueProcessResult.Skipped)
    }

    @Test
    fun `Skip config on START advances chain immediately`() {
        val advanced = IssueProcessResult.Chained(subtaskKey, null)
        val handler = buildHandler(DeployConfig.Skip())
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START) { advanced }
        assertEquals(advanced, result)
    }

    @Test
    fun `unreadable parent on START skips instead of approving the deploy`() {
        // SF-1560: een mislukte parent-lees mag niet als "geen parent-project" gelezen worden --
        // dat zette de deploy zonder enige actie op DEPLOY_APPROVED.
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "dep", timeoutMinutes = 5),
            capturedUpdates = updates,
            probe = DeploymentStatusProbe { _, _ -> error("deploy-doel mag niet getriggerd/bevraagd worden") },
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> error("apkReleaseProbe mag hier niet aangeroepen worden") },
            parentReadFails = true,
        )
        var advanced = false
        val logs = ListAppender<ILoggingEvent>().also {
            it.start()
            (LoggerFactory.getLogger(DeploySubtaskHandler::class.java) as Logger).addAppender(it)
        }

        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START) {
            advanced = true
            IssueProcessResult.Chained(subtaskKey, null)
        }

        assertEquals(IssueProcessResult.Skipped(subtaskKey, "deploy-parent-unavailable"), result)
        assertFalse(advanced, "advanceChain mag niet aangeroepen worden bij een onleesbare parent")
        assertTrue(updates.isEmpty(), "er mag geen enkele fase-update geschreven worden, ook niet DEPLOY_APPROVED")
        val warnings = logs.list.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().formattedMessage.contains(parentKey), "warn-regel hoort de parent-key te noemen")
        assertNotNull(warnings.single().throwableProxy, "warn-regel hoort de onderliggende exception mee te geven")
    }

    // --- Skip met apkCheck: geen premature 'klaar' voor APK-achtige deploy-doelen (SF-2) ---

    @Test
    fun `Skip config without apkCheck approves instantly without consulting the APK probe`() {
        // Regressie: het gedrag voor een Skip-doel ZONDER apkCheck (default) moet exact
        // ongewijzigd blijven -- geen enkele aanroep naar de artifact-check.
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            DeployConfig.Skip(),
            capturedUpdates = updates,
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> error("apkReleaseProbe mag hier niet aangeroepen worden") },
        )
        var advanced = false
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START) {
            advanced = true
            IssueProcessResult.Chained(subtaskKey, null)
        }
        assertTrue(advanced, "Skip zonder apkCheck hoort direct te approven en de keten door te zetten")
        assertTrue(result is IssueProcessResult.Chained)
        assertEquals(SubtaskPhase.DEPLOY_APPROVED.trackerValue, updates.single().second.values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `Skip config with apkCheck waits in DEPLOYING while no APK release has appeared yet`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            DeployConfig.Skip(apkCheck = true, timeoutMinutes = 10),
            capturedUpdates = updates,
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> null },
        )

        // START: er is iets te bewaken (apkCheck) -> DEPLOYING, geen instant approve.
        val startResult = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)
        assertTrue(startResult is IssueProcessResult.Recovered, "apkCheck-Skip hoort naar DEPLOYING te gaan: $startResult")
        assertEquals(SubtaskPhase.DEPLOYING.trackerValue, updates.single().second.values[TrackerField.SUBTASK_PHASE])

        // DEPLOYING-poll: nog geen release gevonden -> subtaak blijft non-terminaal.
        val pollResult = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(pollResult is IssueProcessResult.Skipped, "geen APK-release -> moet blijven wachten: $pollResult")
        assertTrue(
            SubtaskPhase.DEPLOY_APPROVED.trackerValue !in updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] },
            "zonder gevonden release mag de subtaak niet terminaal worden (dat zou de premature 'klaar'-melding terugbrengen)",
        )
    }

    @Test
    fun `Skip config with apkCheck approves once the APK release actually appears`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val release = ApkReleaseInfo(downloadUrl = "https://example/app.apk", createdAt = now.plusMinutes(1))
        val handler = buildHandler(
            DeployConfig.Skip(apkCheck = true, timeoutMinutes = 10),
            capturedUpdates = updates,
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> release },
        )

        handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)
        val pollResult = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)

        assertTrue(pollResult is IssueProcessResult.Recovered, "APK-release gevonden -> subtaak hoort te approven: $pollResult")
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in updates.map { it.second.values[TrackerField.SUBTASK_PHASE] })

        // Vervolg-poll op DEPLOY_APPROVED (zoals de orchestrator 'm de volgende cycle oppakt) advancet
        // de keten pas dan -- exact hetzelfde patroon als de rest-restart-keten hierboven, dus precies
        // één moment waarop de subtaak (en daarmee de Telegram-DONE-melding) terminaal wordt.
        var advanced = false
        val advanceResult = handler.process(
            subtask(SubtaskPhase.DEPLOY_APPROVED),
            SubtaskPhase.DEPLOY_APPROVED,
        ) { advanced = true; IssueProcessResult.Chained(subtaskKey, null) }
        assertTrue(advanced)
        assertTrue(advanceResult is IssueProcessResult.Chained)
    }

    @Test
    fun `Skip config with apkCheck still approves after the story run was already closed by the merge`() {
        // Regressie SF-1710/SF-1717: ManualCommandService sluit de story-run al af zodra de merge-
        // subtaak klaar is, ruim vóórdat de DEPLOY-subtaak begint te pollen. Vóór de fix zocht
        // apkReleaseReady via `openOrCreate(parentKey, "")` -- vond geen "open" run meer, en maakte
        // een nieuwe LEGE spookrun aan (geen targetRepo) -> apkReleaseReady gaf altijd `false` terug,
        // hoe lang je ook wachtte. Met `latestFor` moet de échte (gesloten) run nog gewoon gevonden
        // worden, en de aanwezige APK-release dus alsnog leiden tot een approve.
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val release = ApkReleaseInfo(downloadUrl = "https://example/app.apk", createdAt = now.plusMinutes(1))
        val handler = buildHandler(
            DeployConfig.Skip(apkCheck = true, timeoutMinutes = 10),
            capturedUpdates = updates,
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> release },
            closeStoryRunAfterSetup = true,
        )
        val pollResult = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(
            pollResult is IssueProcessResult.Recovered,
            "APK-release is aanwezig; een gesloten story-run mag dat niet verbergen: $pollResult",
        )
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in updates.map { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `multi-target matchPaths still resolves correctly after the story run was already closed by the merge`() {
        // Zelfde regressie als hierboven, maar dan voor changedPaths (matchedTargets fail-open):
        // vóór de fix zou een gesloten story-run ook hier een lege spookrun opleveren (geen PR-nummer),
        // waardoor changedPaths() `null` teruggaf en ALLE matchPaths-doelen ten onrechte meetelden --
        // exact het "6 doelen i.p.v. 2"-effect dat in de praktijk werd gezien.
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        var backendProbed = false
        val probe = DeploymentStatusProbe { _, deployment ->
            if (deployment == "backend-app") { backendProbed = true; null } else "registry/app:sha-1"
        }
        val handler = buildHandler(
            DeployConfig.Skip(),
            capturedUpdates = updates,
            probe = probe,
            deployTargets = listOf(
                DeployTarget(
                    name = "frontend", matchPaths = listOf("frontend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "frontend-app", timeoutMinutes = 20),
                ),
                DeployTarget(
                    name = "backend", matchPaths = listOf("backend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "backend-app", timeoutMinutes = 20),
                ),
            ),
            // Alleen frontend/ geraakt -- backend/ hoort dus NIET bewaakt te worden.
            changedFiles = listOf("frontend/App.tsx"),
            closeStoryRunAfterSetup = true,
        )
        val startResult = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)
        assertTrue(startResult is IssueProcessResult.Recovered, "precies één match hoort de normale flow te volgen: $startResult")
        val pollResult = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(pollResult is IssueProcessResult.Recovered, "alleen frontend geraakt en die is klaar -> hoort te approven: $pollResult")
        assertTrue(!backendProbed, "backend/ is niet geraakt -> mag na een gesloten story-run nog steeds niet bewaakt worden (geen fail-open)")
    }

    @Test
    fun `Skip config with apkCheck times out like other targets when the release never appears`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val pastTime = now.minusMinutes(15)
        val handler = buildHandler(
            DeployConfig.Skip(apkCheck = true, timeoutMinutes = 10),
            capturedUpdates = updates,
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> null },
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = pastTime), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(result is IssueProcessResult.Errored)
        val failedUpdate = updates.map { it.second }.first { it.values[TrackerField.SUBTASK_PHASE] == SubtaskPhase.DEPLOY_FAILED.trackerValue }
        val error = failedUpdate.values[TrackerField.ERROR] as? String
        assertNotNull(error, "TrackerField.ERROR moet in dezelfde update-call gezet worden")
        assertTrue(error!!.isNotBlank())
    }

    @Test
    fun `rest-restart START sends restart and sets DEPLOYING`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        // Use a config pointing to a non-existing URL to test the error path
        val handler = buildHandler(
            DeployConfig.RestRestart(
                restartUrl = "http://127.0.0.1:0/api/restart",
                versionUrl = "http://127.0.0.1:0/api/version",
                tokenEnvVar = "SF_TEST_NONEXISTENT_TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
            capturedUpdates = updates,
        )
        // No secret configured → should error
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)
        assertTrue(result is IssueProcessResult.Errored)
    }

    @Test
    fun `rest-restart resolves token via resolver not process env`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            DeployConfig.RestRestart(
                restartUrl = "http://127.0.0.1:0/api/restart",
                versionUrl = "http://127.0.0.1:0/api/version",
                tokenEnvVar = "SF_FACTORY_API_TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
            capturedUpdates = updates,
            // Token NIET in de procesomgeving, wél via de factory-config (zoals secrets.env).
            secrets = mapOf("SF_FACTORY_API_TOKEN" to "secret-from-file"),
        )

        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

        // De token is gevonden, dus we komen voorbij de token-check en proberen de restart te POSTen
        // (die faalt op de onbereikbare URL). De fout mag dus NIET de "token niet gevonden"-fout zijn.
        assertTrue(result is IssueProcessResult.Errored)
        val errorMessages = updates.mapNotNull { it.second.values[TrackerField.ERROR] as? String }
        assertTrue(errorMessages.none { it.contains("niet gevonden") }, "token had gevonden moeten worden: $errorMessages")
        assertTrue(errorMessages.any { it.contains("restart-aanvraag") }, "verwacht een restart-poging: $errorMessages")
    }

    @Test
    fun `rest-restart persists DEPLOYING before triggering the restart`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val phasesAtRestart = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/version") { exchange ->
            val body = """{"commitDate":"2026-01-01T09:00:00Z"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/restart") { exchange ->
            // Snapshot welke fasen al naar de tracker zijn geschreven op het moment dat de restart binnenkomt.
            phasesAtRestart.set(updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] as? String })
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            val handler = buildHandler(
                DeployConfig.RestRestart(
                    restartUrl = "http://127.0.0.1:$port/api/restart",
                    versionUrl = "http://127.0.0.1:$port/api/version",
                    tokenEnvVar = "SF_FACTORY_API_TOKEN",
                    pollIntervalSeconds = 1,
                    timeoutMinutes = 10,
                ),
                capturedUpdates = updates,
                secrets = mapOf("SF_FACTORY_API_TOKEN" to "secret"),
            )

            val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

            // De kern van de fix: DEPLOYING is al gepersisteerd VÓÓR de restart-POST, zodat de subtaak
            // een self-kill overleeft en de orchestrator 'm ná de herstart in DEPLOYING oppakt.
            assertTrue(
                phasesAtRestart.get().contains(SubtaskPhase.DEPLOYING.trackerValue),
                "DEPLOYING had vóór de restart-POST gepersisteerd moeten zijn, was: ${phasesAtRestart.get()}",
            )
            assertTrue(result is IssueProcessResult.Recovered)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rest-restart doorloopt de hele keten van start via deploying naar deploy-approved`() {
        // Integratietest voor het niet-Skip-deploypad (SF-154/SF-164-gat): één echte HTTP-server
        // speelt de doelservice, en de handler doorloopt beide fasen ná elkaar — START triggert de
        // restart-POST en persisteert DEPLOYING + het trigger-tijdstip; de DEPLOYING-poll leest
        // /api/version, ziet een herstart ná dat trigger-tijdstip en advancet de keten via
        // DEPLOY_APPROVED. De fasen worden dus écht aan elkaar doorgegeven (AGENT_STARTED_AT uit
        // stap 1 voedt de poll in stap 2), wat de losse per-fase-tests hierboven niet bewijzen.
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val restartCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/restart") { exchange ->
            restartCalls.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/api/version") { exchange ->
            // De service meldt zich "opnieuw opgestart" ná het trigger-tijdstip (= now, de fixed clock).
            val body = """{"commitHash":"abc","startedAt":"${now.plusSeconds(30)}"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val handler = buildHandler(
                DeployConfig.RestRestart(
                    restartUrl = "http://127.0.0.1:$port/api/restart",
                    versionUrl = "http://127.0.0.1:$port/api/version",
                    tokenEnvVar = "SF_FACTORY_API_TOKEN",
                    pollIntervalSeconds = 1,
                    timeoutMinutes = 10,
                ),
                capturedUpdates = updates,
                secrets = mapOf("SF_FACTORY_API_TOKEN" to "secret"),
            )

            // Fase 1 — START: restart getriggerd, DEPLOYING + trigger-tijdstip gepersisteerd.
            val startResult = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)
            assertTrue(startResult is IssueProcessResult.Recovered, "START hoort in DEPLOYING te eindigen: $startResult")
            assertEquals(1, restartCalls.get(), "de restart-POST hoort precies één keer verstuurd te zijn")
            val triggeredAt = updates.mapNotNull { it.second.values[TrackerField.AGENT_STARTED_AT] as? OffsetDateTime }.single()

            // Fase 2 — DEPLOYING-poll (zoals de orchestrator 'm de volgende cycle oppakt, mét het in
            // fase 1 gepersisteerde trigger-tijdstip): herstart gezien → DEPLOY_APPROVED + advanceChain.
            var advanced = false
            val pollResult = handler.process(
                subtask(SubtaskPhase.DEPLOYING, agentStartedAt = triggeredAt),
                SubtaskPhase.DEPLOYING,
            ) { advanced = true; IssueProcessResult.Chained(subtaskKey, null) }

            assertTrue(pollResult is IssueProcessResult.Recovered, "DEPLOYING hoort in DEPLOY_APPROVED te eindigen: $pollResult")
            val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
            assertEquals(
                listOf(SubtaskPhase.DEPLOYING.trackerValue, SubtaskPhase.DEPLOY_APPROVED.trackerValue),
                phases.filterNotNull(),
                "verwachtte precies de fase-overgangen deploying → deploy-approved",
            )
            // NB: de keten-advance gebeurt in de productie-flow pas op de vólgende poll (fase
            // DEPLOY_APPROVED → advanceChain); de goedkeur-poll zelf advancet niet.
            assertEquals(false, advanced, "de DEPLOYING-poll zelf hoort de keten nog niet te advancen")
            val advanceResult = handler.process(
                subtask(SubtaskPhase.DEPLOY_APPROVED),
                SubtaskPhase.DEPLOY_APPROVED,
            ) { advanced = true; IssueProcessResult.Chained(subtaskKey, null) }
            assertTrue(advanced, "DEPLOY_APPROVED hoort de keten door te zetten")
            assertTrue(advanceResult is IssueProcessResult.Chained)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rest-restart timeout sets DEPLOY_FAILED`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val pastTime = now.minusMinutes(10)
        val handler = buildHandler(
            DeployConfig.RestRestart(
                restartUrl = "http://127.0.0.1:0/api/restart",
                versionUrl = "http://127.0.0.1:0/api/version",
                tokenEnvVar = "SF_TEST_TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 5,
            ),
            capturedUpdates = updates,
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = pastTime), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(result is IssueProcessResult.Errored)
        val failedUpdate = updates.map { it.second }.first { it.values[TrackerField.SUBTASK_PHASE] == SubtaskPhase.DEPLOY_FAILED.trackerValue }
        val error = failedUpdate.values[TrackerField.ERROR] as? String
        assertNotNull(error, "TrackerField.ERROR moet in dezelfde update-call gezet worden")
        assertTrue(error!!.isNotBlank())
    }

    @Test
    fun `openshift-watch timeout sets DEPLOY_FAILED`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val pastTime = now.minusMinutes(15)
        val handler = buildHandler(
            DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "app", timeoutMinutes = 10),
            capturedUpdates = updates,
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = pastTime), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(result is IssueProcessResult.Errored)
        val failedUpdate = updates.map { it.second }.first { it.values[TrackerField.SUBTASK_PHASE] == SubtaskPhase.DEPLOY_FAILED.trackerValue }
        val error = failedUpdate.values[TrackerField.ERROR] as? String
        assertNotNull(error, "TrackerField.ERROR moet in dezelfde update-call gezet worden")
        assertTrue(error!!.isNotBlank())
    }

    @Test
    fun `parseStartedAt extracts ISO datetime from json`() {
        val handler = buildHandler(DeployConfig.Skip())
        val json = """{"commitHash":"abc","startedAt":"2026-01-01T13:00:00+02:00","branch":"main"}"""
        val started = handler.parseStartedAt(json)
        assertNotNull(started)
        assertEquals(OffsetDateTime.parse("2026-01-01T13:00:00+02:00"), started)
    }

    @Test
    fun `parseStartedAt returns null for malformed json`() {
        val handler = buildHandler(DeployConfig.Skip())
        assertNull(handler.parseStartedAt("not json at all"))
        assertNull(handler.parseStartedAt("""{"other":"field"}"""))
    }

    @Test
    fun `rest-restart approves when live SHA matches expected merge SHA`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val fullSha = "abc1234def5678"
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/version") { exchange ->
            // De service rapporteert een short-SHA-prefix van de verwachte merge-commit.
            val body = """{"commitHash":"abc1234"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val handler = buildHandler(
                DeployConfig.RestRestart(
                    restartUrl = "http://127.0.0.1:$port/api/restart",
                    versionUrl = "http://127.0.0.1:$port/api/version",
                    tokenEnvVar = "SF_FACTORY_API_TOKEN",
                    pollIntervalSeconds = 1,
                    timeoutMinutes = 10,
                ),
                capturedUpdates = updates,
                expectedSha = fullSha,
            )
            val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
            assertTrue(result is IssueProcessResult.Recovered)
            val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
            assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in phases)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rest-restart keeps waiting when live SHA does not match expected`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/version") { exchange ->
            // Oude build blijft live: verkeerde SHA + al lang geleden gestart → géén approve.
            val body = """{"commitHash":"oldsha00","startedAt":"${now.plusMinutes(1)}"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val handler = buildHandler(
                DeployConfig.RestRestart(
                    restartUrl = "http://127.0.0.1:$port/api/restart",
                    versionUrl = "http://127.0.0.1:$port/api/version",
                    tokenEnvVar = "SF_FACTORY_API_TOKEN",
                    pollIntervalSeconds = 1,
                    timeoutMinutes = 10,
                ),
                capturedUpdates = updates,
                expectedSha = "newsha1234",
            )
            val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
            assertTrue(result is IssueProcessResult.Skipped, "verkeerde SHA hoort te blijven wachten: $result")
            val phases = updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] }
            assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue !in phases, "verkeerde SHA mag niet approven")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openshift-watch argocd approves on Synced Healthy Succeeded matching revision`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val probe = object : DeploymentStatusProbe {
            override fun currentImage(namespace: String, deployment: String): String? = ""
            override fun argoApplicationStatus(namespace: String, application: String) =
                ArgoApplicationStatus("Synced", "Healthy", "Succeeded", "abc1234")
        }
        val handler = buildHandler(
            DeployConfig.OpenshiftWatch(
                namespace = "ns", deployment = "app", timeoutMinutes = 20,
                argocdApp = "my-app", argocdNamespace = "argocd",
            ),
            capturedUpdates = updates,
            probe = probe,
            expectedSha = "abc1234def",
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(result is IssueProcessResult.Recovered, "gezonde ArgoCD-app hoort te approven: $result")
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in updates.map { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `openshift-watch argocd keeps waiting when unhealthy or wrong revision`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        // Synced + Succeeded maar Degraded én verkeerde revisie → geen approve.
        val probe = object : DeploymentStatusProbe {
            override fun currentImage(namespace: String, deployment: String): String? = ""
            override fun argoApplicationStatus(namespace: String, application: String) =
                ArgoApplicationStatus("Synced", "Degraded", "Succeeded", "wrongsha")
        }
        val handler = buildHandler(
            DeployConfig.OpenshiftWatch(
                namespace = "ns", deployment = "app", timeoutMinutes = 20,
                argocdApp = "my-app", argocdNamespace = "argocd",
            ),
            capturedUpdates = updates,
            probe = probe,
            expectedSha = "abc1234def",
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(result is IssueProcessResult.Skipped, "ongezonde ArgoCD-app hoort te blijven wachten: $result")
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue !in updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `openshift-watch falls back to image heuristic without argocd config`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val probe = DeploymentStatusProbe { _, _ -> "registry/app:sha-123" }
        val handler = buildHandler(
            DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "app", timeoutMinutes = 20),
            capturedUpdates = updates,
            probe = probe,
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(result is IssueProcessResult.Recovered, "zonder ArgoCD-config hoort de image-heuristiek te gelden: $result")
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in updates.map { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `shaPrefixMatch matches short and full SHA both directions`() {
        val handler = buildHandler(DeployConfig.Skip())
        assertTrue(handler.shaPrefixMatch("abc1234", "abc1234def5678"))
        assertTrue(handler.shaPrefixMatch("ABC1234DEF5678", "abc1234"))
        assertTrue(!handler.shaPrefixMatch("abc1234", "def5678"))
        assertTrue(!handler.shaPrefixMatch("", "abc"))
    }

    @Test
    fun `rest-restart approves once service restarted after trigger`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        // /api/version meldt een startedAt ná het deploy-trigger-tijdstip (= now, agentStartedAt).
        val restartedAt = now.plusMinutes(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/version") { exchange ->
            val body = """{"commitHash":"abc","startedAt":"$restartedAt"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val handler = buildHandler(
                DeployConfig.RestRestart(
                    restartUrl = "http://127.0.0.1:$port/api/restart",
                    versionUrl = "http://127.0.0.1:$port/api/version",
                    tokenEnvVar = "SF_FACTORY_API_TOKEN",
                    pollIntervalSeconds = 1,
                    timeoutMinutes = 10,
                ),
                capturedUpdates = updates,
            )
            // agentStartedAt = now (het trigger-tijdstip); de service meldt een latere startedAt → geslaagd.
            val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
            assertTrue(result is IssueProcessResult.Recovered)
            val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
            assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in phases)
        } finally {
            server.stop(0)
        }
    }

    // --- Multi-deployment routing (SF-1: `deploy:` als lijst + matchPaths) ---

    @Test
    fun `multi-target no path match approves immediately without consulting any probe`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        var probeCalled = false
        val probe = DeploymentStatusProbe { _, _ -> probeCalled = true; "registry/app:sha-1" }
        val handler = buildHandler(
            DeployConfig.Skip(),
            capturedUpdates = updates,
            probe = probe,
            deployTargets = listOf(
                DeployTarget(
                    name = "frontend", matchPaths = listOf("frontend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "frontend-app", timeoutMinutes = 20),
                ),
                DeployTarget(
                    name = "backend", matchPaths = listOf("backend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "backend-app", timeoutMinutes = 20),
                ),
            ),
            // Docs-only wijziging: raakt geen enkele matchPaths-prefix.
            changedFiles = listOf("docs/readme.md"),
        )
        var advanced = false
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START) {
            advanced = true
            IssueProcessResult.Chained(subtaskKey, null)
        }
        assertTrue(advanced, "geen enkele matchPaths-prefix geraakt -> direct approve + keten door")
        assertTrue(result is IssueProcessResult.Chained)
        assertEquals(SubtaskPhase.DEPLOY_APPROVED.trackerValue, updates.single().second.values[TrackerField.SUBTASK_PHASE])
        assertTrue(!probeCalled, "geen doel geraakt -> geen enkele deploy-probe hoort aangeroepen te zijn")
    }

    @Test
    fun `multi-target exactly one match follows the normal single-target flow`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        // Alleen het backend-doel hoort bewaakt te worden; het frontend-image zou (als het per
        // ongeluk toch bewaakt werd) altijd null blijven en dus nooit approven — een falende test
        // zou dus wijzen op frontend die tóch meedoet.
        val probe = DeploymentStatusProbe { _, deployment -> if (deployment == "backend-app") "registry/app:sha-123" else null }
        val handler = buildHandler(
            DeployConfig.Skip(),
            capturedUpdates = updates,
            probe = probe,
            deployTargets = listOf(
                DeployTarget(
                    name = "frontend", matchPaths = listOf("frontend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "frontend-app", timeoutMinutes = 20),
                ),
                DeployTarget(
                    name = "backend", matchPaths = listOf("backend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "backend-app", timeoutMinutes = 20),
                ),
            ),
            changedFiles = listOf("backend/Foo.kt"),
        )

        val startResult = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)
        assertTrue(startResult is IssueProcessResult.Recovered, "precies één match hoort de normale START->DEPLOYING-flow te volgen: $startResult")
        assertEquals(SubtaskPhase.DEPLOYING.trackerValue, updates.single().second.values[TrackerField.SUBTASK_PHASE])

        val pollResult = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(pollResult is IssueProcessResult.Recovered, "het enige geraakte doel (backend) is al klaar: $pollResult")
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in updates.map { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `multi-target multiple matches waits for all before approving`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val backendReady = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = DeploymentStatusProbe { _, deployment ->
            when (deployment) {
                "frontend-app" -> "registry/app:sha-1" // frontend is meteen klaar
                "backend-app" -> if (backendReady.get()) "registry/app:sha-2" else null
                else -> null
            }
        }
        val handler = buildHandler(
            DeployConfig.Skip(),
            capturedUpdates = updates,
            probe = probe,
            deployTargets = listOf(
                DeployTarget(
                    name = "frontend", matchPaths = listOf("frontend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "frontend-app", timeoutMinutes = 20),
                ),
                DeployTarget(
                    name = "backend", matchPaths = listOf("backend/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "backend-app", timeoutMinutes = 20),
                ),
            ),
            changedFiles = listOf("frontend/App.tsx", "backend/Foo.kt"),
        )

        handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

        // Frontend is al klaar, backend nog niet -> nog NIET approven (moet op alle geraakte
        // doelen wachten).
        val firstPoll = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(firstPoll is IssueProcessResult.Skipped, "backend nog niet klaar -> mag nog niet approven: $firstPoll")
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue !in updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] })

        backendReady.set(true)
        val secondPoll = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(secondPoll is IssueProcessResult.Recovered, "beide geraakte doelen zijn nu klaar -> hoort te approven: $secondPoll")
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in updates.map { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `multi-target an already-ready target's own elapsed timeout does not fail the subtask while another target is still legitimately pending`() {
        // Regressietest voor SF-1710 (ontdekt bij SF-1704): "fast" heeft een korte timeout (10 min) en
        // is al klaar; "slow" heeft een langere timeout (30 min) en is nog bezig. Op t=15 min is
        // fast's eigen timeout al lang verstreken, maar omdat fast al ready is mag dat de subtaak niet
        // laten falen -- slow zit nog ruim binnen zijn eigen budget, dus het geheel hoort nog te wachten.
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val probe = DeploymentStatusProbe { _, deployment -> if (deployment == "fast-app") "registry/app:sha-1" else null }
        val handler = buildHandler(
            DeployConfig.Skip(),
            capturedUpdates = updates,
            probe = probe,
            deployTargets = listOf(
                DeployTarget(
                    name = "fast", matchPaths = listOf("fast/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "fast-app", timeoutMinutes = 10),
                ),
                DeployTarget(
                    name = "slow", matchPaths = listOf("slow/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "slow-app", timeoutMinutes = 30),
                ),
            ),
            changedFiles = listOf("fast/App.tsx", "slow/Foo.kt"),
        )

        val startedAt = now.minusMinutes(15)
        val poll = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = startedAt), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(
            poll is IssueProcessResult.Skipped,
            "fast is al klaar en mag zijn eigen (verstreken) timeout niet meer laten gelden; slow zit nog binnen budget: $poll",
        )
        assertTrue(SubtaskPhase.DEPLOY_FAILED.trackerValue !in updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] })
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue !in updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `multi-target a still-pending target that exceeds its own timeout still fails the subtask`() {
        // Contrast met de vorige test: hier is "slow" zelf ook nog niet klaar EN over zijn eigen
        // timeout heen -> de subtaak hoort dan wél te falen (de fix mag alleen al-klare doelen
        // immuun maken voor hun eigen timeout, niet doelen die zelf nog legitiem hangen).
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val probe = DeploymentStatusProbe { _, deployment -> if (deployment == "fast-app") "registry/app:sha-1" else null }
        val handler = buildHandler(
            DeployConfig.Skip(),
            capturedUpdates = updates,
            probe = probe,
            deployTargets = listOf(
                DeployTarget(
                    name = "fast", matchPaths = listOf("fast/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "fast-app", timeoutMinutes = 10),
                ),
                DeployTarget(
                    name = "slow", matchPaths = listOf("slow/"),
                    config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "slow-app", timeoutMinutes = 5),
                ),
            ),
            changedFiles = listOf("fast/App.tsx", "slow/Foo.kt"),
        )

        val startedAt = now.minusMinutes(15)
        val poll = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = startedAt), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(poll is IssueProcessResult.Errored, "slow is zelf nog niet klaar en over zijn eigen timeout heen -> hoort te falen: $poll")
        assertTrue(SubtaskPhase.DEPLOY_FAILED.trackerValue in updates.mapNotNull { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    @Test
    fun `legacy single deploy block keeps the exact old behavior regardless of the story diff`() {
        // Backward-compat: geen deployTargets meegegeven (dus alleen het enkelvoudige deployConfig),
        // en een diff die geen enkel expliciet pad raakt -- moet ALSNOG het volledige single-target-
        // gedrag doorlopen (matchPaths is impliciet leeg = altijd van toepassing), niet direct approven.
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val probe = DeploymentStatusProbe { _, _ -> "registry/app:sha-123" }
        val handler = buildHandler(
            DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "app", timeoutMinutes = 20),
            capturedUpdates = updates,
            probe = probe,
            changedFiles = listOf("some/unrelated/path.txt"),
        )
        val startResult = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)
        assertTrue(startResult is IssueProcessResult.Recovered, "enkelvoudig deploy-blok hoort altijd bewaakt te worden, ongeacht de diff: $startResult")
        val pollResult = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = now), SubtaskPhase.DEPLOYING, defaultAdvance)
        assertTrue(pollResult is IssueProcessResult.Recovered)
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.trackerValue in updates.map { it.second.values[TrackerField.SUBTASK_PHASE] })
    }

    // --- matchedDeployTargetsFor (Story 4: story-detail per-onderdeel build-status) ---
    // De story-detail-pagina hergebruikt dit publieke, read-only stuk van de handler i.p.v. de
    // matchPaths-bepaling zelf te dupliceren (zie DeployTargetStatusApi/DashboardQueryService).

    @Test
    fun `matchedDeployTargetsFor returns only the matchPaths-touched targets, not the rest`() {
        val handler = buildHandler(
            DeployConfig.Skip(),
            deployTargets = listOf(
                DeployTarget(
                    name = "frontend", matchPaths = listOf("frontend/"),
                    config = DeployConfig.RestRestart(
                        restartUrl = "http://x/restart", versionUrl = "http://x/version",
                        tokenEnvVar = "T", pollIntervalSeconds = 5, timeoutMinutes = 5,
                    ),
                ),
                DeployTarget(
                    name = "backend", matchPaths = listOf("backend/"),
                    config = DeployConfig.RestRestart(
                        restartUrl = "http://y/restart", versionUrl = "http://y/version",
                        tokenEnvVar = "T", pollIntervalSeconds = 5, timeoutMinutes = 5,
                    ),
                ),
                DeployTarget(name = "docs-skip", matchPaths = listOf("docs/"), config = DeployConfig.Skip()),
            ),
            // Alleen frontend/-paden geraakt: backend en docs-skip mogen niet in het resultaat zitten.
            changedFiles = listOf("frontend/lib/main.dart"),
        )

        val matched = handler.matchedDeployTargetsFor(parentKey, "softwarefactory")

        assertEquals(listOf("frontend"), matched.map { it.target.name })
        assertTrue(matched.single().watched, "een RestRestart-doel heeft altijd iets te bewaken")
    }

    @Test
    fun `matchedDeployTargetsFor marks a matched Skip target without apkCheck as not watched`() {
        val handler = buildHandler(
            DeployConfig.Skip(),
            deployTargets = listOf(
                DeployTarget(
                    name = "backend", matchPaths = listOf("backend/"),
                    config = DeployConfig.RestRestart(
                        restartUrl = "http://y/restart", versionUrl = "http://y/version",
                        tokenEnvVar = "T", pollIntervalSeconds = 5, timeoutMinutes = 5,
                    ),
                ),
                DeployTarget(name = "docs-skip", matchPaths = listOf("docs/"), config = DeployConfig.Skip()),
            ),
            changedFiles = listOf("backend/Foo.kt", "docs/readme.md"),
        )

        val matched = handler.matchedDeployTargetsFor(parentKey, "softwarefactory")

        assertEquals(setOf("backend", "docs-skip"), matched.map { it.target.name }.toSet())
        assertTrue(matched.single { it.target.name == "backend" }.watched)
        assertFalse(
            matched.single { it.target.name == "docs-skip" }.watched,
            "een Skip-doel zonder apkCheck heeft niets te bewaken, ook al is het wel geraakt",
        )
    }
}
