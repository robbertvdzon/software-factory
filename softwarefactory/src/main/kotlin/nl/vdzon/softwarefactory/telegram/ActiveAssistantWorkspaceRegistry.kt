package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.core.ActiveWorkspaceSource
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class ActiveAssistantWorkspaceRegistry : ActiveWorkspaceSource {
    private val registrations = ConcurrentHashMap<Path, AtomicInteger>()

    override fun activePaths(): Set<Path> = registrations.keys.toSet()

    fun <T> whileActive(paths: Collection<Path>, action: () -> T): T {
        val normalized = paths.map { it.toAbsolutePath().normalize() }.distinct()
        normalized.forEach { registrations.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
        return try {
            action()
        } finally {
            normalized.forEach { path ->
                registrations.computeIfPresent(path) { _, count -> if (count.decrementAndGet() == 0) null else count }
            }
        }
    }
}
