package nl.vdzon.softwarefactory.pipeline.models

/**
 * Of één deploy-doel van een al-gemergede story écht live staat (Story 5 — deployedAt/Rollout-tab),
 * zie [nl.vdzon.softwarefactory.pipeline.DeployRolloutStatusApi]. Anders dan [MatchedDeployTarget]
 * (bewaakt dit doel iets?) is dit het daadwerkelijke, actuele resultaat van de ancestor-/APK-check.
 */
data class DeployTargetLiveStatus(val name: String, val live: Boolean)
