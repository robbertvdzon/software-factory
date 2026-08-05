package nl.vdzon.softwarefactory.core

/**
 * De tracker-custom-veldnamen van het factory-model — één gedeelde bron voor zowel de
 * factory (softwarefactory) als de dashboard-read-client (dashboard-backend), zodat een
 * veldnaam-wijziging niet stilletjes één van de twee kan breken. Verhuisd uit
 * softwarefactory/core/TrackerModels.kt; zelfde package, dus bestaande imports bleven gelijk.
 */
enum class TrackerField(val displayName: String) {
    REPO("Repo"),
    AI_SUPPLIER("AI-supplier"),
    AI_PHASE("AI Phase"),
    AI_LEVEL("AI Level"),
    AI_MODEL("AI Model"),
    AI_REASONING_EFFORT("AI Reasoning Effort"),
    STORY_PHASE("Story Phase"),
    SUBTASK_PHASE("Subtask Phase"),
    SUBTASK_TYPE("Subtask Type"),
    AI_MAX_DEVELOPER_LOOPBACKS("AI Max Developer Loopbacks"),
    // Per-issue override van de test-chain-reset-cap; `resume` op een test-cap-error verhoogt
    // deze zodat de keten verder kan (spiegel van AI_MAX_DEVELOPER_LOOPBACKS).
    AI_MAX_TEST_CHAIN_RESETS("AI Max Test Chain Resets"),
    AI_TOKEN_BUDGET("AI Token Budget"),
    AI_TOKENS_USED("AI Tokens Used"),
    AGENT_STARTED_AT("AgentStartedAt"),
    RETRY_AFTER("RetryAfter"),
    PAUSED("Paused"),
    // Drie onafhankelijke story-assen: vragen toestaan, goedkeuring en concrete melding-events.
    // Alleen de story wordt ingesteld; subtaken erven de actuele parentwaarden.
    QUESTIONS_ALLOWED("QuestionsAllowed"),
    APPROVAL_MODE("ApprovalMode"),
    NOTIFICATION_EVENTS("NotificationEvents"),
    // SF-1959 — hotfix-as: alleen bij het aanmaken van een story te zetten (default UIT). Een
    // hotfix-story slaat refine/plan/review/test/documentatie over; zie de HOTFIX-subtaakketen.
    HOTFIX("Hotfix"),
    ERROR("Error"),
}
