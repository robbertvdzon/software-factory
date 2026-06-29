package nl.vdzon.softwarefactory.web.controllers

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.web.services.FactoryProcessService
import nl.vdzon.softwarefactory.web.services.FactoryVersionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Publieke API-endpoints voor deploy-monitoring en factory-restart.
 *
 * - `GET /api/version` — geeft versie-info terug (publiek, geen auth vereist).
 * - `POST /api/restart` — triggert een factory-herstart; vereist Bearer-token via `SF_FACTORY_API_TOKEN`.
 */
@RestController
@RequestMapping("/api")
class FactoryApiController(
    private val versionService: FactoryVersionService,
    private val processService: FactoryProcessService,
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
        // Lees de token via de factory-config (secrets.env/properties.env + env-vars), niet alleen
        // System.getenv — die staat in secrets.env en wordt niet in de procesomgeving geëxporteerd.
        val expectedToken = factoryEnvironmentProvider.resolvedValues()["SF_FACTORY_API_TOKEN"]?.takeIf { it.isNotBlank() }
            ?: run {
                logger.warn("SF_FACTORY_API_TOKEN niet geconfigureerd; /api/restart geweigerd.")
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            }
        val authHeader = request.getHeader("Authorization") ?: ""
        val providedToken = if (authHeader.startsWith("Bearer ")) authHeader.removePrefix("Bearer ") else ""
        if (!constantTimeEquals(providedToken, expectedToken)) {
            logger.warn("/api/restart: ongeldig of ontbrekend Bearer-token.")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        logger.info("/api/restart aangevraagd via API.")
        processService.requestRestart()
        return ResponseEntity.ok().build()
    }

    /**
     * Constante-tijd tokenvergelijking om timing-side-channels te voorkomen, net als de
     * dashboard-auth (FactoryDashboardAuth.login) en dashboard-backend AuthService al doen.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}
