package nl.vdzon.softwarefactory.config

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

sealed class DeployConfig {
    /**
     * SF-2 (Telegram-melding niet meer premature): [apkCheck] is het onderscheid tussen "Skip-doel
     * zonder zinvolle artifact-check" (default, ongewijzigd gedrag: instant `deploy-approved`) en
     * "Skip-doel dat als GitHub-release-APK gepubliceerd wordt" — voor dat laatste wacht
     * `DeploySubtaskHandler` met `deploy-approved` tot [nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe]
     * een release ná de deploy-starttijd vindt (zelfde poort als `TelegramResultNotifyPoller`,
     * SF-1134), zodat de Telegram-DONE-melding pas verstuurd wordt als de APK er ook echt is.
     * [timeoutMinutes] geldt alleen als [apkCheck] aan staat (anders wordt er toch niet gepolld).
     */
    data class Skip(
        val apkCheck: Boolean = false,
        val timeoutMinutes: Int = ProjectConfiguration.DEFAULT_DEPLOY_TIMEOUT_MINUTES,
    ) : DeployConfig()
    data class RestRestart(
        val restartUrl: String,
        val versionUrl: String,
        val tokenEnvVar: String,
        val pollIntervalSeconds: Int,
        val timeoutMinutes: Int,
    ) : DeployConfig()
    data class OpenshiftWatch(
        val namespace: String,
        val deployment: String,
        val timeoutMinutes: Int,
        // Optioneel: als beide gezet zijn, is ArgoCD de waarheidsbron (Synced+Healthy+Succeeded op de
        // verwachte revisie) i.p.v. de "image niet-leeg"-heuristiek. Ontbreekt de config → bestaand gedrag.
        val argocdApp: String? = null,
        val argocdNamespace: String? = null,
        // SF-1134 — optionele publieke URL van de live component, gebruikt door de
        // telegram-result-notify-poller voor een extra HTTP-200-check bovenop de
        // ArgoCD/image-bevestiging die DeploySubtaskHandler al doet. Ontbreekt de config, dan
        // slaat die extra check over (geen regressie voor projecten zonder liveUrl).
        val liveUrl: String? = null,
    ) : DeployConfig()
}

/**
 * Eén te tonen "live component" op het Projects-scherm: een OpenShift-deployment waarvan de
 * dashboard-service de huidige image + pod-uptime opzoekt (zie
 * [nl.vdzon.softwarefactory.dashboard.services.FactoryDashboardService]). Losstaand van [DeployConfig]:
 * dat stuurt HOE een deploy getriggerd/bevestigd wordt (merge-pipeline), dit is puur informatief en
 * een project kan er nul, één of meerdere hebben (bv. softwarefactory zelf: backend én frontend apart).
 */
data class LiveComponentConfig(
    val label: String,
    val namespace: String,
    val deployment: String,
    // Optioneel: OpenShift-console-URL voor deze component, gebruikt door het Projects-scherm om het
    // deploy-bolletje (branch-timeline) doorklikbaar te maken. Ontbreekt de config, dan blijft het
    // bolletje tooltip-only (geen kapotte link).
    val consoleUrl: String? = null,
    // Optioneel: naam van de GitHub Actions-workflow die dít component bouwt (bv. "Build
    // robberts-assistent-backend image"). Zonder deze koppeling vergelijkt de sync-badge tegen de
    // laatst afgeronde run van WELKE workflow dan ook op main — in een monorepo met meerdere
    // path-filtered workflows (bv. meerdere apps/componenten in één project) laat dat een component
    // onterecht "loopt achter" zien zodra een ANDER component een main-build triggert. Met deze naam
    // vergelijkt DashboardQueryService.fetchLiveComponents tegen de laatste run van dít component's
    // eigen workflow; ontbreekt de config, dan blijft het oude (project-brede) gedrag intact.
    val workflowName: String? = null,
)

/**
 * Eén te bewaken deploy-doel binnen een project. Analoog aan `pathPrefixes` op `VerificationCommand`
 * (`.factory/verification.yaml`): [matchPaths] (leeg = "altijd van toepassing") bepaalt of dit doel
 * meedoet zodra de story-diff een van deze pad-prefixen raakt. Een project kan zo meerdere
 * onafhankelijke deploybare onderdelen hebben (bv. backend + frontend + een APK), elk met een eigen
 * [DeployConfig] en eigen [matchPaths]. Het bestaande enkelvoudige `deploy:`-blok in projects.yaml
 * levert hier een lijst van precies één doel met lege [matchPaths] op — functioneel identiek aan het
 * gedrag van vóór de multi-deployment-lijst (altijd bewaakt, ongeacht de diff).
 */
