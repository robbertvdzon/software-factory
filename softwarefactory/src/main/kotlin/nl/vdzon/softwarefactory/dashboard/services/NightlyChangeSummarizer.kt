package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.nightly.services.NightlyChangeRef
import nl.vdzon.softwarefactory.nightly.services.NightlyJobChanges
import nl.vdzon.softwarefactory.nightly.services.NightlySection
import nl.vdzon.softwarefactory.telegram.AssistantClient
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Vat per nachtelijke story samen wát er die nacht veranderde, voor de [nl.vdzon.softwarefactory.nightly]
 * digest. Resolvet eerst de feitelijke links (dashboard + de PR) uit de story-run, en laat dan — best
 * effort — de Claude-assistent in een Docker-container de commits/PR's inspecteren (`gh`) en een korte
 * samenvatting per story teruggeven als JSON. Faalt de AI of ontbreekt config, dan komen alleen de links
 * terug en valt de digest terug op enkel de feiten.
 *
 * De AI krijgt alléén metadata mee (story-key, titel, repo-slug, PR-nummer); de diffs haalt de agent zelf
 * op met `gh` (met de meegegeven `GH_TOKEN`), zodat we geen grote diff-teksten als CLI-argument doorgeven.
 */
@Component
class NightlyChangeSummarizer(
    private val assistantClient: AssistantClient,
    private val repository: FactoryDashboardRepository,
    private val secrets: FactorySecrets,
    private val git: GitApi = GitApi.default(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Per story-key: links + (waar mogelijk) een AI-samenvatting van de wijzigingen. */
    fun describe(stories: List<NightlyChangeRef>): Map<String, NightlyJobChanges> {
        val refs = stories.filter { it.storyKey.isNotBlank() }.associateBy { it.storyKey }
        if (refs.isEmpty()) return emptyMap()

        val contexts = refs.mapValues { (_, ref) -> storyContext(ref) }
        // Basis: alleen links (dashboard + PR), ook als de AI straks niets oplevert.
        val base = contexts.mapValues { (_, ctx) ->
            NightlyJobChanges(storyLink = ctx.storyLink, changeUrl = ctx.prUrl)
        }.toMutableMap()

        val summarizable = contexts.filterValues { it.slug != null && it.prNumber != null }
        if (summarizable.isEmpty() || !assistantClient.enabled || secrets.githubToken.isBlank()) {
            if (summarizable.isNotEmpty()) {
                logger.info("Nightly: AI-samenvatting overgeslagen (assistent uit of geen GitHub-token); alleen links.")
            }
            return base
        }

        // Eén AI-aanroep per project: kleinere scope (minder PR's per call) en faal-isolatie — een
        // hapering bij het ene project laat het andere project z'n samenvatting houden. Per call retries.
        summarizable.entries.groupBy { it.value.project }.forEach { (project, entries) ->
            val projectContexts = entries.associate { it.key to it.value }
            summarizeWithRetry(project, projectContexts).forEach { (storyKey, narrative) ->
                val ctx = contexts[storyKey] ?: return@forEach
                val commitUrl = narrative.commit
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sha -> ctx.slug?.let { "https://github.com/$it/commit/$sha" } }
                base[storyKey] = NightlyJobChanges(
                    storyLink = ctx.storyLink,
                    changeUrl = commitUrl ?: ctx.prUrl,
                    sections = narrative.sections,
                )
            }
        }
        return base
    }

    /**
     * Vraagt de AI-samenvatting voor één project op, met retries: claude faalt soms transient (API-hik,
     * timeout, containerstart onder druk) en valt dan terug op leeg. Een paar pogingen maakt de digest
     * betrouwbaar; pas na alle pogingen leeg valt dit project terug op enkel links.
     */
    private fun summarizeWithRetry(project: String, contexts: Map<String, StoryContext>): Map<String, Narrative> {
        repeat(MAX_AI_ATTEMPTS) { attempt ->
            val parsed = runCatching { runAi(contexts) }
                .onFailure { logger.warn("Nightly: AI-samenvatting voor {} faalde (poging {}/{}).", project, attempt + 1, MAX_AI_ATTEMPTS, it) }
                .getOrDefault(emptyMap())
            if (parsed.isNotEmpty()) return parsed
            logger.warn("Nightly: AI-samenvatting voor {} leeg (poging {}/{}).", project, attempt + 1, MAX_AI_ATTEMPTS)
        }
        logger.warn("Nightly: AI-samenvatting voor {} bleef leeg na {} pogingen; alleen links.", project, MAX_AI_ATTEMPTS)
        return emptyMap()
    }

    private fun runAi(contexts: Map<String, StoryContext>): Map<String, Narrative> {
        val reply = assistantClient.askForSummary(
            systemPrompt = SYSTEM_PROMPT,
            userMessage = userMessage(contexts),
            extraEnv = mapOf("GH_TOKEN" to secrets.githubToken, "GITHUB_TOKEN" to secrets.githubToken),
            timeoutSeconds = AI_TIMEOUT_SECONDS,
        )
        if (reply.isError) {
            logger.warn("Nightly: AI-samenvatting gaf een fout terug; alleen links.")
            return emptyMap()
        }
        logger.info("Nightly: AI-samenvatting klaar (kosten ~${'$'}${"%.2f".format(reply.costUsd)}).")
        return parseResponse(objectMapper, reply.text)
    }

    private fun userMessage(contexts: Map<String, StoryContext>): String {
        val byProject = contexts.entries
            .groupBy { it.value.project }
            .toSortedMap(compareBy { it.lowercase() })
        val lines = mutableListOf("Vat per story samen wat er vannacht veranderde. De jobs van vannacht:", "")
        byProject.forEach { (project, entries) ->
            lines += "PROJECT $project"
            entries.sortedBy { it.key }.forEach { (storyKey, ctx) ->
                lines += "- $storyKey | repo ${ctx.slug} | PR #${ctx.prNumber} | \"${ctx.title}\""
            }
            lines += ""
        }
        lines += "Inspecteer per story de PR-diff, bv. `gh pr diff <pr> --repo <slug>` (en `--name-only` voor"
        lines += "het overzicht). De merge-commit-sha haal je met"
        lines += "`gh pr view <pr> --repo <slug> --json mergeCommit -q .mergeCommit.oid`."
        lines += "Geef je antwoord als JSON volgens het afgesproken schema. Geen andere tekst."
        return lines.joinToString("\n")
    }

    private fun storyContext(ref: NightlyChangeRef): StoryContext {
        val run = runCatching { repository.latestStoryRun(ref.storyKey) }.getOrNull()
        val slug = run?.targetRepo?.let { git.repositorySlug(it)?.removeSuffix(".git") }
        return StoryContext(
            project = ref.project,
            title = ref.title,
            slug = slug,
            prNumber = run?.prNumber,
            prUrl = run?.prUrl,
            storyLink = secrets.dashboardBaseUrl?.takeIf { it.isNotBlank() }
                ?.trimEnd('/')?.let { "$it/stories/${ref.storyKey}" },
        )
    }

    /** AI-samenvatting van één story: optionele merge-commit + de inhoudelijke regels. */
    internal data class Narrative(val commit: String?, val sections: List<NightlySection>)

    private data class StoryContext(
        val project: String,
        val title: String,
        val slug: String?,
        val prNumber: Int?,
        val prUrl: String?,
        val storyLink: String?,
    )

    companion object {
        private const val DIGEST_CHAT_ID = "nightly-digest"
        private const val AI_TIMEOUT_SECONDS = 600L

        /** Aantal pogingen per project-samenvatting voordat we op enkel links terugvallen. */
        private const val MAX_AI_ATTEMPTS = 3

        /** Parse het JSON-antwoord (tolereert wat omringende tekst) naar story-key → samenvatting. */
        internal fun parseResponse(objectMapper: ObjectMapper, text: String): Map<String, Narrative> {
            val json = extractJsonObject(text) ?: return emptyMap()
            val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: return emptyMap()
            val jobs = root.path("jobs").takeIf { it.isArray } ?: return emptyMap()
            val result = LinkedHashMap<String, Narrative>()
            jobs.forEach { node ->
                val storyKey = node.path("story").asText("").trim()
                if (storyKey.isEmpty()) return@forEach
                val commit = node.path("commit").asText("").trim().takeIf { it.isNotEmpty() }
                val sections = node.path("sections").takeIf { it.isArray }?.mapNotNull { section ->
                    val label = section.path("label").asText("").trim()
                    val sectionText = section.path("text").asText("").trim()
                    if (label.isEmpty() || sectionText.isEmpty()) null else NightlySection(label, sectionText)
                }.orEmpty()
                result[storyKey] = Narrative(commit, sections)
            }
            return result
        }

        /** Pakt het buitenste `{...}`-object uit een tekst (claude zet er soms wat omheen). */
        private fun extractJsonObject(text: String): String? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start in 0 until end) text.substring(start, end + 1) else null
        }

        private val SYSTEM_PROMPT = """
            Je bent de nightly-digest-samensteller van de Software Factory. Elke nacht draaien autonome
            "nightly jobs"; elke job is een story die als PR (squash-merge) op de hoofdbranch belandt.

            Je krijgt per project de stories van vannacht met hun repo-slug en PR-nummer. Maak per story een
            korte, concrete samenvatting van wát er DAADWERKELIJK veranderde, op basis van de echte diff —
            niet alleen de commit-message. Gebruik `gh` (er is een GH_TOKEN gezet) om de PR-diff te lezen.
            Negeer worklog-bestanden (docs/stories/worklog/*) en de story-definitie (docs/stories/*.md): dat
            is ruis. Kijk naar de echte code- en docwijzigingen.

            Beschrijf op hoofdlijnen (NIET file-voor-file) met deze vaste kopjes, en laat een kopje WEG als
            er niets onder valt (verzin niets):
            - "Wat": wat is er functioneel/qua code veranderd?
            - "Security": welke kwetsbaarheid gedicht of hardening toegepast?
            - "Kwaliteit": refactors, consistentie, dead code opgeruimd, tests toegevoegd?
            - "Docs": wat is aangepast en waarom?

            Regels: schrijf in het Nederlands, beknopt en concreet (1–2 zinnen per kopje). Geen marketingtaal,
            geen "diverse verbeteringen". Noem concreet wát er veranderde, maar duik niet in elke regel.

            Antwoord met UITSLUITEND JSON in dit schema (geen andere tekst eromheen):
            {"jobs":[{"story":"SF-123","commit":"<merge-commit-sha of leeg>","sections":[
              {"label":"Wat","text":"..."},{"label":"Security","text":"..."},
              {"label":"Kwaliteit","text":"..."},{"label":"Docs","text":"..."}]}]}
        """.trimIndent()
    }
}
