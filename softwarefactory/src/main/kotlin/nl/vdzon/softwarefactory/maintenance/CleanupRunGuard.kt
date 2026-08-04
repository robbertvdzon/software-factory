package nl.vdzon.softwarefactory.maintenance

/**
 * Dubbel-draaien-bescherming voor de opruimrondes: per soort mag er hooguit één ronde tegelijk lopen,
 * ongeacht of die handmatig ("Nu draaien") of door de scheduler is gestart.
 *
 * Bewust in-memory en per JVM (zie `services/InMemoryCleanupRunGuard`): de factory draait als één
 * proces en zowel de schedulers als de bridge-afhandeling zitten daarin, dus een DB-lock zou alleen
 * complexiteit toevoegen. Draait de factory ooit met meerdere instanties, dan is dit het punt om te
 * herzien.
 *
 * Woont als poort in het root-package van `maintenance` zodat zowel de GitHub-cleanup
 * (`maintenance :: services`) als de factory-brede opruimers (`runtime`) en het dashboard dezelfde
 * bewaking gebruiken zonder dat er een interne subpackage over een module-grens hoeft (zie
 * `ModulithArchitectureTest`).
 */
interface CleanupRunGuard {
    /** `true` als deze aanroep het slot pakte; de aanroeper moet dan gegarandeerd [finish] doen. */
    fun tryStart(kind: String): Boolean

    fun finish(kind: String)

    /** De soorten die nu draaien — voedt de uitgeschakelde knoppen in het Opruimen-scherm. */
    fun runningKinds(): List<String>

    /** Draait [block] met de bewaking vast; `null` als die soort al liep. */
    fun <T> withKind(kind: String, block: () -> T): T? {
        if (!tryStart(kind)) return null
        return try {
            block()
        } finally {
            finish(kind)
        }
    }

    companion object {
        /**
         * Losse instantie voor code die zonder Spring-context draait (unit-tests, en de defaults van
         * componenten die de bean normaal geïnjecteerd krijgen).
         */
        fun inMemory(): CleanupRunGuard = nl.vdzon.softwarefactory.maintenance.services.InMemoryCleanupRunGuard()
    }
}
