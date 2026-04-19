package ee.schimke.composeai.cli

import ee.schimke.composeai.plugin.tooling.ComposePreviewModel
import ee.schimke.composeai.plugin.tooling.ModuleInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

/**
 * `compose-preview doctor`
 *
 * Two layers of checks:
 *
 * **Environment** (always runs, safe outside a Gradle project):
 *   - Java 17+ on PATH
 *   - GitHub Packages credentials set and valid (`composeAiTools.githubUser` /
 *     `...Token` in `~/.gradle/gradle.properties`, or `GITHUB_ACTOR` /
 *     `GITHUB_TOKEN` env vars)
 *   - HEAD probe against `maven.pkg.github.com` verifies `read:packages`
 *
 * **Project** (runs when a `settings.gradle[.kts]` is found at `--project` or cwd):
 *   - Plugin applied to at least one module
 *   - Consumer's test-runtime classpath vs main-variant classpath satisfies
 *     the AAR/R.id version-alignment rules for preview rendering. Checks:
 *       - `deps.<module>.ui-test-manifest` — ui-test-manifest on test classpath
 *       - `deps.<module>.activity-vs-navigationevent` — navigationevent on test, older activity on main
 *       - `deps.<module>.compose-ui-vs-core` — compose-ui 1.10+ on test, older androidx.core on main
 *       - `deps.<module>.compose-bom` (warning) — no Compose BOM declared
 *
 * Output modes:
 *   - Default: human-friendly ANSI with ✓ / ! / ✗ / ∙ markers, per-check remediation.
 *   - `--json`: machine-readable [DoctorReport] (schema `compose-preview-doctor/v1`).
 *     Agents should prefer this — remediations come with concrete `commands[]`
 *     they can apply directly.
 *   - `--explain`: prints extended rationale for each non-ok check, including
 *     the specific exception class an unfixed misconfig will surface at render
 *     time. Useful for humans first hitting a failure; noisy for agents.
 *
 * Exits 0 when no errors (warnings OK), 1 when any check reports ERROR.
 */
class DoctorCommand(args: List<String>) {
    private val jsonOut = "--json" in args
    private val explain = "--explain" in args
    private val verbose = "--verbose" in args || "-v" in args
    private val projectDirArg = args.flagValue("--project")
    private val pluginVersion = args.flagValue("--plugin-version") ?: DEFAULT_PLUGIN_VERSION

    private val checks = mutableListOf<DoctorCheck>()

    fun run() {
        // Environment checks always run.
        checkJava()
        val creds = checkCredentials()
        if (creds != null) probeMaven(creds)
        checkComposeBomVersion()

        // Project checks: only when a Gradle project is reachable.
        val projectDir = resolveProjectDir()
        if (projectDir != null) {
            runProjectChecks(projectDir)
        } else {
            addCheck(
                DoctorCheck(
                    id = "project.detected",
                    category = "project",
                    status = "skipped",
                    message = "no Gradle project at ${projectDirArg ?: "."}",
                    detail = "project-scope compatibility checks were skipped",
                    remediation = DoctorRemediation(
                        summary = "run doctor from a Gradle project root, or pass `--project <dir>`",
                    ),
                ),
            )
        }

        emit()
    }

    private fun resolveProjectDir(): File? {
        val dir = File(projectDirArg ?: ".").absoluteFile
        if (!dir.exists() || !dir.isDirectory) return null
        return if (File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()) {
            dir
        } else null
    }

    // --- Env checks ---------------------------------------------------------

    private fun checkJava() {
        val version = System.getProperty("java.specification.version")
        val major = version?.substringBefore('.')?.toIntOrNull()
        if (major != null && major >= 17) {
            addCheck(
                DoctorCheck(
                    id = "env.java-17",
                    category = "env",
                    status = "ok",
                    message = "Java $version on PATH",
                ),
            )
        } else {
            addCheck(
                DoctorCheck(
                    id = "env.java-17",
                    category = "env",
                    status = "error",
                    message = "Java 17+ required, got ${version ?: "unknown"}",
                    remediation = DoctorRemediation(
                        summary = "Install a JDK 17 and put it on PATH, or set JAVA_HOME.",
                        commands = listOf("sdk install java 17.0.11-tem"),
                    ),
                ),
            )
        }
    }

