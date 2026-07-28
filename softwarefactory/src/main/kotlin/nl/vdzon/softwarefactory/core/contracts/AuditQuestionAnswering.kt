package nl.vdzon.softwarefactory.core.contracts

/**
 * Beantwoorden van een blokkerende auditvraag, als losse poort zodat transportmodules (Telegram,
 * bridge) 'm kunnen aanroepen zonder van de `audit`-module af te hangen.
 *
 * Bewust niet op [FactoryOperations] geplakt: die wordt in veel testfixtures met de hand
 * geconstrueerd, en dan sleep je de complete audit-scheduler met z'n repositories overal mee naar
 * binnen voor één methode. `fun interface` zodat een test 'm met een lambda kan invullen, net als de
 * andere smalle poorten hier.
 */
fun interface AuditQuestionAnswering {
    /**
     * Slaat het antwoord op en plant meteen een vervolgrun van die audit in. `false` als de vraag
     * niet (meer) openstond — bijvoorbeeld bij een dubbele submit of als 'ie al via het dashboard
     * beantwoord was.
     */
    fun answerAuditQuestion(questionId: Long, answer: String): Boolean
}
