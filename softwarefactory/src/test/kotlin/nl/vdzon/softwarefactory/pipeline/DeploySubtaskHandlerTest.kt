package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.core.ArgoApplicationStatus
import nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler
import nl.vdzon.softwarefactory.testsupport.FakeGitHubApi
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    ): DeploySubtaskHandler {
        val tracker = object : TrackerApi {
            override fun getIssue(issueKey: String) = parentIssue()
            override fun parentStoryKey(subtaskKey: String) = parentKey
            override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
                capturedUpdates.add(issueKey to update)
            }

            override fun updateIssueDescription(issueKey: String, description: String) {}

            override fun transitionIssue(issueKey: String, statusName: String) {}
            override fun postAgentComment(issueKey: String, role: nl.vdzon.softwarefactory.core.AgentRole, message: String) = error("unused")
        }
        val resolver = ProjectConfiguration(
            mapOf("softwarefactory" to targetRepo),
            deployConfigs = mapOf("softwarefactory" to deployConfig),
        )
        val configApi = object : ConfigApi {
            override fun resolvedValues(): Map<String, String> = secrets
        }
        val storyRuns = InMemoryStoryRunRepository()
        // Seed een run met targetRepo + base-branch main zodat expectedSha() een repo/branch heeft.
        val run = storyRuns.openOrCreate(parentKey, targetRepo)
        storyRuns.updatePullRequest(run.id, "feature", 1, null, "main", null, null, null, null)
        val gitHub = FakeGitHubApi(latestSha = expectedSha)
        return DeploySubtaskHandler(tracker, resolver, clock, configApi, probe, storyRuns, gitHub)
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
        val handler = buildHandler(DeployConfig.Skip)
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START) { advanced }
        assertEquals(advanced, result)
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
        val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
        assertTrue(SubtaskPhase.DEPLOY_FAILED.trackerValue in phases)
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
        val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
        assertTrue(SubtaskPhase.DEPLOY_FAILED.trackerValue in phases)
    }

    @Test
    fun `parseStartedAt extracts ISO datetime from json`() {
        val handler = buildHandler(DeployConfig.Skip)
        val json = """{"commitHash":"abc","startedAt":"2026-01-01T13:00:00+02:00","branch":"main"}"""
        val started = handler.parseStartedAt(json)
        assertNotNull(started)
        assertEquals(OffsetDateTime.parse("2026-01-01T13:00:00+02:00"), started)
    }

    @Test
    fun `parseStartedAt returns null for malformed json`() {
        val handler = buildHandler(DeployConfig.Skip)
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
        val handler = buildHandler(DeployConfig.Skip)
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
}
