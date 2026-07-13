package nl.vdzon.softwarefactory.nightly

import nl.vdzon.softwarefactory.nightly.models.NightlyStoryOutcome
import nl.vdzon.softwarefactory.nightly.services.NightlyChangeRef
import nl.vdzon.softwarefactory.nightly.services.NightlyJob
import nl.vdzon.softwarefactory.nightly.services.NightlyJobChanges

/**
 * Poort waarmee de [NightlyScheduler] (in de `nightly`-module) de rest van de factory bereikt zonder er
 * direct van af te hangen. De implementatie ([nl.vdzon.softwarefactory.web] `NightlyGatewayAdapter`)
 * delegeert naar de bestaande dashboard-service, de tracker en Telegram. Zo blijft de scheduler-module
 * los koppelbaar en puur testbaar met een fake-gateway.
 */
interface NightlyGateway {

    /** Alle nachtelijke jobs van alle projecten (uit `.factory/nightly/<job>/job.yaml`). */
    fun allJobs(): List<NightlyJob>

    /**
     * Maakt en start een silent story voor de gegeven job (zoals de Nightly-knop: silent=true,
     * start=true) en geeft de aangemaakte story-key terug.
     */
    fun startStory(project: String, jobName: String): String

    /** De huidige uitkomst (status + kosten/tijden) van de nachtelijke story met deze key. */
    fun storyOutcome(storyKey: String): NightlyStoryOutcome

    /** Klikbare dashboard-link naar de story, of leeg als er geen dashboard geconfigureerd is. */
    fun storyLink(storyKey: String): String

    /**
     * Verrijkt elke afgeronde story met links (dashboard + de wijziging) en een AI-samenvatting van wát
     * er die nacht veranderde (op basis van de commits/PR). Best-effort: faalt de AI of ontbreekt er
     * config, dan komen er minder (of geen) details terug en valt de digest terug op enkel de feiten.
     * Default leeg zodat fakes/oudere implementaties blijven werken.
     */
    fun describeChanges(stories: List<NightlyChangeRef>): Map<String, NightlyJobChanges> = emptyMap()

    /**
     * Stuurt een digest-bericht naar Telegram. [project] bepaalt het kanaal: de projectgroep uit
     * projects.yaml, of het standaardkanaal als [project] null is of geen eigen kanaal heeft.
     * @return true bij succes.
     */
    fun sendDigest(project: String?, text: String): Boolean
}

interface NightlyControl {
    fun startManualRun(): Boolean
    fun stopActiveRun(): Boolean
}
