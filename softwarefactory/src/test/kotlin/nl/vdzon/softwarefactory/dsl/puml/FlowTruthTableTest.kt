package nl.vdzon.softwarefactory.dsl.puml

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowTruthTableTest {

    @Test
    fun `parse flow puml en genereer een complete waarheidstabel`() {
        val plantUml = readResource("/flow.puml")

        val flow = PlantUmlActivityParser.parse(plantUml)
        val table = flow.toTruthTable()

        // De flow is een boom: elk pad eindigt in een actie -> geen lege resultaten.
        assertTrue(table.rows.isNotEmpty(), "Er moet minstens één pad zijn")
        assertTrue(table.rows.all { it.result.isNotBlank() }, "Elk pad moet in een actie eindigen")

        // Geen dubbele paden (zelfde antwoord-combinatie mag maar één keer voorkomen).
        val combinations = table.rows.map { it.answers }
        assertEquals(combinations.size, combinations.toSet().size, "Paden moeten uniek zijn")

        // De root-vraag wordt op elk pad gesteld.
        val rootQuestion = table.questions.first()
        assertTrue(table.rows.all { rootQuestion in it.answers }, "Root-vraag moet op elk pad voorkomen")

        // Schrijf de tabel weg naar de test-resources.
        val output = resourcesDir().resolve("flow-truthtable.md")
        output.writeText(table.toMarkdown())
        assertTrue(output.exists())
    }

    private fun readResource(path: String): String =
        javaClass.getResource(path)?.readText()
            ?: error("Resource niet gevonden: $path")

    /** Bron-resources map, afgeleid van de gecompileerde test-classes locatie (robuust ongeacht cwd). */
    private fun resourcesDir(): File {
        val testClasses = File(javaClass.protectionDomain.codeSource.location.toURI())
        // .../target/test-classes  ->  module root  ->  src/test/resources
        val moduleRoot = testClasses.parentFile.parentFile
        return File(moduleRoot, "src/test/resources").apply { mkdirs() }
    }
}
