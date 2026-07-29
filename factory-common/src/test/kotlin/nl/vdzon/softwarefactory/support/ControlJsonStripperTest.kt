package nl.vdzon.softwarefactory.support

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ControlJsonStripperTest {

    @Test
    fun `strips a single trailing phase control block`() {
        val text = "Alles werkt.\n\n{\"phase\":\"tested\"}"

        val stripped = ControlJsonStripper.stripTrailingControlJson(text)

        assertEquals("Alles werkt.", stripped)
    }

    @Test
    fun `strips multiple trailing control blocks in order`() {
        val text = "Rapport-inhoud hier.\n\n{\"agent_tips_update\":[]}\n{\"phase\":\"tested\"}"

        val stripped = ControlJsonStripper.stripTrailingControlJson(text)

        assertEquals("Rapport-inhoud hier.", stripped)
    }

    @Test
    fun `does not cut content that follows the last control block`() {
        val text = "{\"phase\":\"tested\"}\n\nDit is geen controleblok maar losse tekst erna."

        val stripped = ControlJsonStripper.stripTrailingControlJson(text)

        assertEquals(text, stripped)
    }

    @Test
    fun `is quote-aware and does not misparse braces quoted inside report text`() {
        val text = "Voorbeeldcode: \"{ niet echt json }\" werkt zoals verwacht.\n\n{\"phase\":\"tested\"}"

        val stripped = ControlJsonStripper.stripTrailingControlJson(text)

        assertEquals("Voorbeeldcode: \"{ niet echt json }\" werkt zoals verwacht.", stripped)
    }

    @Test
    fun `leaves a trailing block alone when it has no recognizable control key`() {
        val text = "Rapport met een afsluitend object.\n\n{\"foo\":\"bar\"}"

        val stripped = ControlJsonStripper.stripTrailingControlJson(text)

        assertEquals(text, stripped)
    }

    @Test
    fun `leaves text without any trailing JSON block completely untouched`() {
        val text = "Gewoon een rapport zonder enige JSON erin."

        val stripped = ControlJsonStripper.stripTrailingControlJson(text)

        assertEquals(text, stripped)
    }
}
