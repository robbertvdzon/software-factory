package nl.vdzon.softwarefactory.nightly.services

import nl.vdzon.softwarefactory.nightly.*
import nl.vdzon.softwarefactory.nightly.models.*
import nl.vdzon.softwarefactory.nightly.types.*
import nl.vdzon.softwarefactory.nightly.services.*
import nl.vdzon.softwarefactory.nightly.repositories.*

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.git.GitApi
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
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

/**
 * Een job inclusief de story-beschrijving (`story.md`); nodig om er een story van te maken.
 *
 * [subtasks] is de GEORDENDE lijst gedeclareerde subtaken uit `subtasks.yaml` (config-pad), of `null`
 * als er geen `subtasks.yaml` bestaat (legacy-pad: refine + plan). Een lege lijst kan niet voorkomen —
 * validatie eist minstens één subtaak, anders wordt de job overgeslagen met een fout.
 */
data class NightlyJobDetail(
    val job: NightlyJob,
    val story: String,
    val subtasks: List<SubtaskSpec>? = null,
)

/** Gegooid als een `subtasks.yaml` aanwezig maar ongeldig is; de job wordt dan overgeslagen. */
class NightlySubtasksConfigException(message: String) : RuntimeException(message)

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

    /**
     * Eén job inclusief `story.md`, vers opgehaald (cache omzeilend) zodat we de laatste tekst krijgen.
     *
     * Bevat de job een `subtasks.yaml`, dan wordt die (en de bijbehorende `<title>.md`-bestanden) gelezen
     * en gevalideerd; een validatiefout gooit [NightlySubtasksConfigException] zodat de job wordt
     * overgeslagen en de fout in de nachtelijke digest belandt. Zonder `subtasks.yaml` → `subtasks = null`
     * (legacy-pad).
     */
    fun readJob(repoUrl: String, project: String, name: String): NightlyJobDetail? {
        val slug = git.repositorySlug(repoUrl) ?: return null
        val jobYaml = ghJson(slug, "$NIGHTLY_DIR/$name/job.yaml") ?: return null
        val storyNode = ghJson(slug, "$NIGHTLY_DIR/$name/story.md")
        val story = storyNode?.let { decodeContent(it) } ?: ""
        val job = parseJob(project, name, decodeContent(jobYaml))

        val subtasksNode = ghJson(slug, "$NIGHTLY_DIR/$name/subtasks.yaml")
            ?: return NightlyJobDetail(job, story, subtasks = null)
        // Config-pad: subtasks.yaml is leidend. story.md is dan verplicht (validatie-eis).
        if (storyNode == null) {
            throw NightlySubtasksConfigException(
                "$project/$name: subtasks.yaml aanwezig maar story.md ontbreekt.",
            )
        }
        val specs = parseAndValidateSubtasks(slug, project, name, decodeContent(subtasksNode))
        return NightlyJobDetail(job, story, subtasks = specs)
    }

    /**
     * Parse + valideer `subtasks.yaml` (een geordende lijst van `type`+`title`) en laad per AI-subtaak
     * de beschrijving uit `<title>.md`. Faalt (met een duidelijke melding) bij: geen lijst / lege lijst /
     * ontbrekend of ongeldig type / dubbele titel / ontbrekend `<title>.md` voor een AI-subtaak.
     */
    private fun parseAndValidateSubtasks(
        slug: String,
        project: String,
        name: String,
        yaml: String,
    ): List<SubtaskSpec> {
        fun fail(reason: String): Nothing =
            throw NightlySubtasksConfigException("$project/$name: subtasks.yaml $reason")

        // SafeConstructor: alleen platte YAML-data, geen instantiatie van willekeurige Java-typen
        // (subtasks.yaml komt uit deels-untrusted project-repo's — zie parseJob).
        val root = runCatching { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) }
            .getOrElse { fail("parseert niet (${it.message}).") }
        val items = (root as? List<*>) ?: fail("moet een lijst van subtaken zijn.")
        if (items.isEmpty()) fail("bevat geen subtaken.")

        val seenTitles = mutableSetOf<String>()
        return items.mapIndexed { index, item ->
            val map = (item as? Map<*, *>) ?: fail("item ${index + 1} is geen map met 'type'/'title'.")
            val typeRaw = (map["type"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: fail("item ${index + 1} mist een 'type'.")
            val title = (map["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: fail("item ${index + 1} mist een 'title'.")
            val type = SubtaskType.fromTracker(typeRaw)?.takeIf { it in ALLOWED_TYPES }
                ?: fail("subtaak '$title' heeft een ongeldig type '$typeRaw'.")
            if (!seenTitles.add(title)) fail("bevat de dubbele titel '$title'.")
            val description = if (type in AI_TYPES) {
                val mdNode = ghJson(slug, "$NIGHTLY_DIR/$name/$title.md")
                    ?: fail("AI-subtaak '$title' mist het beschrijvingsbestand '$title.md'.")
                decodeContent(mdNode)
            } else {
                null
            }
            SubtaskSpec(type, title, description)
        }
    }

    private fun parseJob(project: String, name: String, yaml: String): NightlyJob {
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
            error("gh api contents/$path faalde (exit=${result.exitCode}): ${result.output.take(300)}")
        }
        return mapper.readTree(result.stdout)
    }

    /** Decodeert het base64-`content` uit een GitHub contents-respons (met newlines → MIME-decoder). */
    private fun decodeContent(node: JsonNode): String =
        String(Base64.getMimeDecoder().decode(node.path("content").asText("")))

    private fun ghEnv(): Map<String, String> =
        // TODO(fase 3): via ConfigApi
        (factorySecrets?.githubToken ?: System.getenv("SF_GITHUB_TOKEN"))?.takeIf { it.isNotBlank() }
            ?.let { mapOf("GH_TOKEN" to it) } ?: emptyMap()

    private companion object {
        const val NIGHTLY_DIR = ".factory/nightly"
        const val TTL_MILLIS = 60_000L

        // De geldige subtaak-types in subtasks.yaml. Bewust NIET de volledige SubtaskType-enum:
        // 'manual' is geen nightly-config-type (alleen de expliciete manual-approve-poort).
        val ALLOWED_TYPES = setOf(
            SubtaskType.DEVELOPMENT,
            SubtaskType.REVIEW,
            SubtaskType.TEST,
            SubtaskType.SUMMARY,
            SubtaskType.DOCUMENTATION,
            SubtaskType.MERGE,
            SubtaskType.DEPLOY,
            SubtaskType.MANUAL_APPROVE,
        )

        // AI-subtaken hebben een <title>.md nodig; merge/deploy/manual-approve zijn niet-AI-poorten.
        val AI_TYPES = setOf(
            SubtaskType.DEVELOPMENT,
            SubtaskType.REVIEW,
            SubtaskType.TEST,
            SubtaskType.SUMMARY,
            SubtaskType.DOCUMENTATION,
        )
    }
}
