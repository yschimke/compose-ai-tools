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

    it("warns when Isolated Projects is enabled", () => {
        const script = renderInitScript();
        // The allprojects-based injection can't run under IP, so the script detects IP at
        // settingsEvaluated (before the violation aborts the build) and warns the user.
        assert.ok(
            script.includes("import org.gradle.kotlin.dsl.support.serviceOf"),
            "expected the serviceOf import used to probe BuildFeatures",
        );
        assert.ok(
            script.includes(
                "serviceOf<BuildFeatures>().isolatedProjects.active",
            ),
            "expected the script to probe whether Isolated Projects is active",
        );
        assert.ok(
            script.includes("Isolated Projects is enabled"),
            "expected a warning message when IP is on",
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

    it("gates the buildscript classpath injection on per-project pre-applied detection", () => {
        // Regression for #305 (homeassistant-remotecompose): the original gate was a single
        // global boolean, so a mixed-shape project where some modules declare the plugin via
        // `alias(libs.plugins.compose.preview)` and others don't would skip buildscript
        // injection *everywhere*. Then `pluginManager.apply` from the withPlugin hooks would
        // fail in the modules without the catalog alias ("Plugin with id
        // 'ee.schimke.composeai.preview' not found."). The gate is now a per-project set of
        // project directories that declare the plugin themselves; modules without their own
        // declaration still get the buildscript classpath injection so withPlugin's
        // pluginManager.apply can resolve the plugin class.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                "var composeAiPreviewPreAppliedDirs: Set<java.io.File> = emptySet()",
            ),
            "expected the per-project pre-applied directory set declaration",
        );
        assert.ok(
            script.includes(
                "composeAiPreviewPreAppliedDirs = scanForComposeAiPreviewDeclaration(rootDir, projectDirs)",
            ),
            "expected the set to be populated during settingsEvaluated",
        );
        assert.ok(
            script.includes(
                "val composeAiPreviewIsPreApplied = projectDir in composeAiPreviewPreAppliedDirs",
            ),
            "expected the buildscript block to be guarded per-project on the directory set",
        );
        // Catalog alias resolution: the scanner must look at gradle/libs.versions.toml
        // so that `alias(libs.plugins.<x>)` references are detected.
        assert.ok(
            script.includes("gradle/libs.versions.toml"),
            "expected the catalog accessor scanner to read libs.versions.toml",
        );
        // Pin the per-project return shape so a future refactor doesn't silently drop back
        // to the global Boolean (which is the #305 regression mode).
        assert.ok(
            script.includes(
                "fun scanForComposeAiPreviewDeclaration(\n    rootDir: java.io.File,\n    projectDirs: List<java.io.File>,\n): Set<java.io.File> {",
            ),
            "expected scanForComposeAiPreviewDeclaration to return Set<File> of pre-applied project dirs",
        );
    });

    it("skips classpath injection for ancestors of a pre-applied module", () => {
        // Regression for #1855 (auto-inject half): Gradle inherits a project's buildscript
        // classpath into its subprojects' `plugins {}` resolution, so injecting onto the root (or
        // any ancestor) of a module that applies the plugin with a version (`id("...") version
        // "..."` / `alias(libs.plugins.<x>)`) makes that subproject fail with "the plugin is
        // already on the classpath with an unknown version" — sinking discovery for the whole
        // build. Mirror of the CLI renderer's gate; keep both in lockstep.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                "val composeAiPreviewHasPreAppliedDescendant =\n        subprojects.any { it.projectDir in composeAiPreviewPreAppliedDirs }",
            ),
            "expected the pre-applied-descendant scan in allprojects",
        );
        assert.ok(
            script.includes(
                "if (!composeAiPreviewIsPreApplied && !composeAiPreviewHasPreAppliedDescendant) {",
            ),
            "expected the injection to be gated on the pre-applied + descendant flags",
        );
        assert.ok(
            script.includes(
                "if (composeAiPreviewHasPreAppliedDescendant && !composeAiPreviewIsPreApplied) {\n        return@allprojects\n    }",
            ),
            "expected the apply hooks to short-circuit for ancestors of pre-applied modules",
        );
    });

    it("skips composite-included builds in settingsEvaluated and allprojects", () => {
        // Regression for the Confetti report: with `includeBuild("build-logic")` whose
        // settings.gradle.kts declares `exclusiveContent { ... }` in
        // `pluginManagement.repositories`, Gradle 9.3+ rejects any project that adds to
        // `buildscript.repositories`. The init script is evaluated once per build in a
        // composite, so the unguarded `allprojects { buildscript { repositories { ... } } }`
        // previously fired against the included build and tripped the validation. Pins the
        // early-return shape so it doesn't regress. An included build's `gradle.parent` is
        // non-null; the root build's is null.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                "val composeAiPreviewIsIncludedBuild = gradle.parent != null",
            ),
            "expected the included-build flag derived from gradle.parent",
        );
        assert.ok(
            script.includes(
                "if (composeAiPreviewIsIncludedBuild) return@settingsEvaluated",
            ),
            "expected settingsEvaluated to short-circuit for composite-included builds",
        );
        assert.ok(
            script.includes(
                "if (composeAiPreviewIsIncludedBuild) return@allprojects",
            ),
            "expected allprojects to short-circuit for composite-included builds",
        );
    });

    it("forks the exclusiveContent branch on per-project buildscript repos", () => {
        // In the exclusiveContent shape Gradle 9.3+ forbids adding to buildscript.repositories,
        // so the branch forks per-project: modules with their own buildscript repos get the
        // plain coordinate dep injected (their repos resolve it); modules WITHOUT resolve the
        // plugin classpath via a detached configuration and inject files() instead (see the
        // dedicated test below). Neither path adds to buildscript.repositories.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                "var composeAiPreviewSettingsHasExclusiveContent: Boolean = false",
            ),
            "expected the exclusiveContent flag declaration",
        );
        assert.ok(
            script.includes(
                "var composeAiPreviewProjectsWithOwnBuildscriptRepos: Set<java.io.File> = emptySet()",
            ),
            "expected the per-project buildscript-repos set declaration",
        );
        assert.ok(
            script.includes(
                "fun scanForProjectsWithBuildscriptRepos(\n    projectDirs: List<java.io.File>,\n): Set<java.io.File> {",
            ),
            "expected the scanner function in the rendered script",
        );
        assert.ok(
            script.includes(
                "composeAiPreviewProjectsWithOwnBuildscriptRepos =\n            scanForProjectsWithBuildscriptRepos(projectDirs)",
            ),
            "expected the set to be populated inside the exclusiveContent branch",
        );
        assert.ok(
            script.includes(
                "if (!composeAiPreviewSettingsHasExclusiveContent) {\n                    repositories {",
            ),
            "expected the buildscript repositories add to be guarded by the exclusiveContent flag",
        );
        assert.ok(
            script.includes(
                "val composeAiPreviewNeedsResolvedClasspathInject =\n        composeAiPreviewSettingsHasExclusiveContent &&\n            projectDir !in composeAiPreviewProjectsWithOwnBuildscriptRepos",
            ),
            "expected the per-project flag derived from settings flag + buildscript repos scan",
        );
        assert.ok(
            !script.includes(
                "if (composeAiPreviewSettingsHasExclusiveContent) return@allprojects",
            ),
            "the global early-return for exclusiveContent is wrong — must fork per-project",
        );
    });

    it("resolves + injects the plugin classpath as files() in the repo-less exclusiveContent branch", () => {
        // The fix for the Confetti :androidApp failure: a module in the exclusiveContent shape
        // WITHOUT its own buildscript repos can't add to buildscript.repositories (Gradle 9.3+),
        // so we resolve the plugin classpath through the project's own (settings-managed) repos
        // via a detached configuration and inject the resolved JARs as files() — landing the
        // plugin on the module's OWN buildscript classloader (alongside AGP) without touching
        // buildscript.repositories. Previously this branch returned early and the module silently
        // missed the plugin.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                "fun org.gradle.api.Project.composeAiPreviewResolvePluginClasspath(): Set<java.io.File> {",
            ),
            "expected the detached-configuration classpath resolver helper",
        );
        assert.ok(
            script.includes(
                "configurations.detachedConfiguration(composeAiPreviewMarker).files.toSet()",
            ),
            "expected resolution via a detached configuration (not a buildscript.repositories add)",
        );
        assert.ok(
            script.includes('add("classpath", composeAiPreviewClasspathFiles)'),
            "expected the resolved files to be injected onto the buildscript classpath",
        );
        // The resolved set is memoised so a large multi-module build resolves once.
        assert.ok(
            script.includes(
                "composeAiPreviewCachedPluginClasspath?.let { return it }",
            ),
            "expected the resolved classpath to be memoised across modules",
        );
        // Regression guard: the old behavior returned early for this branch, dropping the plugin.
        assert.ok(
            !script.includes(
                "composeAiPreviewSkipExclusiveContentClasspathDep",
            ),
            "the old skip-and-drop flag must be gone — the branch now resolves + injects",
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
        // SNAPSHOT versions auto-enable the seed; non-SNAPSHOT runs still
        // honor the env-var escape hatch.
        const script = renderInitScript();
        assert.ok(
            script.includes(
                'val useMavenLocal = pluginVersion.endsWith("-SNAPSHOT") ||\n    System.getenv("COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL") == "1"',
            ),
            "expected useMavenLocal to be SNAPSHOT-aware with an env-var escape hatch",
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
    /**
     * Seeds the included build's `build.gradle.kts` with the compose-preview
     * plugin id so the sentinel check (#1362) passes. Callers that want to
     * verify the *negative* path can skip this helper.
     */
    function writeComposePreviewGradlePlugin(dir: string): void {
        const pluginDir = path.join(dir, "gradle-plugin");
        fs.mkdirSync(pluginDir, { recursive: true });
        fs.writeFileSync(
            path.join(pluginDir, "build.gradle.kts"),
            "plugins { `java-gradle-plugin` }\n" +
                "gradlePlugin {\n" +
                '  plugins { create("composePreview") { id = "ee.schimke.composeai.preview" } }\n' +
                "}\n",
        );
    }

    it(
        'returns true for settings.gradle.kts declaring includeBuild("gradle-plugin") when the included build publishes the compose-preview plugin',
        withTempDir((dir) => {
            fs.writeFileSync(
                path.join(dir, "settings.gradle.kts"),
                'rootProject.name = "demo"\nincludeBuild("gradle-plugin")\n',
            );
            writeComposePreviewGradlePlugin(dir);
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
            writeComposePreviewGradlePlugin(dir);
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
            writeComposePreviewGradlePlugin(dir);
            assert.strictEqual(hasIncludedPluginBuild(dir), true);
        }),
    );

    it(
        "accepts a Groovy-DSL sentinel build script too",
        withTempDir((dir) => {
            fs.writeFileSync(
                path.join(dir, "settings.gradle.kts"),
                'includeBuild("gradle-plugin")\n',
            );
            const pluginDir = path.join(dir, "gradle-plugin");
            fs.mkdirSync(pluginDir, { recursive: true });
            fs.writeFileSync(
                path.join(pluginDir, "build.gradle"),
                "// Groovy DSL\n" +
                    'gradlePlugin { plugins { composePreview { id = "ee.schimke.composeai.preview" } } }\n',
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

    it(
        'returns false when includeBuild("gradle-plugin") matches but the included build is an unrelated local build-logic project (issue #1362)',
        withTempDir((dir) => {
            // A common convention in unrelated workspaces: a local
            // `gradle-plugin/` included build that publishes convention
            // plugins under a different id. Auto-inject must stay enabled
            // there — the workspace doesn't have a local source of truth
            // for `ee.schimke.composeai.preview`, so dropping
            // `--init-script` regresses preview rendering.
            fs.writeFileSync(
                path.join(dir, "settings.gradle.kts"),
                'rootProject.name = "demo"\nincludeBuild("gradle-plugin")\n',
            );
            const pluginDir = path.join(dir, "gradle-plugin");
            fs.mkdirSync(pluginDir, { recursive: true });
            fs.writeFileSync(
                path.join(pluginDir, "build.gradle.kts"),
                "plugins { `java-gradle-plugin` }\n" +
                    "gradlePlugin {\n" +
                    '  plugins { create("conventions") { id = "com.example.conventions" } }\n' +
                    "}\n",
            );
            assert.strictEqual(hasIncludedPluginBuild(dir), false);
        }),
    );

    it(
        'returns false when includeBuild("gradle-plugin") matches but the included build has no build script at all',
        withTempDir((dir) => {
            fs.writeFileSync(
                path.join(dir, "settings.gradle.kts"),
                'includeBuild("gradle-plugin")\n',
            );
            // No gradle-plugin/build.gradle{.kts} on disk — the sentinel
            // can't confirm the dev-loop shape, so auto-inject stays on.
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
