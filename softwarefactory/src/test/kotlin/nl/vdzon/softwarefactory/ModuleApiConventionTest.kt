package nl.vdzon.softwarefactory

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guardrail for the public Modulith surface. Module roots may expose ports and API metadata,
 * while concrete implementations belong to an internal subpackage.
 */
class ModuleApiConventionTest {
    private val kotlinRoot = Path.of("src/main/kotlin/nl/vdzon/softwarefactory")

    @Test
    fun `module root deviations exactly match the shrink-only allowlist`() {
        val concreteDeclaration = Regex(
            "(?m)^(?!(?:private|internal)\\s)(?:public\\s+)?(?:(?:data|enum|value|open|abstract|sealed)\\s+)?(?:class|object|typealias)\\s+",
        )
        val actual = Files.list(kotlinRoot).use { modules ->
            modules.filter(Files::isDirectory).flatMap { module ->
                Files.list(module).use { files ->
                    files.filter { it.extension == "kt" }
                        .map { kotlinRoot.relativize(it).toString() }
                        .filter { concreteDeclaration.containsMatchIn(kotlinRoot.resolve(it).toFile().readText()) }
                        .toList().stream()
                }
            }.toList().toSet()
        }
        val allowed = javaClass.getResourceAsStream("/module-root-allowlist.txt")!!
            .bufferedReader().readLines().filter(String::isNotBlank).map { it.substringBefore(" | ") }.toSet()

        assertEquals(allowed, actual, "Concrete publieke roottypen moeten naar een intern subpackage verhuizen.")
    }

    @Test
    fun `named models contain only immutable data classes with explicit polymorphic exception`() {
        namedInterfaceSources("models").forEach { source ->
            val models = source.toFile().readText()
            assertFalse(Regex("\\bvar\\s+").containsMatchIn(models), "Publieke models mogen geen var bevatten (${source.fileName}).")
            assertFalse(Regex("\\bMutable[A-Za-z]+").containsMatchIn(models), "Publieke models mogen geen muteerbare collectie exposen.")
            val declarations = Regex("(?m)^(?:data\\s+class|sealed\\s+interface|enum\\s+class|class|interface)\\s+(\\w+)")
                .findAll(models).map { it.groupValues[1] }.toSet()
            assertTrue(
                declarations.all { it == "UiBriefingItem" || Regex("(?m)^data class\\s+$it\\b").containsMatchIn(models) },
                "models is data-class-only; UiBriefingItem is de expliciete polymorfe uitzondering (${source.fileName}).",
            )
        }
    }

    @Test
    fun `named types contain only enum sealed or value contracts`() {
        namedInterfaceSources("types").forEach { source ->
            val text = source.toFile().readText()
            val declarations = Regex("(?m)^(enum\\s+class|sealed\\s+(?:class|interface)|value\\s+class|class|interface)\\s+(\\w+)")
                .findAll(text).toList()
            assertTrue(declarations.isNotEmpty(), "Lege named types-interface: ${source.fileName}")
            assertTrue(
                declarations.all { it.groupValues[1].startsWith("enum ") || it.groupValues[1].startsWith("sealed ") || it.groupValues[1] == "value class" },
                "types bevat een gewone class/interface: ${source.fileName}",
            )
        }
    }

    @Test
    fun `named errors packages contain exception classes only`() {
        Files.walk(kotlinRoot).use { paths ->
            paths.filter { it.name == "package-info.java" && it.toFile().readText().contains("@org.springframework.modulith.NamedInterface(\"errors\")") }
                .forEach { packageInfo ->
                    Files.list(packageInfo.parent).use { types ->
                        types.filter { it.extension == "kt" }.forEach { source ->
                            val text = source.toFile().readText()
                            assertTrue(Regex("class\\s+\\w+[^\\n]*:\\s*(?:Runtime)?Exception").containsMatchIn(text),
                                "${source.fileName} is publiek error-contract maar geen exception.")
                        }
                    }
                }
        }
    }

    @Test
    fun `negative named-interface fixtures are rejected`() {
        assertFalse(validModelFixture("class MutableModel(var value: String)"))
        assertFalse(validTypeFixture("class OrdinaryType"))
        assertFalse(validTypeFixture("class WrongException : RuntimeException()"))
        assertFalse(validErrorFixture("data class ErrorPayload(val message: String)"))
        assertFalse(validErrorFixture("class IOException : RuntimeException()"))
        assertTrue(validModelFixture("data class PublicModel(val value: String)"))
        assertTrue(validTypeFixture("enum class PublicType { VALUE }"))
        assertTrue(validErrorFixture("class TrackerIssueNotFoundException : RuntimeException()"))
    }

    private fun namedInterfaceSources(name: String): List<Path> =
        Files.walk(kotlinRoot).use { paths ->
            paths.filter { it.name == "package-info.java" && it.toFile().readText().contains("@org.springframework.modulith.NamedInterface(\"$name\")") }
                .flatMap { Files.list(it.parent).use { files -> files.filter { file -> file.extension == "kt" }.toList().stream() } }
                .toList()
        }

    private fun validModelFixture(source: String): Boolean =
        !source.contains(Regex("\\bvar\\s+")) && Regex("(?m)^data class\\s+\\w+").containsMatchIn(source)

    private fun validTypeFixture(source: String): Boolean =
        Regex("(?m)^(?:enum class|sealed (?:class|interface)|value class)\\s+\\w+").containsMatchIn(source)

    private fun validErrorFixture(source: String): Boolean =
        Regex("class\\s+\\w+(?:Exception|Error)[^\\n]*:\\s*(?:Runtime)?Exception").containsMatchIn(source) &&
            !Regex("class\\s+(?:IO|SQL|Technical|Generic)\\w*(?:Exception|Error)").containsMatchIn(source)
}
