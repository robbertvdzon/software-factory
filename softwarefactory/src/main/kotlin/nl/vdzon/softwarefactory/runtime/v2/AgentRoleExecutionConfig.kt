package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.AgentRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class AgentRoleExecutionConfig(
    val id: Long? = null,
    val role: AgentRole,
    val projectKey: String?,
    val execution: RuntimeExecution,
    val updatedAt: OffsetDateTime? = null,
    val updatedBy: String,
)

interface AgentRoleExecutionConfigRepository {
    fun list(): List<AgentRoleExecutionConfig>
    fun resolve(role: AgentRole, projectKey: String?): AgentRoleExecutionConfig?
    fun save(config: AgentRoleExecutionConfig): AgentRoleExecutionConfig
    fun delete(role: AgentRole, projectKey: String?): Boolean
}

@Repository
class JdbcAgentRoleExecutionConfigRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : AgentRoleExecutionConfigRepository {
    override fun list(): List<AgentRoleExecutionConfig> =
        jdbcTemplate.query(
            """
            SELECT id, role, project_key, vendor_id, model, mode, updated_at, updated_by
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_role_execution_config
            ORDER BY role, project_key NULLS FIRST
            """.trimIndent(),
        ) { rs, _ ->
            AgentRoleExecutionConfig(
                id = rs.getLong("id"),
                role = role(rs.getString("role")),
                projectKey = rs.getString("project_key"),
                execution = RuntimeExecution(
                    vendorId = rs.getString("vendor_id"),
                    model = rs.getString("model"),
                    mode = RuntimeExecutionMode.valueOf(rs.getString("mode")),
                ),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
                updatedBy = rs.getString("updated_by"),
            )
        }

    override fun resolve(role: AgentRole, projectKey: String?): AgentRoleExecutionConfig? {
        val rows = jdbcTemplate.query(
            """
            SELECT id, role, project_key, vendor_id, model, mode, updated_at, updated_by
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_role_execution_config
            WHERE role = ? AND (project_key = ? OR project_key IS NULL)
            ORDER BY CASE WHEN project_key = ? THEN 0 ELSE 1 END
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                AgentRoleExecutionConfig(
                    id = rs.getLong("id"),
                    role = role(rs.getString("role")),
                    projectKey = rs.getString("project_key"),
                    execution = RuntimeExecution(
                        vendorId = rs.getString("vendor_id"),
                        model = rs.getString("model"),
                        mode = RuntimeExecutionMode.valueOf(rs.getString("mode")),
                    ),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
                    updatedBy = rs.getString("updated_by"),
                )
            },
            role.markerKeyPart,
            projectKey,
            projectKey,
        )
        return rows.firstOrNull()
    }

    @Transactional
    override fun save(config: AgentRoleExecutionConfig): AgentRoleExecutionConfig {
        validate(config)
        jdbcTemplate.update(
            """
            DELETE FROM ${factorySecrets.factoryDatabaseSchema}.agent_role_execution_config
            WHERE role = ? AND project_key IS NOT DISTINCT FROM ?
            """.trimIndent(),
            config.role.markerKeyPart,
            config.projectKey,
        )
        val id = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO ${factorySecrets.factoryDatabaseSchema}.agent_role_execution_config
                  (role, project_key, vendor_id, model, mode, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, now(), ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                config.role.markerKeyPart,
                config.projectKey,
                config.execution.vendorId,
                config.execution.model,
                config.execution.mode.name,
                config.updatedBy,
            ),
        )
        return config.copy(id = id, updatedAt = OffsetDateTime.now())
    }

    override fun delete(role: AgentRole, projectKey: String?): Boolean =
        jdbcTemplate.update(
            """
            DELETE FROM ${factorySecrets.factoryDatabaseSchema}.agent_role_execution_config
            WHERE role = ? AND project_key IS NOT DISTINCT FROM ?
            """.trimIndent(),
            role.markerKeyPart,
            projectKey,
        ) > 0

    private fun validate(config: AgentRoleExecutionConfig) {
        require(config.role in CONFIGURABLE_ROLES) { "Role ${config.role} is not configurable" }
        require(config.execution.vendorId.matches(VENDOR_PATTERN)) { "Invalid vendorId" }
        require(config.execution.model.isNotBlank() && config.execution.model.length <= 160) { "Invalid model" }
        config.projectKey?.let {
            require(it.matches(PROJECT_PATTERN)) { "Invalid projectKey" }
        }
        require(config.updatedBy.isNotBlank() && config.updatedBy.length <= 200) { "Invalid updatedBy" }
    }

    private fun role(value: String): AgentRole =
        AgentRole.entries.first { it.markerKeyPart == value }

    companion object {
        private val VENDOR_PATTERN = Regex("^[a-z][a-z0-9-]{0,99}$")
        private val PROJECT_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_-]{0,99}$")
        val CONFIGURABLE_ROLES = setOf(
            AgentRole.REFINER,
            AgentRole.PLANNER,
            AgentRole.DEVELOPER,
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            AgentRole.SUMMARIZER,
            AgentRole.DOCUMENTER,
            AgentRole.AUDITOR,
        )
    }
}

@Service
class AgentRoleExecutionConfigService(
    private val repository: AgentRoleExecutionConfigRepository,
    private val runtime: AgentRuntimeV2HttpClient,
) {
    fun list(): List<AgentRoleExecutionConfig> = repository.list()

    fun resolve(role: AgentRole, projectKey: String?): AgentRoleExecutionConfig =
        requireNotNull(repository.resolve(role, projectKey)) {
            "No Agent Runtime execution is configured for role ${role.markerKeyPart}"
        }

    fun options(role: AgentRole): List<RuntimeExecutionOption> =
        runtime.executionOptions(taskType(role))

    fun save(config: AgentRoleExecutionConfig): AgentRoleExecutionConfig {
        val offered = options(config.role).any {
            it.available && it.execution == config.execution && taskType(config.role) in it.taskTypes
        }
        require(offered) {
            "Execution ${config.execution.vendorId}/${config.execution.model}/${config.execution.mode} " +
                "is not currently offered for ${config.role.markerKeyPart}"
        }
        return repository.save(config)
    }

    fun deleteOverride(role: AgentRole, projectKey: String): Boolean =
        repository.delete(role, projectKey)

    private fun taskType(role: AgentRole): RuntimeTaskType =
        if (role in REPOSITORY_ROLES) RuntimeTaskType.REPOSITORY_AGENT else RuntimeTaskType.STRUCTURED_GENERATION

    companion object {
        private val REPOSITORY_ROLES = setOf(
            AgentRole.DEVELOPER,
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            AgentRole.DOCUMENTER,
            AgentRole.AUDITOR,
        )
    }
}

