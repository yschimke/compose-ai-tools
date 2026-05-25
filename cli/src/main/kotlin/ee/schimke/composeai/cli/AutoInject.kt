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
 * Portal + Maven Central + Google). The init script is idempotent — if the user already applies the
 * plugin manually, `plugins.hasPlugin(...)` short-circuits and it's a no-op. CI's integration
 * matrix materialises this same script via `compose-preview init-script --path` rather than
 * shipping a CI-only variant.
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
// Pre-applied detection is *per project*: for each subproject whose build
// file declares the plugin with a version — either literal
// `id("...") version "..."` or via `alias(libs.plugins.<x>)` where the
// version catalog maps <x> to this plugin id — we skip the buildscript
// classpath injection for that project. Gradle's plugins {} DSL rejects
// `id(...) version "..."` when the same plugin is also on the buildscript
// classpath ("the plugin is already on the classpath with an unknown
// version, so compatibility cannot be checked"), and the user's own
// declaration provides resolution via plugin marker repos. Projects that
// don't declare the plugin themselves still get the buildscript classpath
// injection so the withPlugin / pluginManager.apply path can find the
// plugin class — this is what mixed-shape multi-module projects need
// (e.g. an `:app` module that applies the plugin via catalog alias, plus
// a sibling `:rc-components` android-library module that doesn't; the init
// script's withPlugin("com.android.library") hook fires in rc-components
// too and the plugin must be resolvable from its buildscript classpath).
// The withPlugin hooks in projects that already apply the plugin no-op via
// the plugins.hasPlugin(...) guard.
//
// `COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1` opts the buildscript repos into
// `mavenLocal()` — exercised by the gradle-plugin functional tests, which
// resolve the plugin from `~/.m2` (where `:publishToMavenLocal` puts it)
// rather than Maven Central. Plain users have no reason to flip this on:
// it widens the search surface to whatever snapshots happen to be cached
// locally and is therefore opt-in, not the default.
//
// Auto-inject is suppressed for modules that apply
// `com.android.kotlin.multiplatform.library` — the AGP-KMP plugin's single
// `android` variant trips a variant-ambiguity error on
// `androidRuntimeClasspath` once the renderer-android artifact view kicks in,
// breaking `compose-preview show` for any project where the plugin landed
// purely via auto-inject. The supported KMP-Android layout
// (samples/cmp-shared, with a `jvm("desktop")` target) still works when the
// user adds `id("ee.schimke.composeai.preview")` to that module's plugins {}
// block themselves — we just no longer apply it implicitly.

val pluginVersion = "$pluginVersion"
val useMavenLocal = System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"

var composeAiPreviewPreAppliedDirs: Set<java.io.File> = emptySet()
var composeAiPreviewKmpAndroidDirs: Set<java.io.File> = emptySet()

fun composeAiPreviewCatalogAccessors(rootDir: java.io.File): List<Regex> {
    val catalog = java.io.File(rootDir, "gradle/libs.versions.toml")
    if (!catalog.isFile) return emptyList()
    val text = runCatching { catalog.readText() }.getOrNull() ?: return emptyList()
    val pluginsHeader = Regex("(?m)^\\[plugins\\]\\s*${'$'}").find(text) ?: return emptyList()
    val sectionStart = pluginsHeader.range.last + 1
    val nextSection = Regex("(?m)^\\[").find(text, sectionStart)
    val section = text.substring(sectionStart, nextSection?.range?.first ?: text.length)
    val entryRe = Regex(
        "(?m)^[ \\t]*([A-Za-z0-9_.\\-]+)\\s*=\\s*(?:" +
            "\\{[^}]*\\bid\\s*=\\s*\"ee\\.schimke\\.composeai\\.preview\"[^}]*\\}|" +
            "\"ee\\.schimke\\.composeai\\.preview(?::[^\"]*)?\"" +
            ")"
    )
    return entryRe.findAll(section).map { match ->
        val accessor = match.groupValues[1].replace(Regex("[-_]"), ".")
        Regex("\\blibs\\s*\\.\\s*plugins\\s*\\.\\s*" + Regex.escape(accessor) + "\\b")
    }.toList()
}

