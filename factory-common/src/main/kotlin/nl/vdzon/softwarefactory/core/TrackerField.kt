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
    PAUSED("Paused"),
    // SF-1261 — drie onafhankelijke story-assen (vervangen SILENT/AUTO_APPROVE/TELEGRAM_RESULT_NOTIFY):
    // vragen toestaan (boolean, default aan), goedkeuring (enum) en meldingen (enum). Alleen op de
    // story gezet; subtaken erven via parent-lookup (zie TrackerCapabilities.effectiveQuestionsAllowed/
    // effectiveNotifyMode en HumanActionPolicy.autoApproveActive).
    QUESTIONS_ALLOWED("QuestionsAllowed"),
    APPROVAL_MODE("ApprovalMode"),
    NOTIFY_MODE("NotifyMode"),
    ERROR("Error"),
}