data class DeployTarget(
    val name: String,
    val matchPaths: List<String> = emptyList(),
    val config: DeployConfig,
)

interface ProjectRepositoryCatalog {
    fun repoFor(projectName: String?): String?
    fun resolve(repoOrName: String?): String?
    fun projectNames(): List<String>
}

interface ProjectTelegramSettings {
    fun projectNameForChatId(chatId: String?): String?
    fun telegramChatIdFor(projectName: String?): String?
    fun telegramChatIds(): Set<String>
}

interface ProjectAssistantSettings : ProjectRepositoryCatalog, ProjectTelegramSettings {
    fun privateFilesFor(projectName: String?): List<String>
    fun baseProjectName(): String?
}

interface ProjectMergePolicy {
    fun requiredChecksFor(projectName: String?): Set<String>
    fun requiredChecksForRepo(targetRepo: String): Set<String>
    fun requireCompleteMergePolicies()
}

interface ProjectDeploymentSettings {
    fun deployConfigFor(projectName: String?): DeployConfig
    fun liveComponentsFor(projectName: String?): List<LiveComponentConfig>

    /**
     * Alle deploy-doelen voor [projectName], mét hun [DeployTarget.matchPaths]. Backward-compat: een
     * project met het (oude) enkelvoudige `deploy:`-blok levert hier een lijst van precies één doel
     * met lege `matchPaths` op (functioneel identiek aan [deployConfigFor]). Onbekend/ongeconfigureerd
     * project → lijst met één Skip-doel (nooit leeg, zodat aanroepers "geen project" en "expliciete
     * skip" niet apart hoeven te onderscheiden).
     */
    fun deployTargetsFor(projectName: String?): List<DeployTarget>
}

/**
 * Eén "release-tag-prefix → Android package-naam"-koppeling (opt-in via `apkPackages:` in
 * projects.yaml). Nodig omdat [DownloadInfo][nl.vdzon.softwarefactory.dashboard.models.DownloadInfo]
 * zelf geen package-naam kent (dat is geen GitHub-API-veld) — het "App-updates"-scherm heeft 'm wel
 * nodig om de écht geïnstalleerde versie op het toestel op te vragen (native, per package). Eén repo
 * kan meerdere apps met losse tag-prefixes publiceren (bv. robberts-assistent: wind-latest,
 * groentetuin-latest, ...), vandaar een lijst i.p.v. één package-naam per project.
 */
data class ApkPackageMapping(
    val tagPrefix: String,
    val packageName: String,
    // Optioneel: naam van de GitHub Actions-workflow die déze app bouwt (bv. "Build Groentetuin
    // APK"). Zelfde reden als LiveComponentConfig.workflowName: zonder koppeling vergelijkt de
    // apk-sync-badge tegen de laatst afgeronde run van willekeurig welke workflow op main, wat in
    // een monorepo met meerdere apps een app onterecht "loopt achter" laat zien zodra een ANDERE
    // app een main-build triggert.
    val workflowName: String? = null,
)

interface ProjectDashboardSettings : ProjectRepositoryCatalog, ProjectDeploymentSettings {
    /**
     * Android package-namen voor de APK-releases van [projectName], gematcht via
     * [ApkPackageMapping.tagPrefix] tegen [DownloadInfo.releaseTag][nl.vdzon.softwarefactory.dashboard.models.DownloadInfo.releaseTag].
     * Leeg = geen mapping geconfigureerd (het "App-updates"-scherm valt dan terug op de oude,
     * package-naam-loze heuristiek).
     */
    fun apkPackagesFor(projectName: String?): List<ApkPackageMapping>
}

/** Eén "bewaar de laatste N releases met deze tag-prefix"-regel, zie [ReleaseCleanupConfig]. */
data class ReleasePrefixRule(val prefix: String, val keep: Int)

/** Eén "bewaar de laatste N versions van dit ghcr.io-package"-regel, zie [ReleaseCleanupConfig]. */
data class PackageCleanupRule(val name: String, val keep: Int)

/**
 * Nachtelijke release/package-cleanup-config voor één project (opt-in via `releaseCleanup:` in
 * projects.yaml). [protectedManifestPaths] zijn repo-relatieve paden (bv. kustomization.yaml's)
 * die de cleanup-scheduler uitleest op `sha-<kort>`-vermeldingen om nooit een image te verwijderen
 * dat nog in productie/preview draait; [alwaysKeepTags] beschermt daarnaast losse, met de hand
 * benoemde tags (default "main", de mutable fallback-tag die build-images.yml ook zet).
 */
