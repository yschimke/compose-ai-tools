package ee.schimke.composeai.cli

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

/**
 * `compose-preview doctor`
 *
 * Verifies the prerequisites for using the plugin before the user edits any
 * Gradle files:
 *
 *   1. Java 21+ is reachable.
 *   2. GitHub Packages credentials (`composeAiTools.githubUser` / `...Token`)
 *      are set in ~/.gradle/gradle.properties, or `GITHUB_ACTOR` /
 *      `GITHUB_TOKEN` are set in the environment.
 *   3. The credentials actually resolve a package — a HEAD against the plugin
 *      POM on `maven.pkg.github.com`. A 200 means auth is good and
 *      `read:packages` is effective (fine-grained tokens don't expose scopes
 *      via the REST API, so we probe instead of introspecting).
 *   4. If token looks like a classic PAT, also surfaces the `x-oauth-scopes`
 *      header so the user can see what scopes are actually granted.
 *
 * Exits 0 when everything checks out, 1 otherwise. Designed to be safe to run
 * outside a Gradle project — it never touches the filesystem.
 */
class DoctorCommand(args: List<String>) {
    private val verbose = "--verbose" in args || "-v" in args
    private val pluginVersion = args.flagValue("--plugin-version") ?: DEFAULT_PLUGIN_VERSION

    private var errors = 0
    private var warnings = 0

    fun run() {
        println("compose-preview doctor")
        println()

        checkJava()
        val creds = checkCredentials()
        if (creds != null) probeMaven(creds)
        checkComposeVersion()

        println()
        when {
            errors > 0 -> {
                println("✗ $errors problem(s) found" + if (warnings > 0) ", $warnings warning(s)" else "")
                println()
                println("Fix the errors above before applying the plugin to your build.")
                exitProcess(1)
            }
            warnings > 0 -> println("✓ ok ($warnings warning(s))")
            else -> println("✓ all checks passed")
        }
    }

    // --- Checks -------------------------------------------------------------

    private fun checkJava() {
        val version = System.getProperty("java.specification.version")
        val major = version?.substringBefore('.')?.toIntOrNull()
        if (major != null && major >= 21) {
            ok("Java $version on PATH")
        } else {
            err("Java 21+ required, got ${version ?: "unknown"}")
            hint("Install a JDK 21 and put it on PATH, or set JAVA_HOME.")
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
                err("no GitHub Packages credentials found")
                hint("Store a token in ${propsFile.path}:")
                hint("    composeAiTools.githubUser=<your-github-username>")
                hint("    composeAiTools.githubToken=<PAT with read:packages>")
                hint("Or: gh auth refresh -h github.com -s read:packages && gh auth token")
                return null
            }
            token == null -> {
                err("composeAiTools.githubToken is not set")
                return null
            }
            user == null -> {
                warn("composeAiTools.githubUser not set; falling back to 'x' for BASIC auth")
            }
        }

        val source = when {
            props["composeAiTools.githubToken"] != null -> propsFile.path
            else -> "\$GITHUB_TOKEN env var"
        }
        ok("credentials found ($source)")

