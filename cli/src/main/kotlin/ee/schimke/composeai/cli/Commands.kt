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
    protected val module: String? = args.flagValue("--module")
    protected val variant: String = args.flagValue("--variant") ?: "debug"
    protected val filter: String? = args.flagValue("--filter")

    abstract fun run()

    protected fun runGradle(vararg tasks: String): Int {
        val gradlew = findGradlew() ?: run {
            System.err.println("Cannot find gradlew in current directory or parents")
            exitProcess(1)
        }
        val cmd = mutableListOf(gradlew.absolutePath)
        cmd.addAll(tasks)
        val process = ProcessBuilder(cmd)
            .inheritIO()
            .start()
        return process.waitFor()
    }

    protected fun readManifest(): PreviewManifest? {
        val buildDir = if (module != null) File("$module/build") else File("build")
        val manifestFile = File(buildDir, "compose-previews/previews.json")
        if (!manifestFile.exists()) return null
        return json.decodeFromString(manifestFile.readText())
    }

    private fun findGradlew(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val gradlew = File(dir, "gradlew")
            if (gradlew.exists() && gradlew.canExecute()) return gradlew
            dir = dir.parentFile
        }
        return null
    }

    private fun List<String>.flagValue(flag: String): String? {
        val idx = indexOf(flag)
        return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
    }
}

class ShowCommand(args: List<String>) : Command(args) {
    override fun run() {
        val taskPrefix = if (module != null) ":$module:" else ":"
        val exitCode = runGradle("${taskPrefix}renderAllPreviews")
        if (exitCode != 0) {
            System.err.println("Build failed with exit code $exitCode")
            exitProcess(1)
        }

        val manifest = readManifest()
        if (manifest == null || manifest.previews.isEmpty()) {
            println("No previews found.")
            exitProcess(3)
        }

        for (preview in manifest.previews) {
            println("${preview.functionName} (${preview.id})")
            if (preview.renderOutput != null) {
                val buildDir = if (module != null) "$module/build" else "build"
                val pngFile = File("$buildDir/compose-previews/${preview.renderOutput}")
                if (pngFile.exists()) {
                    println("  → ${pngFile.absolutePath}")
                }
            }
        }
    }
}

class ListCommand(args: List<String>) : Command(args) {
    private val jsonOutput = "--json" in args

    override fun run() {
        val taskPrefix = if (module != null) ":$module:" else ":"
        val exitCode = runGradle("${taskPrefix}discoverPreviews")
        if (exitCode != 0) exitProcess(1)

        val manifest = readManifest()
        if (manifest == null || manifest.previews.isEmpty()) {
            if (jsonOutput) println("[]") else println("No previews found.")
            exitProcess(3)
        }

        val filtered = if (filter != null) {
            manifest.previews.filter { it.id.contains(filter!!, ignoreCase = true) }
        } else {
            manifest.previews
        }

        if (jsonOutput) {
            println(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PreviewEntry.serializer()), filtered))
        } else {
            for (p in filtered) {
                println("${p.id}  (${p.sourceFile ?: "unknown"})")
            }
        }
    }
}

class RenderCommand(args: List<String>) : Command(args) {
    private val output: String? = args.let {
        val idx = it.indexOf("--output")
        if (idx >= 0 && idx + 1 < it.size) it[idx + 1] else null
    }

    override fun run() {
        val taskPrefix = if (module != null) ":$module:" else ":"
        val exitCode = runGradle("${taskPrefix}renderAllPreviews")
        if (exitCode != 0) exitProcess(2)

        val manifest = readManifest() ?: run {
            System.err.println("No preview manifest found")
            exitProcess(2)
        }

        val target = args.firstOrNull { !it.startsWith("--") && it != module && it != variant && it != output }
        if (target != null) {
            val preview = manifest.previews.find { it.id.contains(target, ignoreCase = true) }
            if (preview != null && preview.renderOutput != null && output != null) {
                val buildDir = if (module != null) "$module/build" else "build"
                val src = File("$buildDir/compose-previews/${preview.renderOutput}")
                if (src.exists()) {
                    src.copyTo(File(output), overwrite = true)
                    println("Rendered to $output")
                }
            }
        } else {
            println("Rendered ${manifest.previews.size} preview(s)")
        }
    }
}
