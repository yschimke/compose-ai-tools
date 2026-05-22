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
// Pre-applied detection: if any build file in the project tree declares the
// plugin with a version — either literal \`id("...") version "..."\` or via
// \`alias(libs.plugins.<x>)\` where the version catalog maps <x> to this
// plugin id — we skip the buildscript classpath injection below. Gradle's
// plugins {} DSL rejects \`id(...) version "..."\` when the same plugin is
// also on the buildscript classpath ("the plugin is already on the
// classpath with an unknown version, so compatibility cannot be checked"),
// and the user's own declaration provides resolution via plugin marker
// repos. The withPlugin hooks remain registered and no-op via the
// plugins.hasPlugin(...) guard.
//
// \`COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1\` opts the buildscript repos into
// \`mavenLocal()\` — mirrors the CLI's AutoInject.kt behavior. Useful for
// pointing the extension at a locally-published SNAPSHOT of the plugin
// during dev (e.g. \`./gradlew publishToMavenLocal\` against this repo, then
// launch VS Code with the flag set). Off by default so cached snapshots
// don't widen the search surface for normal users.

val pluginVersion = "${pluginVersion}"
val useMavenLocal = System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"

var composeAiPreviewPreApplied = false

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
): Boolean {
    val catalogAccessors = composeAiPreviewCatalogAccessors(rootDir)
    val literalVersionedRe = Regex(
        "\\\\bid\\\\s*[(\\\\s]\\\\s*[\\"']ee\\\\.schimke\\\\.composeai\\\\.preview[\\"']\\\\s*\\\\)?\\\\s*(?:\\\\.\\\\s*)?version\\\\b"
    )
    for (dir in projectDirs) {
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val buildFile = java.io.File(dir, name)
            if (!buildFile.isFile) continue
            val raw = runCatching { buildFile.readText() }.getOrNull() ?: continue
            val text = composeAiPreviewStripComments(raw)
            if (literalVersionedRe.containsMatchIn(text)) return true
            for (re in catalogAccessors) {
                if (re.containsMatchIn(text)) return true
            }
        }
    }
    return false
}

gradle.settingsEvaluated {
    val projectDirs = mutableListOf<java.io.File>()
    fun collect(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
        projectDirs.add(descriptor.projectDir)
        descriptor.children.forEach { collect(it) }
    }
    collect(rootProject)
    composeAiPreviewPreApplied = scanForComposeAiPreviewDeclaration(rootDir, projectDirs)

    // When opting into mavenLocal, also seed it at the settings level so the renderer-android AAR
    // and any other ee.schimke.composeai:* runtime artifacts resolve from ~/.m2 alongside the
    // plugin classpath. Consumers with \`RepositoriesMode.FAIL_ON_PROJECT_REPOS\` refuse per-project
    // repos, so settings-level seeding is the only path that survives. pluginManagement.repositories
    // .mavenLocal() covers the catalog-alias / literal-\`id(...) version "..."\` case where resolution
    // goes through the plugins DSL instead of our buildscript classpath injection.
    //
    // Gradle only auto-adds the default Plugin Portal when \`pluginManagement.repositories\` is empty
    // after settings evaluation — once we explicitly add \`mavenLocal()\` the list is non-empty and
    // the default is suppressed, so a \`build-logic\` module that relies on the implicit default for
    // \`kotlin-dsl\` (resolved via plugin marker from the Gradle Plugin Portal) fails. Restore those
    // defaults explicitly when the build didn't declare any plugin repos of its own.
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
    if (!composeAiPreviewPreApplied) {
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
                    "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:$pluginVersion",
                )
            }
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