    private fun checkCredentials(): Credentials? {
        val propsFile = File(System.getProperty("user.home"), ".gradle/gradle.properties")
        val props = loadProperties(propsFile)

        val user = props["composeAiTools.githubUser"]
            ?: System.getenv("GITHUB_ACTOR")
        val token = props["composeAiTools.githubToken"]
            ?: System.getenv("GITHUB_TOKEN")

        when {
            user == null && token == null -> {
                addCheck(
                    DoctorCheck(
                        id = "env.github-credentials",
                        category = "env",
                        status = "error",
                        message = "no GitHub Packages credentials found",
                        remediation = DoctorRemediation(
                            summary = "Store a GitHub token with `read:packages` scope.",
                            commands = listOf(
                                "gh auth refresh -h github.com -s read:packages",
                                "gh auth token",
                            ),
                            docs = "https://github.com/$REPO#credentials",
                        ),
                    ),
                )
                return null
            }
            token == null -> {
                addCheck(
                    DoctorCheck(
                        id = "env.github-credentials",
                        category = "env",
                        status = "error",
                        message = "composeAiTools.githubToken is not set",
                    ),
                )
                return null
            }
        }

        val source = when {
            props["composeAiTools.githubToken"] != null -> propsFile.path
            else -> "\$GITHUB_TOKEN env var"
        }
        addCheck(
            DoctorCheck(
                id = "env.github-credentials",
                category = "env",
                status = if (user == null) "warning" else "ok",
                message = "credentials found ($source)",
                detail = if (user == null) "composeAiTools.githubUser not set; falling back to 'x' for BASIC auth" else null,
            ),
        )
        return Credentials(user ?: "x", token!!)
    }

    private fun probeMaven(creds: Credentials) {
        val path = "ee/schimke/composeai/gradle-plugin/$pluginVersion/gradle-plugin-$pluginVersion.pom"
        val url = "$MAVEN_BASE/$path"

        val (code, headers) = head(url, creds)

        val check = when (code) {
            in 200..299 -> DoctorCheck(
                id = "env.github-packages",
                category = "env",
                status = "ok",
                message = "GitHub Packages auth works (HEAD $pluginVersion → $code)",
            )
            404 -> DoctorCheck(
                id = "env.github-packages",
                category = "env",
                status = "warning",
                message = "plugin v$pluginVersion not found on GitHub Packages",
                detail = "auth+scope work (404, not 401), but this version is not published; pick a different version",
                remediation = DoctorRemediation(
                    summary = "Check available releases.",
                    docs = "https://github.com/$REPO/releases",
                ),
            )
            401 -> DoctorCheck(
                id = "env.github-packages",
                category = "env",
                status = "error",
                message = "GitHub Packages rejected credentials (HTTP 401)",
                detail = headers.entries.firstOrNull { it.key.equals("x-oauth-scopes", true) }?.value
                    ?.let { "scopes reported: ${it.ifBlank { "(none)" }}" },
                remediation = DoctorRemediation(
                    summary = "Token is missing `read:packages` or is invalid.",
                    commands = listOf("gh auth refresh -h github.com -s read:packages"),
                    docs = "https://github.com/settings/tokens/new",
                ),
            )
            403 -> DoctorCheck(
                id = "env.github-packages",
                category = "env",
                status = "error",
                message = "GitHub Packages refused (HTTP 403)",
                detail = "token likely lacks `read:packages`",
            )
            else -> DoctorCheck(
                id = "env.github-packages",
                category = "env",
                status = "error",
                message = "unexpected HTTP $code from $url",
            )
        }
        addCheck(check)
    }

    // --- Project checks -----------------------------------------------------

