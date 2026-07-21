import * as fs from "fs";
import * as path from "path";
import * as crypto from "crypto";
import { BUNDLED_PLUGIN_VERSION } from "./version.generated";

/**
 * Bundles a Gradle init script that auto-applies the Compose Preview plugin
 * onto Android / Compose Multiplatform projects, so users don't have to edit
 * their `build.gradle.kts` to opt in. The extension hands the script to
 * Gradle via `--init-script <path>` on every invocation (see
 * `GradleService.argsProvider`).
 *
 * Stays in sync with the CLI's `cli/.../AutoInject.kt::renderInitScript` —
 * same body shape, same pre-applied detector, same withPlugin hooks. CI's
 * integration matrix drives it via `compose-preview init-script --path` so
 * external-repo runs exercise the production code path rather than a CI-only
 * variant.
 *
 * The bundled plugin coordinate (`BUNDLED_PLUGIN_VERSION`) is generated at
 * compile time from `.release-please-manifest.json` (or the `PLUGIN_VERSION`
 * env override in release builds) — mirrors the CLI's
 * `generateCliVersionResource` Gradle task. See `scripts/generate-version.mjs`.
 */

export { BUNDLED_PLUGIN_VERSION };

export const INIT_SCRIPT_FILENAME = "apply-compose-ai-preview.init.gradle.kts";

/**
 * Renders the Kotlin-DSL init-script body with [pluginVersion] baked in.
 * Pure function so unit tests can assert the wire shape without going
 * through the filesystem.
 */
