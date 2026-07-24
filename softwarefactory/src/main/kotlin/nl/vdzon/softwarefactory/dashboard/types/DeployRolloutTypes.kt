package nl.vdzon.softwarefactory.dashboard.types

/**
 * Runtime-status van één deploy-doel op de story-detail-pagina (Story 4 — story-detail per-
 * onderdeel build-status). Afgeleid van de DEPLOY-subtaakfase — er is geen aparte per-doel-
 * persistentie: [nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler] bewaakt alle
 * geraakte, niet-Skip doelen in dezelfde DEPLOYING-poll en zet in één keer DEPLOY_APPROVED/
 * DEPLOY_FAILED zodra ALLE doelen klaar zijn (of het eerste doel z'n timeout overschrijdt).
 */
enum class DeployTargetRuntimeStatus { PENDING, IN_PROGRESS, DONE, FAILED }

/**
 * Expliciet "zit nog in PR" vs "gemerged, wacht op productie-deploy"-onderscheid voor de DEPLOY-
 * subtaak (Story 4) — afgeleid uit de combinatie van de MERGE-subtaakfase (gemerged of niet) en de
 * status van de geraakte deploy-doelen, zodat de gebruiker dit niet zelf uit de generieke
 * subtaak-fasenamen hoeft af te leiden.
 */
enum class DeployRolloutStage {
    /** MERGE-subtaak nog niet op `merge-approved`: de PR van deze story staat nog open. */
    IN_PULL_REQUEST,
    /** Gemergd, maar nog niet alle geraakte deploy-doelen zijn klaar. */
    MERGED_AWAITING_DEPLOY,
    /** Gemergd en alle geraakte deploy-doelen zijn klaar (of er is geen enkel doel geraakt). */
    DEPLOYED,
    /** Gemergd, maar minstens één geraakt deploy-doel is gefaald. */
    DEPLOY_FAILED,
}
