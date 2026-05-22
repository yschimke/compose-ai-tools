import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    BUNDLED_PLUGIN_VERSION,
    INIT_SCRIPT_FILENAME,
    hasIncludedPluginBuild,
    initScriptDigest,
    materializeInitScript,
    renderInitScript,
} from "../initScript";

function withTempDir(
    fn: (dir: string) => void | Promise<void>,
): () => Promise<void> {
    return async () => {
        const dir = fs.mkdtempSync(
            path.join(os.tmpdir(), "compose-preview-test-"),
        );
        try {
            await fn(dir);
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    };
}

describe("renderInitScript", () => {
    it("bakes the pinned plugin version into the script", () => {
        const script = renderInitScript("9.9.9-test");
        assert.match(
            script,
            /val pluginVersion = "9\.9\.9-test"/,
            "expected the plugin version to be interpolated",
        );
        assert.match(
            script,
            /ee\.schimke\.composeai\.preview:ee\.schimke\.composeai\.preview\.gradle\.plugin:\$pluginVersion/,
            "expected the buildscript classpath coordinate to reference the version variable",
        );
    });

    it("falls back to BUNDLED_PLUGIN_VERSION when no argument is given", () => {
        const script = renderInitScript();
        assert.ok(
            script.includes(`val pluginVersion = "${BUNDLED_PLUGIN_VERSION}"`),
        );
    });

    it("applies the plugin via withPlugin on each injectable host id", () => {
        const script = renderInitScript();
        for (const id of [
            "com.android.application",
            "com.android.library",
            "org.jetbrains.compose",
        ]) {
            // Substring assertion — avoids the regex-escape bookkeeping
            // CodeQL flagged for an incomplete `.replace(/\./g, ...)` that
            // missed `\`, `*`, and friends. Plugin ids never carry regex
            // metachars beyond `.`, but the static analyzer can't see that.
            assert.ok(
                script.includes(
                    `pluginManager.withPlugin("${id}") { applyComposeAiPreview() }`,
                ),
                `expected withPlugin hook for ${id}`,
            );
        }
    });

    it("guards against double-apply with hasPlugin check", () => {
        const script = renderInitScript();
        assert.match(
            script,
            /if \(plugins\.hasPlugin\("ee\.schimke\.composeai\.preview"\)\) return/,
        );
    });

    it("gates the buildscript classpath injection on pre-applied detection", () => {
        // When the consumer already declares the plugin (via plugins {} with
        // version, or `alias(libs.plugins.<x>)`), unconditionally injecting
        // the plugin onto the buildscript classpath makes Gradle reject the
        // user's plugins block with "plugin already on the classpath with an
        // unknown version". The init script must scan the project tree and
        // skip injection when any build file declares the plugin with a
        // version.
        const script = renderInitScript();
        assert.ok(
            script.includes("var composeAiPreviewPreApplied = false"),
            "expected the pre-applied flag declaration",
        );
        assert.ok(
            script.includes(
                "composeAiPreviewPreApplied = scanForComposeAiPreviewDeclaration(rootDir, projectDirs)",
            ),
            "expected the flag to be set during settingsEvaluated",
        );
        assert.ok(
            script.includes("if (!composeAiPreviewPreApplied) {"),
            "expected the buildscript block to be guarded by the flag",
        );
        // Catalog alias resolution: the scanner must look at gradle/libs.versions.toml
        // so that `alias(libs.plugins.<x>)` references are detected.
        assert.ok(
            script.includes("gradle/libs.versions.toml"),
            "expected the catalog accessor scanner to read libs.versions.toml",
        );
    });

    it("limits the scan to included project descriptors, not the filesystem", () => {
        // Codex P1 review on PR #1183: an unrelated nested build (e.g., a tooling
        // build or sample app checked into the workspace but not part of this
        // settings file) must not flip the pre-applied flag. The scan walks
        // settings.rootProject's descriptor tree so only modules included by
        // this build are inspected.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                "fun collect(descriptor: org.gradle.api.initialization.ProjectDescriptor)",
            ),
            "expected a recursive collect() over ProjectDescriptor children",
        );
        assert.ok(
            script.includes("collect(rootProject)"),
            "expected the scan to seed from settings.rootProject",
        );
        // The old filesystem-walk implementation referenced skipDirs to avoid
        // descending into build/.gradle/etc. The new descriptor-based walk
        // doesn't need it — assert the legacy artefact is gone so a future
        // regression that reintroduces the broad filesystem walk fails loudly.
        assert.ok(
            !script.includes('"node_modules"'),
            "expected the filesystem-walk skipDirs set to be gone",
        );
    });

    it("strips comments before matching so commented-out declarations don't count", () => {
        // Codex P2 review on PR #1183: a documentation line like
        // `// id("ee.schimke.composeai.preview") version "..."` or
        // `// alias(libs.plugins.compose.preview)` must not flip the
        // pre-applied flag and disable classpath injection.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                "fun composeAiPreviewStripComments(source: String): String",
            ),
            "expected a comment-stripper helper inside the rendered script",
        );
        assert.ok(
            script.includes("composeAiPreviewStripComments(raw)"),
            "expected the scanner to run text through the comment stripper",
        );
    });

    it("honors COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL via a script-runtime env read", () => {
        // Mirrors the CLI's AutoInject.kt — the env var is read inside the
        // rendered Kotlin (at Gradle invocation time), so users can flip
        // mavenLocal on per-invocation without re-rendering the script.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                'val useMavenLocal = System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"',
            ),
            "expected useMavenLocal to be read from env inside the rendered Kotlin",
        );
        assert.ok(
            script.includes("if (useMavenLocal) mavenLocal()"),
            "expected mavenLocal() to be guarded by useMavenLocal in buildscript repos",
        );
        assert.ok(
            script.includes("pluginManagement.repositories.mavenLocal()"),
            "expected pluginManagement-level mavenLocal seeding for plugins-DSL resolution",
        );
        assert.ok(
            script.includes(
                "dependencyResolutionManagement.repositories.mavenLocal()",
            ),
            "expected dependencyResolutionManagement-level seeding for runtime AAR resolution",
        );
    });

    it("uses pluginManager.withPlugin, NOT afterEvaluate (as a code construct)", () => {
        // AGP's `finalizeDsl` callbacks have to register before the DSL lock —
        // afterEvaluate runs after that lock and would skip preview registration
        // entirely. This is the same constraint the CI script documents.
        //
        // The header comment legitimately mentions afterEvaluate to explain
        // *why* we don't use it, so check for the call forms rather than the
        // bare word.
        const script = renderInitScript();
        assert.ok(
            !script.includes("afterEvaluate("),
            "init script must not call afterEvaluate(...)",
        );
        assert.ok(
            !script.includes("afterEvaluate {"),
            "init script must not use the afterEvaluate { ... } block form",
        );
    });
});