export function renderInitScript(
    pluginVersion: string = BUNDLED_PLUGIN_VERSION,
): string {
    return `// Compose Preview auto-inject init script.
//
// Materialised by the Compose Preview VS Code extension and passed via
// --init-script on every Gradle invocation the extension makes. Applies
// ee.schimke.composeai.preview (version pinned to ${pluginVersion}) to every
// project that already applies com.android.application,
// com.android.library, or org.jetbrains.compose — so consumers don't have
// to edit their build files.
//
// Application uses pluginManager.withPlugin(...) (not afterEvaluate) so
// AGP finalizeDsl / onVariants callbacks register before the DSL lock.
//
// Pre-applied detection is *per project*: for each subproject whose build
// file declares the plugin with a version — either literal
// \`id("...") version "..."\` or via \`alias(libs.plugins.<x>)\` where the
// version catalog maps <x> to this plugin id — we skip the buildscript
// classpath injection for that project. Gradle's plugins {} DSL rejects
// \`id(...) version "..."\` when the same plugin is also on the buildscript
// classpath ("the plugin is already on the classpath with an unknown
// version, so compatibility cannot be checked"), and the user's own
// declaration provides resolution via plugin marker repos. Projects that
// don't declare the plugin themselves still get the buildscript classpath
// injection so the withPlugin / pluginManager.apply path can find the
// plugin class — this is what mixed-shape multi-module projects need
// (e.g. an \`:app\` module that applies the plugin via catalog alias, plus
// a sibling \`:rc-components\` android-library module that doesn't; the
// init script's withPlugin("com.android.library") hook fires in
// rc-components too and the plugin must be resolvable from its buildscript
// classpath). The withPlugin hooks in projects that already apply the
// plugin no-op via the plugins.hasPlugin(...) guard.
//
// \`mavenLocal()\` is added to the buildscript / settings repos automatically
// when \`pluginVersion\` ends in \`-SNAPSHOT\` — mirrors the CLI's AutoInject.kt
// behavior. The only place an unpublished SNAPSHOT plugin can live is
// \`~/.m2\` (\`./gradlew publishToMavenLocal\` against this repo), so a SNAPSHOT
// build that doesn't seed it is unusable. Released versions leave \`~/.m2\`
// alone: the plugin is on Plugin Portal / Maven Central, and widening the
// search surface to local snapshots would only invite version skew.
// \`COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1\` still forces the seed on for
// non-SNAPSHOT runs.

// Gradle 9.3+ rejects adding to \`buildscript.repositories\` once
// \`exclusiveContent { ... }\` is declared in \`settings.pluginManagement
// .repositories\` (directly, or shared via \`listOf(repositories,
// dependencyResolutionManagement.repositories).forEach\` — the Confetti shape;
// issue #1482). So in that shape a module without its own \`buildscript {
// repositories { ... } }\` has no repository to resolve our classpath
// coordinate from. Rather than drop auto-inject there (which silently left
// e.g. Confetti's \`:androidApp\` plugin-less), we resolve the plugin's
// classpath through the project's own (settings-managed) repositories via a
// detached configuration and inject the resolved JARs with \`classpath(files(
// ...))\` — that lands the plugin on the module's OWN buildscript classloader,
// alongside AGP (unlike an initscript/settings/parent classloader, which
// can't see AGP types — the reason the \`initscript { classpath }\` route was
// reverted in #1483), and never touches \`buildscript.repositories\`.

import org.gradle.api.configuration.BuildFeatures
import org.gradle.kotlin.dsl.support.serviceOf

val pluginVersion = "${pluginVersion}"
val useMavenLocal = pluginVersion.endsWith("-SNAPSHOT") ||
    System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"

var composeAiPreviewPreAppliedDirs: Set<java.io.File> = emptySet()
var composeAiPreviewSettingsHasExclusiveContent: Boolean = false
var composeAiPreviewProjectsWithOwnBuildscriptRepos: Set<java.io.File> = emptySet()

fun composeAiPreviewCatalogAccessors(rootDir: java.io.File): List<Regex> {
    val catalog = java.io.File(rootDir, "gradle/libs.versions.toml")
    if (!catalog.isFile) return emptyList()
    val text = runCatching { catalog.readText() }.getOrNull() ?: return emptyList()
    val pluginsHeader = Regex("(?m)^\\\\[plugins\\\\]\\\\s*$").find(text) ?: return emptyList()
    val sectionStart = pluginsHeader.range.last + 1
    val nextSection = Regex("(?m)^\\\\[").find(text, sectionStart)
    val section = text.substring(sectionStart, nextSection?.range?.first ?: text.length)
    val entryRe = Regex(
        "(?m)^[ \\\\t]*([A-Za-z0-9_.\\\\-]+)\\\\s*=\\\\s*(?:" +
            "\\\\{[^}]*\\\\bid\\\\s*=\\\\s*\\"ee\\\\.schimke\\\\.composeai\\\\.preview\\"[^}]*\\\\}|" +
            "\\"ee\\\\.schimke\\\\.composeai\\\\.preview(?::[^\\"]*)?\\"" +
            ")"
    )
    return entryRe.findAll(section).map { match ->
        val accessor = match.groupValues[1].replace(Regex("[-_]"), ".")
        Regex("\\\\blibs\\\\s*\\\\.\\\\s*plugins\\\\s*\\\\.\\\\s*" + Regex.escape(accessor) + "\\\\b")
    }.toList()
}

// Strips // line comments and /* */ block comments before the regex match so a
// commented-out example like \`// id("ee.schimke.composeai.preview") version "..."\`
// (or \`// alias(libs.plugins.compose.preview)\`) doesn't get treated as a real
// declaration and disable classpath injection for projects that actually need
// auto-inject. String-literal tracking is intentionally out of scope — Gradle
// scripts rarely embed a comment-prefix in a string in a way that matters here.
fun composeAiPreviewStripComments(source: String): String {
    val sb = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        if (c == '/' && next == '/') {
            val newline = source.indexOf('\\n', i)
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
        "\\\\bid\\\\s*[(\\\\s]\\\\s*[\\"']ee\\\\.schimke\\\\.composeai\\\\.preview[\\"']\\\\s*\\\\)?\\\\s*(?:\\\\.\\\\s*)?version\\\\b"
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

// Text-based detector for \`exclusiveContent\` inside a \`pluginManagement { repositories { ... } }\`
// block (directly or via the Confetti-style \`listOf(repositories, dependencyResolutionManagement
// .repositories).forEach\` pattern). Used to skip the buildscript injection altogether when
// Gradle 9.3+'s exclusiveContent-vs-buildscript-repos validation would otherwise fire.
fun composeAiPreviewSettingsDeclaresExclusiveContent(settingsDir: java.io.File): Boolean {
    val candidates = listOf(
        java.io.File(settingsDir, "settings.gradle.kts"),
        java.io.File(settingsDir, "settings.gradle"),
    )
    for (file in candidates) {
        if (!file.isFile) continue
        val raw = runCatching { file.readText() }.getOrNull() ?: continue
        val text = composeAiPreviewStripComments(raw)
        if (!Regex("\\\\bexclusiveContent\\\\b").containsMatchIn(text)) continue
        var i = 0
        while (i < text.length) {
            val match = Regex("\\\\bpluginManagement\\\\b").find(text, i) ?: break
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
            if (Regex("\\\\bexclusiveContent\\\\b").containsMatchIn(block)) return true
            i = blockEnd + 1
        }
    }
    return false
}

// Returns project directories that declare their own \`buildscript { repositories { ... } }\`
// block. Used in the exclusiveContent branch to decide where our classpath dep can possibly
// resolve — modules without their own buildscript repos can't resolve it and would crash
// the whole Tooling API query at configuration time.
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
                val match = Regex("\\\\bbuildscript\\\\b").find(text, i) ?: break
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
                if (Regex("\\\\brepositories\\\\b").containsMatchIn(block)) {
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

// Skip composite-included builds entirely — both the settings scan and the \`allprojects\`
// injection. The init script is evaluated once per build in a composite (root + each
// \`includeBuild(...)\`), so without this guard \`allprojects { buildscript { repositories { ... } } }\`
// fires for the included build's projects too. That breaks any included build whose
// \`pluginManagement.repositories\` declares \`exclusiveContent { ... }\`: Gradle 9.3+ rejects
// adding to \`buildscript.repositories\` once exclusiveContent is in
// \`settings.pluginManagement.repositories\` (e.g. Confetti's \`:build-logic\`). Included builds in
// this pattern are conventionally plugin builds (\`build-logic\`, \`gradle-conventions\`) that don't
// host \`@Preview\` composables, so injecting the plugin classpath there is unnecessary — and the
// existing pre-applied scan only walks the *root* build's project hierarchy anyway, so
// included-build projects were never tracked. An included build's \`Gradle\` instance has a
// non-null \`parent\`; the root build's is \`null\`.
val composeAiPreviewIsIncludedBuild = gradle.parent != null

gradle.settingsEvaluated {
    if (composeAiPreviewIsIncludedBuild) return@settingsEvaluated

    // The \`allprojects { buildscript { … } }\` injection below is a cross-project configuration
    // that Isolated Projects forbids ("Project ':' cannot access 'Project.buildscript' … via
    // 'allprojects'"), so auto-inject can't run under IP. settingsEvaluated fires before that
    // violation, so this is the one place a warning is reliably delivered to the user.
    val composeAiPreviewIpActive =
        runCatching { gradle.serviceOf<BuildFeatures>().isolatedProjects.active.get() }
            .getOrDefault(false)
    if (composeAiPreviewIpActive) {
        logger.warn(
            "compose-preview: Isolated Projects is enabled " +
                "(org.gradle.unsafe.isolated-projects=true). Auto-inject configures projects via " +
                "\`allprojects { }\`, which Isolated Projects rejects, so discovery/render will fail. " +
                "Disable Isolated Projects for compose-preview runs " +
                "(e.g. -Dorg.gradle.unsafe.isolated-projects=false), or apply " +
                "id(\\"ee.schimke.composeai.preview\\") manually in each module's build script."
        )
    }

    val projectDirs = mutableListOf<java.io.File>()
    fun collect(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
        projectDirs.add(descriptor.projectDir)
        descriptor.children.forEach { collect(it) }
    }
    collect(rootProject)
    composeAiPreviewPreAppliedDirs = scanForComposeAiPreviewDeclaration(rootDir, projectDirs)
    composeAiPreviewSettingsHasExclusiveContent =
        composeAiPreviewSettingsDeclaresExclusiveContent(settingsDir)
    if (composeAiPreviewSettingsHasExclusiveContent) {
        composeAiPreviewProjectsWithOwnBuildscriptRepos =
            scanForProjectsWithBuildscriptRepos(projectDirs)
    }

    // When opting into mavenLocal, also seed it at the settings level so the renderer-android AAR
    // and any other ee.schimke.composeai:* runtime artifacts resolve from ~/.m2 alongside the
    // plugin classpath. Consumers with \`RepositoriesMode.FAIL_ON_PROJECT_REPOS\` refuse per-project
    // repos, so settings-level seeding is the only path that survives. pluginManagement.repositories
    // .mavenLocal() covers the catalog-alias / literal-\`id(...) version "..."\` case where resolution
    // goes through the plugins DSL instead of our buildscript classpath injection.
    //
    // Gradle only auto-adds the default Plugin Portal when \`pluginManagement.repositories\` is empty
    // after settings evaluation — once we explicitly add \`mavenLocal()\` the list is non-empty and
    // the default is suppressed, so restore the defaults explicitly when the build didn't declare
    // any plugin repos of its own.
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

// Resolves the compose-preview plugin's buildscript classpath — the plugin
// marker, its implementation JAR, and their transitive deps — through THIS
// project's own repositories (the settings-level dependencyResolutionManagement
// repos in the exclusiveContent shape, where \`mavenLocal()\` / the plugin repo
// were seeded above). Returned as raw files so the caller can inject them via
// \`buildscript { dependencies { classpath(files(...)) } }\` WITHOUT adding to
// \`buildscript.repositories\` — the add Gradle 9.3+ forbids under exclusiveContent
// (#1482). A detached configuration resolves against the project's repository
// handler, so it is unaffected by that validation. Empty set on any resolution
// failure (e.g. a released plugin whose coordinate isn't in the consumer's
// project repos) so the caller degrades to a no-op instead of crashing the
// Tooling API model query.
var composeAiPreviewCachedPluginClasspath: Set<java.io.File>? = null

fun org.gradle.api.Project.composeAiPreviewResolvePluginClasspath(): Set<java.io.File> {
    // The resolved classpath is identical across every module (same coordinate,
    // same settings-managed repos), so memoise the first successful resolution
    // and reuse it — a large multi-module build then resolves once, not once
    // per Android module. Only cache on success so a module that transiently
    // can't resolve doesn't poison the memo.
    composeAiPreviewCachedPluginClasspath?.let { return it }
    val composeAiPreviewMarker =
        dependencies.create(
            "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:$pluginVersion"
        )
    val composeAiPreviewResolved =
        runCatching {
            configurations.detachedConfiguration(composeAiPreviewMarker).files.toSet()
        }.getOrDefault(emptySet())
    if (composeAiPreviewResolved.isNotEmpty()) {
        composeAiPreviewCachedPluginClasspath = composeAiPreviewResolved
    }
    return composeAiPreviewResolved
}

allprojects {
    if (composeAiPreviewIsIncludedBuild) return@allprojects

    // In the exclusiveContent shape, Gradle 9.3+ rejects *adding* to
    // buildscript.repositories (#1482), so a module without its own \`buildscript {
    // repositories { ... } }\` can't resolve our classpath coordinate. For those
    // modules resolve the plugin classpath ourselves (through the project's
    // settings-managed repos) and inject the files — see
    // composeAiPreviewResolvePluginClasspath above. Modules that DO declare their
    // own buildscript repos still take the plain coordinate path below (their
    // repos resolve it).
    val composeAiPreviewNeedsResolvedClasspathInject =
        composeAiPreviewSettingsHasExclusiveContent &&
            projectDir !in composeAiPreviewProjectsWithOwnBuildscriptRepos

    // Gradle inherits a project's buildscript classpath into its subprojects' \`plugins { }\`
    // resolution, so injecting the plugin onto an ANCESTOR of a module that applies it via the
    // versioned plugins DSL (\`id("...") version "..."\` / \`alias(libs.plugins.<x>)\`) makes that
    // subproject fail with "the plugin is already on the classpath with an unknown version" — which
    // sinks its configuration and makes the CLI/VS Code model query return zero modules (issue
    // #1855). Skip injection (and the apply hooks) for any project that has a pre-applied
    // descendant — the root especially — since that classpath is the one that leaks in.
    val composeAiPreviewHasPreAppliedDescendant =
        subprojects.any { it.projectDir in composeAiPreviewPreAppliedDirs }

    val composeAiPreviewIsPreApplied = projectDir in composeAiPreviewPreAppliedDirs

    if (!composeAiPreviewIsPreApplied && !composeAiPreviewHasPreAppliedDescendant) {
        if (composeAiPreviewNeedsResolvedClasspathInject) {
            // exclusiveContent + no own buildscript repos: resolve + inject files()
            // so the plugin lands on this module's buildscript classloader without
            // touching buildscript.repositories. Empty means we couldn't resolve it
            // (e.g. released plugin not in the consumer's repos) — degrade to a
            // no-op, exactly as this branch did before it learned to inject.
            val composeAiPreviewClasspath = composeAiPreviewResolvePluginClasspath()
            if (composeAiPreviewClasspath.isEmpty()) return@allprojects
            val composeAiPreviewClasspathFiles = files(composeAiPreviewClasspath)
            buildscript {
                dependencies {
                    add("classpath", composeAiPreviewClasspathFiles)
                }
            }
        } else {
            buildscript {
                // Only add repositories when the settings don't declare
                // exclusiveContent — Gradle 9.3+ rejects the add otherwise (#1482).
                // In the exclusiveContent-with-own-repos case the module's own
                // buildscript repositories resolve the coordinate.
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
                        "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:$pluginVersion",
                    )
                }
            }
        }
    }

    // Skip the apply hooks only for an ancestor of a pre-applied module (its
    // injection was skipped above, and applying here would leak into the
    // descendant's classpath). A pre-applied module keeps its hooks — they no-op
    // via the hasPlugin guard.
    if (composeAiPreviewHasPreAppliedDescendant && !composeAiPreviewIsPreApplied) {
        return@allprojects
    }

    fun applyComposeAiPreview() {
        if (plugins.hasPlugin("ee.schimke.composeai.preview")) return
        pluginManager.apply("ee.schimke.composeai.preview")
    }

    pluginManager.withPlugin("com.android.application") { applyComposeAiPreview() }
    pluginManager.withPlugin("com.android.library") { applyComposeAiPreview() }
    pluginManager.withPlugin("org.jetbrains.compose") { applyComposeAiPreview() }
}
`;
}