// Catalog-alias accessors for `com.android.kotlin.multiplatform.library` — same shape as
// composeAiPreviewCatalogAccessors but pinned to the KMP-Android plugin id so a module
// declaring it via `alias(libs.plugins.android.kotlin.multiplatform.library)` is detected
// alongside the literal `id("com.android.kotlin.multiplatform.library")` form.
fun composeAiPreviewKmpAndroidCatalogAccessors(rootDir: java.io.File): List<Regex> {
    val catalog = java.io.File(rootDir, "gradle/libs.versions.toml")
    if (!catalog.isFile) return emptyList()
    val text = runCatching { catalog.readText() }.getOrNull() ?: return emptyList()
    val pluginsHeader = Regex("(?m)^\\[plugins\\]\\s*${'$'}").find(text) ?: return emptyList()
    val sectionStart = pluginsHeader.range.last + 1
    val nextSection = Regex("(?m)^\\[").find(text, sectionStart)
    val section = text.substring(sectionStart, nextSection?.range?.first ?: text.length)
    val entryRe = Regex(
        "(?m)^[ \\t]*([A-Za-z0-9_.\\-]+)\\s*=\\s*(?:" +
            "\\{[^}]*\\bid\\s*=\\s*\"com\\.android\\.kotlin\\.multiplatform\\.library\"[^}]*\\}|" +
            "\"com\\.android\\.kotlin\\.multiplatform\\.library(?::[^\"]*)?\"" +
            ")"
    )
    return entryRe.findAll(section).map { match ->
        val accessor = match.groupValues[1].replace(Regex("[-_]"), ".")
        Regex("\\blibs\\s*\\.\\s*plugins\\s*\\.\\s*" + Regex.escape(accessor) + "\\b")
    }.toList()
}

// Strips // line comments and /* */ block comments so a documentation line like
// `// id("ee.schimke.composeai.preview") version "..."` (or the alias variant)
// doesn't get treated as a real declaration and disable classpath injection for
// projects that actually need auto-inject.
fun composeAiPreviewStripComments(source: String): String {
    val sb = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        if (c == '/' && next == '/') {
            val newline = source.indexOf('\n', i)
            if (newline < 0) break
            i = newline
        } else if (c == '/' && next == '*') {
            val end = source.indexOf("*/", i + 2)
            i = if (end < 0) source.length else end + 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

fun scanForComposeAiPreviewDeclaration(
    rootDir: java.io.File,
    projectDirs: List<java.io.File>,
): Set<java.io.File> {
    val catalogAccessors = composeAiPreviewCatalogAccessors(rootDir)
    val literalVersionedRe = Regex(
        "\\bid\\s*[(\\s]\\s*[\"']ee\\.schimke\\.composeai\\.preview[\"']\\s*\\)?\\s*(?:\\.\\s*)?version\\b"
    )
    val declared = LinkedHashSet<java.io.File>()
    for (dir in projectDirs) {
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val buildFile = java.io.File(dir, name)
            if (!buildFile.isFile) continue
            val raw = runCatching { buildFile.readText() }.getOrNull() ?: continue
            val text = composeAiPreviewStripComments(raw)
            if (literalVersionedRe.containsMatchIn(text)) {
                declared.add(dir)
                break
            }
            if (catalogAccessors.any { it.containsMatchIn(text) }) {
                declared.add(dir)
                break
            }
        }
    }
    return declared
}

// Scan for modules that apply `com.android.kotlin.multiplatform.library` — auto-inject
// skips both the buildscript classpath injection and the withPlugin apply hooks for these
// dirs. Applying compose-preview to a KMP-Android module trips an AGP-KMP variant model
// mismatch on `androidRuntimeClasspath` once the consumer's CLI run resolves the renderer's
// artifact view. Users who explicitly want previews on a KMP-Android module can add
// `id("ee.schimke.composeai.preview")` to that module's plugins {} block themselves — the
// supported layout in samples/cmp-shared (with a `jvm("desktop")` target) still works that
// way; we just don't apply it implicitly anymore.
fun scanForKmpAndroidDeclaration(
    rootDir: java.io.File,
    projectDirs: List<java.io.File>,
): Set<java.io.File> {
    val catalogAccessors = composeAiPreviewKmpAndroidCatalogAccessors(rootDir)
    val literalRe = Regex(
        "\\bid\\s*[(\\s]\\s*[\"']com\\.android\\.kotlin\\.multiplatform\\.library[\"']"
    )
    val declared = LinkedHashSet<java.io.File>()
    for (dir in projectDirs) {
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val buildFile = java.io.File(dir, name)
            if (!buildFile.isFile) continue
            val raw = runCatching { buildFile.readText() }.getOrNull() ?: continue
            val text = composeAiPreviewStripComments(raw)
            if (literalRe.containsMatchIn(text)) {
                declared.add(dir)
                break
            }
            if (catalogAccessors.any { it.containsMatchIn(text) }) {
                declared.add(dir)
                break
            }
        }
    }
    return declared
}

