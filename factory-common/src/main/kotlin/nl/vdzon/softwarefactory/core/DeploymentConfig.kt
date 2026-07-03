package nl.vdzon.softwarefactory.core

data class DeploymentConfig(
    val defaultBaseBranch: String = "main",
    val branchPrefix: String = "ai/",
    val previewUrlTemplate: String? = null,
    val previewNamespaceTemplate: String? = null,
    val previewDbSecretRecipe: String? = null,
)