    private fun runProjectChecks(projectDir: File) {
        val model = try {
            GradleConnection(projectDir, verbose = verbose).use { gc ->
                gc.runBuildAction(GatherComposePreviewModelAction())
            }
        } catch (e: Exception) {
            addCheck(
                DoctorCheck(
                    id = "project.model",
                    category = "project",
                    status = "error",
                    message = "could not fetch plugin Tooling model",
                    detail = e.message,
                    remediation = DoctorRemediation(
                        summary = "Ensure the project builds (`./gradlew help`) and the plugin is applied.",
                    ),
                ),
            )
            return
        }

        if (model == null || model.modules.isEmpty()) {
            addCheck(
                DoctorCheck(
                    id = "project.plugin-applied",
                    category = "project",
                    status = "error",
                    message = "no modules have the compose-preview plugin applied",
                    remediation = DoctorRemediation(
                        summary = "Apply the plugin in your module's `plugins { }` block.",
                        commands = listOf("id(\"ee.schimke.composeai.preview\") version \"$pluginVersion\""),
                        docs = "https://github.com/$REPO#usage",
                    ),
                ),
            )
            return
        }

        addCheck(
            DoctorCheck(
                id = "project.plugin-applied",
                category = "project",
                status = "ok",
                message = "plugin applied in ${model.modules.size} module(s)",
                detail = model.modules.keys.joinToString(", "),
            ),
        )

        for ((modulePath, info) in model.modules) {
            checkModuleCompat(modulePath, info)
        }
    }

    /**
     * Render findings produced plugin-side (see [CompatRules] in gradle-plugin)
     * as doctor checks. CLI doesn't run compat logic of its own — one source
     * of truth, consumed by both CLI and VS Code. Rule thresholds and
     * remediation phrasing live in the plugin.
     */
    private fun checkModuleCompat(modulePath: String, info: ModuleInfo) {
        val variant = info.variant

        if (info.mainRuntimeDependencies.isEmpty() && info.testRuntimeDependencies.isEmpty()) {
            addCheck(
                DoctorCheck(
                    id = "deps.${idSafe(modulePath)}.resolve",
                    category = "deps",
                    status = "skipped",
                    message = "$modulePath — dependency resolution returned empty for variant '$variant'",
                    detail = "the plugin was applied but neither ${variant}RuntimeClasspath nor ${variant}UnitTestRuntimeClasspath resolved",
                ),
            )
            return
        }

        if (info.findings.isEmpty()) {
            addCheck(
                DoctorCheck(
                    id = "deps.${idSafe(modulePath)}.compat",
                    category = "deps",
                    status = "ok",
                    message = "$modulePath — no compatibility issues found",
                ),
            )
            return
        }

        for (finding in info.findings) {
            val status = when (finding.severity) {
                "error" -> "error"
                "warning" -> "warning"
                else -> "info"
            }
            // Short-form detail is the default; `--explain` also surfaces
            // the long-form detail from the plugin (the "why this breaks at
            // render time" rationale).
            val detail = if (explain) finding.detail else null
            val remediation = if (finding.remediationSummary != null) {
                DoctorRemediation(
                    summary = finding.remediationSummary!!,
                    commands = finding.remediationCommands,
                    docs = finding.docsUrl,
                )
            } else null
            addCheck(
                DoctorCheck(
                    id = "deps.${idSafe(modulePath)}.${finding.id}",
                    category = "deps",
                    status = status,
                    message = "$modulePath — ${finding.message}",
                    detail = detail,
                    remediation = remediation,
                ),
            )
        }
    }

