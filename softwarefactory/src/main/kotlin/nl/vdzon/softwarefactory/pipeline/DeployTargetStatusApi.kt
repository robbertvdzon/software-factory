package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.pipeline.models.MatchedDeployTarget

/**
 * Publieke, read-only poort op de matchPaths-/needsWatch-bepaling van
 * [nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler] (Story 4 —
 * story-detail per-onderdeel build-status, zie `docs/plan-multi-deployment-en-rollout-2026-07.md`).
 *
 * Bestaat zodat de dashboard-module exact dezelfde "welke deploy-doelen raakt deze story"-logica
 * kan hergebruiken die [nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler.process] zelf
 * ook gebruikt, zonder de matchPaths-/story-diff-berekening een tweede keer te implementeren (of de
 * dashboard-module rechtstreeks van de `pipeline.service`-implementatieklasse te laten afhangen —
 * zie `ModulithArchitectureTest`: modules mogen elkaar alleen via zo'n root-package-poort kennen,
 * net als [nl.vdzon.softwarefactory.orchestrator.OrchestratorApi]/`runtime.AgentLogApi`).
 */
fun interface DeployTargetStatusApi {
    /**
     * De deploy-doelen die de story achter [parentStoryKey] raakt (matchPaths tegen de PR-diff),
     * elk met of het doel iets te bewaken heeft vóórdat het als "klaar" mag gelden (zie
     * [nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler.needsWatch]) — een Skip-doel
     * zonder `apkCheck` heeft niets te bewaken en geldt altijd als (impliciet) klaar.
     */
    fun matchedDeployTargetsFor(parentStoryKey: String, projectName: String?): List<MatchedDeployTarget>
}
