package nl.vdzon.softwarefactory.core

/**
 * Markeert een run die zonder geldig gestructureerd besluit in de gewone vraagfase eindigde.
 * Zo blijft een technische fallback te onderscheiden van een echte vraag aan de PO.
 */
object AgentNoDecision {
    private const val OUTCOME_PREFIX = "no-decision-"

    fun outcomeFor(phase: String): String = "$OUTCOME_PREFIX$phase"

    fun isNoDecision(outcome: String?): Boolean = outcome?.startsWith(OUTCOME_PREFIX) == true
}