/**
 * Writes the rendered init script into [storageDir] iff its contents differ
 * from what's already there. Returns the absolute path Gradle should receive
 * via `--init-script`. Idempotent: re-running with the same plugin version
 * leaves the file untouched (and its mtime, which keeps Gradle's
 * configuration cache happy).
 *
 * Failures (storage dir not writable, disk full) propagate to the caller so
 * activation can downgrade to "no auto-inject" rather than silently passing
 * a nonexistent path to Gradle.
 */
export function materializeInitScript(
    storageDir: string,
    pluginVersion: string = BUNDLED_PLUGIN_VERSION,
): string {
    fs.mkdirSync(storageDir, { recursive: true });
    const target = path.join(storageDir, INIT_SCRIPT_FILENAME);
    const desired = renderInitScript(pluginVersion);
    let existing: string | null = null;
    try {
        existing = fs.readFileSync(target, "utf-8");
    } catch {
        /* first write, or unreadable — fall through and rewrite */
    }
    if (existing !== desired) {
        fs.writeFileSync(target, desired, "utf-8");
    }
    return target;
}

/** Stable digest of the rendered script — useful for telemetry-free
 *  cache invalidation on tests that want to assert the script changed. */
export function initScriptDigest(
    pluginVersion: string = BUNDLED_PLUGIN_VERSION,
): string {
    return crypto
        .createHash("sha256")
        .update(renderInitScript(pluginVersion))
        .digest("hex")
        .slice(0, 16);
}

