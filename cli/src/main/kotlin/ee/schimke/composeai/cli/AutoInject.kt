package ee.schimke.composeai.cli

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-inject the `ee.schimke.composeai.preview` Gradle plugin into the user's build via
 * `--init-script`, so the CLI works against projects that haven't manually applied the plugin in
 * their `build.gradle.kts`.
 *
 * Mirrors the VS Code extension's [`initScript.ts`] auto-inject path — see that file's kdoc for the
 * rationale (`pluginManager.withPlugin` over `afterEvaluate`, why we resolve via Gradle Plugin
 * Portal + Maven Central + Google) and the CI variant at
 * `.github/ci/apply-compose-ai.init.gradle.kts`. The init script is idempotent — if the user
 * already applies the plugin manually, `plugins.hasPlugin(...)` short-circuits and it's a no-op.
 *
 * Opt-out:
 * - `--no-auto-inject` on any CLI invocation,
 * - `COMPOSE_PREVIEW_NO_AUTO_INJECT=1` in the environment, or
 * - the project root's `settings.gradle[.kts]` already includes the plugin's source build via
 *   `includeBuild("gradle-plugin")` — i.e. this CLI is being driven against the compose-ai-tools
 *   repo's own samples (or a fork doing the same). Adding a Maven-resolved classpath alongside an
 *   included build would conflict.
 */
const val INIT_SCRIPT_FILENAME = "apply-compose-ai-preview.init.gradle.kts"

/**
 * Renders the Kotlin-DSL init-script body with [pluginVersion] baked in. Pure function so unit
 * tests can assert the wire shape without going through the filesystem. Kept in lockstep with the
 * VS Code extension's `renderInitScript`.
 */
internal fun renderInitScript(pluginVersion: String): String =
  """// Compose Preview auto-inject init script.
//
// Materialised by the compose-preview CLI and passed via --init-script on
// every Gradle invocation the CLI makes. Applies ee.schimke.composeai.preview
// (version pinned to $pluginVersion) to every project that already applies
// com.android.application, com.android.library, or org.jetbrains.compose —
// so consumers don't have to edit their build files. Disable per-run with
// --no-auto-inject or COMPOSE_PREVIEW_NO_AUTO_INJECT=1.
//
// Application uses pluginManager.withPlugin(...) (not afterEvaluate) so AGP
// finalizeDsl / onVariants callbacks register before the DSL lock.
//
// `COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1` opts the buildscript repos into
// `mavenLocal()` — exercised by the gradle-plugin functional tests, which
// resolve the plugin from `~/.m2` (where `:publishToMavenLocal` puts it)
// rather than Maven Central. Plain users have no reason to flip this on:
// it widens the search surface to whatever snapshots happen to be cached
// locally and is therefore opt-in, not the default.

val pluginVersion = "$pluginVersion"
val useMavenLocal = System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"

allprojects {
    buildscript {
        repositories {
            gradlePluginPortal()
            mavenCentral()
            google()
            if (useMavenLocal) mavenLocal()
        }
        dependencies {
            add(
                "classpath",
                "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:${'$'}pluginVersion",
            )
        }
    }

    fun applyComposeAiPreview() {
        if (plugins.hasPlugin("ee.schimke.composeai.preview")) return
        pluginManager.apply("ee.schimke.composeai.preview")
    }

    pluginManager.withPlugin("com.android.application") { applyComposeAiPreview() }
    pluginManager.withPlugin("com.android.library") { applyComposeAiPreview() }
    pluginManager.withPlugin("org.jetbrains.compose") { applyComposeAiPreview() }
}
"""

/**
 * Writes the rendered init script into [storageDir] iff its contents differ from what's already
 * there. Returns the absolute path Gradle should receive via `--init-script`. Idempotent:
 * re-running with the same plugin version leaves the file untouched (and its mtime, which keeps
 * Gradle's configuration cache happy).
 */
internal fun materializeInitScript(storageDir: File, pluginVersion: String): File {
  storageDir.mkdirs()
  val target = File(storageDir, INIT_SCRIPT_FILENAME)
  val desired = renderInitScript(pluginVersion)
  val existing = if (target.isFile) runCatching { target.readText() }.getOrNull() else null
  if (existing != desired) target.writeText(desired)
  return target
}

