package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteResponse
import nl.vdzon.softwarefactory.runtime.types.CompletionOutcome
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AgentRunCompletionController(
    private val runtimeApi: RuntimeApi,
) {
    // De module-API levert een domeinresultaat; alleen hier (web-adapter) wordt dat
    // naar HTTP vertaald: verwerkt → 200, geen actieve run gevonden → 404.
    @PostMapping("/agent-run/complete")
    fun complete(@RequestBody request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> =
        when (val outcome = runtimeApi.complete(request)) {
            is CompletionOutcome.Completed ->
                ResponseEntity.ok(AgentRunCompleteResponse(outcome.agentRunId, outcome.storyRunId))
            CompletionOutcome.NoActiveRun -> ResponseEntity.notFound().build()
        }
}
