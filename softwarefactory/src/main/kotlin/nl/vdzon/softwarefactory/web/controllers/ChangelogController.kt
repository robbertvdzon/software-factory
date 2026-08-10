package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Publieke, ongeauthenticeerde changelog per project: `short_description_summary` van elke story
 * die er een heeft (door de SUMMARIZER geschreven ná oplevering), nieuwste eerst. Bewust zonder
 * [nl.vdzon.softwarefactory.config.BearerTokenAuthorizer] — bedoeld zodat andere apps (personal-feed,
 * robberts-assistent, ...) via hun eigen backend een "wat is er nieuw"-lijst kunnen tonen, net als
 * [AgentRunCompletionController] al ongeauthenticeerd is (daar is netwerkisolatie de grens, hier is
 * de inhoud zelf bewust publiek). De eigen dashboard-frontend gebruikt in plaats hiervan de
 * geauthenticeerde bridge-route (zie `BridgeRequestHandler`'s `"changelog.for"`-commando).
 */
@RestController
@RequestMapping("/api/v1/public/changelog")
class ChangelogController(
    private val trackerApi: TrackerCapabilities,
) {
    @GetMapping("/{projectName}")
    fun changelog(@PathVariable projectName: String): ResponseEntity<List<Map<String, String?>>> {
        val entries = trackerApi.changelogFor(projectName).map {
            mapOf(
                "timestamp" to it.fields.updatedAt?.toString(),
                "shortDescriptionSummary" to it.shortDescriptionSummary,
            )
        }
        return ResponseEntity.ok(entries)
    }
}
