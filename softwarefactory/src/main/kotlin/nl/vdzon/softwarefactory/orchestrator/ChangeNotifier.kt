package nl.vdzon.softwarefactory.orchestrator

/**
 * Signaal dat de factory-state mogelijk is veranderd (na een orchestrator-poll of een
 * handmatige actie). De weblaag implementeert dit om aangesloten browsers via SSE te laten
 * verversen. Bewust in de orchestrator-laag gedefinieerd zodat de afhankelijkheid
 * web → orchestrator loopt (en niet andersom).
 */
interface ChangeNotifier {
    fun notifyChanged()

    /** No-op implementatie voor contexten zonder UI (bv. tests). */
    object Noop : ChangeNotifier {
        override fun notifyChanged() {}
    }
}
