package nl.vdzon.softwarefactory.pipeline.models

import nl.vdzon.softwarefactory.config.DeployTarget

/**
 * Eén deploy-doel dat een story raakt (matchPaths), met of dat doel door
 * [nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler] bewaakt wordt — zie
 * [nl.vdzon.softwarefactory.pipeline.DeployTargetStatusApi].
 */
data class MatchedDeployTarget(
    val target: DeployTarget,
    val watched: Boolean,
)
