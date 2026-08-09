package nl.vdzon.softwarefactory

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guardrail bij SF-2059: elke `HttpRequest.newBuilder(...)` in productiecode moet een expliciete
 * `.timeout(...)` zetten. Zonder die timeout wacht `HttpClient.send` in het ergste geval eindeloos,
 * en omdat alle periodieke factory-taken op één scheduler-lijn staan legt één hangende call de hele
 * pijplijn stil.
 *
 * Bewust een broncontrole (zelfde recept als [ModuleApiConventionTest]) i.p.v. een HTTP-test per
 * client: er is geen mock-framework in deze repo en een echte time-out afwachten kost per plek
 * seconden, terwijl de regel juist over álle plekken tegelijk gaat — ook de plekken die er later
 * bijkomen.
 */
class HttpRequestTimeoutConventionTest {

    /** Testen draaien met cwd = modulebasedir, dus de zustermodules staan een niveau hoger. */
    private val mainSourceRoots = listOf(
        Path.of("src/main"),
        Path.of("../agentworker/src/main"),
        Path.of("../dashboard-backend/src/main"),
        Path.of("../factory-common/src/main"),
    )

    @Test
    fun `every HttpRequest builder sets an explicit timeout`() {
        val offenders = mainSourceRoots.flatMap(::buildersWithoutTimeout)

        assertEquals(
            emptyList(), offenders,
            "Elke HttpRequest.newBuilder(...) hoort een expliciete .timeout(...) te krijgen (zie SF-2059).",
        )
    }

    /**
     * De builders in deze repo lopen vaak over meerdere regels en worden soms als
     * `HttpRequest.Builder` uit een helper teruggegeven, dus we kijken naar de tekst tussen
     * `HttpRequest.newBuilder(` en de eerstvolgende `.build()` — dat dekt beide vormen.
     */
    private fun buildersWithoutTimeout(root: Path): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }.sorted().flatMap { file ->
            val text = file.toFile().readText()
            BUILDER_START.findAll(text).mapNotNull { match ->
                val end = text.indexOf(".build()", match.range.first).takeIf { it >= 0 } ?: text.length
                val lineNumber = text.take(match.range.first).count { it == '\n' } + 1
                "$file:$lineNumber".takeIf { !text.substring(match.range.first, end).contains(".timeout(") }
            }.toList()
        }
    }

    private companion object {
        private val BUILDER_START = Regex("HttpRequest\\.newBuilder\\(")
    }
}
