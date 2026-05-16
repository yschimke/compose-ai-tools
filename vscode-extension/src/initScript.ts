import * as fs from "fs";
import * as path from "path";
import * as crypto from "crypto";

/**
 * Bundles a Gradle init script that auto-applies the Compose Preview plugin
 * onto Android / Compose Multiplatform projects, so users don't have to edit
 * their `build.gradle.kts` to opt in. The extension hands the script to
 * Gradle via `--init-script <path>` on every invocation (see
 * `GradleService.argsProvider`).
 *
 * The script mirrors the CI variant at `.github/ci/apply-compose-ai.init.gradle.kts`
 * with one difference: production deployments resolve the plugin from Maven
 * Central rather than `mavenLocal()`, and the on/off env-var gate
 * (`COMPOSE_AI_TOOLS`) is dropped — when the extension passes the script it
 * always intends to apply.
 */

// x-release-please-start-version
/**
 * Plugin coordinate this extension build ships with. Bumped in lockstep with
 * `release-please` so the init script always resolves a release that exists
 * on Maven Central. SNAPSHOT-only consumers can still apply the plugin via
 * their own `build.gradle.kts`; the auto-inject path is for the common
 * "happy-path" release flow.
 */
export const BUNDLED_PLUGIN_VERSION = "0.10.10";
// x-release-please-end

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

val pluginVersion = "${pluginVersion}"

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

fun scanForComposeAiPreviewDeclaration(rootDir: java.io.File): Boolean {
    val catalogAccessors = composeAiPreviewCatalogAccessors(rootDir)
    val literalVersionedRe = Regex(
        "\\\\bid\\\\s*[(\\\\s]\\\\s*[\\"']ee\\\\.schimke\\\\.composeai\\\\.preview[\\"']\\\\s*\\\\)?\\\\s*(?:\\\\.\\\\s*)?version\\\\b"
    )
    val skipDirs = setOf("build", ".gradle", ".git", "node_modules", "out", ".idea")
    fun scan(dir: java.io.File, depth: Int): Boolean {
        if (depth > 6) return false
        val children = dir.listFiles() ?: return false
        for (child in children) {
            if (child.isFile && (child.name == "build.gradle.kts" || child.name == "build.gradle")) {
                val text = runCatching { child.readText() }.getOrNull() ?: continue
                if (literalVersionedRe.containsMatchIn(text)) return true
                for (re in catalogAccessors) {
                    if (re.containsMatchIn(text)) return true
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
    return scan(rootDir, 0)
}

gradle.settingsEvaluated {
    composeAiPreviewPreApplied = scanForComposeAiPreviewDeclaration(rootDir)
}

allprojects {
    if (!composeAiPreviewPreApplied) {
        buildscript {
            repositories {
                gradlePluginPortal()
                mavenCentral()
                google()
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
