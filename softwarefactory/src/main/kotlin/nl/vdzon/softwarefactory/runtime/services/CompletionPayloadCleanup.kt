package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * De retentie op de payloads van `agent_run_completions` (`SF_COMPLETION_RETENTION_DAYS`): één
 * opruimsoort, met één ronde die zowel de completion-recovery (elke ~2 s, zie
 * [AgentRunCompletionService.reconcileDurableCompletions]) als de "Nu draaien"-knop aanroept.
 *
 * Bewust een eigen component en niet langer inline in `AgentRunCompletionService`: die klasse doet al
 * de hele afrondingsflow, en de purge is een losstaand opruimmechanisme dat nu ook van buitenaf
 * aanroepbaar moet zijn (SF-1929).
 */
@Component
class CompletionPayloadCleanup(
    private val factoryEnvironmentProvider: ConfigApi,
    private val cleanupLog: CleanupLogWriter? = null,
) : CleanupRunner {

    private var coordinator: DurableCompletionCoordinator? = null

    /** Optioneel: zonder durable completions (`SF_DURABLE_COMPLETION`) valt er niets te purgen. */
    @Autowired(required = false)
    fun configureDurableCompletion(durableCompletionCoordinator: DurableCompletionCoordinator?) {
        coordinator = durableCompletionCoordinator
    }

    override val cleanupKind: String = CleanupKinds.COMPLETION_PAYLOADS

    /** De purge kent geen eigen aan/uit-schakelaar; hij bestaat alleen mét durable-coordinator. */
    override fun cleanupEnabled(): Boolean = coordinator != null

    override fun runCleanupRoundLocked(trigger: String) {
        if (cleanupLog == null) purgeOnce() else cleanupLog.runLocked(cleanupKind, trigger, ::purgeOnce)
    }

    /**
     * Het geplande pad. Deze ronde hangt aan een poll van elke ~2 s; de logregel blijft daarom
     * onderdrukt zolang er niets verwijderd is en er geen fout was (zie [CleanupLogWriter]).
     */
    fun purgeScheduled() {
        if (cleanupLog == null) purgeOnce()
        else cleanupLog.runGuarded(cleanupKind, CleanupTriggers.SCHEDULED, enabled = true, round = ::purgeOnce)
    }

    /** Eén purge; geeft het aantal opgeruimde payloads terug (0 zonder durable-coordinator). */
    private fun purgeOnce(): Int = coordinator?.purgePayloads(retention) ?: 0

    private val retention: Duration by lazy {
        Duration.ofDays(
            factoryEnvironmentProvider.resolvedValues()["SF_COMPLETION_RETENTION_DAYS"]
                ?.toLongOrNull()?.coerceIn(1, MAX_RETENTION_DAYS) ?: DEFAULT_RETENTION_DAYS,
        )
    }

    private companion object {
        const val DEFAULT_RETENTION_DAYS = 30L
        const val MAX_RETENTION_DAYS = 3650L
    }
}
