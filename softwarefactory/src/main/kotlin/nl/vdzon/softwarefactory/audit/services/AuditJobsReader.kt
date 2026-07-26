package nl.vdzon.softwarefactory.audit.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitApi
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Base64

/** Eén audit zoals gedeclareerd in `.factory/nightly/<name>/job.yaml`. */
data class AuditJob(
    val project: String,
    val name: String,
    val title: String,
    val enabled: Boolean,
    val aiSupplier: String?,
    val aiModel: String?,
    val priority: String?,
)

/**
 * Een audit inclusief de vaste prompt (`prompt.md`) — de instructie die de auditor-agent uitvoert:
 * alleen lezen, evt. bestaande kwaliteits-/security-tooling draaien, oude rapporten + memory
 * raadplegen, een rapport schrijven en hoogstens 1 vervolg-story voorstellen.
 */
data class AuditJobDetail(
    val job: AuditJob,
    val prompt: String,
)

/** Gegooid als job.yaml of prompt.md ontbreekt/ongeldig is; de audit wordt dan overgeslagen. */
class AuditJobConfigException(message: String) : RuntimeException(message)

data class AuditJobsResult(
    val jobs: List<AuditJob>,
    val errors: List<String>,
)

/**
 * Leest de audit-declaraties (`.factory/nightly/<name>/{job.yaml,prompt.md}`) van elk geconfigureerd
 * project rechtstreeks van GitHub via `gh api` (contents-endpoint, default branch). Geen checkout
 * nodig voor het lezen van de config zelf — weerspiegelt altijd `main`. Zelfde route als voorheen
 * [nl.vdzon.softwarefactory.nightly.services.NightlyJobsReader], maar zonder subtasks.yaml-pad: een
 * audit heeft geen subtaken-lijst, alleen één vaste prompt.
 */
@Component
class AuditJobsReader(
    private val git: GitApi = GitApi.default(),
    private val factorySecrets: FactorySecrets? = null,
) {
    private val mapper = jacksonObjectMapper()

    @Volatile private var cache: Pair<Long, AuditJobsResult>? = null

    /** Alle audits (metadata, geen prompt-tekst) voor de gegeven projecten (naam → repo-URL). */
    fun readAll(projects: List<Pair<String, String>>): AuditJobsResult {
        cache?.let { (at, result) ->
            if (System.currentTimeMillis() - at < TTL_MILLIS) return result
        }
        val jobs = mutableListOf<AuditJob>()
        val errors = mutableListOf<String>()
        for ((project, repoUrl) in projects) {
            val slug = git.repositorySlug(repoUrl)
            if (slug == null) {
                errors += "Project '$project': repo-URL niet herkend als GitHub: $repoUrl"
                continue
            }
            val listingResult = runCatching { ghJson(slug, AUDIT_DIR) }
            if (listingResult.isFailure) {
                errors += "Project '$project': kon $AUDIT_DIR niet lezen (${listingResult.exceptionOrNull()?.message})"
                continue
            }
            val listing = listingResult.getOrNull()
            if (listing == null || !listing.isArray) continue
            for (entry in listing) {
                if (entry.path("type").asText() != "dir") continue
                val name = entry.path("name").asText()
                val jobYaml = runCatching { ghJson(slug, "$AUDIT_DIR/$name/job.yaml") }.getOrElse {
                    errors += "Project '$project'/$name: job.yaml onleesbaar (${it.message})"
                    null
                } ?: continue
                runCatching { parseJob(project, name, decodeContent(jobYaml)) }
                    .onSuccess { jobs += it }
                    .onFailure { errors += "Project '$project'/$name: job.yaml ongeldig (${it.message})" }
            }
        }
        val sorted = jobs.sortedWith(compareBy({ it.project.lowercase() }, { it.name.lowercase() }))
        val result = AuditJobsResult(sorted, errors)
        cache = System.currentTimeMillis() to result
        return result
    }

    /**
     * Eén audit inclusief `prompt.md`, vers opgehaald (cache omzeilend). Gooit
     * [AuditJobConfigException] als `prompt.md` ontbreekt zodat de audit wordt overgeslagen en de
     * fout in de digest belandt.
     */
    fun readJob(repoUrl: String, project: String, name: String): AuditJobDetail? {
        val slug = git.repositorySlug(repoUrl) ?: return null
        val jobYaml = ghJson(slug, "$AUDIT_DIR/$name/job.yaml") ?: return null
        val job = parseJob(project, name, decodeContent(jobYaml))
        val promptNode = ghJson(slug, "$AUDIT_DIR/$name/prompt.md")
            ?: throw AuditJobConfigException("$project/$name: prompt.md ontbreekt.")
        return AuditJobDetail(job, decodeContent(promptNode))
    }

    private fun parseJob(project: String, name: String, yaml: String): AuditJob {
        // SafeConstructor: alleen standaard YAML-typen, geen instantiatie van willekeurige Java-typen.
        // job.yaml komt uit configureerbare project-repo's (deels untrusted), dus dit sluit
        // deserialisatie-RCE uit en is gedragsneutraal omdat we enkel platte data (scalars/maps) lezen.
        val map = (Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        fun str(key: String): String? = (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun bool(key: String, default: Boolean): Boolean = when (val v = map[key]) {
            is Boolean -> v
            null -> default
            else -> v.toString().trim().equals("true", ignoreCase = true)
        }
        return AuditJob(
            project = project,
            name = name,
            title = str("title") ?: name,
            enabled = bool("enabled", true),
            aiSupplier = str("aiSupplier"),
            aiModel = str("aiModel"),
            priority = map["priority"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** `gh api repos/<slug>/contents/<path>`. Null bij 404 (pad bestaat niet); gooit bij andere fouten. */
    private fun ghJson(slug: String, path: String): JsonNode? {
        val result = git.runCommand(
            command = listOf("gh", "api", "repos/$slug/contents/$path"),
            env = ghEnv(),
            timeoutSeconds = 30,
        )
        if (result.exitCode != 0) {
            if (result.output.contains("Not Found", ignoreCase = true) || result.output.contains("404")) {
                return null
            }
            error("gh api contents/$path faalde (exit=${result.exitCode}): ${result.output.take(300)}")
        }
        return mapper.readTree(result.stdout)
    }

    /** Decodeert het base64-`content` uit een GitHub contents-respons (met newlines → MIME-decoder). */
    private fun decodeContent(node: JsonNode): String =
        String(Base64.getMimeDecoder().decode(node.path("content").asText("")))

    private fun ghEnv(): Map<String, String> =
        (factorySecrets?.githubToken ?: System.getenv("SF_GITHUB_TOKEN"))?.takeIf { it.isNotBlank() }
            ?.let { mapOf("GH_TOKEN" to it) } ?: emptyMap()

    private companion object {
        const val AUDIT_DIR = ".factory/nightly"
        const val TTL_MILLIS = 60_000L
    }
}
