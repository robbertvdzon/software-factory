package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.pipeline.models.DeployTargetLiveStatus

/**
 * Publieke, read-only poort op [nl.vdzon.softwarefactory.pipeline.service.StoryDeployReconciler]'s
 * "is dit deploy-doel al écht live"-check (Story 5 — `deployedAt`/Rollout-tab, zie
 * `docs/plan-multi-deployment-en-rollout-2026-07.md`).
 *
 * Bestaat zodat de dashboard-module (Rollout-lijst) exact dezelfde ancestor-/APK-check kan tonen die
 * de reconciler ook gebruikt om `deployedAt` te zetten, zonder een tweede implementatie (of de
 * dashboard-module rechtstreeks van `pipeline.service.StoryDeployReconciler` te laten afhangen — zie
 * `ModulithArchitectureTest`, net als [DeployTargetStatusApi]/`orchestrator.OrchestratorApi`).
 */
fun interface DeployRolloutStatusApi {
    /**
     * Live-status per deploy-doel dat de story achter [storyKey] raakt, of `null` als dat nog niet
     * te bepalen is (bv. de merge-commit/-tijd van de PR is niet op te halen) — de aanroeper
     * behandelt dat fail-safe als "nog niet bevestigd", niet als "geen doelen".
     */
    fun liveStatusFor(storyKey: String, targetRepo: String, prNumber: Int): List<DeployTargetLiveStatus>?
}
