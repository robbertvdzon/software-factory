package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.ai.shared.AgentOutcomeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regressievangnet voor de audit-/summary-extractors: een afsluitend `agent_tips_update`-blok
 * mag het besluitblok niet wegvagen (SF-2292).
 */
class AgentOutcomeParserExtrasTest {

    private val auditDecision = """
        {"phase":"audit-finished","score":7.5,"scoreLabel":"voldoende",
         "proposedStory":{"title":"Titel van het voorstel","description":"Beschrijving van het voorstel"},
         "questions":["Eerste vraag","  ","Tweede vraag"]}
    """.trimIndent()

    private val summaryDecision = """
        {"phase":"summarized","descriptionSummary":"  Lange samenvatting  ",
         "shortDescriptionSummary":"Korte samenvatting"}
    """.trimIndent()

    private val tipsBlock = """
        {"agent_tips_update":[{"category":"cat","key":"sleutel","content":"inhoud"}]}
    """.trimIndent()

    private fun assertAuditFieldsIntact(text: String, hint: String) {
        val extras = AgentOutcomeParser.extractAuditExtras(text)

        assertEquals(7.5, extras.score, hint)
        assertEquals("voldoende", extras.scoreLabel, hint)
        assertEquals("Titel van het voorstel", extras.proposedStoryTitle, hint)
        assertEquals("Beschrijving van het voorstel", extras.proposedStoryDescription, hint)
        assertEquals(listOf("Eerste vraag", "Tweede vraag"), extras.questions, hint)
    }

    private fun assertSummaryFieldsIntact(text: String, hint: String) {
        val extras = AgentOutcomeParser.extractSummaryExtras(text)

        assertEquals("Lange samenvatting", extras.descriptionSummary, hint)
        assertEquals("Korte samenvatting", extras.shortDescriptionSummary, hint)
    }

    @Test
    fun `audit-extras komen door bij de voorgeschreven volgorde met het tipsblok eerst`() {
        assertAuditFieldsIntact(
            "Wat ik gedaan heb...\n\n$tipsBlock\n\n$auditDecision\n",
            "voorgeschreven volgorde: tipsblok eerst, besluitblok laatst",
        )
    }

    @Test
    fun `audit-extras komen door als het tipsblok als laatste blok staat`() {
        assertAuditFieldsIntact(
            "Wat ik gedaan heb...\n\n$auditDecision\n\n$tipsBlock\n",
            "omgekeerde volgorde: besluitblok eerst, tipsblok laatst",
        )
    }

    @Test
    fun `audit-extras komen door bij losse prozatekst na het besluitblok`() {
        assertAuditFieldsIntact(
            "$auditDecision\n\nNog wat afsluitende proza zonder JSON.\n",
            "prozatekst na het besluitblok",
        )
    }

    @Test
    fun `audit-voorstel met accolades en geciteerde JSON in de beschrijving komt heel door`() {
        val description = "Los op dat }-tekens en een fragment als {\\\"phase\\\":\\\"developed\\\"} blijven staan."
        // Regressievangnet op de string-bewuste jsonObjects() (commit 1817b43); geen tipsblok erna,
        // zodat deze test los staat van de volgorde-fix.
        val text = """{"phase":"audit-finished","proposedStory":{"title":"Escapes","description":"$description"}}"""

        val extras = AgentOutcomeParser.extractAuditExtras(text)

        assertEquals("Escapes", extras.proposedStoryTitle)
        assertEquals(
            "Los op dat }-tekens en een fragment als {\"phase\":\"developed\"} blijven staan.",
            extras.proposedStoryDescription,
        )
    }

    @Test
    fun `audit-extras zijn leeg zonder score voorstel of vragen`() {
        val extras = AgentOutcomeParser.extractAuditExtras("{\"phase\":\"audit-finished\"}\n\n$tipsBlock\n")

        assertNull(extras.score)
        assertNull(extras.scoreLabel)
        assertNull(extras.proposedStoryTitle)
        assertNull(extras.proposedStoryDescription)
        assertTrue(extras.questions.isEmpty(), "verwacht geen vragen")
    }

    @Test
    fun `summary-extras komen door bij de voorgeschreven volgorde met het tipsblok eerst`() {
        assertSummaryFieldsIntact(
            "Samenvatting van de story...\n\n$tipsBlock\n\n$summaryDecision\n",
            "voorgeschreven volgorde: tipsblok eerst, besluitblok laatst",
        )
    }

    @Test
    fun `summary-extras komen door als het tipsblok als laatste blok staat`() {
        assertSummaryFieldsIntact(
            "Samenvatting van de story...\n\n$summaryDecision\n\n$tipsBlock\n",
            "omgekeerde volgorde: besluitblok eerst, tipsblok laatst",
        )
    }

    @Test
    fun `summary-extras komen door bij losse prozatekst na het besluitblok`() {
        assertSummaryFieldsIntact(
            "$summaryDecision\n\nNog wat afsluitende proza zonder JSON.\n",
            "prozatekst na het besluitblok",
        )
    }

    @Test
    fun `summary-extras zijn leeg zonder samenvattingen`() {
        val extras = AgentOutcomeParser.extractSummaryExtras("{\"phase\":\"summarized\"}\n\n$tipsBlock\n")

        assertNull(extras.descriptionSummary)
        assertNull(extras.shortDescriptionSummary)
    }
}
