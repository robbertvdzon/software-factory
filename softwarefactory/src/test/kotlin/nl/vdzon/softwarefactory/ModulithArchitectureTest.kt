package nl.vdzon.softwarefactory

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModulithArchitectureTest {
    @Test
    fun `application modules are valid`() {
        ApplicationModules.of(SoftwareFactoryApplication::class.java).verify()
    }
}