// Skip composite-included builds entirely — both the settings scan and the `allprojects`
// injection. The init script is evaluated once per build in a composite (root + each
// `includeBuild(...)`), so without this guard `allprojects { buildscript { repositories { ... } } }`
// fires for the included build's projects too. That breaks any included build whose
// `pluginManagement.repositories` declares `exclusiveContent { ... }`: Gradle 9.3+ rejects
// adding to `buildscript.repositories` once exclusiveContent is in
// `settings.pluginManagement.repositories` (e.g. Confetti's `:build-logic`, which excludes
// `com.apollographql.execution` SNAPSHOTs to an Apollo bucket). Included builds in this pattern
// are conventionally plugin builds (`build-logic`, `gradle-conventions`) that don't host
// `@Preview` composables, so injecting the plugin classpath there is unnecessary — and the
// existing pre-applied / KMP-Android scans only walk the *root* build's project hierarchy
// anyway, so included-build projects were never tracked. An included build's `Gradle` instance
// has a non-null `parent`; the root build's is `null`.
val composeAiPreviewIsIncludedBuild = gradle.parent != null

gradle.settingsEvaluated {
    if (composeAiPreviewIsIncludedBuild) return@settingsEvaluated
    val projectDirs = mutableListOf<java.io.File>()
    fun collect(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
        projectDirs.add(descriptor.projectDir)
        descriptor.children.forEach { collect(it) }
    }
    collect(rootProject)
    composeAiPreviewPreAppliedDirs = scanForComposeAiPreviewDeclaration(rootDir, projectDirs)
    composeAiPreviewKmpAndroidDirs = scanForKmpAndroidDeclaration(rootDir, projectDirs)

    // When opting into mavenLocal, also seed it at the settings level so the renderer-android AAR
    // and any other ee.schimke.composeai:* runtime artifacts resolve from ~/.m2 alongside the
    // plugin classpath. Consumers with `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (e.g. wear-os-
    // samples WearTilesKotlin) refuse per-project repos, so settings-level seeding is the only
    // path that survives. pluginManagement.repositories.mavenLocal() covers the catalog-alias /
    // literal-`id(...) version "..."` case where resolution goes through the plugins DSL instead
    // of our buildscript classpath injection.
    //
    // Gradle only auto-adds the default Plugin Portal when `pluginManagement.repositories` is
    // empty after settings evaluation — once we explicitly add `mavenLocal()` the list is
    // non-empty and the default is suppressed, so restore the defaults explicitly when the build
    // didn't declare any plugin repos of its own.
    if (useMavenLocal) {
        val seedPluginDefaults = pluginManagement.repositories.isEmpty()
        pluginManagement.repositories.mavenLocal()
        if (seedPluginDefaults) {
            pluginManagement.repositories.gradlePluginPortal()
            pluginManagement.repositories.mavenCentral()
            pluginManagement.repositories.google()
        }
        dependencyResolutionManagement.repositories.mavenLocal()
    }
}

