package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteResponse
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AgentRunCompletionController(
    private val runtimeApi: RuntimeApi,
) {
    @PostMapping("/agent-run/complete")
    fun complete(@RequestBody request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> =
        runtimeApi.complete(request)
}