    /**
     * Grep-based "is your compose-bom recent enough" pre-flight. Runs
     * BEFORE any Gradle call, so it works even outside a Gradle project
     * or before the plugin is applied — complements the plugin-side
     * `CompatRules` findings, which only fire once Gradle has resolved
     * the test classpath. Renderer-android is compiled with compose-
     * compiler 2.2.21, which emits calls to
     * `ComposeUiNode.setCompositeKeyHash` — first shipped in compose-ui
     * 1.9 (compose-bom 2025.01.00). Older consumers hit `NoSuchMethodError`
     * the moment `renderPreviews` starts.
     */
    private fun checkComposeBomVersion() {
        val workspace = File(projectDirArg ?: ".").canonicalFile
        val versions = findComposeBomDeclarations(workspace)
        if (versions.isEmpty()) return // No declarations → nothing to assert on.

        val tooOld = versions.filter { (_, v) -> v.isOlderThan(MIN_BOM_YEAR, MIN_BOM_MONTH) }
        if (tooOld.isEmpty()) {
            val summary = versions.joinToString(", ") { (_, v) -> v.raw }
            addCheck(
                DoctorCheck(
                    id = "env.compose-bom-version",
                    category = "env",
                    status = "ok",
                    message = "compose-bom version(s) look recent enough ($summary)",
                ),
            )
            return
        }
        val floor = "$MIN_BOM_YEAR.${MIN_BOM_MONTH.toString().padStart(2, '0')}.00"
        for ((source, v) in tooOld) {
            addCheck(
                DoctorCheck(
                    id = "env.compose-bom-version",
                    category = "env",
                    status = "warning",
                    message = "compose-bom ${v.raw} declared in ${source.relativeTo(workspace).path} — renderer needs ≥$floor",
                    detail = "Older BOMs lack `ComposeUiNode.setCompositeKeyHash`; `renderPreviews` will fail with NoSuchMethodError.",
                    remediation = DoctorRemediation(
                        summary = "Bump the BOM.",
                        commands = listOf("compose-bom = \"$floor\""),
                    ),
                ),
            )
        }
    }

    /**
     * Returns `(fileWhereFound, parsedVersion)` for every
     * `androidx.compose:compose-bom` version literal we find under [root].
     * Scans `gradle/libs.versions.toml` and every `build.gradle[.kts]`,
     * early-exiting at depth 4 so we don't wander through `build/`.
     */
    private fun findComposeBomDeclarations(root: File): List<Pair<File, ComposeVersion>> {
        val out = mutableListOf<Pair<File, ComposeVersion>>()
        val tomlRegex = Regex("""compose-bom\s*=\s*"([^"]+)"""")
        val bomInlineRegex = Regex("""["']androidx\.compose:compose-bom:([0-9][0-9A-Za-z.\-]+)["']""")

        fun scanTextFile(file: File) {
            val text = try { file.readText() } catch (_: Exception) { return }
            tomlRegex.findAll(text).forEach { m ->
                ComposeVersion.parse(m.groupValues[1])?.let { out += file to it }
            }
            bomInlineRegex.findAll(text).forEach { m ->
                ComposeVersion.parse(m.groupValues[1])?.let { out += file to it }
            }
        }

        fun walk(dir: File, depth: Int) {
            if (depth > 4 || dir.name.startsWith(".") || dir.name in SKIP_DIRS) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                when {
                    f.isDirectory -> walk(f, depth + 1)
                    f.name == "libs.versions.toml" -> scanTextFile(f)
                    f.name == "build.gradle.kts" || f.name == "build.gradle" -> scanTextFile(f)
                }
            }
        }
        walk(root, 0)
        return out
    }

    /**
     * Compose BOM version in `YYYY.MM.NN` form. Enough precision for
     * "is this older than 2025.01"; we never need to differentiate patches.
     */
    private data class ComposeVersion(val year: Int, val month: Int, val raw: String) {
        fun isOlderThan(minYear: Int, minMonth: Int): Boolean =
            year < minYear || (year == minYear && month < minMonth)

