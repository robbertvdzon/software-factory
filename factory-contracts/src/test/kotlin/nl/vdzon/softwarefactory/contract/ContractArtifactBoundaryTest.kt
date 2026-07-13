package nl.vdzon.softwarefactory.contract

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractArtifactBoundaryTest {
    @Test
    fun `production artifact contains only wire contracts and readers`() {
        val root = Path.of("src/main")
        val files = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
        assertTrue(files.isNotEmpty(), "factory-contracts must contain production wire sources")
        assertTrue(files.all { it.extension == "kt" }, "fixtures and sample payloads are forbidden in production")
        val source = files.joinToString("\n") { it.readText() }
        assertFalse(source.contains("org.springframework"), "contracts may not import Spring")
        assertFalse(source.contains("org.yaml.snakeyaml"), "contracts may not import SnakeYAML")
    }
}
