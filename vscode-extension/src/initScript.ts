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
// --init-script on every Gradle invocation the extension makes. Loads
// ee.schimke.composeai.preview (version pinned to ${pluginVersion}) into
// the init-script classloader so every project that already applies
// com.android.application, com.android.library, or org.jetbrains.compose
// can have it applied via \`pluginManager.apply(...)\` without us ever
// mutating that project's \`buildscript.repositories\` — Gradle 9.3+
// rejects adding to \`buildscript.repositories\` once any settings file in
// the composite declares \`exclusiveContent { ... }\` inside
// \`pluginManagement.repositories\` (issues #1470, #1482). The init-script
// classpath sits on a parent classloader of every project's plugin
// classloader, so \`pluginManager.apply\` resolves the plugin via its
// META-INF/gradle-plugins descriptor without touching any project repo
// list at all.
//
// Application uses pluginManager.withPlugin(...) (not afterEvaluate) so
// AGP finalizeDsl / onVariants callbacks register before the DSL lock.
//
// Pre-applied detection is *per project*: for each subproject whose build
// file declares the plugin with a version — either literal
// \`id("...") version "..."\` or via \`alias(libs.plugins.<x>)\` where the
// version catalog maps <x> to this plugin id — we skip the withPlugin
// apply hooks entirely. The user's own \`plugins { }\` block resolves and
// applies the plugin from a project-scoped classloader (a child of the
// init-script one); double-applying via our hook would risk a duplicate-
// application error or class-identity confusion across classloaders. The
// \`plugins.hasPlugin(...)\` guard inside applyComposeAiPreview() is the
// defence-in-depth backstop for non-versioned apply forms.
//
// \`COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1\` opts the init-script's
// plugin-resolution repos into \`mavenLocal()\` — mirrors the CLI's
// AutoInject.kt behavior. Useful for pointing the extension at a locally-
// published SNAPSHOT of the plugin during dev (e.g. \`./gradlew
// publishToMavenLocal\` against this repo, then launch VS Code with the
// flag set). Off by default so cached snapshots don't widen the search
// surface for normal users.

initscript {
    val useMavenLocal = System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        if (useMavenLocal) mavenLocal()
    }
    dependencies {
        classpath("ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:${pluginVersion}")
    }
}

val useMavenLocal = System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"

var composeAiPreviewPreAppliedDirs: Set<java.io.File> = emptySet()

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

// Skip composite-included builds entirely — both the settings scan and the \`allprojects\`
// hooks. The init script is evaluated once per build in a composite (root + each
// \`includeBuild(...)\`), so an unguarded \`allprojects { ... }\` fires for the included build's
// projects too. Included builds in the conventional pattern (\`build-logic\`,
// \`gradle-conventions\`) don't host \`@Preview\` composables, so applying the plugin there is
// wasteful, and the pre-applied scan only walks the *root* build's project hierarchy anyway.
// With the init-script classpath approach this is no longer load-bearing for Gradle 9.3+'s
// \`exclusiveContent\` validation (issue #1482) — we never touch \`buildscript.repositories\`
// anywhere — but the guard stays as defence-in-depth and to skip pointless work in plugin-
// only builds. An included build's \`Gradle\` instance has a non-null \`parent\`; the root
// build's is \`null\`.
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

    // When opting into mavenLocal, seed it at the settings level so the renderer-android AAR
    // and any other ee.schimke.composeai:* runtime artifacts resolve from ~/.m2 at task-
    // execution time. The plugin class itself comes from the init-script classloader, so this
    // only matters for the runtime artifacts — but consumers with
    // \`RepositoriesMode.FAIL_ON_PROJECT_REPOS\` refuse per-project repos, so settings-level
    // seeding is the only path that survives. pluginManagement.repositories.mavenLocal()
    // covers the catalog-alias / literal-\`id(...) version "..."\` case where the user resolves
    // the plugin via the plugins DSL instead of relying on our init-script classpath.
    //
    // Gradle only auto-adds the default Plugin Portal when \`pluginManagement.repositories\` is
    // empty after settings evaluation — once we explicitly add \`mavenLocal()\` the list is
    // non-empty and the default is suppressed, so restore the defaults explicitly when the
    // build didn't declare any plugin repos of its own.
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
    // Skip the apply hooks for projects that already declare the plugin themselves. The
    // user's \`plugins { id("...") version "..." }\` resolves the plugin from a project-scoped
    // classloader; double-applying via our hook would risk class-identity confusion across
    // classloaders. The hasPlugin() guard inside applyComposeAiPreview() is the defence-in-
    // depth backstop for non-versioned apply forms the scanner doesn't catch.
    if (projectDir in composeAiPreviewPreAppliedDirs) return@allprojects

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
