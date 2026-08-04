package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRepository
import nl.vdzon.softwarefactory.runtime.services.CleanupLogWriter
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime

/**
 * Vangt op wat een opruimer in de gedeelde opruim-log zou schrijven, zonder database. Handgeschreven
 * subklasse-fake omdat deze repo geen mock-framework heeft; de schrijfregel zelf ("alleen bij
 * verwijderingen of een fout") wordt in [CleanupLogWriterTest] tegen de échte implementatie getest.
 */
class RecordingCleanupLog : CleanupLogWriter(
    MaintenanceCleanupRunRepository(JdbcTemplate(), fakeSecrets()),
) {
    data class Entry(val kind: String, val startedAt: OffsetDateTime, val itemsDeleted: Int, val error: String?)

    val entries = mutableListOf<Entry>()

    override fun record(kind: String, startedAt: OffsetDateTime, itemsDeleted: Int, error: String?) {
        entries += Entry(kind, startedAt, itemsDeleted, error)
    }

    private companion object {
        fun fakeSecrets() = FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "token",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "test",
        )
    }
}