data class ReleaseCleanupConfig(
    val releases: List<ReleasePrefixRule> = emptyList(),
    val packages: List<PackageCleanupRule> = emptyList(),
    val protectedManifestPaths: List<String> = emptyList(),
    val alwaysKeepTags: Set<String> = setOf("main"),
)

interface ProjectReleaseCleanupSettings {
    /** De release/package-cleanup-config voor [projectName], of null als niet geconfigureerd (= skip). */
    fun releaseCleanupFor(projectName: String?): ReleaseCleanupConfig?
}

/**
 * Mapt een logische projectnaam (waarde van het `Project`-veld op een story) naar een git-repo.
 *
 * De koppeling staat in een YAML-config-bestand naast de andere config (`projects.yaml`):
 *
 * ```yaml
 * projects:
 *   - name: personal-feed
 *     repo: git@github.com:robbert/personal-feed.git
 *   - name: softwarefactory
 *     repo: https://github.com/robbert/softwarefactory.git
 * ```
 *
 * Matching op naam is hoofdletter-ongevoelig en negeert omringende spaties. Eén tracker-project
 * kan zo stories voor verschillende repo's bevatten; subtaken erven de repo van hun parent-story.
 *
 * Het bestand wordt één keer (bij opstart) ingelezen. Ontbreekt het of is een entry ongeldig, dan
 * wordt dat gelogd en blijft de betreffende naam simpelweg onopgelost (→ story zonder repo).
 */