/**
 * True when [workspaceRoot]'s `settings.gradle[.kts]` declares
 * `includeBuild("gradle-plugin")` AND that included build actually publishes
 * `ee.schimke.composeai.preview` — the compose-ai-tools repo's own dev-loop
 * layout. Stacking a Maven-resolved classpath dep (auto-inject) on top of an
 * included build that already provides `ee.schimke.composeai.preview` makes
 * Gradle compile the consumer's build script against the published version
 * baked into the extension, while the included build's local source provides
 * a different (potentially newer) shape — so a build file that references a
 * property added since that published version fails with "Unresolved
 * reference". Skipping auto-inject in this case lets the included build win.
 *
 * The sentinel check (looking for the plugin id inside
 * `gradle-plugin/build.gradle.kts`) is the issue #1362 narrowing: previously
 * any workspace that happened to nest a local build-logic module under the
 * conventional name `gradle-plugin` matched this guard and the extension
 * silently dropped `--init-script`, regressing preview rendering for users
 * who don't manually apply the plugin. The compose-ai-tools repo is the only
 * shape that pairs that include name with the compose-preview plugin id, so
 * requiring both keeps the dev-loop carve-out without false-positiving on
 * unrelated workspaces.
 *
 * Mirrors the CLI's `hasIncludedPluginBuild` in `cli/.../AutoInject.kt`.
 */
