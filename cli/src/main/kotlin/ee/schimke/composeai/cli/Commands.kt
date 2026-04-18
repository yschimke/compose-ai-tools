package ee.schimke.composeai.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/** On-disk shape mirrors gradle-plugin/PreviewData.kt (parsed with ignoreUnknownKeys). */
@Serializable
data class PreviewParams(
    val name: String? = null,
    val device: String? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val fontScale: Float = 1.0f,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val uiMode: Int = 0,
    val locale: String? = null,
    val group: String? = null,
    val wrapperClassName: String? = null,
)

@Serializable
data class PreviewInfo(
    val id: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val params: PreviewParams = PreviewParams(),
    val renderOutput: String? = null,
)

@Serializable
data class PreviewManifest(
    val module: String,
    val variant: String,
    val previews: List<PreviewInfo>,
    /**
     * Relative path (from this manifest file's parent directory) to the
     * sidecar ATF accessibility report, when `composePreview.accessibilityChecks`
     * is enabled on this module. `null` means the feature is off.
     */
    val accessibilityReport: String? = null,
)

@Serializable
data class AccessibilityFinding(
    val level: String,
    val type: String,
    val message: String,
    val viewDescription: String? = null,
    val boundsInScreen: String? = null,
)

@Serializable
data class AccessibilityEntry(
    val previewId: String,
    val findings: List<AccessibilityFinding>,
    val annotatedPath: String? = null,
)

@Serializable
data class AccessibilityReport(
    val module: String,
    val entries: List<AccessibilityEntry>,
)

/** CLI output DTO — enriches manifest entries with runtime data agents need. */
@Serializable
data class PreviewResult(
    val id: String,
    val module: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val params: PreviewParams = PreviewParams(),
    val pngPath: String? = null,
    val sha256: String? = null,
    val changed: Boolean? = null,
    /**
     * ATF findings for this preview, or `null` when accessibility checks were
     * disabled for this module. Empty list means checks ran and found nothing.
     */
    val a11yFindings: List<AccessibilityFinding>? = null,
    /**
     * Absolute path to an annotated screenshot showing each finding as a
     * numbered badge + legend. `null` when there were no findings or
     * accessibility checks are disabled.
     */
    val a11yAnnotatedPath: String? = null,
)

@Serializable
private data class CliState(val shas: Map<String, String> = emptyMap())

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

abstract class Command(protected val args: List<String>) {
    protected val explicitModule: String? = args.flagValue("--module")
    protected val variant: String = args.flagValue("--variant") ?: "debug"
    protected val filter: String? = args.flagValue("--filter")
    protected val exactId: String? = args.flagValue("--id")
    protected val verbose: Boolean = "--verbose" in args || "-v" in args
    protected val progress: Boolean = verbose || "--progress" in args
    protected val timeoutSeconds: Long = args.flagValue("--timeout")?.toLongOrNull() ?: 300

    protected lateinit var projectDir: File
        private set

    abstract fun run()

