package nl.vdzon.softwarefactory

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModulithArchitectureTest {
    private val sourceRoot = Path.of("src/main/kotlin/nl/vdzon/softwarefactory")
    private val modules = setOf(
        "bridge", "config", "contract", "core", "dashboard", "docs", "git", "github",
        "knowledge", "merge", "nightly", "orchestrator", "pipeline", "preview", "runtime",
        "support", "telegram", "tracker", "verification", "web",
    )

    @Test
    fun `application modules are valid`() {
        ApplicationModules.of(SoftwareFactoryApplication::class.java).verify()
    }

    @Test
    fun `every production module has explicit fail-closed dependency metadata`() {
        modules.forEach { module ->
            val metadata = sourceRoot.resolve("$module/package-info.java")
            assertTrue(metadata.toFile().isFile, "Module $module mist package-info.java")
            val text = metadata.readText()
            assertTrue(text.contains("@org.springframework.modulith.ApplicationModule"), "Module $module mist @ApplicationModule")
            assertTrue(text.contains("allowedDependencies"), "Module $module mist allowedDependencies")
            assertFalse(text.contains("\"*\""), "Module $module gebruikt een wildcard")
        }
    }

    @Test
    fun `transport adapters do not depend on each other`() {
        val transports = setOf("bridge", "telegram", "web")
        transports.forEach { source ->
            sourceRoot.resolve(source).toFile().walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                transports.minus(source).forEach { target ->
                    assertFalse(
                        file.readText().contains("import nl.vdzon.softwarefactory.$target."),
                        "$source mag transportmodule $target niet importeren (${file.name})",
                    )
                }
            }
        }
    }

    @Test
    fun `negative dependency fixtures are rejected`() {
        val namedInterfaces = mapOf("dashboard" to setOf("models", "types"))
        assertFalse(isAllowed("bridge", "web.controllers", setOf("dashboard"), namedInterfaces))
        assertFalse(isAllowed("bridge", "dashboard.services", setOf("dashboard"), namedInterfaces))
        assertFalse(isAllowed("bridge", "dashboard.unknown", setOf("dashboard :: unknown"), namedInterfaces))
        assertTrue(isAllowed("bridge", "dashboard", setOf("dashboard"), namedInterfaces))
        assertTrue(isAllowed("bridge", "dashboard.models", setOf("dashboard :: models"), namedInterfaces))
    }

    private fun isAllowed(
        source: String,
        targetPackage: String,
        allowed: Set<String>,
        namedInterfaces: Map<String, Set<String>>,
    ): Boolean {
        val parts = targetPackage.split('.')
        val target = parts.first()
        if (source in setOf("bridge", "telegram", "web") && target in setOf("bridge", "telegram", "web")) return false
        if (parts.size == 1) return target in allowed
        val named = parts[1]
        return named in namedInterfaces[target].orEmpty() && "$target :: $named" in allowed
    }
}
