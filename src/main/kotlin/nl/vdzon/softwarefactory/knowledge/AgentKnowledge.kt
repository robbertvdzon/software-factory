package nl.vdzon.softwarefactory.knowledge

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitRepositoryUrl
import nl.vdzon.softwarefactory.jira.AgentRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.OffsetDateTime

data class AgentKnowledgeEntry(
    val targetRepo: String,
    val role: String,
    val category: String,
    val key: String,
    val content: String,
    val updatedByStory: String?,
    val updatedAt: OffsetDateTime?,
)

data class AgentKnowledgeUpdateRequest(
    val targetRepo: String,
    val role: String,
    val category: String,
    val key: String,
    val content: String,
    val updatedByStory: String? = null,
)

interface AgentKnowledgeRepository {
    fun find(targetRepo: String, role: AgentRole): List<AgentKnowledgeEntry>

    fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry
}

@Repository
class JdbcAgentKnowledgeRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : AgentKnowledgeRepository {
    override fun find(targetRepo: String, role: AgentRole): List<AgentKnowledgeEntry> =
        jdbcTemplate.query(
            """
            SELECT target_repo, role, category, key, content, updated_by_story, updated_at
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_knowledge
            WHERE target_repo = ? AND role = ?
            ORDER BY category ASC, key ASC
            """.trimIndent(),
            { rs, _ -> rs.toEntry() },
            targetRepo,
            role.markerKeyPart,
        )

    override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
        requireNotNull(
            jdbcTemplate.query(
                """
                INSERT INTO ${factorySecrets.factoryDatabaseSchema}.agent_knowledge
                    (target_repo, role, category, key, content, updated_by_story)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (target_repo, role, category, key)
                DO UPDATE SET content = EXCLUDED.content,
                              updated_by_story = EXCLUDED.updated_by_story,
                              updated_at = now()
                RETURNING target_repo, role, category, key, content, updated_by_story, updated_at
                """.trimIndent(),
                { rs, _ -> rs.toEntry() },
                request.targetRepo,
                request.role,
                request.category,
                request.key,
                request.content,
                request.updatedByStory,
            ).firstOrNull(),
        )

    private fun ResultSet.toEntry(): AgentKnowledgeEntry =
        AgentKnowledgeEntry(
            targetRepo = getString("target_repo"),
            role = getString("role"),
            category = getString("category"),
            key = getString("key"),
            content = getString("content"),
            updatedByStory = getString("updated_by_story"),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java),
        )
}

@Service
class AgentKnowledgeService(
    private val repository: AgentKnowledgeRepository,
) {
    fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> {
        val agentRole = parseAgentRole(role)
        return repository.find(TargetRepoNormalizer.normalize(targetRepo), agentRole)
    }

    fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry {
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

@RestController
class AgentKnowledgeController(
    private val service: AgentKnowledgeService,
) {
    @GetMapping("/agent-knowledge")
    fun find(
        @RequestParam("target_repo") targetRepo: String,
        @RequestParam("role") role: String,
    ): List<AgentKnowledgeEntry> =
        service.find(targetRepo, role)

    @PostMapping("/agent-knowledge/update")
    fun update(@RequestBody request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
        service.upsert(request)
}

object TargetRepoNormalizer {
    fun normalize(rawTargetRepo: String): String {
        val trimmed = rawTargetRepo.trim().removeSuffix("/")
        GitRepositoryUrl.parse(trimmed).slug?.let { return "github.com/${it.removeSuffix(".git")}" }
        return trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("ssh://")
            .removeSuffix(".git")
    }
}
