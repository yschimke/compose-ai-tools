package ee.schimke.composeai.cli

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
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
    /**
     * Compose density factor (= densityDpi / 160) resolved at discovery from
     * the @Preview device; null means "use the renderer default". Carried
     * through so agents can spot per-device fan-outs without re-reading the
     * Gradle manifest.
     */
    val density: Float? = null,
    val fontScale: Float = 1.0f,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val uiMode: Int = 0,
    val locale: String? = null,
    val group: String? = null,
    val wrapperClassName: String? = null,
    /** "COMPOSE" or "TILE". Free-form string so unknown future kinds round-trip. */
    val kind: String = "COMPOSE",
)

/**
 * Scroll state of a capture. Mirrors `ScrollCapture` in
 * gradle-plugin/PreviewData.kt — kept as a string-typed mirror so unknown
 * future modes/axes round-trip cleanly through the CLI.
 */
@Serializable
data class ScrollCapture(
    val mode: String,
    val axis: String = "VERTICAL",
    val maxScrollPx: Int = 0,
    val reduceMotion: Boolean = true,
    val atEnd: Boolean = false,
    val reachedPx: Int? = null,
)

@Serializable
data class Capture(
    val advanceTimeMillis: Long? = null,
    val scroll: ScrollCapture? = null,
    val renderOutput: String = "",
)

@Serializable
data class PreviewInfo(
    val id: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val params: PreviewParams = PreviewParams(),
    val captures: List<Capture> = listOf(Capture()),
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

/**
 * One rendered snapshot inside a [PreviewResult]. Carries the dimensional
 * coordinates ([advanceTimeMillis], [scroll]) that distinguish this capture
 * from its siblings, plus runtime data the agent needs to act on it
 * ([pngPath], [sha256], [changed]).
 *
 * A static preview produces a single `CaptureResult` with both dimensions
 * null; an animation/scroll fan-out produces N entries — one row per capture
 * filename on disk.
 */
@Serializable
data class CaptureResult(
    val advanceTimeMillis: Long? = null,
    val scroll: ScrollCapture? = null,
    val pngPath: String? = null,
    val sha256: String? = null,
    val changed: Boolean? = null,
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
    /**
     * All rendered snapshots for this preview. Always at least one element.
     * `length > 1` ⇔ a `@RoboComposePreviewOptions` time fan-out or a
     * scroll-with-progress capture — agents that need every PNG should
     * iterate this list rather than reading [pngPath].
     */
    val captures: List<CaptureResult> = emptyList(),
    /** First capture's PNG path. Kept for back-compat with existing agents. */
    val pngPath: String? = null,
    /** First capture's PNG sha256. Kept for back-compat. */
    val sha256: String? = null,
    /** First capture's `changed` flag. Kept for back-compat. */
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

/**
 * Versioned envelope for `compose-preview show|list|a11y --json`. Pinning the
 * schema lets agents detect format breaks without dispatching on field
 * shapes — bump [SHOW_LIST_SCHEMA] when the per-row shape changes.
 *
 * Top-level [previews] is the same `PreviewResult` list the unwrapped form
 * used to emit. The [counts] block is filled in by `show`/`a11y` (where
 * `changed` is meaningful) and lets agents skip downloading every PNG when
 * they only care about the diff against the previous run.
 */
@Serializable
data class PreviewListResponse(
    val schema: String = SHOW_LIST_SCHEMA,
    val previews: List<PreviewResult>,
    val counts: PreviewCounts? = null,
)

@Serializable
data class PreviewCounts(
    val total: Int,
    val changed: Int,
    val unchanged: Int,
    val missing: Int,
)

/**
 * Compact response shape emitted under `--brief`. Drops everything an agent
 * already had from a prior `show --json` (functionName, className, params,
 * sourceFile) and shortens field names so the per-row JSON shrinks to ~5x
 * smaller. Keys are intentionally terse:
 *   `png` = absolute PNG path, `sha` = first 12 hex chars of sha256,
 *   `time` = advanceTimeMillis, `scroll` = scroll mode string.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BriefPreviewListResponse(
    // Always-encode so brief mode (encodeDefaults=false) still emits the
    // version pin agents grep for.
    @EncodeDefault val schema: String = SHOW_LIST_BRIEF_SCHEMA,
    val previews: List<BriefPreviewResult>,
    val counts: PreviewCounts? = null,
)

@Serializable
data class BriefPreviewResult(
    val id: String,
    /** Omitted in single-module output. */
    val module: String? = null,
    val captures: List<BriefCapture>,
    /** Number of ATF findings; null when a11y is off for the module. */
    val a11y: Int? = null,
)

@Serializable
data class BriefCapture(
    /** Absolute path; null when render didn't produce a PNG. */
    val png: String? = null,
    /** sha256 prefix (12 hex chars); null when no PNG. */
    val sha: String? = null,
    /** null when first run / unknown. */
    val changed: Boolean? = null,
    /** advanceTimeMillis; omitted for static captures. */
    val time: Long? = null,
    /** Scroll mode (`END`/`LONG`); omitted when no scroll drive. */
    val scroll: String? = null,
)

internal const val SHOW_LIST_SCHEMA = "compose-preview-show/v1"
internal const val SHOW_LIST_BRIEF_SCHEMA = "compose-preview-show-brief/v1"

@Serializable
private data class CliState(val shas: Map<String, String> = emptyMap())

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

/**
 * JSON config for `--brief`: no pretty-print (one-line-per-row encoding is
 * the common agent consumption pattern) and `encodeDefaults = false` so all
 * the null/false/0 fields drop out instead of bloating the payload.
 */
private val briefJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = false
}

