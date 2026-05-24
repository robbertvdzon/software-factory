package nl.vdzon.softwarefactory.config

class FactorySecrets(
    val youTrackBaseUrl: String = "",
    val youTrackToken: String = "",
    val youTrackProjects: List<String> = emptyList(),
    val githubToken: String = "",
    val factoryDatabaseUrl: String = "",
    val factoryDatabaseSchema: String = "",
    val kubeconfig: String? = null,
    val aiCredentialsDir: String? = null,
    val aiOauthToken: String? = null,
    val loadedFrom: String = "agentworker",
)
