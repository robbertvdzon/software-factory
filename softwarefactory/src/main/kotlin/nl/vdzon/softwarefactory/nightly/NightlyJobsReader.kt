package nl.vdzon.softwarefactory.nightly

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.util.Base64

/** Eén nachtelijke job zoals gedeclareerd in `.factory/nightly/<name>/job.yaml`. */
data class NightlyJob(
    val project: String,
    val name: String,
    val title: String,
    val enabled: Boolean,
    val silent: Boolean,
    val aiSupplier: String?,
    val aiModel: String?,
    val priority: String?,
)

/** Een job inclusief de story-beschrijving (`story.md`); nodig om er een story van te maken. */
data class NightlyJobDetail(
    val job: NightlyJob,
    val story: String,
)

data class NightlyJobsResult(
    val jobs: List<NightlyJob>,
    val errors: List<String>,
)

/**
 * Leest de nachtelijke-job-declaraties (`.factory/nightly/<name>/{job.yaml,story.md}`) van elk
 * geconfigureerd project rechtstreeks van GitHub via `gh api` (contents-endpoint, default branch).
 * Geen checkout nodig — weerspiegelt altijd `main`. Een korte TTL-cache voorkomt dat de pagina bij
 * elke refresh GitHub bevraagt. Hergebruikt dezelfde `gh`-CLI + `SF_GITHUB_TOKEN` als de rest van de
 * factory.
 */
@Component
class NightlyJobsReader(
    private val git: GitApi = GitApi.default(),
    private val factorySecrets: FactorySecrets? = null,
) {
    private val mapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile private var cache: Pair<Long, NightlyJobsResult>? = null

    /** Alle jobs (metadata, geen story-tekst) voor de gegeven projecten (naam → repo-URL). */
    fun readAll(projects: List<Pair<String, String>>): NightlyJobsResult {
        cache?.let { (at, result) ->
            if (System.currentTimeMillis() - at < TTL_MILLIS) return result
        }
        val jobs = mutableListOf<NightlyJob>()
        val errors = mutableListOf<String>()
        for ((project, repoUrl) in projects) {
            val slug = git.repositorySlug(repoUrl)
            if (slug == null) {
                errors += "Project '$project': repo-URL niet herkend als GitHub: $repoUrl"
                continue
            }
            val listingResult = runCatching { ghJson(slug, NIGHTLY_DIR) }
            if (listingResult.isFailure) {
                errors += "Project '$project': kon $NIGHTLY_DIR niet lezen (${listingResult.exceptionOrNull()?.message})"
                continue
            }
            // Geen nightly-map (404 → null) of een onverwacht type → gewoon geen jobs.
            val listing = listingResult.getOrNull()
            if (listing == null || !listing.isArray) continue
            for (entry in listing) {
                if (entry.path("type").asText() != "dir") continue
                val name = entry.path("name").asText()
                val jobYaml = runCatching { ghJson(slug, "$NIGHTLY_DIR/$name/job.yaml") }.getOrElse {
                    errors += "Project '$project'/$name: job.yaml onleesbaar (${it.message})"
                    null
                } ?: continue
                runCatching { parseJob(project, name, decodeContent(jobYaml)) }
                    .onSuccess { jobs += it }
                    .onFailure { errors += "Project '$project'/$name: job.yaml ongeldig (${it.message})" }
            }
        }
        val sorted = jobs.sortedWith(compareBy({ it.project.lowercase() }, { it.name.lowercase() }))
        val result = NightlyJobsResult(sorted, errors)
        cache = System.currentTimeMillis() to result
        return result
    }

    /** Eén job inclusief `story.md`, vers opgehaald (cache omzeilend) zodat we de laatste tekst krijgen. */
    fun readJob(repoUrl: String, project: String, name: String): NightlyJobDetail? {
        val slug = git.repositorySlug(repoUrl) ?: return null
        val jobYaml = ghJson(slug, "$NIGHTLY_DIR/$name/job.yaml") ?: return null
        val story = ghJson(slug, "$NIGHTLY_DIR/$name/story.md")?.let { decodeContent(it) } ?: ""
        return NightlyJobDetail(parseJob(project, name, decodeContent(jobYaml)), story)
    }

    private fun parseJob(project: String, name: String, yaml: String): NightlyJob {
        val map = (Yaml().load<Any?>(yaml) as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        fun str(key: String): String? = (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun bool(key: String, default: Boolean): Boolean = when (val v = map[key]) {
            is Boolean -> v
            null -> default
            else -> v.toString().trim().equals("true", ignoreCase = true)
        }
        return NightlyJob(
            project = project,
            name = name,
            title = str("title") ?: name,
            enabled = bool("enabled", true),
            silent = bool("silent", true),
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
            throw RuntimeException("gh api contents/$path faalde (exit=${result.exitCode}): ${result.output.take(300)}")
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
        const val NIGHTLY_DIR = ".factory/nightly"
        const val TTL_MILLIS = 60_000L
    }
}
