package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/version` — versie-info van het draaiende proces (publiek, geen auth vereist); gebruikt
 * door het dashboard en door deploy-monitoring om te zien welke commit live is.
 */
@RestController
@RequestMapping("/api")
class FactoryApiController(
    private val versionService: FactoryVersionQuery,
) {
    @GetMapping("/version", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun version(): ResponseEntity<Map<String, Any?>> {
        val info = versionService.info()
        return ResponseEntity.ok(
            mapOf(
                "commitHash" to info.commitShort,
                "commitDate" to info.commitDate,
                "branch" to info.branch,
                "commitSubject" to info.commitSubject,
                "startedAt" to info.startedAt.toString(),
                "dirty" to info.dirty,
            ),
        )
    }
}
