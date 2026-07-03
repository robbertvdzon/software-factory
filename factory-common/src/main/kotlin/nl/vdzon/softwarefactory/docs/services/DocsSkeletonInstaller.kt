package nl.vdzon.softwarefactory.docs.services

import nl.vdzon.softwarefactory.docs.DocsInstallResult
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream

class DocsSkeletonInstaller(
    private val skeletonRoot: Path? = null,
) {
    fun install(targetRoot: Path, overwrite: Boolean = false): DocsInstallResult {
        val normalizedTarget = targetRoot.toAbsolutePath().normalize()
        normalizedTarget.createDirectories()
        require(normalizedTarget.isDirectory()) { "Target path is not a directory: $normalizedTarget" }

        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        loadEntries().forEach { entry ->
            val target = normalizedTarget.resolve(entry).normalize()
            require(target.startsWith(normalizedTarget)) { "Skeleton entry escapes target root: $entry" }
            target.parent.createDirectories()

            if (target.exists() && !overwrite) {
                skipped += entry
            } else {
                openEntry(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                created += entry
            }
        }
        return DocsInstallResult(created = created, skipped = skipped)
    }

    private fun loadEntries(): List<String> =
        (skeletonRoot?.let { root ->
            root.resolve(".manifest").inputStream().bufferedReader().use { reader ->
                reader.readLines()
            }
        } ?: classpathResource("docs-skeleton/.manifest").bufferedReader().use { reader ->
            reader.readLines()
        })
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

    private fun openEntry(entry: String) =
        skeletonRoot?.resolve(entry)?.inputStream()
            ?: classpathResource("docs-skeleton/$entry")

    private fun classpathResource(path: String) =
        requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) {
            "Missing classpath resource: $path"
        }
}
