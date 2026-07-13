package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec

/**
 * Geëxposeerde runtime-poort voor het declaratief materialiseren van subtaken.
 *
 * De implementatie ([nl.vdzon.softwarefactory.runtime.services.SubtaskPlanMaterializer]) leeft in
 * een niet-geëxposeerd sub-package; web-adapters (o.a. het nightly config-pad in
 * `DashboardQueryService`, SF-787) mogen die concrete klasse niet direct injecteren zonder de
 * Spring-Modulith module-grens te schenden. Ze injecteren daarom deze poort.
 */
interface SubtaskMaterializationApi {
    /**
     * Materialiseer EXACT de meegegeven specs onder de story, in de gegeven volgorde. Er wordt niets
     * auto-toegevoegd (documentation/merge/deploy/manual-approve): de opgegeven lijst is volledig
     * leidend. Idempotent op titel: een spec waarvan de titel al als subtaak onder de parent bestaat,
     * wordt overgeslagen. Subtaken erven de AI-supplier van de story, zodat de poller ze oppikt.
     */
    fun materializeFromSpecs(storyKey: String, specs: List<SubtaskSpec>)
}