/** Stable 16-char hex digest of the rendered script. Useful for tests / cache-bust diagnostics. */
internal fun initScriptDigest(pluginVersion: String): String {
  val bytes =
    MessageDigest.getInstance("SHA-256").digest(renderInitScript(pluginVersion).toByteArray())
  return bytes.joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * Default per-version storage directory under the user home, picked so multiple CLI versions can
 * coexist without racing on the same file path. Mirrors VS Code's `globalStorageUri` approach.
 *
 * Honours `XDG_CACHE_HOME` when set (Linux/BSD), else falls back to `~/.compose-preview/init`.
 */
internal fun defaultInitScriptStorageDir(version: String): File {
  val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
  val base =
    if (xdg != null) File(xdg, "compose-preview/init")
    else File(System.getProperty("user.home"), ".compose-preview/init")
  return File(base, version)
}

/**
 * Returns the `--init-script <path>` arguments to prepend to every Gradle invocation, or an empty
 * list when auto-inject is disabled. Materialises the script on first call.
 *
 * Opt-out (any one of these disables auto-inject):
 * - `--no-auto-inject` in [args],
 * - `COMPOSE_PREVIEW_NO_AUTO_INJECT=1` in the environment,
 * - [projectRoot]'s `settings.gradle[.kts]` declares `includeBuild("gradle-plugin")` (the
 *   compose-ai-tools dev-loop layout — running the CLI against its own samples).
 *
 * Failures (storage dir not writable, disk full) are swallowed with a stderr note and downgrade to
 * "no auto-inject" — the CLI continues with whatever the user has manually configured.
 */
internal fun autoInjectInitScriptArgs(
  args: List<String>,
  pluginVersion: String = BUNDLE_VERSION,
  storageDir: File = defaultInitScriptStorageDir(pluginVersion),
  env: (String) -> String? = System::getenv,
  projectRoot: File? = null,
  stderr: (String) -> Unit = System.err::println,
): List<String> {
  if ("--no-auto-inject" in args) return emptyList()
  if (env("COMPOSE_PREVIEW_NO_AUTO_INJECT") == "1") return emptyList()
  if (projectRoot != null && hasIncludedPluginBuild(projectRoot)) return emptyList()
  return try {
    val path = materializeInitScript(storageDir, pluginVersion)
    listOf("--init-script", path.absolutePath)
  } catch (e: Exception) {
    stderr(
      "compose-preview: auto-inject disabled — could not materialise init script in $storageDir: ${e.message}"
    )
    emptyList()
  }
}

/**
 * True when [projectRoot]'s settings file declares `includeBuild("gradle-plugin")` (Kotlin or
 * Groovy DSL, single or double quotes, with or without surrounding whitespace). Used to short-
 * circuit auto-inject in the compose-ai-tools repo itself — the agent-audit-samples CI job and any
 * local `./samples/...` development loop drive the CLI against this same root, and stacking a
 * Maven-resolved classpath dep on top of the included build conflicts with it.
 *
 * Visible for tests.
 */
internal fun hasIncludedPluginBuild(projectRoot: File): Boolean {
  val candidates =
    listOf(File(projectRoot, "settings.gradle.kts"), File(projectRoot, "settings.gradle"))
  val pattern = Regex("""includeBuild\s*\(\s*["']gradle-plugin["']\s*\)""")
  return candidates.any { it.isFile && pattern.containsMatchIn(it.readText()) }
}

/**
 * Matches the plugin being *applied* literally — `id("ee.schimke.composeai.preview")`, `id
 * "ee.schimke.composeai.preview"`, or `apply(plugin = "ee.schimke.composeai.preview")` — in any
 * build script. Kept in sync with the VS Code extension's `APPLIES_PLUGIN_RE`.
 *
 * The version-catalog alias form (`alias(libs.plugins.<x>)`) is intentionally out of scope: there's
 * no way to know from the build script alone which alias maps to which plugin id without parsing
 * `gradle/libs.versions.toml`. Consumers using catalogs see a spurious warning the first time — the
 * opt-out flag is documented in the warning text.
 */
private val PLUGIN_APPLIED_RE =
  Regex("""(?:\bid\s*[(\s]\s*|apply\s*\(\s*plugin\s*=\s*)["']ee\.schimke\.composeai\.preview["']""")

private val PLUGIN_APPLY_FALSE_RE = Regex("""\bapply\s+false\b""")

/**
 * True when *any* `build.gradle.kts` / `build.gradle` under [projectRoot] applies the plugin
 * literally. Walks the project tree (max depth 6, skipping `build/`, `.gradle/`, `.git/`,
 * `node_modules/`) to cover deeply nested module layouts. Returns false on the first hint that the
 * plugin is supplied entirely via auto-inject so callers can nudge the user toward a permanent
 * `plugins { ... }` entry.
 *
 * Conservative on the "applied" side: a line matching [PLUGIN_APPLIED_RE] with `apply false` on the
 * same line is skipped — that's the root-build pattern where a plugin is declared for subprojects
 * but not applied in the current module.
 */
internal fun pluginAppliedInBuildScripts(projectRoot: File, maxDepth: Int = 6): Boolean {
  val skipDirs = setOf("build", ".gradle", ".git", "node_modules", "out", ".idea")
  fun scan(dir: File, depth: Int): Boolean {
    if (depth > maxDepth) return false
    val children = dir.listFiles() ?: return false
    for (child in children) {
      if (child.isFile && (child.name == "build.gradle.kts" || child.name == "build.gradle")) {
        val text = runCatching { child.readText() }.getOrNull() ?: continue
        for (line in text.lineSequence()) {
          if (!PLUGIN_APPLIED_RE.containsMatchIn(line)) continue
          if (PLUGIN_APPLY_FALSE_RE.containsMatchIn(line)) continue
          return true
        }
      }
    }
    for (child in children) {
      if (child.isDirectory && child.name !in skipDirs && !child.name.startsWith(".")) {
        if (scan(child, depth + 1)) return true
      }
    }
    return false
  }
  return scan(projectRoot, 0)
}

private val pluginWarningPrinted = AtomicBoolean(false)

/**
 * Warns once per CLI process when the project relies entirely on auto-inject — i.e. no module's
 * build script applies `ee.schimke.composeai.preview` directly. The CLI continues to function, but
 * a permanent `plugins { id("ee.schimke.composeai.preview") version "<v>" }` declaration unlocks
 * IDE / agent integrations that read the project's static config (VS Code's marker scan, Android
 * Studio gutter icons) and avoids the per-invocation init-script materialisation cost.
 *
 * Skipped when auto-inject itself is disabled — the user has opted out of the bundled flow and the
 * warning would be noise.
 *
 * Suppressible via `--no-plugin-warning` on the CLI invocation or
 * `COMPOSE_PREVIEW_NO_PLUGIN_WARNING=1` in the environment.
 */
internal fun warnIfPluginNotPreApplied(
  args: List<String>,
  projectRoot: File,
  pluginVersion: String = BUNDLE_VERSION,
  env: (String) -> String? = System::getenv,
  stderr: (String) -> Unit = System.err::println,
  resetFlag: Boolean = false,
) {
  if (resetFlag) pluginWarningPrinted.set(false)
  if ("--no-auto-inject" in args) return
  if (env("COMPOSE_PREVIEW_NO_AUTO_INJECT") == "1") return
  if ("--no-plugin-warning" in args) return
  if (env("COMPOSE_PREVIEW_NO_PLUGIN_WARNING") == "1") return
  if (hasIncludedPluginBuild(projectRoot)) return
  if (pluginAppliedInBuildScripts(projectRoot)) return
  if (!pluginWarningPrinted.compareAndSet(false, true)) return
  stderr(
    "compose-preview: plugin not applied in any build.gradle(.kts); running via auto-inject. " +
      "For best IDE / agent support add to your module's plugins { } block: " +
      "id(\"ee.schimke.composeai.preview\") version \"$pluginVersion\" " +
      "(suppress with --no-plugin-warning or COMPOSE_PREVIEW_NO_PLUGIN_WARNING=1)."
  )
}
