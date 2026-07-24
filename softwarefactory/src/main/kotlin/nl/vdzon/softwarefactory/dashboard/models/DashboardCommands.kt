package nl.vdzon.softwarefactory.dashboard.models

import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.NotifyMode

data class CreateStoryCommand(
    val projectKey: String?,
    val title: String,
    val description: String?,
    val repo: String?,
    val aiSupplier: String?,
    val aiModel: String?,
    val start: Boolean,
    val questionsAllowed: Boolean = true,
    val approvalMode: String = ApprovalMode.AUTOMATIC.trackerValue,
    val notifyMode: String = NotifyMode.WHEN_DONE.trackerValue,
)