allprojects {
    if (composeAiPreviewIsIncludedBuild) return@allprojects
    // Skip BOTH the buildscript classpath injection AND the apply hooks for KMP-Android
    // modules. Doing only one half leaves the other half active: skipping the classpath
    // injection alone still fires the withPlugin hooks (which then fail to find the plugin
    // class), and skipping only the hooks still drags an unused plugin marker onto the
    // buildscript classpath. Both halves gated together is the safe shape — see
    // scanForKmpAndroidDeclaration() above for the rationale.
    val composeAiPreviewSkipKmpAndroid = projectDir in composeAiPreviewKmpAndroidDirs

    if (!composeAiPreviewSkipKmpAndroid && projectDir !in composeAiPreviewPreAppliedDirs) {
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
    }

    if (composeAiPreviewSkipKmpAndroid) return@allprojects

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
 * Matches the plugin being *applied* literally in any build script — covers
 * - Kotlin DSL: `id("ee.schimke.composeai.preview")`,
 * - Groovy DSL: `id 'ee.schimke.composeai.preview'`,
 * - Kotlin DSL legacy: `apply(plugin = "ee.schimke.composeai.preview")`,
 * - Groovy DSL legacy: `apply plugin: 'ee.schimke.composeai.preview'`.
 *
 * Kept in sync with the VS Code extension's `APPLIES_PLUGIN_RE` plus the extra Groovy `apply
 * plugin:` legacy form (Codex P2 review on PR #1171).
 *
 * Version-catalog `alias(libs.plugins.<x>)` declarations are handled out-of-band via
 * [catalogPluginAccessorRegexes] / [pluginAppliedInBuildScripts] — the catalog parser resolves
 * which accessor names map to this plugin id, then the scanner pairs them with build-file
 * references.
 */
private val PLUGIN_APPLIED_RE =
  Regex(
    """(?:\bid\s*[(\s]\s*|apply\s*\(\s*plugin\s*=\s*|\bapply\s+plugin\s*:\s*)["']ee\.schimke\.composeai\.preview["']"""
  )

private val PLUGIN_APPLY_FALSE_RE = Regex("""\bapply\s+false\b""")

/**
 * Returns regexes matching `libs.plugins.<accessor>` for every version-catalog alias under
 * [projectRoot]'s `gradle/libs.versions.toml` whose `id` resolves to this plugin. Empty when the
 * catalog is missing or doesn't declare the plugin. Kept simple via regex parsing (not a full TOML
 * parser): the entries we care about — `alias = { id = "...", version = "..." }` and `alias =
 * "id:version"` — are stable enough that a literal scan covers the realistic cases.
 *
 * Visible for tests.
 */
internal fun catalogPluginAccessorRegexes(projectRoot: File): List<Regex> {
  val catalog = File(projectRoot, "gradle/libs.versions.toml")
  if (!catalog.isFile) return emptyList()
  val text = runCatching { catalog.readText() }.getOrNull() ?: return emptyList()
  val header = Regex("""(?m)^\[plugins\]\s*$""").find(text) ?: return emptyList()
  val start = header.range.last + 1
  val nextSection = Regex("""(?m)^\[""").find(text, start)
  val section = text.substring(start, nextSection?.range?.first ?: text.length)
  val entryRe =
    Regex(
      """(?m)^[ \t]*([A-Za-z0-9_.\-]+)\s*=\s*(?:\{[^}]*\bid\s*=\s*"ee\.schimke\.composeai\.preview"[^}]*\}|"ee\.schimke\.composeai\.preview(?::[^"]*)?")"""
    )
  return entryRe
    .findAll(section)
    .map { match ->
      val accessor = match.groupValues[1].replace(Regex("[-_]"), ".")
      Regex("""\blibs\s*\.\s*plugins\s*\.\s*${Regex.escape(accessor)}\b""")
    }
    .toList()
}

/**
 * True when *any* `build.gradle.kts` / `build.gradle` under [projectRoot] applies the plugin
 * literally. Walks the project tree (max depth 6, skipping `build/`, `.gradle/`, `.git/`,
 * `node_modules/`) to cover deeply nested module layouts. Returns false on the first hint that the
 * plugin is supplied entirely via auto-inject so callers can nudge the user toward a permanent
 * `plugins { ... }` entry.
 *
 * Conservative on the "applied" side: a line matching [PLUGIN_APPLIED_RE] with `apply false` on the
 * same line is skipped — that's the root-build pattern where a plugin is declared for subprojects
 * but not applied in the current module. Single-line `// …` and block `/* … */` comments are
 * stripped before matching: a script that *documents* the plugin in a comment shouldn't be
 * misclassified as having applied it.
 */
internal fun pluginAppliedInBuildScripts(projectRoot: File, maxDepth: Int = 6): Boolean {
  val skipDirs = setOf("build", ".gradle", ".git", "node_modules", "out", ".idea")
  val catalogAccessors = catalogPluginAccessorRegexes(projectRoot)
  fun scan(dir: File, depth: Int): Boolean {
    if (depth > maxDepth) return false
    val children = dir.listFiles() ?: return false
    for (child in children) {
      if (child.isFile && (child.name == "build.gradle.kts" || child.name == "build.gradle")) {
        val raw = runCatching { child.readText() }.getOrNull() ?: continue
        val text = stripGradleComments(raw)
        for (line in text.lineSequence()) {
          if (PLUGIN_APPLY_FALSE_RE.containsMatchIn(line)) continue
          if (PLUGIN_APPLIED_RE.containsMatchIn(line)) return true
          for (re in catalogAccessors) {
            if (re.containsMatchIn(line)) return true
          }
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

/**
 * Removes `// …` line comments and `/* … */` block comments from a Gradle build script before the
 * pre-application detector scans it. Doesn't try to be a full Kotlin / Groovy parser: enough to
 * keep a `// id("ee.schimke.composeai.preview")` documentation line out of a positive match. String
 * literals aren't tracked — a deliberately-quoted comment-prefix inside a string is rare enough in
 * build scripts to ignore.
 */
internal fun stripGradleComments(source: String): String {
  val sb = StringBuilder(source.length)
  var i = 0
  while (i < source.length) {
    val c = source[i]
    val next = source.getOrNull(i + 1)
    if (c == '/' && next == '/') {
      val newline = source.indexOf('\n', i)
      if (newline < 0) break
      i = newline
    } else if (c == '/' && next == '*') {
      val end = source.indexOf("*/", i + 2)
      i = if (end < 0) source.length else end + 2
    } else {
      sb.append(c)
      i++
    }
  }
  return sb.toString()
}

private val pluginWarningPrinted = AtomicBoolean(false)

/**
 * Warns once per CLI process when the project relies entirely on auto-inject — i.e. no module's
 * build script applies `ee.schimke.composeai.preview` directly. The CLI continues to function, but
 * a permanent `plugins { id("ee.schimke.composeai.preview") version "<v>" }` declaration unlocks
 * IDE / agent integrations that read the project's static config (VS Code's marker scan, Android
 * Studio gutter icons) and avoids the per-invocation init-script materialisation cost.
 *
 * [autoInjectActive] must be `true` only when [autoInjectInitScriptArgs] actually returned an
 * `--init-script` pair this run — passing the result through avoids the false-positive "running via
 * auto-inject" warning when the init-script materialisation failed (e.g. unwritable cache dir, disk
 * full), in which case the CLI is running with *no* plugin source at all and should not pretend
 * auto-inject saved the day (Codex P2 review on PR #1171). The function also bails when auto-inject
 * is disabled by flag / env opt-out — defence in depth in case a caller forgets to read
 * [autoInjectActive] off [autoInjectInitScriptArgs].
 *
 * Suppressible via `--no-plugin-warning` on the CLI invocation or
 * `COMPOSE_PREVIEW_NO_PLUGIN_WARNING=1` in the environment.
 */
internal fun warnIfPluginNotPreApplied(
  args: List<String>,
  projectRoot: File,
  autoInjectActive: Boolean,
  pluginVersion: String = BUNDLE_VERSION,
  env: (String) -> String? = System::getenv,
  stderr: (String) -> Unit = System.err::println,
  resetFlag: Boolean = false,
) {
  if (resetFlag) pluginWarningPrinted.set(false)
  if (!autoInjectActive) return
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
