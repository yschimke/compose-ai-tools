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
        private const val DEFAULT_PLUGIN_VERSION = "0.3.4" // x-release-please-version
    }
}

private fun List<String>.flagValue(flag: String): String? {
    val idx = indexOf(flag)
    return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}
