package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.DeployTarget
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseInfo
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.core.contracts.ArgoApplicationStatus
import nl.vdzon.softwarefactory.core.contracts.DeploymentPodInfo
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.github.PullRequestMergeInfo
import nl.vdzon.softwarefactory.pipeline.models.MatchedDeployTarget
import nl.vdzon.softwarefactory.pipeline.service.StoryDeployReconciler
import nl.vdzon.softwarefactory.testsupport.FakeGitHubApi
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Story 5: dekt [StoryDeployReconciler] — de reconciler die `deployedAt` zet zodra ALLE geraakte
 * deploy-doelen van een al-gemergede story écht live staan (ancestor-/APK-check), fail-safe
 * (nooit gokken) en idempotent (tweede run zonder nieuwe info heeft geen extra effect).
 */
class StoryDeployReconcilerTest {

    private val storyKey = "SF-500"
    private val targetRepo = "git@github.com:robbert/sf.git"
    private val prNumber = 42
    private val mergeCommitSha = "merge-sha-1"
    private val mergedAt = OffsetDateTime.parse("2026-07-10T09:00:00Z")
    private val now = OffsetDateTime.parse("2026-07-20T10:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    private fun storyIssue() = TrackerIssue(
        key = storyKey,
        summary = "Story onder test",
        status = "Done",
        fields = TrackerIssueFields(
            targetRepo = targetRepo,
            repo = "softwarefactory",
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

    private fun tracker() = object : TrackerApi {
        override fun getIssue(issueKey: String) = storyIssue()
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = error("unused")
        override fun transitionIssue(issueKey: String, statusName: String) = error("unused")
        override fun postAgentComment(issueKey: String, role: nl.vdzon.softwarefactory.core.AgentRole, message: String) = error("unused")
    }

    // deployment = [name] zodat elk doel in een test z'n eigen live-image kan krijgen
    // (podImagesByDeployment hieronder, keyed op deployment-naam).
    private fun openshiftTarget(name: String = "backend") = MatchedDeployTarget(
        target = DeployTarget(
            name = name,
            config = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = name, timeoutMinutes = 20),
        ),
        watched = true,
    )

    private fun reconciler(
        run: StoryRunRecord,
        matchedTargets: List<MatchedDeployTarget>,
        ancestorResults: Map<Pair<String, String>, Boolean?> = emptyMap(),
        podImagesByDeployment: Map<String, String> = emptyMap(),
        apkReleaseAfter: (OffsetDateTime) -> ApkReleaseInfo? = { null },
    ): Pair<StoryDeployReconciler, FakeStoryRunRepository> {
        val repository = FakeStoryRunRepository(listOf(run))
        val gitHubApi = FakeGitHubApi(
            mergeInfoByPr = mapOf(prNumber to PullRequestMergeInfo(mergeCommitSha, mergedAt)),
            ancestorResults = ancestorResults,
        )
        val deploymentStatusProbe = object : DeploymentStatusProbe {
            override fun currentImage(namespace: String, deployment: String): String? = podImagesByDeployment[deployment]
            override fun runningPod(namespace: String, deployment: String): DeploymentPodInfo? =
                podImagesByDeployment[deployment]?.let { DeploymentPodInfo(image = it, startedAt = null) }
            override fun argoApplicationStatus(namespace: String, application: String): ArgoApplicationStatus? = null
        }
        val apkReleaseProbe = ApkReleaseProbe { _, _, after -> apkReleaseAfter(after) }
        val reconciler = StoryDeployReconciler(
            storyRunRepository = repository,
            issueTrackerClient = tracker(),
            deployTargetStatusApi = DeployTargetStatusApi { _, _ -> matchedTargets },
            gitHubApi = gitHubApi,
            deploymentStatusProbe = deploymentStatusProbe,
            apkReleaseProbe = apkReleaseProbe,
            clock = clock,
        )
        return reconciler to repository
    }

    private fun mergedRun(deployedAt: OffsetDateTime? = null) = StoryRunRecord(
        id = 1L,
        storyKey = storyKey,
        targetRepo = targetRepo,
        prNumber = prNumber,
        deployedAt = deployedAt,
    )

    @Test
    fun `all targets live sets deployedAt`() {
        val target = openshiftTarget()
        val (reconciler, repository) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(target),
            ancestorResults = mapOf((mergeCommitSha to "live-sha") to true),
            podImagesByDeployment = mapOf("backend" to "ghcr.io/robbert/sf:sha-live-sha"),
        )

        reconciler.poll()

        val updated = repository.get(1L)
        assertEquals(now, updated?.deployedAt)
    }

    @Test
    fun `one target not yet live keeps deployedAt null`() {
        val liveTarget = openshiftTarget("backend")
        val notYetLiveTarget = openshiftTarget("frontend")
        val (reconciler, repository) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(liveTarget, notYetLiveTarget),
            // "backend" is al live (ancestor-check groen); "frontend" draait nog het oude image
            // (geen ancestorResults-entry voor die live-sha -> onbepaalbaar -> fail-safe niet-live).
            ancestorResults = mapOf((mergeCommitSha to "backend-live-sha") to true),
            podImagesByDeployment = mapOf(
                "backend" to "ghcr.io/robbert/sf:sha-backend-live-sha",
                "frontend" to "ghcr.io/robbert/sf:sha-frontend-old-sha",
            ),
        )

