package nl.vdzon.softwarefactory.web.controllers

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.softwarefactory.config.BearerTokenAuthorizer
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.roadmap.RoadmapApi
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Machine-API waarmee het roadmap-proces zijn adviesrang leest en bijwerkt. */
@RestController
@RequestMapping("/api/tracker/roadmap")
class RoadmapProcessApiController(
    private val roadmapApi: RoadmapApi,
    private val configApi: ConfigApi,
) {
    @GetMapping
    fun roadmap(request: HttpServletRequest): ResponseEntity<Any> {
        authorize(request)?.let { return it }
        return ResponseEntity.ok(roadmapApi.roadmap())
    }

    @PostMapping("/epics/{epicId}/process-rank")
    fun updateProcessRank(
        request: HttpServletRequest,
        @PathVariable epicId: Long,
        @RequestBody body: ProcessRankRequest,
    ): ResponseEntity<Any> {
        authorize(request)?.let { return it }
        return try {
            ResponseEntity.ok(roadmapApi.updateProcessRank(epicId, body.processRank) as Any)
        } catch (invalid: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to invalid.message))
        }
    }

    private fun authorize(request: HttpServletRequest): ResponseEntity<Any>? =
        if (BearerTokenAuthorizer.isAuthorized(configApi, request)) null else ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
}

data class ProcessRankRequest(val processRank: Int)