export function hasIncludedPluginBuild(workspaceRoot: string): boolean {
    const candidates = [
        path.join(workspaceRoot, "settings.gradle.kts"),
        path.join(workspaceRoot, "settings.gradle"),
    ];
    const pattern = /includeBuild\s*\(\s*["']gradle-plugin["']\s*\)/;
    let includedBuildDeclared = false;
    for (const file of candidates) {
        let text: string;
        try {
            text = fs.readFileSync(file, "utf-8");
        } catch {
            continue;
        }
        if (pattern.test(text)) {
            includedBuildDeclared = true;
            break;
        }
    }
    if (!includedBuildDeclared) return false;

    // Sentinel: the included build must actually publish the compose-preview
    // plugin id. Look at both build-script flavours so a Groovy-DSL
    // gradle-plugin module is still recognised. Reading is best-effort — a
    // missing/unreadable build script means it's not the compose-ai-tools
    // shape, so auto-inject should stay on.
    const sentinelCandidates = [
        path.join(workspaceRoot, "gradle-plugin", "build.gradle.kts"),
        path.join(workspaceRoot, "gradle-plugin", "build.gradle"),
    ];
    for (const file of sentinelCandidates) {
        let text: string;
        try {
            text = fs.readFileSync(file, "utf-8");
        } catch {
            continue;
        }
        if (text.includes("ee.schimke.composeai.preview")) return true;
    }
    return false;
}
