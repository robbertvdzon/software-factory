package nl.vdzon.softwarefactory.web.controllers

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.softwarefactory.config.BearerTokenAuthorizer
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.dashboard.FactoryProcessControl
import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Publieke API-endpoints voor deploy-monitoring en factory-restart.
 *
 * - `GET /api/version` — geeft versie-info terug (publiek, geen auth vereist).
 * - `POST /api/restart` — triggert een factory-herstart; vereist Bearer-token via `SF_FACTORY_API_TOKEN`.
 */
@RestController
@RequestMapping("/api")
class FactoryApiController(
    private val versionService: FactoryVersionQuery,
    private val processService: FactoryProcessControl,
    private val factoryEnvironmentProvider: ConfigApi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

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

    @PostMapping("/restart")
    fun restart(request: HttpServletRequest): ResponseEntity<Void> {
        if (!BearerTokenAuthorizer.isAuthorized(factoryEnvironmentProvider, request)) {
            logger.warn("/api/restart: ongeldig, ontbrekend of niet-geconfigureerd Bearer-token.")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        logger.info("/api/restart aangevraagd via API.")
        processService.requestRestart()
        return ResponseEntity.ok().build()
    }
}
