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
    fun `named models contain only immutable data classes with explicit legacy exception`() {
        val models = kotlinRoot.resolve("dashboard/models/FactoryDashboardModels.kt").toFile().readText()
        assertFalse(Regex("\\bvar\\s+").containsMatchIn(models), "Publieke models mogen geen var bevatten.")
        assertFalse(Regex("\\bMutable[A-Za-z]+").containsMatchIn(models), "Publieke models mogen geen muteerbare collectie exposen.")
        val declarations = Regex("(?m)^(?:sealed\\s+)?(?:data\\s+class|interface)\\s+(\\w+)").findAll(models)
            .map { it.groupValues[1] }.toSet()
        assertTrue(declarations.all { it == "UiBriefingItem" || Regex("data class\\s+$it\\b").containsMatchIn(models) },
            "models is data-class-only; UiBriefingItem is de expliciete bestaande polymorfe uitzondering.")
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
}
