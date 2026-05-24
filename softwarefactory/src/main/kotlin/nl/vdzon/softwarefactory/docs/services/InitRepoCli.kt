package nl.vdzon.softwarefactory.docs.services

import nl.vdzon.softwarefactory.docs.DocsInstallResult
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = parseInitRepoOptions(args)
    val result = DocsSkeletonInstaller(options.skeletonRoot).install(options.targetRoot, options.overwrite)

    result.created.forEach { println("created $it") }
    result.skipped.forEach { println("skipped $it") }
    println("factory docs ready in ${options.targetRoot.toAbsolutePath().normalize()}")
}

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

private data class InitRepoOptions(
    val targetRoot: Path,
    val overwrite: Boolean,
    val skeletonRoot: Path?,
)

private fun parseInitRepoOptions(args: Array<String>): InitRepoOptions {
    var targetRoot = Path.of(".")
    var targetSet = false
    var overwrite = false
    var skeletonRoot: Path? = null
    var index = 0

    while (index < args.size) {
        when (val arg = args[index]) {
            "-h", "--help" -> {
                printUsage()
                exitProcess(0)
            }
            "--overwrite" -> overwrite = true
            "--skeleton" -> {
                index++
                if (index >= args.size) {
                    fail("--skeleton requires a path")
                }
                skeletonRoot = Path.of(args[index])
            }
            else -> {
                if (arg.startsWith("-")) {
                    fail("Unknown option: $arg")
                }
                if (targetSet) {
                    fail("Only one target path is supported")
                }
                targetRoot = Path.of(arg)
                targetSet = true
            }
        }
        index++
    }

    return InitRepoOptions(targetRoot = targetRoot, overwrite = overwrite, skeletonRoot = skeletonRoot)
}

private fun printUsage() {
    println(
        """
        Usage: factory init-repo [path] [--overwrite] [--skeleton path]

        Copies the Software Factory docs skeleton into an existing target repo.
        Existing files are skipped unless --overwrite is passed.
        """.trimIndent(),
    )
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    printUsage()
    exitProcess(2)
}
