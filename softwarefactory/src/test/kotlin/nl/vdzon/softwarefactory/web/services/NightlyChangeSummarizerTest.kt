package nl.vdzon.softwarefactory.web.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NightlyChangeSummarizerTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `parses jobs with commit and sections`() {
        val json = """
            {"jobs":[
              {"story":"SF-428","commit":"abc123","sections":[
                {"label":"Wat","text":"Login hardened"},
                {"label":"Security","text":"Constant-time vergelijking"}]},
              {"story":"SF-413","commit":"","sections":[
                {"label":"Kwaliteit","text":"E2e-test toegevoegd"}]}
            ]}
        """.trimIndent()

        val result = NightlyChangeSummarizer.parseResponse(mapper, json)

        assertEquals(setOf("SF-428", "SF-413"), result.keys)
        assertEquals("abc123", result["SF-428"]!!.commit)
        assertEquals(2, result["SF-428"]!!.sections.size)
        assertEquals("Security", result["SF-428"]!!.sections[1].label)
        assertNull(result["SF-413"]!!.commit) // lege commit => null
        assertEquals(1, result["SF-413"]!!.sections.size)
    }

    @Test
    fun `tolerates surrounding prose around the json`() {
        val text = "Hier is het resultaat:\n{\"jobs\":[{\"story\":\"SF-1\",\"sections\":[{\"label\":\"Wat\",\"text\":\"x\"}]}]}\nKlaar."
        val result = NightlyChangeSummarizer.parseResponse(mapper, text)
        assertEquals(setOf("SF-1"), result.keys)
    }

    @Test
    fun `drops sections with empty label or text and returns empty on garbage`() {
        val json = """{"jobs":[{"story":"SF-9","sections":[{"label":"","text":"x"},{"label":"Wat","text":""},{"label":"Docs","text":"y"}]}]}"""
        val result = NightlyChangeSummarizer.parseResponse(mapper, json)
        assertEquals(1, result["SF-9"]!!.sections.size)
        assertEquals("Docs", result["SF-9"]!!.sections.first().label)

        assertTrue(NightlyChangeSummarizer.parseResponse(mapper, "geen json hier").isEmpty())
    }
}
