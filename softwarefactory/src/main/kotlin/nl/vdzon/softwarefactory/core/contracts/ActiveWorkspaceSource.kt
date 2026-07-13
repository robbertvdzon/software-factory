package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.core.contracts.*

import java.nio.file.Path

fun interface ActiveWorkspaceSource {
    fun activePaths(): Set<Path>
}