abstract class Command(protected val args: List<String>) {
    protected val explicitModule: String? = args.flagValue("--module")
    protected val variant: String = args.flagValue("--variant") ?: "debug"
    protected val filter: String? = args.flagValue("--filter")
    protected val exactId: String? = args.flagValue("--id")
    protected val verbose: Boolean = "--verbose" in args || "-v" in args
    protected val progress: Boolean = verbose || "--progress" in args
    protected val timeoutSeconds: Long = args.flagValue("--timeout")?.toLongOrNull() ?: 300
    /** When true, drop previews with no `changed=true` capture from JSON output. */
    protected val changedOnly: Boolean = "--changed-only" in args
    /**
     * Compact JSON: drop `functionName`/`className`/`sourceFile`/`module`/`params`
     * from each row, keep `id` + `captures`. Designed for agent re-render loops
     * where the full metadata was already cached on first call.
     */
    protected val brief: Boolean = "--brief" in args

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
     * (i.e. the feature is enabled in Gradle), and returns a lookup from
     * `"<module>/<previewId>"` to (findings, annotatedPathAbsolute). The
     * annotated path is resolved against the report file's parent so the
     * value we emit is an absolute filesystem path agents can open directly.
     *
     * Following the manifest pointer — rather than hard-coding
     * `accessibility.json` — means disabling checks in Gradle cleanly makes
     * findings disappear from CLI output; we never report stale reports from
     * a prior opt-in run.
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
     * Reads PNGs for every capture of every manifest entry, hashes them,
     * compares against the per-module sidecar state file to emit per-capture
     * `changed`, and persists the new hashes. Sidecar lives under
     * `build/compose-previews/` so it gets wiped on `./gradlew clean`.
     *
     * State is keyed `<id>` for the first capture (preserves legacy state
     * files from before per-capture tracking) and `<id>#<n>` for subsequent
     * captures of an animation/scroll fan-out. The top-level `pngPath` /
     * `sha256` / `changed` on [PreviewResult] mirror the first capture
     * verbatim so existing agents keep working.
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
                val captureResults = p.captures.mapIndexed { index, capture ->
                    val pngFile = capture.renderOutput
                        .takeIf { it.isNotEmpty() }
                        ?.let { projectDir.resolve("$module/build/compose-previews/$it").canonicalFile }
                        ?.takeIf { it.exists() }
                    val sha = pngFile?.let { sha256(it.readBytes()) }
                    val stateKey = if (index == 0) p.id else "${p.id}#$index"
                    if (sha != null) updated[stateKey] = sha
                    val priorSha = prior[stateKey]
                    val changed = when {
                        sha == null -> null
                        priorSha == null -> true
                        else -> priorSha != sha
                    }
                    CaptureResult(
                        advanceTimeMillis = capture.advanceTimeMillis,
                        scroll = capture.scroll,
                        pngPath = pngFile?.absolutePath,
                        sha256 = sha,
                        changed = changed,
                    )
                }
                val first = captureResults.firstOrNull()
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
                    captures = captureResults,
                    pngPath = first?.pngPath,
                    sha256 = first?.sha256,
                    changed = first?.changed,
                    a11yFindings = a11y,
                    a11yAnnotatedPath = a11yPair?.second,
                )
            }

            writeState(module, CliState(updated))
        }
        return results
    }

    /** True if the preview has at least one capture with `changed = true`. */
    protected fun PreviewResult.anyChanged(): Boolean =
        captures.any { it.changed == true } || changed == true

    /** Filters by `--id` / `--filter` and (optionally) `--changed-only`. */
    protected fun applyFilters(all: List<PreviewResult>): List<PreviewResult> =
        all.filter { matchesRequest(it) && (!changedOnly || it.anyChanged()) }

    /**
     * @param results rows to emit (after `--id`/`--filter`/`--changed-only`)
     * @param countsScope rows the [PreviewCounts] should be computed from —
     *   typically the unfiltered set so the agent sees totals even when
     *   `--changed-only` narrows the visible rows. Pass `null` to omit counts.
     */
    protected fun encodeResponse(
        results: List<PreviewResult>,
        countsScope: List<PreviewResult>?,
    ): String {
        val counts = countsScope?.let { countsOf(it) }
        if (brief) {
            val multiModule = results.map { it.module }.distinct().size > 1
            val brief = results.map { r ->
                BriefPreviewResult(
                    id = r.id,
                    module = r.module.takeIf { multiModule },
                    captures = r.captures.map { c ->
                        BriefCapture(
                            png = c.pngPath,
                            sha = c.sha256?.take(12),
                            changed = c.changed,
                            time = c.advanceTimeMillis,
                            scroll = c.scroll?.mode,
                        )
                    },
                    a11y = r.a11yFindings?.size,
                )
            }
            return briefJson.encodeToString(
                BriefPreviewListResponse.serializer(),
                BriefPreviewListResponse(previews = brief, counts = counts),
            )
        }
        return json.encodeToString(
            PreviewListResponse.serializer(),
            PreviewListResponse(previews = results, counts = counts),
        )
    }

    private fun countsOf(results: List<PreviewResult>) = PreviewCounts(
        total = results.size,
        changed = results.count { it.anyChanged() },
        unchanged = results.count { !it.anyChanged() && it.captures.any { c -> c.pngPath != null } },
        missing = results.count { it.captures.all { c -> c.pngPath == null } },
    )

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
                if (jsonOutput) println(encodeResponse(emptyList(), countsScope = emptyList()))
                else println("No previews found.")
                exitProcess(3)
            }

            val all = buildResults(manifests)
            val filtered = applyFilters(all)

            if (filtered.isEmpty()) {
                // Counts reflect the full discovered set so an agent using
                // `--changed-only` can still see "60 unchanged, 0 changed"
                // and skip a follow-up query.
                if (jsonOutput) println(encodeResponse(emptyList(), countsScope = all))
                else println("No previews matched.")
                exitProcess(3)
            }

            if (jsonOutput) {
                println(encodeResponse(filtered, countsScope = all))
            } else {
                var lastModule: String? = null
                for (r in filtered) {
                    if (modules.size > 1 && r.module != lastModule) {
                        println("[${r.module}]")
                        lastModule = r.module
                    }
                    val statusTag = when {
                        r.pngPath == null -> " [no PNG]"
                        r.anyChanged() -> " [changed]"
                        else -> ""
                    }
                    val shaTag = r.sha256?.let { "  sha=${it.take(12)}" } ?: ""
                    println("${r.functionName} (${r.id})$statusTag$shaTag")
                    if (r.captures.size <= 1) {
                        if (r.pngPath != null) println("  ${r.pngPath}")
                    } else {
                        for (c in r.captures) {
                            val tag = when {
                                c.pngPath == null -> " [no PNG]"
                                c.changed == true -> " [changed]"
                                else -> ""
                            }
                            val coord = listOfNotNull(
                                c.advanceTimeMillis?.let { "${it}ms" },
                                c.scroll?.let { "scroll ${it.mode.lowercase()}" },
                            ).joinToString(" · ").ifEmpty { "default" }
                            println("  [$coord]$tag ${c.pngPath ?: ""}")
                        }
                    }
                }
            }

            // "Missing" = at least one capture failed to produce a PNG.
            val missing = filtered.filter { r -> r.captures.any { it.pngPath == null } }
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
            // `--changed-only` is meaningless without rendering; ignore it here.
            val all = buildResults(manifests)
            val filtered = all.filter { matchesRequest(it) }

            if (filtered.isEmpty()) {
                if (jsonOutput) println(encodeResponse(emptyList(), countsScope = null))
                else println("No previews found.")
                exitProcess(3)
            }

            if (jsonOutput) {
                println(encodeResponse(filtered, countsScope = null))
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
            // `render` ignores `--changed-only` so the agent can ask "render
            // the world, but report only what changed" via a follow-up
            // `show --changed-only`.
            val filtered = all.filter { matchesRequest(it) }

            if (filtered.isEmpty()) {
                System.err.println("No previews matched.")
                exitProcess(3)
            }

            val missing = filtered.filter { r -> r.captures.any { it.pngPath == null } }

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
                val changedCount = filtered.count { it.anyChanged() }
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
                if (jsonOutput) println(encodeResponse(emptyList(), countsScope = null))
                else {
                    println(
                        "No module has accessibility checks enabled. Add\n" +
                            "  composePreview { accessibilityChecks { enabled = true } }\n" +
                            "to the module's build.gradle.kts."
                    )
                }
                exitProcess(if (buildOk) 0 else 2)
            }

            val all = buildResults(manifests)
            val filtered = all.filter {
                matchesRequest(it) && it.a11yFindings != null && (!changedOnly || it.anyChanged())
            }

            if (jsonOutput) {
                println(encodeResponse(filtered, countsScope = null))
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
