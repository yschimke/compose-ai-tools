package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.security.MessageDigest
import okio.FileSystem
import okio.Path.Companion.toPath

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
// `mavenLocal()` is added to the buildscript / settings repos automatically
// when [pluginVersion] ends in `-SNAPSHOT` — the only place an unpublished
// SNAPSHOT plugin can live is `~/.m2`, so a SNAPSHOT CLI that doesn't seed
// it is unusable. Released CLIs leave `~/.m2` untouched: the plugin is on
// Plugin Portal / Maven Central, and widening the search surface to local
// snapshots would only invite accidental version mismatches.
// `COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1` still forces the seed on for
// non-SNAPSHOT runs — used by the gradle-plugin functional tests, which
// publish the CLI's own release version to `~/.m2` and resolve from there
// rather than Maven Central.
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
//
// When the consumer's settings file declares `exclusiveContent { ... }`
// inside `pluginManagement { repositories { ... } }` (the Confetti shape;
// issues #1470, #1482), Gradle 9.3+ rejects *adding* to
// `buildscript.repositories` from any project. We detect that shape and
// fork the behaviour per-project: modules that have their own
// `buildscript { repositories { ... } }` declared get the classpath dep
// injected (resolution can succeed via the consumer's repos); modules
// that don't are skipped entirely (the classpath dep would be unresolvable
// and would `Cannot resolve external dependency ... because no
// repositories are defined`, crashing the whole Tooling API query — the
// 0.11.8 regression). Skipped modules silently miss the plugin — there is no
// log because auto-inject is meant to be invisible; users who want previews on
// such a module can add `plugins { id("ee.schimke.composeai.preview") }` themselves.
//
// PR #1483 tried to dodge the validation by loading the plugin via init-
// script classpath. That works for the validation but breaks every AGP-
// touching code path at runtime — our plugin references
// `AndroidComponentsExtension` etc. directly, and an init-script-loaded
// plugin sits on a *sibling* classloader of AGP, so the JVM throws
// `NoClassDefFoundError` the moment any AGP-aware path runs. The current
// approach keeps the plugin on the project's buildscript classloader so
// AGP visibility stays intact.

val pluginVersion = "$pluginVersion"
val useMavenLocal = pluginVersion.endsWith("-SNAPSHOT") ||
    System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"

var composeAiPreviewPreAppliedDirs: Set<java.io.File> = emptySet()
var composeAiPreviewKmpAndroidDirs: Set<java.io.File> = emptySet()
var composeAiPreviewSettingsHasExclusiveContent: Boolean = false
// Projects that declare their own `buildscript { repositories { ... } }` block — populated
// at settingsEvaluated time. Only consulted when composeAiPreviewSettingsHasExclusiveContent
// is true: in that case we can't add repos to buildscript.repositories ourselves (Gradle
// 9.3+ validation), so the only projects where our classpath dep can possibly resolve are
// the ones that already have their own buildscript repos. For all other projects we skip
// the injection entirely to avoid `Cannot resolve external dependency ... because no
// repositories are defined` failures that crash the whole Tooling API query (issue from
// 0.11.8 follow-up).
var composeAiPreviewProjectsWithOwnBuildscriptRepos: Set<java.io.File> = emptySet()

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