        reconciler.poll()

        assertNull(repository.get(1L)?.deployedAt)
    }

    @Test
    fun `older story is recognized live when its merge-commit is an ancestor of a later live sha`() {
        // Story A merget met commit "merge-sha-1"; een latere story B merget en deployt daarna —
        // de huidige live-SHA is "later-live-sha" (NIET gelijk aan merge-sha-1, maar wel een
        // ancestor-nakomeling ervan). De ancestor-check moet dat zonder speciale-geval-logica herkennen.
        val target = openshiftTarget()
        val (reconciler, repository) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(target),
            ancestorResults = mapOf((mergeCommitSha to "later-live-sha") to true),
            podImagesByDeployment = mapOf("backend" to "ghcr.io/robbert/sf:sha-later-live-sha"),
        )

        reconciler.poll()

        assertEquals(now, repository.get(1L)?.deployedAt)
    }

    @Test
    fun `running the reconciler twice has no double effect (idempotent)`() {
        val target = openshiftTarget()
        val (reconciler, repository) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(target),
            ancestorResults = mapOf((mergeCommitSha to "live-sha") to true),
            podImagesByDeployment = mapOf("backend" to "ghcr.io/robbert/sf:sha-live-sha"),
        )

        reconciler.poll()
        reconciler.poll()

        assertEquals(1, repository.markDeployedCalls.size)
        assertEquals(now, repository.get(1L)?.deployedAt)
    }

    @Test
    fun `an unwatched skip target always counts as live`() {
        val unwatchedSkip = MatchedDeployTarget(
            target = DeployTarget(name = "docs", config = DeployConfig.Skip(apkCheck = false)),
            watched = false,
        )
        val (reconciler, repository) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(unwatchedSkip),
        )

        reconciler.poll()

        assertEquals(now, repository.get(1L)?.deployedAt)
    }

    @Test
    fun `apk skip target becomes live only once a release appears after the merge time`() {
        val apkTarget = MatchedDeployTarget(
            target = DeployTarget(name = "apk", config = DeployConfig.Skip(apkCheck = true)),
            watched = true,
        )
        val (reconciler, repository) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(apkTarget),
            apkReleaseAfter = { null },
        )
        reconciler.poll()
        assertNull(repository.get(1L)?.deployedAt, "geen release gevonden -> nog niet live")

        val (reconcilerWithRelease, repositoryWithRelease) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(apkTarget),
            apkReleaseAfter = { after -> ApkReleaseInfo("https://example/apk", after.plusMinutes(5)) },
        )
        reconcilerWithRelease.poll()
        assertEquals(now, repositoryWithRelease.get(1L)?.deployedAt)
    }

    @Test
    fun `a story without a PR number is skipped (fail-safe, no crash)`() {
        val run = mergedRun().copy(prNumber = null)
        val (reconciler, repository) = reconciler(run = run, matchedTargets = listOf(openshiftTarget()))

        reconciler.poll()

        assertNull(repository.get(1L)?.deployedAt)
    }

    @Test
    fun `liveStatusFor exposes per-target status for the rollout list without mutating deployedAt`() {
        val target = openshiftTarget("backend")
        val (reconciler, repository) = reconciler(
            run = mergedRun(),
            matchedTargets = listOf(target),
            ancestorResults = mapOf((mergeCommitSha to "live-sha") to true),
            podImagesByDeployment = mapOf("backend" to "ghcr.io/robbert/sf:sha-live-sha"),
        )

        val statuses = reconciler.liveStatusFor(storyKey, targetRepo, prNumber)

        assertTrue(statuses != null && statuses.size == 1 && statuses.single().live)
        assertNull(repository.get(1L)?.deployedAt, "liveStatusFor moet read-only zijn")
    }

    /** Kleine, dedicated in-memory fake: [InMemoryStoryRunRepository] verwijdert runs bij [close], dus niet bruikbaar hier. */
    private class FakeStoryRunRepository(seed: List<StoryRunRecord>) : StoryRunRepository {
        private val byId = seed.associateBy { it.id }.toMutableMap()
        val markDeployedCalls = mutableListOf<Long>()

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            byId.values.firstOrNull { it.storyKey == storyKey } ?: error("not seeded: $storyKey")

        override fun get(storyRunId: Long): StoryRunRecord? = byId[storyRunId]

        override fun updatePullRequest(
            storyRunId: Long,
            branchName: String,
            prNumber: Int?,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) = error("unused in this test")

        override fun activePullRequests(): List<StoryRunRecord> = emptyList()

        override fun activeRuns(): List<StoryRunRecord> = byId.values.toList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) = error("unused in this test")

        override fun runsAwaitingDeployConfirmation(): List<StoryRunRecord> =
            byId.values.filter { it.deployedAt == null }.toList()

        override fun markDeployed(storyRunId: Long, deployedAt: OffsetDateTime) {
            markDeployedCalls += storyRunId
            byId[storyRunId]?.let { byId[storyRunId] = it.copy(deployedAt = deployedAt) }
        }
    }
}
