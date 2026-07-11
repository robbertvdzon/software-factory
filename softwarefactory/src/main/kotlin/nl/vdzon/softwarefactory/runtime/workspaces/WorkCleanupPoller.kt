package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.core.ActiveWorkspaceSource
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.util.Comparator
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Achtervang-opruiming voor tijdelijke `work/`-subroots die de factory-runtime zelf aanmaakt
 * (agent-workspaces, story-checkouts, assistant-checkouts, assistant-sessies). De bestaande
 * event-gedreven cleaners ruimen alleen op bij succesvolle run-completion of expliciete
 * purge/merge; deze scheduler vangt weesmappen op na crashes/killed processes door top-level
 * entries te verwijderen die langer dan de retentieperiode niet meer zijn aangeraakt (mtime).
 */
@Component
class WorkCleanupPoller(
    private val settings: WorkCleanupSettings,
    private val workRoot: Path = AgentWorkspaceFactory.projectRoot().resolve("work"),
    private val clock: Clock = Clock.systemUTC(),
    private val activeWorkspaceSources: List<ActiveWorkspaceSource> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${softwarefactory.work-cleanup-poll-ms:3600000}")
    fun poll() {
        if (!settings.enabled) {
            return
        }
        runCatching { cleanupOnce() }
            .onFailure { logger.warn("Work cleanup poll failed.", it) }
    }

    /** Scant alle vier subroots en verwijdert verlopen top-level entries. Geeft het aantal verwijderde entries terug. */
    fun cleanupOnce(): Int {
        val activePaths = runCatching {
            activeWorkspaceSources
                .flatMap { it.activePaths() }
                .mapTo(linkedSetOf()) { it.toAbsolutePath().normalize() }
        }.getOrElse {
            logger.warn("Work cleanup skipped: active workspace source failed.", it)
            return 0
        }
        var removed = 0
        removed += cleanupFlatRoot(workRoot.resolve("agent-workspaces"), activePaths)
        removed += cleanupFlatRoot(workRoot.resolve("stories"), activePaths)
        removed += cleanupFlatRoot(workRoot.resolve("assistant-checkouts"), activePaths)
        removed += cleanupAssistantSessions(workRoot.resolve("assistant"), activePaths)
        return removed
    }

    private fun cleanupFlatRoot(root: Path, activePaths: Set<Path>): Int {
        if (!root.exists() || !root.isDirectory()) {
            return 0
        }
        var removed = 0
        Files.newDirectoryStream(root).use { entries ->
            entries.forEach { entry ->
                if (removeIfExpired(entry, root, activePaths)) {
                    removed++
                }
            }
        }
        return removed
    }

    private fun cleanupAssistantSessions(root: Path, activePaths: Set<Path>): Int {
        if (!root.exists() || !root.isDirectory()) {
            return 0
        }
        var removed = 0
        Files.newDirectoryStream(root).use { chatDirs ->
            chatDirs.filter { it.isDirectory() }.forEach { chatDir ->
                Files.newDirectoryStream(chatDir).use { sessionDirs ->
                    sessionDirs.filter { it.isDirectory() }.forEach { sessionDir ->
                        val sessionWorkDir = sessionDir.resolve("work")
                        listOf("in", "out").forEach { name ->
                            val entry = sessionWorkDir.resolve(name)
                            if (entry.exists() && removeIfExpired(entry, root, activePaths)) {
                                removed++
                            }
                        }
                    }
                }
            }
        }
        return removed
    }

    private fun removeIfExpired(entry: Path, root: Path, activePaths: Set<Path>): Boolean = runCatching {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedEntry = entry.toAbsolutePath().normalize()
        require(normalizedEntry.startsWith(normalizedRoot)) {
            "Refusing to inspect entry outside work-cleanup root: $normalizedEntry"
        }

        if (activePaths.any { active -> active.startsWith(normalizedEntry) || normalizedEntry.startsWith(active) }) {
            return@runCatching false
        }

        val age = age(normalizedEntry)
        if (age < Duration.ofDays(settings.retentionDays)) {
            return@runCatching false
        }

        Files.walk(normalizedEntry).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
        logger.info("Work cleanup removed {} (age={})", normalizedEntry, age)
        true
    }.onFailure { exception ->
        logger.warn("Work cleanup failed for {}", entry, exception)
    }.getOrDefault(false)

    private fun age(entry: Path): Duration =
        Duration.between(latestModifiedTime(entry).toInstant(), clock.instant())

    private fun latestModifiedTime(entry: Path): FileTime {
        if (!entry.isDirectory()) {
            return Files.getLastModifiedTime(entry)
        }
        return Files.walk(entry).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { Files.getLastModifiedTime(it) }
                .max(Comparator.naturalOrder())
                .orElseGet { Files.getLastModifiedTime(entry) }
        }
    }
}
