package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class AgentKnowledgeController(
    private val knowledgeApi: KnowledgeApi,
) {
    @GetMapping("/agent-knowledge")
    fun find(
        @RequestParam("target_repo") targetRepo: String,
        @RequestParam("role") role: String,
    ): List<AgentKnowledgeEntry> =
        knowledgeApi.find(targetRepo, role)

    @PostMapping("/agent-knowledge/update")
    fun update(@RequestBody request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
        knowledgeApi.upsert(request)
}

