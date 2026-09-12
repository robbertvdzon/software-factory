package nl.vdzon.softwarefactory.web.controllers

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Readiness/liveness voor de Deployment; bewust zonder database-check zodat een probe nooit de pool leegtrekt. */
@RestController
class HealthController {
    @GetMapping("/healthz")
    fun health(): Map<String, String> = mapOf("status" to "ok")
}
