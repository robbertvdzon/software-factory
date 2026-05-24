package nl.vdzon.softwarefactory.knowledge.services

import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.knowledge.repositories.AgentKnowledgeRepository
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.springframework.stereotype.Service

@Service
class AgentKnowledgeService(
    private val repository: AgentKnowledgeRepository,
) : KnowledgeApi {
    override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> {
        val agentRole = parseAgentRole(role)
        return repository.find(TargetRepoNormalizer.normalize(targetRepo), agentRole)
    }

    override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry {
        val agentRole = parseAgentRole(request.role)
        val normalizedRequest = request.copy(
            targetRepo = TargetRepoNormalizer.normalize(request.targetRepo),
            role = agentRole.markerKeyPart,
            category = request.category.trim().ifBlank { "general" },
            key = request.key.trim(),
            content = request.content.trim(),
            updatedByStory = request.updatedByStory?.trim()?.takeIf { it.isNotBlank() },
        )
        require(normalizedRequest.key.isNotBlank()) { "Knowledge key is required." }
        require(normalizedRequest.content.isNotBlank()) { "Knowledge content is required." }
        return repository.upsert(normalizedRequest)
    }

    private fun parseAgentRole(role: String): AgentRole =
        AgentRole.entries.firstOrNull { it.markerKeyPart == role || it.name.equals(role, ignoreCase = true) }
            ?.takeIf { it in agentRoles }
            ?: throw IllegalArgumentException("Unknown agent role for knowledge: $role")

    private companion object {
        val agentRoles = setOf(AgentRole.REFINER, AgentRole.DEVELOPER, AgentRole.REVIEWER, AgentRole.TESTER)
    }
}

object TargetRepoNormalizer {
    fun normalize(rawTargetRepo: String): String {
        val trimmed = rawTargetRepo.trim().removeSuffix("/")
        GitApi.default().repositorySlug(trimmed)?.let { return "github.com/${it.removeSuffix(".git")}" }
        return trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("ssh://")
            .removeSuffix(".git")
    }
}
