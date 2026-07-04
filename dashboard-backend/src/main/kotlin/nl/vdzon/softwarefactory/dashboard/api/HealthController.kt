package nl.vdzon.softwarefactory.dashboard.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {
    @GetMapping("/healthz")
    fun health(): Map<String, String> = mapOf("status" to "ok")
}