describe("materializeInitScript", () => {
    it(
        "writes the rendered script to <storageDir>/<filename> and returns the path",
        withTempDir((dir) => {
            const target = materializeInitScript(dir, "1.2.3");
            assert.strictEqual(
                target,
                path.join(dir, INIT_SCRIPT_FILENAME),
                "should return the absolute path inside storageDir",
            );
            const onDisk = fs.readFileSync(target, "utf-8");
            assert.strictEqual(onDisk, renderInitScript("1.2.3"));
        }),
    );

    it(
        "creates the storage directory if missing (recursive)",
        withTempDir((dir) => {
            const nested = path.join(dir, "globalStorage", "compose-preview");
            materializeInitScript(nested, "1.0.0");
            assert.ok(fs.statSync(nested).isDirectory());
            assert.ok(fs.existsSync(path.join(nested, INIT_SCRIPT_FILENAME)));
        }),
    );

    it(
        "is idempotent — re-running with the same version leaves the file untouched",
        withTempDir((dir) => {
            const first = materializeInitScript(dir, "1.0.0");
            const stat1 = fs.statSync(first);
            // Tick forward so a write would produce a different mtime.
            const future = new Date(stat1.mtimeMs + 5000);
            fs.utimesSync(first, future, future);
            const stat2BeforeRerun = fs.statSync(first);
            const second = materializeInitScript(dir, "1.0.0");
            assert.strictEqual(second, first);
            const stat3 = fs.statSync(first);
            assert.strictEqual(
                stat3.mtimeMs,
                stat2BeforeRerun.mtimeMs,
                "expected no rewrite when contents are unchanged",
            );
        }),
    );

    it(
        "rewrites when the plugin version changes",
        withTempDir((dir) => {
            materializeInitScript(dir, "1.0.0");
            const target = materializeInitScript(dir, "2.0.0");
            const onDisk = fs.readFileSync(target, "utf-8");
            assert.match(onDisk, /val pluginVersion = "2\.0\.0"/);
            assert.ok(!onDisk.includes('val pluginVersion = "1.0.0"'));
        }),
    );
});

describe("hasIncludedPluginBuild", () => {
    it(
        'returns true for settings.gradle.kts declaring includeBuild("gradle-plugin")',
        withTempDir((dir) => {
            fs.writeFileSync(
                path.join(dir, "settings.gradle.kts"),
                'rootProject.name = "demo"\nincludeBuild("gradle-plugin")\n',
            );
            assert.strictEqual(hasIncludedPluginBuild(dir), true);
        }),
    );

    it(
        "returns true for Groovy settings.gradle with single quotes and parens",
        withTempDir((dir) => {
            fs.writeFileSync(
                path.join(dir, "settings.gradle"),
                "rootProject.name = 'demo'\nincludeBuild('gradle-plugin')\n",
            );
            assert.strictEqual(hasIncludedPluginBuild(dir), true);
        }),
    );

    it(
        "returns true when includeBuild() has extra whitespace",
        withTempDir((dir) => {
            fs.writeFileSync(
                path.join(dir, "settings.gradle.kts"),
                'includeBuild( "gradle-plugin" )\n',
            );
            assert.strictEqual(hasIncludedPluginBuild(dir), true);
        }),
    );

    it(
        "returns false when no settings file exists",
        withTempDir((dir) => {
            assert.strictEqual(hasIncludedPluginBuild(dir), false);
        }),
    );

    it(
        "returns false when settings.gradle.kts includes a different build",
        withTempDir((dir) => {
            fs.writeFileSync(
                path.join(dir, "settings.gradle.kts"),
                'includeBuild("build-logic")\n',
            );
            assert.strictEqual(hasIncludedPluginBuild(dir), false);
        }),
    );
});

describe("initScriptDigest", () => {
    it("is stable for the same plugin version", () => {
        assert.strictEqual(
            initScriptDigest("1.0.0"),
            initScriptDigest("1.0.0"),
        );
    });

    it("differs across plugin versions", () => {
        assert.notStrictEqual(
            initScriptDigest("1.0.0"),
            initScriptDigest("1.0.1"),
        );
    });

    it("returns a 16-char hex prefix", () => {
        const digest = initScriptDigest("1.0.0");
        assert.strictEqual(digest.length, 16);
        assert.match(digest, /^[0-9a-f]{16}$/);
    });
});
