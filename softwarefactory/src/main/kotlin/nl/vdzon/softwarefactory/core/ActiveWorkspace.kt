package nl.vdzon.softwarefactory.core

import java.nio.file.Path

fun interface ActiveWorkspaceSource {
    fun activePaths(): Set<Path>
}
