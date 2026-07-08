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
    AUTO_APPROVE("Auto-approve"),
    AI_PHASE("AI Phase"),
    AI_LEVEL("AI Level"),
    AI_MODEL("AI Model"),
    AI_REASONING_EFFORT("AI Reasoning Effort"),
    STORY_PHASE("Story Phase"),
    SUBTASK_PHASE("Subtask Phase"),
    SUBTASK_TYPE("Subtask Type"),
    AI_MAX_DEVELOPER_LOOPBACKS("AI Max Developer Loopbacks"),
    AI_TOKEN_BUDGET("AI Token Budget"),
    AI_TOKENS_USED("AI Tokens Used"),
    AGENT_STARTED_AT("AgentStartedAt"),
    PAUSED("Paused"),
    // SF-335 — autonoom verwerken: bij `Silent=true` loopt de story zonder mens door en stuurt de
    // factory geen Telegram-meldingen; onduidelijkheden worden een error i.p.v. een wachtmoment.
    SILENT("Silent"),
    ERROR("Error"),
}