        return Credentials(user ?: "x", token!!)
    }

    private fun probeMaven(creds: Credentials) {
        // Probe the plugin POM on GitHub Packages. 200 = auth works + read:packages
        // is effective. 401/403 = bad creds or missing scope. 404 = auth worked but
        // the specific version doesn't exist (still good news for the auth check).
        val path = "ee/schimke/composeai/gradle-plugin/$pluginVersion/gradle-plugin-$pluginVersion.pom"
        val url = "$MAVEN_BASE/$path"

        val (code, headers) = head(url, creds)

        when (code) {
            in 200..299 -> ok("GitHub Packages auth works (HEAD $pluginVersion → $code)")
            404 -> {
                // 404 from maven.pkg.github.com with valid auth still means auth+scope
                // worked — GitHub returns 401 if the token can't see the package at all.
                ok("GitHub Packages auth works (plugin v$pluginVersion not published, but token can see the repo)")
                warn("pinned plugin version $pluginVersion not found; check https://github.com/$REPO/releases")
            }
            401 -> {
                err("GitHub Packages rejected credentials (HTTP 401)")
                surfaceScopes(headers)
                hint("The token is missing 'read:packages' or is invalid.")
                hint("Fix: gh auth refresh -h github.com -s read:packages")
                hint("  or: create a classic PAT with 'read:packages' at https://github.com/settings/tokens/new")
            }
            403 -> {
                err("GitHub Packages refused (HTTP 403) — token likely lacks 'read:packages'")
                surfaceScopes(headers)
            }
            else -> {
                err("unexpected HTTP $code from $url")
                if (verbose) headers.forEach { (k, v) -> System.err.println("  $k: $v") }
            }
        }
    }

    /**
     * Flags Compose versions that are known-too-old for the renderer to run
     * against. Renderer-android is compiled with compose-compiler 2.2.21,
     * which emits calls to `ComposeUiNode.setCompositeKeyHash` and friends
     * — first shipped in compose-ui 1.9 (compose-bom 2025.01.00). Older
     * consumers hit `NoSuchMethodError` the moment `renderPreviews` starts.
     * This check walks `gradle/libs.versions.toml` + every `build.gradle*`
     * for a compose-bom or compose-ui version declaration and compares
     * against that floor.
     *
     * No Gradle call — we just grep the sources. That keeps doctor a
     * pre-flight check (runnable before the user even applies the plugin),
     * matching the other checks' shape.
     */
    private fun checkComposeVersion() {
        val workspace = File(".").canonicalFile
        val versions = findComposeVersionDeclarations(workspace)
        if (versions.isEmpty()) {
            // No declarations found → nothing to check. Don't warn: it's
            // entirely possible doctor is being run outside a Gradle project,
            // or versions are declared somewhere we didn't look.
            return
        }

        val tooOld = versions.filter { (_, v) -> v.isOlderThan(MIN_BOM_YEAR, MIN_BOM_MONTH) }
        if (tooOld.isEmpty()) {
            val summary = versions.joinToString(", ") { (_, v) -> v.raw }
            ok("compose-bom version(s) look recent enough ($summary)")
            return
        }
        for ((source, v) in tooOld) {
            warn("compose-bom ${v.raw} declared in ${source.relativeTo(workspace).path} — renderer needs ≥$MIN_BOM_YEAR.${MIN_BOM_MONTH.toString().padStart(2, '0')}.00")
        }
        hint("Older BOMs lack `ComposeUiNode.setCompositeKeyHash`; `renderPreviews` will fail with NoSuchMethodError.")
        hint("Bump the bom:  compose-bom = \"$MIN_BOM_YEAR.${MIN_BOM_MONTH.toString().padStart(2, '0')}.00\"  (or newer)")
    }

    /**
     * Returns `(fileWhereFound, parsedVersion)` for every
     * `androidx.compose:compose-bom` version literal we find. Sources
     * checked (in order): `gradle/libs.versions.toml`, `build.gradle`
     * / `build.gradle.kts` under the workspace. Early-exits at depth 4
     * to avoid wandering through `build/` etc.
     */
    private fun findComposeVersionDeclarations(root: File): List<Pair<File, ComposeVersion>> {
        val out = mutableListOf<Pair<File, ComposeVersion>>()
        val tomlRegex = Regex("""compose-bom\s*=\s*"([^"]+)"""")
        // Matches `platform("androidx.compose:compose-bom:YYYY.MM.XX")` inline.
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

    // --- Helpers ------------------------------------------------------------

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
            err("network probe failed: ${e.message}")
            -1 to emptyMap()
        } finally {
            conn.disconnect()
        }
    }

    private fun surfaceScopes(headers: Map<String, String>) {
        val scopes = headers.entries.firstOrNull { it.key.equals("x-oauth-scopes", true) }?.value
        if (scopes != null) {
            hint("token scopes reported by GitHub: ${scopes.ifBlank { "(none)" }}")
            if ("read:packages" !in scopes) {
                hint("missing 'read:packages'.")
            }
        } else {
            hint("(no x-oauth-scopes header — fine-grained tokens don't expose scopes this way)")
        }
    }

    private fun loadProperties(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            Properties().apply { file.inputStream().use { load(it) } }
                .entries.associate { it.key.toString() to it.value.toString() }
        } catch (e: Exception) {
            warn("could not read ${file.path}: ${e.message}")
            emptyMap()
        }
    }

    private fun ok(msg: String) = println("  ✓ $msg")
    private fun warn(msg: String) {
        warnings++
        println("  ! $msg")
    }
    private fun err(msg: String) {
        errors++
        println("  ✗ $msg")
    }
    private fun hint(msg: String) = println("      $msg")

    private data class Credentials(val user: String, val token: String)

    companion object {
        private const val REPO = "yschimke/compose-ai-tools"
        private const val MAVEN_BASE = "https://maven.pkg.github.com/$REPO"
        private const val DEFAULT_PLUGIN_VERSION = "0.4.0" // x-release-please-version

        /**
         * Minimum supported Compose BOM — 2025.01.00 → compose-ui 1.9.0.
         * That's the first BOM where `ComposeUiNode.setCompositeKeyHash`
         * (emitted by compose-compiler 2.2.21) exists on the runtime side.
         */
        private const val MIN_BOM_YEAR = 2025
        private const val MIN_BOM_MONTH = 1

        private val SKIP_DIRS = setOf("build", "node_modules", "out", "dist", ".gradle")
    }
}

private fun List<String>.flagValue(flag: String): String? {
    val idx = indexOf(flag)
    return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}
