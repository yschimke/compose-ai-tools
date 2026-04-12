package ee.schimke.composeai.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

@Serializable
data class PreviewManifest(
    val module: String,
    val variant: String,
    val previews: List<PreviewEntry>,
)

@Serializable
data class PreviewEntry(
    val id: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val renderOutput: String? = null,
)

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

abstract class Command(protected val args: List<String>) {
    protected val explicitModule: String? = args.flagValue("--module")
    protected val variant: String = args.flagValue("--variant") ?: "debug"
    protected val filter: String? = args.flagValue("--filter")
    protected val verbose: Boolean = "--verbose" in args || "-v" in args
    protected val timeoutSeconds: Long = args.flagValue("--timeout")?.toLongOrNull() ?: 300

    abstract fun run()

    protected fun withGradle(block: (GradleConnection) -> Unit) {
        val projectDir = findProjectRoot() ?: run {
            System.err.println("Cannot find Gradle project root (no gradlew found)")
            exitProcess(1)
        }

        GradleConnection(projectDir, verbose).use(block)
    }

    /**
     * Resolve which modules to operate on.
     * If --module is specified, use that. Otherwise, auto-detect by finding
     * all modules that have a discoverPreviews task registered.
     */
    protected fun resolveModules(gradle: GradleConnection): List<String> {
        if (explicitModule != null) return listOf(explicitModule!!)

        val modules = gradle.findPreviewModules()
        if (modules.isEmpty()) {
            System.err.println("No modules with compose-ai-tools plugin found.")
            System.err.println("Apply the plugin: id(\"ee.schimke.composeai.preview\")")
            exitProcess(1)
        }
        if (verbose || modules.size > 1) {
            System.err.println("Found preview modules: ${modules.joinToString(", ")}")
        }
        return modules
    }

    protected fun runGradle(gradle: GradleConnection, vararg tasks: String): Boolean {
        return gradle.runTasks(*tasks, timeoutSeconds = timeoutSeconds)
    }

    protected fun readManifest(module: String): PreviewManifest? {
        val buildDir = File("$module/build")
        val manifestFile = File(buildDir, "compose-previews/previews.json")
        if (!manifestFile.exists()) return null
        return json.decodeFromString(manifestFile.readText())
    }

    protected fun readAllManifests(modules: List<String>): List<Pair<String, PreviewManifest>> {
        return modules.mapNotNull { module ->
            readManifest(module)?.let { module to it }
        }
    }

    private fun findProjectRoot(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "gradlew").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }
}

class ShowCommand(args: List<String>) : Command(args) {
    override fun run() {
        withGradle { gradle ->
            val modules = resolveModules(gradle)
            val tasks = modules.map { ":$it:renderAllPreviews" }.toTypedArray()

            if (!runGradle(gradle, *tasks)) {
                System.err.println("Render failed")
                exitProcess(1)
            }

            val manifests = readAllManifests(modules)
            if (manifests.isEmpty() || manifests.all { it.second.previews.isEmpty() }) {
                println("No previews found.")
                exitProcess(3)
            }

            for ((module, manifest) in manifests) {
                if (manifests.size > 1) println("[$module]")
                for (preview in manifest.previews) {
                    println("${preview.functionName} (${preview.id})")
                    if (preview.renderOutput != null) {
                        val pngFile = File("$module/build/compose-previews/${preview.renderOutput}")
                        if (pngFile.exists()) {
                            println("  ${pngFile.absolutePath}")
                        }
                    }
                }
            }
        }
    }
}

class ListCommand(args: List<String>) : Command(args) {
    private val jsonOutput = "--json" in args

    override fun run() {
        withGradle { gradle ->
            val modules = resolveModules(gradle)
            val tasks = modules.map { ":$it:discoverPreviews" }.toTypedArray()

            if (!runGradle(gradle, *tasks)) exitProcess(1)

            val manifests = readAllManifests(modules)
            val allPreviews = manifests.flatMap { (_, m) -> m.previews }

            val filtered = if (filter != null) {
                allPreviews.filter { it.id.contains(filter!!, ignoreCase = true) }
            } else {
                allPreviews
            }

            if (filtered.isEmpty()) {
                if (jsonOutput) println("[]") else println("No previews found.")
                exitProcess(3)
            }

            if (jsonOutput) {
                println(json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(PreviewEntry.serializer()),
                    filtered,
                ))
            } else {
                for (p in filtered) {
                    println("${p.id}  (${p.sourceFile ?: "unknown"})")
                }
            }
        }
    }
}

class RenderCommand(args: List<String>) : Command(args) {
    private val output: String? = args.flagValue("--output")

    override fun run() {
        withGradle { gradle ->
            val modules = resolveModules(gradle)
            val tasks = modules.map { ":$it:renderAllPreviews" }.toTypedArray()

            if (!runGradle(gradle, *tasks)) exitProcess(2)

            val manifests = readAllManifests(modules)

            val target = args.firstOrNull {
                !it.startsWith("--") && it != explicitModule && it != variant && it != output
            }
            if (target != null && output != null) {
                val allPreviews = manifests.flatMap { (module, m) ->
                    m.previews.map { module to it }
                }
                val match = allPreviews.find { (_, p) -> p.id.contains(target, ignoreCase = true) }
                if (match != null) {
                    val (module, preview) = match
                    if (preview.renderOutput != null) {
                        val src = File("$module/build/compose-previews/${preview.renderOutput}")
                        if (src.exists()) {
                            src.copyTo(File(output), overwrite = true)
                            println("Rendered to $output")
                        }
                    }
                }
            } else {
                val total = manifests.sumOf { it.second.previews.size }
                println("Rendered $total preview(s)")
            }
        }
    }
}

private fun List<String>.flagValue(flag: String): String? {
    val idx = indexOf(flag)
    return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}
