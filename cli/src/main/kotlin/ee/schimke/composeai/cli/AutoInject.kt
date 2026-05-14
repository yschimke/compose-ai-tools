package ee.schimke.composeai.cli

import java.io.File
import java.security.MessageDigest

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
 * - `--no-auto-inject` on any CLI invocation, or
 * - `COMPOSE_PREVIEW_NO_AUTO_INJECT=1` in the environment.
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

val pluginVersion = "$pluginVersion"

allprojects {
    buildscript {
        repositories {
            gradlePluginPortal()
            mavenCentral()
            google()
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
 * - `COMPOSE_PREVIEW_NO_AUTO_INJECT=1` in the environment.
 *
 * Failures (storage dir not writable, disk full) are swallowed with a stderr note and downgrade to
 * "no auto-inject" — the CLI continues with whatever the user has manually configured.
 */
internal fun autoInjectInitScriptArgs(
  args: List<String>,
  pluginVersion: String = BUNDLE_VERSION,
  storageDir: File = defaultInitScriptStorageDir(pluginVersion),
  env: (String) -> String? = System::getenv,
  stderr: (String) -> Unit = System.err::println,
): List<String> {
  if ("--no-auto-inject" in args) return emptyList()
  if (env("COMPOSE_PREVIEW_NO_AUTO_INJECT") == "1") return emptyList()
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