class ProjectConfiguration(
    repos: Map<String, String>,
    telegramChatIds: Map<String, String> = emptyMap(),
    privateFiles: Map<String, List<String>> = emptyMap(),
    private val baseProject: String? = null,
    deployConfigs: Map<String, DeployConfig> = emptyMap(),
    liveComponents: Map<String, List<LiveComponentConfig>> = emptyMap(),
    requiredChecks: Map<String, Set<String>> = emptyMap(),
    deployTargets: Map<String, List<DeployTarget>> = emptyMap(),
    releaseCleanupConfigs: Map<String, ReleaseCleanupConfig> = emptyMap(),
    apkPackages: Map<String, List<ApkPackageMapping>> = emptyMap(),
) : ProjectAssistantSettings, ProjectMergePolicy, ProjectDashboardSettings, ProjectReleaseCleanupSettings {
    private val byName = LinkedHashMap<String, String>()
    private val byRepoIdentity = LinkedHashMap<String, String>()
    private val originalNames = mutableListOf<String>()
    private val chatIdByName = LinkedHashMap<String, String>()
    private val nameByChatId = LinkedHashMap<String, String>()
    private val privateFilesByName = LinkedHashMap<String, List<String>>()
    private val deployConfigByName = LinkedHashMap<String, DeployConfig>()
    private val liveComponentsByName = LinkedHashMap<String, List<LiveComponentConfig>>()
    private val requiredChecksByName = LinkedHashMap<String, Set<String>>()
    private val deployTargetsByName = LinkedHashMap<String, List<DeployTarget>>()
    private val releaseCleanupByName = LinkedHashMap<String, ReleaseCleanupConfig>()
    private val apkPackagesByName = LinkedHashMap<String, List<ApkPackageMapping>>()

    init {
        repos.forEach { (name, repo) ->
            val key = name.trim().lowercase()
            val value = repo.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                if (byName.put(key, value) == null) {
                    originalNames.add(name.trim())
                }
                byRepoIdentity[repoIdentity(value)] = key
            }
        }
        telegramChatIds.forEach { (name, chatId) ->
            val key = name.trim().lowercase()
            val value = chatId.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                chatIdByName[key] = value
                nameByChatId[value] = name.trim()
            }
        }
        privateFiles.forEach { (name, files) ->
            val key = name.trim().lowercase()
            val clean = files.map { it.trim() }.filter { it.isNotEmpty() }
            if (key.isNotEmpty() && clean.isNotEmpty()) {
                privateFilesByName[key] = clean
            }
        }
        deployConfigs.forEach { (name, config) ->
            val key = name.trim().lowercase()
            if (key.isNotEmpty()) deployConfigByName[key] = config
        }
        liveComponents.forEach { (name, components) ->
            val key = name.trim().lowercase()
            if (key.isNotEmpty() && components.isNotEmpty()) liveComponentsByName[key] = components
        }
        requiredChecks.forEach { (name, checks) ->
            val key = name.trim().lowercase()
            val clean = checks.map(String::trim).filter(String::isNotEmpty).toSet()
            if (key.isNotEmpty() && clean.isNotEmpty()) requiredChecksByName[key] = clean
        }
        deployTargets.forEach { (name, targets) ->
            val key = name.trim().lowercase()
            if (key.isNotEmpty() && targets.isNotEmpty()) deployTargetsByName[key] = targets
        }
        releaseCleanupConfigs.forEach { (name, config) ->
            val key = name.trim().lowercase()
            if (key.isNotEmpty()) releaseCleanupByName[key] = config
        }
        apkPackages.forEach { (name, mappings) ->
            val key = name.trim().lowercase()
            if (key.isNotEmpty() && mappings.isNotEmpty()) apkPackagesByName[key] = mappings
        }
    }

    /**
     * Vindt de canonieke (lowercased) projectsleutel voor [value] — hetzij een geconfigureerde
     * projectnaam, hetzij een repo-URL in willekeurige vorm (https/ssh, met/zonder `.git`, met/zonder
     * trailing slash) die naar dezelfde repository wijst als een geconfigureerd project. Nodig omdat
     * externe aanleveraars (bv. Product Factory's `targetRepositoryUrl`) een andere URL-vorm gebruiken
     * dan `projects.yaml` zonder dat het een andere repository is — vóór deze fix liep zo'n story de
     * mergepolicy en deploy-config van zijn eigen project mis (lege `requiredChecks` → nooit gemerged;
     * `deployTargetsFor` → stille Skip i.p.v. de echte OpenShift-watch). Onbekend/leeg → null.
     */
    private fun keyFor(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lowered = trimmed.lowercase()
        if (byName.containsKey(lowered)) return lowered
        return byRepoIdentity[repoIdentity(trimmed)]
    }

    /** De `private:`-bestanden (paden) voor [projectName] die de assistent read-only krijgt. */
    override fun privateFilesFor(projectName: String?): List<String> {
        val key = keyFor(projectName) ?: return emptyList()
        return privateFilesByName[key].orEmpty()
    }

    /** De altijd-meegegeven basislaag (top-level `base:` in projects.yaml), of null. */
    override fun baseProjectName(): String? = baseProject?.trim()?.takeIf { it.isNotEmpty() }

    /** De projectnaam (originele schrijfwijze) die bij [chatId] hoort, of null voor onbekende kanalen. */
    override fun projectNameForChatId(chatId: String?): String? {
        val key = chatId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return nameByChatId[key]
    }

    /** Het Telegram-kanaal (chat-id) voor [projectName], of null als de naam leeg/onbekend/zonder kanaal is. */
    override fun telegramChatIdFor(projectName: String?): String? {
        val key = projectName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return chatIdByName[key]
    }

    /** Alle geconfigureerde Telegram-kanalen (voor de inkomende chat-id-allowlist). */
    override fun telegramChatIds(): Set<String> = chatIdByName.values.toSet()

    /** Verplichte GitHub-checknamen voor de mergepolicy van [projectName]. */
    override fun requiredChecksFor(projectName: String?): Set<String> {
        val key = keyFor(projectName) ?: return emptySet()
        return requiredChecksByName[key].orEmpty()
    }

    /** Policylookup voor stories waarvan het Repo-veld de geconfigureerde URL zelf bevat. */
    override fun requiredChecksForRepo(targetRepo: String): Set<String> {
        val key = keyFor(targetRepo) ?: return emptySet()
        return requiredChecksByName[key].orEmpty()
    }

    /** Faalt bij opstart wanneer een geconfigureerde target-repository geen expliciete mergepolicy heeft. */
    override fun requireCompleteMergePolicies() {
        val missing = byName.keys - requiredChecksByName.keys
        require(missing.isEmpty()) {
            "Project-config mist niet-lege merge.requiredChecks voor: ${missing.sorted().joinToString()}"
        }
    }

    /** De deploy-config voor [projectName]; default skip als niet geconfigureerd. */
    override fun deployConfigFor(projectName: String?): DeployConfig {
        val key = keyFor(projectName) ?: return DeployConfig.Skip()
        return deployConfigByName[key] ?: DeployConfig.Skip()
    }

    /**
     * De deploy-doelen voor [projectName]. Een expliciete lijst-vorm (`deploy:` als YAML-lijst) wint;
     * ontbreekt die maar is er wel het (oude) enkelvoudige `deploy:`-blok, dan wordt daarvan één doel
     * afgeleid met lege `matchPaths` (= altijd van toepassing, identiek aan het gedrag van vóór deze
     * lijst-vorm). Onbekend/ongeconfigureerd project → één Skip-doel.
     */
    override fun deployTargetsFor(projectName: String?): List<DeployTarget> {
        val key = keyFor(projectName)
            ?: return listOf(DeployTarget(name = "default", config = DeployConfig.Skip()))
        deployTargetsByName[key]?.let { return it }
        val config = deployConfigByName[key] ?: DeployConfig.Skip()
        return listOf(DeployTarget(name = key, config = config))
    }

    /**
     * De OpenShift-componenten waarvan het Projects-scherm de live image + uptime toont, voor
     * [projectName]. Een expliciete `liveComponents:`-lijst in projects.yaml wint; ontbreekt die
     * maar heeft het project wel `deploy.type: openshift-watch`, dan wordt daarvan één component
     * afgeleid (zodat bestaande configs zonder wijziging al een live-status krijgen). Geen van
     * beide geconfigureerd → lege lijst (het scherm toont dan "geen productieversie bekend").
     */
    override fun liveComponentsFor(projectName: String?): List<LiveComponentConfig> {
        val key = keyFor(projectName) ?: return emptyList()
        liveComponentsByName[key]?.let { return it }
        val deployConfig = deployConfigByName[key]
        if (deployConfig is DeployConfig.OpenshiftWatch && deployConfig.namespace.isNotEmpty() && deployConfig.deployment.isNotEmpty()) {
            return listOf(LiveComponentConfig(label = deployConfig.deployment, namespace = deployConfig.namespace, deployment = deployConfig.deployment))
        }
        return emptyList()
    }

    /** De geconfigureerde repo voor [projectName], of null als de naam leeg/onbekend is. */
    override fun repoFor(projectName: String?): String? {
        val key = keyFor(projectName) ?: return null
        return byName[key]
    }

    /**
     * Bepaalt de repo voor een `Repo`-veldwaarde: staat de tekst als projectnaam in de config,
     * dan de bijbehorende repo; zo niet, dan wordt de tekst zélf als repo-URL gebruikt. Leeg → null.
     */
    override fun resolve(repoOrName: String?): String? {
        val value = repoOrName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return byName[value.lowercase()] ?: value
    }

    /** De release/package-cleanup-config voor [projectName], of null als niet geconfigureerd (= skip). */
    override fun releaseCleanupFor(projectName: String?): ReleaseCleanupConfig? {
        val key = keyFor(projectName) ?: return null
        return releaseCleanupByName[key]
    }

    /** De `apkPackages`-mappings voor [projectName], of leeg als niet geconfigureerd. */
    override fun apkPackagesFor(projectName: String?): List<ApkPackageMapping> {
        val key = keyFor(projectName) ?: return emptyList()
        return apkPackagesByName[key].orEmpty()
    }

    /** Alle geconfigureerde projectnamen (genormaliseerd, lowercased), voor logging/diagnose. */
    fun configuredNames(): Set<String> = byName.keys

    /** De projectnamen in hun originele schrijfwijze — bv. als enum-keuzes in de tracker. */
    override fun projectNames(): List<String> = originalNames.toList()

    companion object {
        private val logger = LoggerFactory.getLogger(ProjectConfiguration::class.java)

        /**
         * Default deploy-timeout (minuten) als een project geen `timeoutMinutes` opgeeft. Verruimd van
         * 10 → 20 min (SF-771) zodat een trage-maar-geslaagde uitrol niet ten onrechte op deploy-failed
         * belandt; per project bij te stellen via `timeoutMinutes`.
         */
        const val DEFAULT_DEPLOY_TIMEOUT_MINUTES = 20

        /**
         * SnakeYAML met [SafeConstructor]: parseert alleen standaard YAML-typen (maps/lijsten/scalars)
         * en weigert YAML-tags die willekeurige Java-typen zouden instantiëren. De project-config bevat
         * uitsluitend platte data, dus dit is gedragsneutraal en sluit deserialisatie-RCE uit.
         */
        private fun safeYaml(): Yaml = Yaml(SafeConstructor(LoaderOptions()))

        /**
         * Canonieke "host/owner/repo"-identiteit van een git-repo-URL, ongeacht vorm: https, ssh
         * (`git@host:owner/repo.git`), met/zonder `.git`-suffix, met/zonder trailing slash, met/zonder
         * hoofdlettergebruik. Twee URL's die dezelfde repository aanduiden normaliseren naar dezelfde
         * string — de basis van [keyFor]'s URL-bewuste projectmatching.
         */
        private fun repoIdentity(value: String): String {
            val cleaned = value.trim().lowercase().removeSuffix("/").removeSuffix(".git")
            val sshMatch = Regex("^git@([^:]+):(.+)$").matchEntire(cleaned)
            return if (sshMatch != null) {
                "${sshMatch.groupValues[1]}/${sshMatch.groupValues[2]}"
            } else {
                cleaned.removePrefix("https://").removePrefix("http://")
            }
        }

        /**
         * Leest [path] (YAML) in. Ontbreekt het bestand, dan een lege resolver (alles → geen repo).
         * Een onleesbaar of foutief bestand levert eveneens een lege resolver op (gelogd), zodat een
         * typefout in de config de hele factory niet platlegt.
         */
        fun fromYaml(path: Path): ProjectConfiguration {
            if (!Files.exists(path)) {
                logger.warn("Project-config '{}' niet gevonden; geen enkele story krijgt een repo tot dit bestaat.", path)
                return ProjectConfiguration(emptyMap())
            }
            return try {
                val parsed = Files.newBufferedReader(path).use { reader -> parse(safeYaml().load(reader)) }
                logger.info(
                    "Project-config '{}' geladen: {} project(en) {}, {} met Telegram-kanaal.",
                    path, parsed.repos.size, parsed.repos.keys, parsed.telegramChatIds.size,
                )
                ProjectConfiguration(
                    parsed.repos, parsed.telegramChatIds, parsed.privateFiles, parsed.base,
                    parsed.deployConfigs, parsed.liveComponents, parsed.requiredChecks,
                    parsed.deployTargets, parsed.releaseCleanupConfigs, parsed.apkPackages,
                )
            } catch (ex: Exception) {
                logger.error("Project-config '{}' kon niet worden gelezen: {}", path, ex.message, ex)
                ProjectConfiguration(emptyMap())
            }
        }

        private data class ParsedProjects(
            val repos: Map<String, String>,
            val telegramChatIds: Map<String, String>,
            val privateFiles: Map<String, List<String>>,
            val base: String?,
            val deployConfigs: Map<String, DeployConfig> = emptyMap(),
            val liveComponents: Map<String, List<LiveComponentConfig>> = emptyMap(),
            val requiredChecks: Map<String, Set<String>> = emptyMap(),
            val deployTargets: Map<String, List<DeployTarget>> = emptyMap(),
            val releaseCleanupConfigs: Map<String, ReleaseCleanupConfig> = emptyMap(),
            val apkPackages: Map<String, List<ApkPackageMapping>> = emptyMap(),
        )

        /**
         * Parseert het optionele `apkPackages:`-blok van één project (lijst van `{tagPrefix,
         * packageName}`). Zelfde defensieve stijl als [parseReleaseCleanup]: een item met een
         * ontbrekend verplicht veld wordt gelogd en overgeslagen.
         */
        private fun parseApkPackages(node: List<*>, projectName: String): List<ApkPackageMapping> =
            node.mapNotNull { it as? Map<*, *> }
                .mapNotNull { rule ->
                    val tagPrefix = (rule["tagPrefix"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val packageName = (rule["packageName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    if (tagPrefix == null || packageName == null) {
                        logger.warn("Project-config: apkPackages-item van '{}' mist 'tagPrefix' of 'packageName'; overgeslagen.", projectName)
                        return@mapNotNull null
                    }
                    val workflowName = (rule["workflowName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ApkPackageMapping(tagPrefix, packageName, workflowName)
                }

        /**
         * Parseert het optionele `releaseCleanup:`-blok van één project. Onbekende/ontbrekende
         * verplichte velden op een lijst-item (`prefix`/`keep` resp. `name`/`keep`) worden gelogd en
         * overgeslagen — zelfde defensieve stijl als [parseDeployConfig], nooit de hele config laten
         * crashen op één typefout.
         */
        private fun parseReleaseCleanup(node: Map<*, *>, projectName: String): ReleaseCleanupConfig {
            val releases = (node["releases"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.mapNotNull { rule ->
                    val prefix = (rule["prefix"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val keep = (rule["keep"] as? Number)?.toInt()
                    if (prefix == null || keep == null) {
                        logger.warn("Project-config: releaseCleanup.releases-item van '{}' mist 'prefix' of 'keep'; overgeslagen.", projectName)
                        return@mapNotNull null
                    }
                    ReleasePrefixRule(prefix, keep)
                }
                .orEmpty()
            val packages = (node["packages"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.mapNotNull { rule ->
                    val name = (rule["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val keep = (rule["keep"] as? Number)?.toInt()
                    if (name == null || keep == null) {
                        logger.warn("Project-config: releaseCleanup.packages-item van '{}' mist 'name' of 'keep'; overgeslagen.", projectName)
                        return@mapNotNull null
                    }
                    PackageCleanupRule(name, keep)
                }
                .orEmpty()
            val protectedManifestPaths = (node["protectedManifestPaths"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                .orEmpty()
            val alwaysKeepTags = (node["alwaysKeepTags"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                ?.toSet()
                ?: setOf("main")
            return ReleaseCleanupConfig(releases, packages, protectedManifestPaths, alwaysKeepTags)
        }

        /**
         * Parseert één `deploy:`-object (zowel het enkelvoudige blok als één item uit de lijst-vorm)
         * naar een [DeployConfig]. Onbekend/ontbrekend `type` → `null` (gelogd), zodat de aanroeper
         * dat item overslaat i.p.v. de hele project-config te laten crashen.
         */
        private fun parseDeployConfig(deployMap: Map<*, *>, projectName: String): DeployConfig? {
            val type = (deployMap["type"] as? String)?.trim()?.lowercase()
            return when (type) {
                // Expliciete skip: vooral nuttig als item ín de lijst-vorm (bv. een APK-component
                // zonder eigen automatische watch, alleen meegenomen voor z'n matchPaths) — het
                // enkelvoudige blok kon dit al impliciet door `deploy:` helemaal weg te laten.
                // `apkCheck: true` (optioneel, default false) laat de DEPLOY-subtaak wachten op een
                // echte GitHub-release-APK i.p.v. instant te approven (SF-2).
                "skip" -> DeployConfig.Skip(
                    apkCheck = (deployMap["apkCheck"] as? Boolean) ?: false,
                    timeoutMinutes = (deployMap["timeoutMinutes"] as? Number)?.toInt() ?: DEFAULT_DEPLOY_TIMEOUT_MINUTES,
                )
                "rest-restart" -> DeployConfig.RestRestart(
                    restartUrl = (deployMap["restartUrl"] as? String)?.trim().orEmpty(),
                    versionUrl = (deployMap["versionUrl"] as? String)?.trim().orEmpty(),
                    tokenEnvVar = (deployMap["tokenEnvVar"] as? String)?.trim().orEmpty(),
                    pollIntervalSeconds = (deployMap["pollIntervalSeconds"] as? Number)?.toInt() ?: 15,
                    timeoutMinutes = (deployMap["timeoutMinutes"] as? Number)?.toInt() ?: DEFAULT_DEPLOY_TIMEOUT_MINUTES,
                )
                "openshift-watch" -> DeployConfig.OpenshiftWatch(
                    namespace = (deployMap["namespace"] as? String)?.trim().orEmpty(),
                    deployment = (deployMap["deployment"] as? String)?.trim().orEmpty(),
                    timeoutMinutes = (deployMap["timeoutMinutes"] as? Number)?.toInt() ?: DEFAULT_DEPLOY_TIMEOUT_MINUTES,
                    argocdApp = (deployMap["argocdApp"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    argocdNamespace = (deployMap["argocdNamespace"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    liveUrl = (deployMap["liveUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                )
                else -> {
                    logger.warn("Project-config: onbekend deploy.type '{}' voor project '{}'.", type, projectName)
                    null
                }
            }
        }

        private fun parse(root: Any?): ParsedProjects {
            val rootMap = root as? Map<*, *>
            val projects = requireNotNull(rootMap?.get("projects") as? List<*>) {
                "verwacht een top-level 'projects:'-lijst"
            }
            val base = (rootMap["base"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val repos = LinkedHashMap<String, String>()
            val chatIds = LinkedHashMap<String, String>()
            val privateFiles = LinkedHashMap<String, List<String>>()
            val deployConfigs = LinkedHashMap<String, DeployConfig>()
            val liveComponents = LinkedHashMap<String, List<LiveComponentConfig>>()
            val requiredChecks = LinkedHashMap<String, Set<String>>()
            val deployTargets = LinkedHashMap<String, List<DeployTarget>>()
            val releaseCleanupConfigs = LinkedHashMap<String, ReleaseCleanupConfig>()
            val apkPackages = LinkedHashMap<String, List<ApkPackageMapping>>()
            projects.forEachIndexed { index, entry ->
                val map = requireNotNull(entry as? Map<*, *>) {
                    "project #${index + 1} is geen naam/repo-object"
                }
                val name = (map["name"] as? String)?.trim().orEmpty()
                val repo = (map["repo"] as? String)?.trim().orEmpty()
                if (name.isEmpty() || repo.isEmpty()) {
                    logger.warn("Project-config: entry #{} mist 'name' of 'repo'; overgeslagen.", index + 1)
                    return@forEachIndexed
                }
                // Bewaar de originele schrijfwijze als sleutel; de resolver dedupt case-insensitive.
                if (repos.put(name, repo) != null) {
                    logger.warn("Project-config: dubbele projectnaam '{}'; laatste waarde wint.", name)
                }
                // telegramChatId is optioneel; YAML kan het als getal of string leveren.
                (map["telegramChatId"])?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { chatIds[name] = it }
                // private is optioneel: een lijst bestandspaden die de assistent read-only krijgt.
                (map["private"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf { p -> p.isNotEmpty() } }
                    ?.takeIf { it.isNotEmpty() }?.let { privateFiles[name] = it }
                (map["merge"] as? Map<*, *>)?.let { mergeMap ->
                    val checks = (mergeMap["requiredChecks"] as? List<*>)
                        ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                        ?.toSet()
                        .orEmpty()
                    if (checks.isNotEmpty()) requiredChecks[name] = checks
                }
                // deploy is optioneel; default = skip. Twee vormen: het bestaande enkelvoudige object
                // (backward-compat, altijd bewaakt) of een lijst van doelen mét eigen `matchPaths` —
                // analoog aan `pathPrefixes` op VerificationCommand (SF-multi-deployment-routing).
                when (val deployNode = map["deploy"]) {
                    null -> Unit
                    is Map<*, *> -> {
                        parseDeployConfig(deployNode, name)?.let { config ->
                            deployConfigs[name] = config
                            deployTargets[name] = listOf(DeployTarget(name = name, matchPaths = emptyList(), config = config))
                        }
                    }
                    is List<*> -> {
                        val targets = deployNode.mapIndexedNotNull { targetIndex, node ->
                            val targetMap = node as? Map<*, *> ?: run {
                                logger.warn("Project-config: deploy-doel #{} van '{}' is geen object; overgeslagen.", targetIndex + 1, name)
                                return@mapIndexedNotNull null
                            }
                            val config = parseDeployConfig(targetMap, name) ?: return@mapIndexedNotNull null
                            val targetName = (targetMap["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                                ?: "$name-${targetIndex + 1}"
                            val matchPaths = (targetMap["matchPaths"] as? List<*>)
                                ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                                .orEmpty()
                            DeployTarget(name = targetName, matchPaths = matchPaths, config = config)
                        }
                        if (targets.isNotEmpty()) {
                            deployTargets[name] = targets
                            // Backward-compat: bestaande aanroepers van deployConfigFor (dashboard,
                            // telegram-result-notify) kennen nog geen lijst — geef ze het eerste doel
                            // als representatieve config.
                            deployConfigs[name] = targets.first().config
                        }
                    }
                    else -> logger.warn("Project-config: 'deploy' van '{}' moet een object of lijst zijn; overgeslagen.", name)
                }
                // liveComponents is optioneel: welke OpenShift-deployments het Projects-scherm als
                // "live versie + uptime" toont. Los van `deploy` (zie [LiveComponentConfig]).
                (map["liveComponents"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
                    ?.mapNotNull { comp ->
                        val namespace = (comp["namespace"] as? String)?.trim().orEmpty()
                        val deployment = (comp["deployment"] as? String)?.trim().orEmpty()
                        if (namespace.isEmpty() || deployment.isEmpty()) {
                            logger.warn("Project-config: liveComponent van '{}' mist 'namespace' of 'deployment'; overgeslagen.", name)
                            return@mapNotNull null
                        }
                        LiveComponentConfig(
                            label = (comp["label"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: deployment,
                            namespace = namespace,
                            deployment = deployment,
                            consoleUrl = (comp["consoleUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                            workflowName = (comp["workflowName"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { liveComponents[name] = it }
                // releaseCleanup is optioneel: opt-in nachtelijke opruiming van oude GitHub Releases +
                // ghcr.io-package-versions (zie MaintenanceCleanupScheduler). Ontbreekt het blok, dan
                // slaat de scheduler dit project simpelweg over.
                (map["releaseCleanup"] as? Map<*, *>)?.let { releaseCleanupConfigs[name] = parseReleaseCleanup(it, name) }
                // apkPackages is optioneel: release-tag-prefix → Android package-naam, zodat het
                // "App-updates"-scherm de écht geïnstalleerde versie kan opvragen. Ontbreekt het blok,
                // dan valt dat scherm terug op de oude heuristiek voor dit projects apps.
                (map["apkPackages"] as? List<*>)?.let { apkPackages[name] = parseApkPackages(it, name) }
            }
            return ParsedProjects(
                repos, chatIds, privateFiles, base, deployConfigs, liveComponents, requiredChecks,
                deployTargets, releaseCleanupConfigs, apkPackages,
            )
        }
    }
}