// Detects whether the build's settings file declares `exclusiveContent { ... }` inside
// `pluginManagement { repositories { ... } }`, either directly or by sharing repository handlers
// (e.g. Confetti's `listOf(repositories, dependencyResolutionManagement.repositories).forEach`
// pattern). Gradle 9.3+ rejects adding to `buildscript.repositories` from any project once that's
// in place, so we use this signal to skip our buildscript classpath injection wholesale.
//
// Detection is text-based — scanning a settings script for `exclusiveContent` references plus
// either a `pluginManagement` block or the listOf-with-pluginManagement-shared-repos pattern.
// We can't introspect the live RepositoryHandler reliably (Gradle's internal
// ExclusiveContentRepository wrapper isn't a stable API), but the text scan is robust enough:
// false positives just degrade auto-inject to a no-op in builds that didn't actually need it
// disabled.
fun composeAiPreviewSettingsDeclaresExclusiveContent(settingsDir: java.io.File): Boolean {
    val candidates = listOf(
        java.io.File(settingsDir, "settings.gradle.kts"),
        java.io.File(settingsDir, "settings.gradle"),
    )
    for (file in candidates) {
        if (!file.isFile) continue
        val raw = runCatching { file.readText() }.getOrNull() ?: continue
        val text = composeAiPreviewStripComments(raw)
        // Cheap early exit: no `exclusiveContent` anywhere → no risk.
        if (!Regex("\\bexclusiveContent\\b").containsMatchIn(text)) continue
        // The validation only fires for exclusiveContent in `pluginManagement.repositories`. The
        // common shapes:
        //   1. Direct: `pluginManagement { repositories { ... exclusiveContent { ... } ... } }`
        //   2. Shared: `pluginManagement { listOf(repositories,
        //      dependencyResolutionManagement.repositories).forEach { ... exclusiveContent ... } }`
        //   3. Indirect via a helper function called from `pluginManagement {}`
        // Walk the file looking for a `pluginManagement` block; if `exclusiveContent` appears
        // anywhere inside that block's braces, the conflict is live. We balance braces by simple
        // depth counting — string literals and other Kotlin-DSL niceties are out of scope, which
        // matches what we already do for the comment-stripper.
        var i = 0
        while (i < text.length) {
            val match =
                Regex("\\bpluginManagement\\b").find(text, i) ?: break
            // Skip to the opening brace, ignoring whitespace.
            var j = match.range.last + 1
            while (j < text.length && text[j].isWhitespace()) j++
            if (j >= text.length || text[j] != '{') {
                i = match.range.last + 1
                continue
            }
            // Brace-balance to find the matching close.
            var depth = 1
            var k = j + 1
            while (k < text.length && depth > 0) {
                when (text[k]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                k++
            }
            val blockEnd = if (depth == 0) k - 1 else text.length
            val block = text.substring(j + 1, blockEnd)
            if (Regex("\\bexclusiveContent\\b").containsMatchIn(block)) return true
            i = blockEnd + 1
        }
    }
    return false
}

// Returns project directories that declare their own `buildscript { repositories { ... } }`
// block. Brace-balance walks the build script: find `buildscript`, find its `{...}` body,
// then check whether that body contains a `repositories` declaration. Used exclusively in
// the exclusiveContent branch to decide where our classpath dep can possibly resolve.
fun scanForProjectsWithBuildscriptRepos(
    projectDirs: List<java.io.File>,
): Set<java.io.File> {
    val declared = LinkedHashSet<java.io.File>()
    for (dir in projectDirs) {
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val buildFile = java.io.File(dir, name)
            if (!buildFile.isFile) continue
            val raw = runCatching { buildFile.readText() }.getOrNull() ?: continue
            val text = composeAiPreviewStripComments(raw)
            var i = 0
            var found = false
            while (i < text.length && !found) {
                val match = Regex("\\bbuildscript\\b").find(text, i) ?: break
                var j = match.range.last + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j >= text.length || text[j] != '{') {
                    i = match.range.last + 1
                    continue
                }
                var depth = 1
                var k = j + 1
                while (k < text.length && depth > 0) {
                    when (text[k]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    k++
                }
                val blockEnd = if (depth == 0) k - 1 else text.length
                val block = text.substring(j + 1, blockEnd)
                if (Regex("\\brepositories\\b").containsMatchIn(block)) {
                    declared.add(dir)
                    found = true
                }
                i = blockEnd + 1
            }
            if (found) break
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
    composeAiPreviewSettingsHasExclusiveContent =
        composeAiPreviewSettingsDeclaresExclusiveContent(settingsDir)
    // Only walk the buildscript-repos scan when it matters — in the non-exclusiveContent
    // path we add repos ourselves, so we don't care whether the project already has any.
    if (composeAiPreviewSettingsHasExclusiveContent) {
        composeAiPreviewProjectsWithOwnBuildscriptRepos =
            scanForProjectsWithBuildscriptRepos(projectDirs)
    }

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

    // In the exclusiveContent shape we can't add repositories to buildscript.repositories
    // (Gradle 9.3+ rejects it; issues #1470, #1482), so projects that don't already have
    // their own buildscript repos have no way to resolve our classpath dep — adding it
    // there would only produce `Cannot resolve external dependency ... because no
    // repositories are defined` at configuration time, which short-circuits the entire
    // Tooling API query (the 0.11.8 regression). Skip the injection wholesale for those
    // projects; they silently miss the plugin, the same as projects that we explicitly
    // skip for KMP-Android, and the user's recourse is the same plugins { } DSL apply.
    val composeAiPreviewSkipExclusiveContentClasspathDep =
        composeAiPreviewSettingsHasExclusiveContent &&
            projectDir !in composeAiPreviewProjectsWithOwnBuildscriptRepos

    if (!composeAiPreviewSkipKmpAndroid &&
        !composeAiPreviewSkipExclusiveContentClasspathDep &&
        projectDir !in composeAiPreviewPreAppliedDirs) {
        buildscript {
            // When the settings file declares `exclusiveContent { ... }` in `pluginManagement {
            // repositories { ... } }`, Gradle 9.3+ rejects any attempt to *add* repositories to
            // `buildscript.repositories` from any project (issues #1470, #1482). We still add
            // the classpath dependency though — at this point we've confirmed the project has
            // its own buildscript { repositories { ... } } declared (via the scan above), so
            // resolution can succeed via those repos. Outside the exclusiveContent branch we
            // both add our repos and add the dep, the original auto-inject happy path.
            if (!composeAiPreviewSettingsHasExclusiveContent) {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                    google()
                    if (useMavenLocal) mavenLocal()
                }
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
    // No buildscript classpath dep was injected and the project doesn't pre-apply, so
    // `pluginManager.apply(...)` from the withPlugin hooks would fail with "Plugin with id
    // ... not found." Skipping the hooks keeps the failure mode quiet — non-preview
    // projects (e.g. Confetti's :backend) configure cleanly with no diagnostic noise.
    if (composeAiPreviewSkipExclusiveContentClasspathDep &&
        projectDir !in composeAiPreviewPreAppliedDirs) return@allprojects

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
internal fun materializeInitScript(
  storageDir: File,
  pluginVersion: String,
  fileSystem: FileSystem = SystemFileSystem,
): File {
  storageDir.mkdirs()
  val target = File(storageDir, INIT_SCRIPT_FILENAME)
  val desired = renderInitScript(pluginVersion)
  val existing =
    if (target.isFile)
      runCatching { fileSystem.read(target.path.toPath()) { readUtf8() } }.getOrNull()
    else null
  if (existing != desired) fileSystem.write(target.path.toPath()) { writeUtf8(desired) }
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
internal fun hasIncludedPluginBuild(
  projectRoot: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val candidates =
    listOf(File(projectRoot, "settings.gradle.kts"), File(projectRoot, "settings.gradle"))
  val pattern = Regex("""includeBuild\s*\(\s*["']gradle-plugin["']\s*\)""")
  return candidates.any {
    it.isFile && pattern.containsMatchIn(fileSystem.read(it.path.toPath()) { readUtf8() })
  }
}

/**
 * Mirrors the rendered init script's `composeAiPreviewSettingsDeclaresExclusiveContent`. Returns
 * `true` when [projectRoot]'s `settings.gradle[.kts]` declares `exclusiveContent { ... }` inside a
 * `pluginManagement { repositories { ... } }` block — directly, via shared repository handlers (the
 * Confetti pattern `listOf(repositories, dependencyResolutionManagement.repositories).forEach`), or
 * via a helper called from `pluginManagement {}`. Used as the off-side reproducer for the Gradle
 * 9.3+ "When using exclusive repository content in 'settings.pluginManagement .repositories', you
 * cannot add repositories to 'buildscript.repositories'" validation (issues #1470, #1482) — the
 * init script's `allprojects { buildscript { ... } }` injection would fail every project
 * configuration once that validation fires, so we skip injection wholesale.
 *
 * Visible for tests. Kept in lockstep with the embedded Kotlin function inside [renderInitScript].
 */
internal fun settingsDeclaresExclusiveContentInPluginManagement(
  projectRoot: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val candidates =
    listOf(File(projectRoot, "settings.gradle.kts"), File(projectRoot, "settings.gradle"))
  for (file in candidates) {
    if (!file.isFile) continue
    val raw =
      runCatching { fileSystem.read(file.path.toPath()) { readUtf8() } }.getOrNull() ?: continue
    val text = stripGradleComments(raw)
    if (!Regex("""\bexclusiveContent\b""").containsMatchIn(text)) continue
    var i = 0
    while (i < text.length) {
      val match = Regex("""\bpluginManagement\b""").find(text, i) ?: break
      var j = match.range.last + 1
      while (j < text.length && text[j].isWhitespace()) j++
      if (j >= text.length || text[j] != '{') {
        i = match.range.last + 1
        continue
      }
      var depth = 1
      var k = j + 1
      while (k < text.length && depth > 0) {
        when (text[k]) {
          '{' -> depth++
          '}' -> depth--
        }
        k++
      }
      val blockEnd = if (depth == 0) k - 1 else text.length
      val block = text.substring(j + 1, blockEnd)
      if (Regex("""\bexclusiveContent\b""").containsMatchIn(block)) return true
      i = blockEnd + 1
    }
  }
  return false
}

/**
 * Mirrors the rendered init script's `scanForProjectsWithBuildscriptRepos`. Returns true when
 * [projectDir]'s `build.gradle[.kts]` declares its own `buildscript { repositories { ... } }` block
 * — the only shape where our classpath dep can possibly resolve in the
 * `exclusiveContent`-in-`pluginManagement.repositories` branch. Brace-balances the script body to
 * scope the `repositories` check to the buildscript block (so an unrelated top-level `repositories
 * { ... }` block doesn't falsely flag the project).
 *
 * Visible for tests.
 */
internal fun projectHasBuildscriptRepositories(
  projectDir: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  for (name in listOf("build.gradle.kts", "build.gradle")) {
    val buildFile = File(projectDir, name)
    if (!buildFile.isFile) continue
    val raw =
      runCatching { fileSystem.read(buildFile.path.toPath()) { readUtf8() } }.getOrNull()
        ?: continue
    val text = stripGradleComments(raw)
    var i = 0
    while (i < text.length) {
      val match = Regex("""\bbuildscript\b""").find(text, i) ?: break
      var j = match.range.last + 1
      while (j < text.length && text[j].isWhitespace()) j++
      if (j >= text.length || text[j] != '{') {
        i = match.range.last + 1
        continue
      }
      var depth = 1
      var k = j + 1
      while (k < text.length && depth > 0) {
        when (text[k]) {
          '{' -> depth++
          '}' -> depth--
        }
        k++
      }
      val blockEnd = if (depth == 0) k - 1 else text.length
      val block = text.substring(j + 1, blockEnd)
      if (Regex("""\brepositories\b""").containsMatchIn(block)) return true
      i = blockEnd + 1
    }
  }
  return false
}

/**
 * Removes `// …` line comments and `/* … */` block comments from a Gradle build script before the
 * exclusiveContent / buildscript-repositories scanners look at it. Doesn't try to be a full Kotlin
 * / Groovy parser: enough to keep a commented-out example from triggering a false positive. String
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
