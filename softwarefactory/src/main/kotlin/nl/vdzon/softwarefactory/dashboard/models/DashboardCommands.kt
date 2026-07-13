package nl.vdzon.softwarefactory.dashboard.models

data class CreateStoryCommand(
    val projectKey: String?,
    val title: String,
    val description: String?,
    val repo: String?,
    val aiSupplier: String?,
    val aiModel: String?,
    val start: Boolean,
    val autoApprove: Boolean = false,
    val silent: Boolean = false,
)
