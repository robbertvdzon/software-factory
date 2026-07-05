package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.SubtaskMaterializationApi
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Fase 3 — materialiseer de door de planner gedeclareerde subtaken, maar alleen
 * wanneer de planner `planned` bereikt (niet `planned-with-questions`).
 *
 * Reconcile met het nieuwe plan: het laatste plan is leidend. Subtaken van een eerder
 * (afgekeurd) plan die niet meer in dit plan staan én nog niet gestart zijn (lege Subtask
 * Phase) worden verwijderd — zo stapelt een reject→re-plan geen wees-subtaken meer op.
 * Subtaken die al lopen/af zijn blijven staan (geen werk weggooien). Daarna idempotent:
 * sla specs over waarvan de titel al als subtask onder de parent bestaat.
 */
@Component
class SubtaskPlanMaterializer(
    private val issueTrackerClient: YouTrackApi,
    // Verplicht: ProjectRepoResolver is een bean (ProjectRepoResolverConfiguration); een stille
    // lege default zou de per-project-config (o.a. manual-approve-vlaggen) onopgemerkt negeren.
    private val projectRepoResolver: ProjectRepoResolver,
) : SubtaskMaterializationApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun materializeIfPlanned(request: AgentRunCompleteRequest, role: AgentRole) {
        if (role != AgentRole.PLANNER || request.phase != StoryPhase.PLANNED.trackerValue || request.subtasks.isEmpty()) {
            return
        }
        val existingSubtasks = runCatching { issueTrackerClient.subtasksOf(request.storyKey) }
            .getOrElse { exception ->
                logger.warn("Kon bestaande subtaken niet ophalen voor {}; sla materialisatie over.", request.storyKey, exception)
                return
            }
        deleteNotStartedSubtasks(request.storyKey, existingSubtasks)
        // Titels van al-gestarte subtaken niet opnieuw aanmaken (die blijven staan).
        val startedTitles = existingSubtasks
            .filter { !it.fields.subtaskPhase.isNullOrBlank() }
            .map { it.summary }
            .toSet()
        // Subtaken erven de AI-supplier van de story (README §7), anders pikt de
        // poller ze niet op (de supplier-check staat vóór de router). De story wordt ook gebruikt
        // om het project (Repo-veld) te bepalen voor de manual-approve-poort (SF-192).
        val parentIssue = runCatching { issueTrackerClient.getIssue(request.storyKey) }.getOrNull()
        // In gedeclareerde volgorde aanmaken → oplopende issue-nummers = plan-volgorde;
        // manual-approve ná de AI-subtaken, merge/deploy als laatste → einde van de keten.
        val specs = plannedSpecs(request) + documentationSpecs() + manualApproveSpecs(parentIssue) + chainClosingSpecs()
        createSubtasks(request.storyKey, specs, startedTitles, parentIssue?.fields?.aiSupplier)
    }

    /**
     * Config-pad (nightly `subtasks.yaml`, SF-787): materialiseer EXACT de meegegeven specs, in de
     * gegeven volgorde. Anders dan [materializeIfPlanned] wordt hier NIETS auto-toegevoegd
     * (documentation/merge/deploy/manual-approve) — de config is volledig leidend. Idempotent op titel:
     * een spec waarvan de titel al als subtaak onder de parent bestaat, wordt overgeslagen (geen
     * reconcile/verwijderen). Subtaken erven de AI-supplier van de story, zodat de poller ze oppikt.
     */
    override fun materializeFromSpecs(storyKey: String, specs: List<SubtaskSpec>) {
        if (specs.isEmpty()) {
            return
        }
        val existingSubtasks = runCatching { issueTrackerClient.subtasksOf(storyKey) }
            .getOrElse { exception ->
                logger.warn("Kon bestaande subtaken niet ophalen voor {}; sla materialisatie over.", storyKey, exception)
                return
            }
        // Idempotent op titel: elke reeds bestaande subtaak-titel niet opnieuw aanmaken.
        val existingTitles = existingSubtasks.map { it.summary }.toSet()
        val parentIssue = runCatching { issueTrackerClient.getIssue(storyKey) }.getOrNull()
        createSubtasks(storyKey, specs, existingTitles, parentIssue?.fields?.aiSupplier)
    }

    /**
     * Reconcile: gooi ALLE nog-niet-gestarte subtaken (lege Subtask Phase) van een eerder plan weg
     * en maak het nieuwe plan vers in gedeclareerde volgorde opnieuw aan. Dat is nodig omdat de
     * uitvoervolgorde op oplopend issue-nummer loopt (zie YouTrackClient.subtasksOf): alleen door
     * vers-in-volgorde aan te maken lopen de nummers gelijk met de plan-volgorde. Subtaken die al
     * lopen/af zijn (niet-lege fase) blijven onaangeroerd — geen werk weggooien.
     */
    private fun deleteNotStartedSubtasks(storyKey: String, existingSubtasks: List<TrackerIssue>) {
        existingSubtasks
            .filter { it.fields.subtaskPhase.isNullOrBlank() }
            .forEach { orphan ->
                runCatching { issueTrackerClient.deleteIssue(orphan.key) }
                    .onSuccess { logger.info("Re-plan: niet-gestarte subtaak {} ({}) verwijderd voor {}.", orphan.key, orphan.summary, storyKey) }
                    .onFailure { exception -> logger.warn("Kon subtaak {} niet verwijderen voor {}.", orphan.key, storyKey, exception) }
            }
    }

    /**
     * De planner levert development/review/test/summary. MERGE en DEPLOY worden NIET door de
     * planner bepaald maar door de factory afgedwongen: elke story sluit af met een merge- en een
     * deploy-subtaak (SF-154). Een eventueel door de planner meegestuurde merge/deploy-spec wordt
     * genegeerd, zodat we nooit dubbele afsluit-subtaken krijgen.
     */
    private fun plannedSpecs(request: AgentRunCompleteRequest): List<SubtaskSpec> =
        request.subtasks.mapNotNull { spec ->
            when (val subtaskType = SubtaskType.fromTracker(spec.type)) {
                null -> {
                    logger.warn("Onbekend Subtask Type '{}' voor story {}; subtask overgeslagen.", spec.type, request.storyKey)
                    null
                }
                // MERGE/DEPLOY (SF-154) en DOCUMENTATION (SF-213) zijn factory-afgedwongen, niet
                // door de planner bepaald: filter een eventueel meegestuurde spec eruit (geen duplicaat).
                SubtaskType.MERGE, SubtaskType.DEPLOY, SubtaskType.DOCUMENTATION -> null
                else -> SubtaskSpec(subtaskType, spec.title, spec.description, spec.model, spec.effort)
            }
        }

    /**
     * Vaste afsluit-subtaken (geen AI-taken). Het gedrag — handmatige/automatische merge en
     * skip/rest-restart/openshift-watch deploy — komt uit projects.yaml en wordt op uitvoertijd
     * door Merge-/DeploySubtaskHandler bepaald. Merge vóór deploy: je deployt pas na de merge.
     */
    private fun chainClosingSpecs(): List<SubtaskSpec> =
        listOf(
            SubtaskSpec(
                SubtaskType.MERGE,
                MERGE_SUBTASK_TITLE,
                "Merge de story-branch (handmatig of automatisch, volgens projects.yaml).",
            ),
            SubtaskSpec(
                SubtaskType.DEPLOY,
                DEPLOY_SUBTASK_TITLE,
                "Deploy de gemergede code naar productie (volgens projects.yaml: skip/rest-restart/openshift-watch).",
            ),
        )

    /**
     * Vaste, factory-afgedwongen documentatie-stap (SF-213): ALTIJD aan (niet per project uit te
     * zetten) en WEL een AI-taak (rol DOCUMENTER). Ingevoegd ná de planner-subtaken (dus ná summary)
     * en vóór de manual-approve-poort. Idempotent via de titel-check in [createSubtasks].
     */
    private fun documentationSpecs(): List<SubtaskSpec> =
        listOf(
            SubtaskSpec(
                SubtaskType.DOCUMENTATION,
                DOCUMENTATION_SUBTASK_TITLE,
                "Werk alle relevante documentatie bij (README's, docs/, runbook/changelogs, API-docs e.d.) " +
                    "zodat die klopt met wat in de story is gedaan.",
            ),
        )

    /**
     * Vaste, niet-AI handmatige goedkeur-poort (SF-192): vlak ná de laatste AI-subtaak (summary)
     * en vóór de merge. Per project uit te zetten via projects.yaml (`manualApprove: false`);
     * ontbreekt de vlag, dan staat de poort AAN. Idempotent via de titel-check in [createSubtasks].
     * SF-335 — een silent story loopt volledig autonoom: de handmatige goedkeur-poort wordt dan
     * niet aangemaakt (merge/deploy blijven wél bestaan). Niet-silent: bestaand gedrag via projects.yaml.
     */
    private fun manualApproveSpecs(parentIssue: TrackerIssue?): List<SubtaskSpec> {
        val parentSilent = parentIssue?.fields?.silent == true
        return if (!parentSilent && projectRepoResolver.manualApproveFor(parentIssue?.fields?.repo)) {
            listOf(
                SubtaskSpec(
                    SubtaskType.MANUAL_APPROVE,
                    MANUAL_APPROVE_SUBTASK_TITLE,
                    "Handmatige goedkeuring vóór de merge (SF-192): keur goed om door te gaan, of keur af met een reden om de hele story opnieuw uit te voeren.",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun createSubtasks(
        storyKey: String,
        specs: List<SubtaskSpec>,
        startedTitles: Set<String>,
        parentSupplier: String?,
    ) {
        val failures = mutableListOf<String>()
        specs
            .filter { it.title.isNotBlank() && it.title !in startedTitles }
            .forEach { spec ->
                runCatching {
                    issueTrackerClient.createSubtask(
                        storyKey,
                        spec,
                        supplier = parentSupplier,
                    )
                }.onFailure { exception ->
                    logger.warn("Subtask aanmaken faalde voor {} ({}).", storyKey, spec.title, exception)
                    failures += "${spec.title}: ${exception.message?.take(300) ?: exception::class.simpleName}"
                }
            }
        // Een mislukte subtaak-aanmaak laat de story onvolledig achter (bv. een ontbrekende
        // merge/deploy-subtaak doordat de YouTrack-enumwaarde niet geregistreerd is). Niet stil
        // doorgaan: zet de story op Error, anders lijkt 'ie 'klaar' terwijl er stappen ontbreken.
        if (failures.isNotEmpty()) {
            val message = "[ORCHESTRATOR] Aanmaken van ${failures.size} subtaak/subtaken faalde voor " +
                "$storyKey: ${failures.joinToString(" | ")}"
            issueTrackerClient.updateIssueFields(
                storyKey,
                TrackerFieldUpdate.of(TrackerField.ERROR to message),
            )
        }
    }

    private companion object {
        // Vaste titels van de afsluitende merge/deploy-subtaken. Moeten stabiel blijven: de
        // idempotentie-check (al-gestarte titels niet opnieuw aanmaken) keyt hierop.
        const val MERGE_SUBTASK_TITLE = "Merge story-branch"
        const val DEPLOY_SUBTASK_TITLE = "Deploy naar productie"
        // Vaste titel van de handmatige goedkeur-poort. Stabiel houden: de idempotentie-check
        // (al-gestarte titels niet opnieuw aanmaken) keyt hierop.
        const val MANUAL_APPROVE_SUBTASK_TITLE = "Handmatige goedkeuring"
        // Vaste titel van de factory-afgedwongen documentatie-stap (SF-213). Stabiel houden: de
        // idempotentie-check (al-gestarte titels niet opnieuw aanmaken) keyt hierop.
        const val DOCUMENTATION_SUBTASK_TITLE = "Werk documentatie bij"
    }
}
