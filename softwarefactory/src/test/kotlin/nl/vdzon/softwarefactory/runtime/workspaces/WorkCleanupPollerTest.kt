package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.core.ActiveWorkspaceSource
import nl.vdzon.softwarefactory.telegram.ActiveAssistantWorkspaceRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class WorkCleanupPollerTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-07-08T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `removes entries older than the retention period from all four subroots`() {
        val workRoot = tempDir.resolve("work").createDirectories()

        touch(workRoot, "agent-workspaces/SF-1-developer-abc", ageDays = 10)
        touch(workRoot, "stories/SF-1/repo", ageDays = 8)
        touch(workRoot, "assistant-checkouts/bot/repo", ageDays = 30)
        touch(workRoot, "assistant/chat-1/session-1/work/in", ageDays = 9)
        touch(workRoot, "assistant/chat-1/session-1/work/out", ageDays = 9)

        val poller = WorkCleanupPoller(
            settings = WorkCleanupSettings(enabled = true, retentionDays = 7),
            workRoot = workRoot,
            clock = fixedClock,
        )

        val removed = poller.cleanupOnce()

        assertEquals(5, removed)
        assertFalse(workRoot.resolve("agent-workspaces/SF-1-developer-abc").exists())
        assertFalse(workRoot.resolve("stories/SF-1").exists())
        assertFalse(workRoot.resolve("assistant-checkouts/bot").exists())
        assertFalse(workRoot.resolve("assistant/chat-1/session-1/work/in").exists())
        assertFalse(workRoot.resolve("assistant/chat-1/session-1/work/out").exists())
    }

    @Test
    fun `keeps entries younger than the retention period`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        touch(workRoot, "agent-workspaces/SF-2-developer-def", ageDays = 1)
        touch(workRoot, "stories/SF-2/repo", ageDays = 6)

        val poller = WorkCleanupPoller(
            settings = WorkCleanupSettings(enabled = true, retentionDays = 7),
            workRoot = workRoot,
            clock = fixedClock,
        )

        val removed = poller.cleanupOnce()

        assertEquals(0, removed)
        assertTrue(workRoot.resolve("agent-workspaces/SF-2-developer-def").exists())
        assertTrue(workRoot.resolve("stories/SF-2").exists())
    }

    @Test
    fun `does nothing when cleanup is disabled`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        touch(workRoot, "agent-workspaces/SF-3-developer-ghi", ageDays = 30)

        val poller = WorkCleanupPoller(
            settings = WorkCleanupSettings(enabled = false, retentionDays = 7),
            workRoot = workRoot,
            clock = fixedClock,
        )

        poller.poll()

        assertTrue(workRoot.resolve("agent-workspaces/SF-3-developer-ghi").exists())
    }

    @Test
    fun `scans all four subroots even when only some contain expired entries`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        touch(workRoot, "agent-workspaces/SF-4-developer-old", ageDays = 30)
        touch(workRoot, "stories/SF-4/repo", ageDays = 1)
        touch(workRoot, "assistant-checkouts/bot/repo", ageDays = 30)
        touch(workRoot, "assistant/chat-1/session-1/work/in", ageDays = 1)
        touch(workRoot, "assistant/chat-1/session-1/work/out", ageDays = 30)

        val poller = WorkCleanupPoller(
            settings = WorkCleanupSettings(enabled = true, retentionDays = 7),
            workRoot = workRoot,
            clock = fixedClock,
        )

        val removed = poller.cleanupOnce()

        assertEquals(3, removed)
        assertFalse(workRoot.resolve("agent-workspaces/SF-4-developer-old").exists())
        assertTrue(workRoot.resolve("stories/SF-4").exists())
        assertFalse(workRoot.resolve("assistant-checkouts/bot").exists())
        assertTrue(workRoot.resolve("assistant/chat-1/session-1/work/in").exists())
        assertFalse(workRoot.resolve("assistant/chat-1/session-1/work/out").exists())
    }

    @Test
    fun `keeps old active agent story checkout and nested assistant paths but removes inactive siblings`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        val activeAgent = touch(workRoot, "agent-workspaces/SF-10-developer", ageDays = 30)
        val activeStoryRepo = touch(workRoot, "stories/SF-10/repo", ageDays = 30)
        val activeCheckoutRepo = touch(workRoot, "assistant-checkouts/factory/repo", ageDays = 30)
        val activeSession = touch(workRoot, "assistant/chat/session/work/in", ageDays = 30).parent.parent
        val inactive = touch(workRoot, "stories/SF-11/repo", ageDays = 30)
        val poller = poller(workRoot, setOf(activeAgent, activeStoryRepo, activeCheckoutRepo, activeSession))

        assertEquals(1, poller.cleanupOnce())

        assertTrue(activeAgent.exists())
        assertTrue(activeStoryRepo.exists())
        assertTrue(activeCheckoutRepo.exists())
        assertTrue(activeSession.resolve("work/in").exists())
        assertFalse(inactive.exists())
    }

    @Test
    fun `removes inactive entry exactly on retention boundary`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        val boundary = touch(workRoot, "stories/SF-boundary/repo", ageDays = 7)

        assertEquals(1, poller(workRoot).cleanupOnce())
        assertFalse(boundary.exists())
    }

    @Test
    fun `source failure fails safe and removes no expired entry`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        val expired = touch(workRoot, "stories/SF-source-down/repo", ageDays = 30)
        val source = ActiveWorkspaceSource { error("database unavailable") }
        val poller = WorkCleanupPoller(
            settings = WorkCleanupSettings(enabled = true, retentionDays = 7),
            workRoot = workRoot,
            clock = fixedClock,
            activeWorkspaceSources = listOf(source),
        )

        assertEquals(0, poller.cleanupOnce())
        assertTrue(expired.exists())
    }

    @Test
    fun `disappeared race candidate is harmless`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        val candidate = touch(workRoot, "stories/SF-race/repo", ageDays = 30)
        val source = ActiveWorkspaceSource {
            candidate.parent.toFile().deleteRecursively()
            emptySet()
        }

        assertEquals(
            0,
            WorkCleanupPoller(
                settings = WorkCleanupSettings(enabled = true, retentionDays = 7),
                workRoot = workRoot,
                clock = fixedClock,
                activeWorkspaceSources = listOf(source),
            ).cleanupOnce(),
        )
    }

    @Test
    fun `symlink candidate never deletes target outside managed root`() {
        val workRoot = tempDir.resolve("work").createDirectories()
        val outside = touch(tempDir, "outside/keep", ageDays = 30)
        val root = workRoot.resolve("stories").createDirectories()
        val link = root.resolve("outside-link")
        Files.createSymbolicLink(link, outside)
        Files.setLastModifiedTime(link, FileTime.from(now.minus(Duration.ofDays(30))))

        poller(workRoot).cleanupOnce()

        assertTrue(outside.exists())
        assertTrue(outside.resolve("marker.txt").exists())
    }

    @Test
    fun `assistant registry keeps overlapping registrations active until final release`() {
        val registry = ActiveAssistantWorkspaceRegistry()
        val workspace = tempDir.resolve("assistant/session")

        registry.whileActive(listOf(workspace)) {
            assertEquals(setOf(workspace.toAbsolutePath().normalize()), registry.activePaths())
            registry.whileActive(listOf(workspace)) {
                assertEquals(1, registry.activePaths().size)
            }
            assertEquals(1, registry.activePaths().size)
        }

        assertTrue(registry.activePaths().isEmpty())
    }

    private fun poller(workRoot: Path, active: Set<Path> = emptySet()): WorkCleanupPoller =
        WorkCleanupPoller(
            settings = WorkCleanupSettings(enabled = true, retentionDays = 7),
            workRoot = workRoot,
            clock = fixedClock,
            activeWorkspaceSources = listOf(ActiveWorkspaceSource { active }),
        )

    private fun touch(workRoot: Path, relativePath: String, ageDays: Long): Path {
        val entry = workRoot.resolve(relativePath).createDirectories()
        val file = entry.resolve("marker.txt")
        file.writeText("content")
        val mtime = FileTime.from(now.minus(Duration.ofDays(ageDays)))
        Files.setLastModifiedTime(file, mtime)
        Files.setLastModifiedTime(entry, mtime)
        return entry
    }
}