    protected fun withGradle(block: (GradleConnection) -> Unit) {
        val root = findProjectRoot() ?: run {
            System.err.println("Cannot find Gradle project root (no gradlew found)")
            exitProcess(1)
        }
        projectDir = root
        GradleConnection(root, verbose, progress).use(block)
    }

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
        val manifestFile = projectDir.resolve("$module/build/compose-previews/previews.json")
        if (!manifestFile.exists()) return null
        return json.decodeFromString(manifestFile.readText())
    }

    protected fun readAllManifests(modules: List<String>): List<Pair<String, PreviewManifest>> {
        return modules.mapNotNull { module ->
            readManifest(module)?.let { module to it }
        }
    }

    /**
     * Reads each module's accessibility report IF the manifest points at one
     * (i.e. the feature is enabled in Gradle). Returns a map keyed by
     * `"$module/$previewId"` so `buildResults` can look up findings without a
     * second filesystem probe.
     *
     * Following the manifest pointer — rather than hard-coding
     * `accessibility.json` — means disabling checks in Gradle cleanly makes
     * findings disappear from CLI output; we never report stale reports from
     * a prior opt-in run.
     */
    /**
     * Loads each module's accessibility report and returns a lookup from
     * `"<module>/<previewId>"` to (findings, annotatedPathAbsolute). The
     * annotated path is resolved against the report file's parent so the
     * value we emit is an absolute filesystem path agents can open directly.
     */
    protected fun readAllA11yReports(
        manifests: List<Pair<String, PreviewManifest>>,
    ): Map<String, Pair<List<AccessibilityFinding>, String?>> {
        val out = mutableMapOf<String, Pair<List<AccessibilityFinding>, String?>>()
        for ((module, manifest) in manifests) {
            val pointer = manifest.accessibilityReport ?: continue
            val reportFile = projectDir.resolve("$module/build/compose-previews/$pointer")
            if (!reportFile.exists()) continue
            val report = try {
                json.decodeFromString(AccessibilityReport.serializer(), reportFile.readText())
            } catch (e: Exception) {
                if (verbose) System.err.println("Warning: unreadable a11y report ${reportFile.path}: ${e.message}")
                continue
            }
            val reportDir = reportFile.parentFile
            for (entry in report.entries) {
                val annotatedAbs = entry.annotatedPath
                    ?.let { reportDir.resolve(it).canonicalFile }
                    ?.takeIf { it.exists() }
                    ?.absolutePath
                out["$module/${entry.previewId}"] = entry.findings to annotatedAbs
            }
        }
        return out
    }

    /**
     * Reads PNGs for every manifest entry, hashes them, compares against the
     * per-module sidecar state file to emit `changed`, and persists the new
     * hashes. Sidecar lives under `build/compose-previews/` so it gets wiped on
     * `./gradlew clean`.
     */
    protected fun buildResults(manifests: List<Pair<String, PreviewManifest>>): List<PreviewResult> {
        val results = mutableListOf<PreviewResult>()
        val a11yByKey = readAllA11yReports(manifests)
        // Track which modules had a11y enabled so we can distinguish "no
        // findings" (empty list) from "feature off" (null) in `PreviewResult`.
        val modulesWithA11y = manifests.filter { it.second.accessibilityReport != null }.map { it.first }.toSet()

        for ((module, manifest) in manifests) {
            val prior = readState(module).shas
            val updated = mutableMapOf<String, String>()

            for (p in manifest.previews) {
                val pngFile = p.renderOutput
                    ?.let { projectDir.resolve("$module/build/compose-previews/$it").canonicalFile }
                    ?.takeIf { it.exists() }
                val sha = pngFile?.let { sha256(it.readBytes()) }
                if (sha != null) updated[p.id] = sha
                val priorSha = prior[p.id]
                val changed = when {
                    sha == null -> null
                    priorSha == null -> true
                    else -> priorSha != sha
                }
                val a11yPair = when {
                    module in modulesWithA11y -> a11yByKey["$module/${p.id}"]
                    else -> null
                }
                val a11y = when {
                    module in modulesWithA11y -> a11yPair?.first ?: emptyList()
                    else -> null
                }
                results += PreviewResult(
                    id = p.id,
                    module = module,
                    functionName = p.functionName,
                    className = p.className,
                    sourceFile = p.sourceFile,
                    params = p.params,
                    pngPath = pngFile?.absolutePath,
                    sha256 = sha,
                    changed = changed,
                    a11yFindings = a11y,
                    a11yAnnotatedPath = a11yPair?.second,
                )
            }

            writeState(module, CliState(updated))
        }
        return results
    }

    protected fun matchesRequest(result: PreviewResult): Boolean {
        if (exactId != null && result.id != exactId) return false
        if (filter != null && !result.id.contains(filter!!, ignoreCase = true)) return false
        return true
    }

    private fun stateFile(module: String): File =
        projectDir.resolve("$module/build/compose-previews/.cli-state.json")

    private fun readState(module: String): CliState {
        val f = stateFile(module)
        if (!f.exists()) return CliState()
        return try {
            json.decodeFromString(CliState.serializer(), f.readText())
        } catch (e: Exception) {
            if (verbose) System.err.println("Warning: corrupt state file ${f.path}, resetting: ${e.message}")
            CliState()
        }
    }

    private fun writeState(module: String, state: CliState) {
        val f = stateFile(module)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(CliState.serializer(), state))
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
    private val jsonOutput = "--json" in args

    override fun run() {
        withGradle { gradle ->
            val modules = resolveModules(gradle)
            val tasks = modules.map { ":$it:renderAllPreviews" }.toTypedArray()

            if (!runGradle(gradle, *tasks)) {
                System.err.println("Render failed")
                exitProcess(2)
            }

            val manifests = readAllManifests(modules)
            if (manifests.isEmpty() || manifests.all { it.second.previews.isEmpty() }) {
                if (jsonOutput) println("[]") else println("No previews found.")
                exitProcess(3)
            }

            val all = buildResults(manifests)
            val filtered = all.filter { matchesRequest(it) }

            if (filtered.isEmpty()) {
                if (jsonOutput) println("[]") else println("No previews matched.")
                exitProcess(3)
            }

            if (jsonOutput) {
                println(json.encodeToString(ListSerializer(PreviewResult.serializer()), filtered))
            } else {
                var lastModule: String? = null
                for (r in filtered) {
                    if (modules.size > 1 && r.module != lastModule) {
                        println("[${r.module}]")
                        lastModule = r.module
                    }
                    val statusTag = when {
                        r.pngPath == null -> " [no PNG]"
                        r.changed == true -> " [changed]"
                        else -> ""
                    }
                    val shaTag = r.sha256?.let { "  sha=${it.take(12)}" } ?: ""
                    println("${r.functionName} (${r.id})$statusTag$shaTag")
                    if (r.pngPath != null) println("  ${r.pngPath}")
                }
            }

            val missing = filtered.filter { it.pngPath == null }
            if (missing.isNotEmpty()) {
                System.err.println(
                    "Render task completed but produced no PNG for ${missing.size} of ${filtered.size} preview(s).",
                )
                System.err.println(
                    "Check the Gradle output above — a common cause is the `renderPreviews` task " +
                        "reporting NO-SOURCE, which means the renderer test class wasn't found on " +
                        "testClassesDirs.",
                )
                exitProcess(2)
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
            // List runs discovery only — PNGs may not exist, so sha/changed are null.
            val all = buildResults(manifests)
            val filtered = all.filter { matchesRequest(it) }

            if (filtered.isEmpty()) {
                if (jsonOutput) println("[]") else println("No previews found.")
                exitProcess(3)
            }

            if (jsonOutput) {
                println(json.encodeToString(ListSerializer(PreviewResult.serializer()), filtered))
            } else {
                for (r in filtered) {
                    println("${r.id}  (${r.sourceFile ?: "unknown"})")
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
            val all = buildResults(manifests)
            val filtered = all.filter { matchesRequest(it) }

            if (filtered.isEmpty()) {
                System.err.println("No previews matched.")
                exitProcess(3)
            }

            val missing = filtered.filter { it.pngPath == null }

            if (output != null) {
                if (filtered.size != 1) {
                    System.err.println(
                        "--output requires a single match (got ${filtered.size}). " +
                            "Narrow with --id <exact> or --filter <substring>.",
                    )
                    exitProcess(1)
                }
                val one = filtered.single()
                if (one.pngPath == null) {
                    System.err.println("Render produced no PNG for: ${one.id}")
                    exitProcess(2)
                }
                File(one.pngPath).copyTo(File(output), overwrite = true)
                println("Rendered ${one.id} to $output")
            } else {
                val rendered = filtered.size - missing.size
                println("Rendered $rendered preview(s)")
                val changedCount = filtered.count { it.changed == true }
                if (changedCount > 0) println("  $changedCount changed since last run")
                if (missing.isNotEmpty()) {
                    System.err.println(
                        "Render task completed but produced no PNG for ${missing.size} preview(s):",
                    )
                    for (r in missing) System.err.println("  ${r.id}")
                    exitProcess(2)
                }
            }
        }
    }
}

/**
 * `compose-preview a11y` — runs `renderAllPreviews` (which pulls in
 * `verifyAccessibility` when enabled) and prints the findings grouped by
 * preview. Exits non-zero if the Gradle build failed (i.e. the configured
 * `failOnErrors`/`failOnWarnings` threshold tripped) or if `--fail-on`
 * overrides the threshold at the CLI level.
 */
class A11yCommand(args: List<String>) : Command(args) {
    private val jsonOutput = "--json" in args
    // "errors" | "warnings" | "none". When not set, exit code mirrors Gradle.
    private val failOn: String? = args.flagValue("--fail-on")

    override fun run() {
        withGradle { gradle ->
            val modules = resolveModules(gradle)
            val tasks = modules.map { ":$it:renderAllPreviews" }.toTypedArray()
            val buildOk = runGradle(gradle, *tasks)

            val manifests = readAllManifests(modules)
            val enabledModules = manifests.filter { it.second.accessibilityReport != null }
            if (enabledModules.isEmpty()) {
                if (jsonOutput) println("[]") else {
                    println(
                        "No module has accessibility checks enabled. Add\n" +
                            "  composePreview { accessibilityChecks { enabled = true } }\n" +
                            "to the module's build.gradle.kts."
                    )
                }
                exitProcess(if (buildOk) 0 else 2)
            }

            val all = buildResults(manifests)
            val filtered = all.filter { matchesRequest(it) && it.a11yFindings != null }

            if (jsonOutput) {
                println(json.encodeToString(ListSerializer(PreviewResult.serializer()), filtered))
            } else {
                val flat = filtered.flatMap { r ->
                    (r.a11yFindings ?: emptyList()).map { f -> r to f }
                }
                if (flat.isEmpty()) {
                    println("No accessibility findings.")
                } else {
                    println("${flat.size} accessibility finding(s):")
                    // Track per-preview so we only print the annotated-PNG
                    // hint once, on the first finding for that preview.
                    var lastPreviewId: String? = null
                    for ((r, f) in flat) {
                        println("  [${f.level}] ${r.id} · ${f.type}")
                        println("      ${f.message}")
                        f.viewDescription?.let { println("      element: $it") }
                        if (r.id != lastPreviewId) {
                            r.a11yAnnotatedPath?.let { println("      annotated: $it") }
                            lastPreviewId = r.id
                        }
                    }
                }
            }

            val errorCount = filtered.sumOf { it.a11yFindings?.count { f -> f.level == "ERROR" } ?: 0 }
            val warnCount = filtered.sumOf { it.a11yFindings?.count { f -> f.level == "WARNING" } ?: 0 }
            val cliFailed = when (failOn) {
                "errors" -> errorCount > 0
                "warnings" -> errorCount > 0 || warnCount > 0
                "none", null -> false
                else -> {
                    System.err.println("Unknown --fail-on value: $failOn (expected errors|warnings|none)")
                    exitProcess(1)
                }
            }
            exitProcess(
                when {
                    cliFailed -> 2
                    !buildOk -> 2
                    else -> 0
                }
            )
        }
    }
}

private fun sha256(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun List<String>.flagValue(flag: String): String? {
    val idx = indexOf(flag)
    return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}