        companion object {
            private val pattern = Regex("""^(\d{4})\.(\d{2})\.\d+""")
            fun parse(s: String): ComposeVersion? {
                val m = pattern.find(s) ?: return null
                return ComposeVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), s)
            }
        }
    }

    // --- Output -------------------------------------------------------------

    private fun emit() {
        if (jsonOut) emitJson() else emitText()
        val errors = checks.count { it.status == "error" }
        exitProcess(if (errors > 0) 1 else 0)
    }

    private fun emitText() {
        println("compose-preview doctor")
        println()
        var currentCategory = ""
        for (check in checks) {
            if (check.category != currentCategory) {
                if (currentCategory.isNotEmpty()) println()
                println("  [${check.category}]")
                currentCategory = check.category
            }
            val marker = when (check.status) {
                "ok" -> "✓"
                "warning" -> "!"
                "error" -> "✗"
                "skipped" -> "∙"
                else -> "?"
            }
            println("  $marker ${check.message}")
            check.detail?.let { println("      $it") }
            check.remediation?.let { r ->
                println("      → ${r.summary}")
                for (cmd in r.commands) println("        \$ $cmd")
                r.docs?.let { println("        docs: $it") }
            }
        }
        println()

        val summary = summary()
        val headline = when {
            summary.error > 0 -> "✗ ${summary.error} error(s), ${summary.warning} warning(s)"
            summary.warning > 0 -> "✓ ok (${summary.warning} warning(s))"
            else -> "✓ all checks passed"
        }
        println(headline)
        if (summary.skipped > 0) println("  ${summary.skipped} check(s) skipped")
    }

    private fun emitJson() {
        val report = DoctorReport(
            pluginVersion = pluginVersion,
            overall = when {
                checks.any { it.status == "error" } -> "error"
                checks.any { it.status == "warning" } -> "warning"
                else -> "ok"
            },
            checks = checks.toList(),
            summary = summary(),
        )
        println(JSON.encodeToString(DoctorReport.serializer(), report))
    }

    private fun summary() = DoctorSummary(
        ok = checks.count { it.status == "ok" },
        warning = checks.count { it.status == "warning" },
        error = checks.count { it.status == "error" },
        skipped = checks.count { it.status == "skipped" },
    )

    // --- Helpers ------------------------------------------------------------

    private fun addCheck(check: DoctorCheck) {
        checks += check
    }

    private fun head(url: String, creds: Credentials): Pair<Int, Map<String, String>> {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            val auth = Base64.getEncoder()
                .encodeToString("${creds.user}:${creds.token}".toByteArray())
            setRequestProperty("Authorization", "Basic $auth")
            setRequestProperty("User-Agent", "compose-preview-doctor")
        }
        return try {
            val code = conn.responseCode
            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.joinToString(", ") }
            code to headers
        } catch (e: Exception) {
            -1 to mapOf("error" to (e.message ?: e.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
    }

    private fun loadProperties(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            Properties().apply { file.inputStream().use { load(it) } }
                .entries.associate { it.key.toString() to it.value.toString() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Sanitise a module path (e.g. `:sample-wear` → `sample-wear`) for use in check ids. */
    private fun idSafe(modulePath: String): String = modulePath.removePrefix(":").ifEmpty { "root" }

    private data class Credentials(val user: String, val token: String)

    companion object {
        private const val REPO = "yschimke/compose-ai-tools"
        private const val MAVEN_BASE = "https://maven.pkg.github.com/$REPO"
        private const val DEFAULT_PLUGIN_VERSION = "0.7.1" // x-release-please-version

        /**
         * Minimum supported Compose BOM — 2025.01.00 → compose-ui 1.9.0.
         * That's the first BOM where `ComposeUiNode.setCompositeKeyHash`
         * (emitted by compose-compiler 2.2.21) exists on the runtime side.
         */
        private const val MIN_BOM_YEAR = 2025
        private const val MIN_BOM_MONTH = 1

        private val SKIP_DIRS = setOf("build", "node_modules", "out", "dist", ".gradle")

        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

// --- Report schema ---------------------------------------------------------
// Stable public contract for external consumers — keep backwards-compatible
// within a major schema version. Schema version lives in [DoctorReport.schema].

@Serializable
data class DoctorReport(
    val schema: String = "compose-preview-doctor/v1",
    val pluginVersion: String,
    val overall: String, // "ok" | "warning" | "error"
    val checks: List<DoctorCheck>,
    val summary: DoctorSummary,
)

@Serializable
data class DoctorCheck(
    /** Stable dotted id — safe to grep / branch on. */
    val id: String,
    /** "env" | "project" | "deps". */
    val category: String,
    /** "ok" | "warning" | "error" | "skipped". */
    val status: String,
    /** Single-line human-readable summary. */
    val message: String,
    /** Multi-line follow-up (optional). Agents can surface to users. */
    val detail: String? = null,
    /** Concrete action to unblock (optional). */
    val remediation: DoctorRemediation? = null,
)

@Serializable
data class DoctorRemediation(
    val summary: String,
    val commands: List<String> = emptyList(),
    val docs: String? = null,
)

@Serializable
data class DoctorSummary(
    val ok: Int,
    val warning: Int,
    val error: Int,
    val skipped: Int,
)

