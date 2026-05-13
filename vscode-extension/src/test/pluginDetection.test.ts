import * as assert from "assert";
import * as path from "path";
import * as fs from "fs";
import * as os from "os";
import {
    APPLIES_PLUGIN_RE,
    appliesInjectableHostPlugin,
    appliesPlugin,
    findPluginAppliedAncestor,
    INJECTABLE_HOST_PLUGIN_IDS,
} from "../pluginDetection";

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

describe("findPluginAppliedAncestor", () => {
    it(
        "finds a direct ancestor that applies the plugin",
        withTempDir((dir) => {
            fs.mkdirSync(path.join(dir, "app", "src"), { recursive: true });
            fs.writeFileSync(
                path.join(dir, "app", "build.gradle.kts"),
                'id("ee.schimke.composeai.preview")',
            );
            assert.strictEqual(
                findPluginAppliedAncestor(
                    path.join(dir, "app", "src", "Foo.kt"),
                ),
                path.join(dir, "app"),
            );
        }),
    );

    it(
        "finds a deeply-nested (worktree-like) ancestor",
        withTempDir((dir) => {
            const wt = path.join(
                dir,
                ".claude",
                "worktrees",
                "wt1",
                "sample-android",
            );
            fs.mkdirSync(path.join(wt, "src", "main", "kotlin"), {
                recursive: true,
            });
            fs.writeFileSync(
                path.join(wt, "build.gradle.kts"),
                'plugins { id("ee.schimke.composeai.preview") }',
            );
            assert.strictEqual(
                findPluginAppliedAncestor(
                    path.join(wt, "src", "main", "kotlin", "Foo.kt"),
                ),
                wt,
            );
        }),
    );

    it(
        "returns null when no ancestor applies the plugin",
        withTempDir((dir) => {
            fs.mkdirSync(path.join(dir, "lib", "src"), { recursive: true });
            fs.writeFileSync(
                path.join(dir, "lib", "build.gradle.kts"),
                'id("org.jetbrains.kotlin.jvm")',
            );
            assert.strictEqual(
                findPluginAppliedAncestor(
                    path.join(dir, "lib", "src", "Foo.kt"),
                ),
                null,
            );
        }),
    );

    it(
        "skips build scripts that only declare the plugin",
        withTempDir((dir) => {
            fs.mkdirSync(path.join(dir, "gradle-plugin", "src"), {
                recursive: true,
            });
            fs.writeFileSync(
                path.join(dir, "gradle-plugin", "build.gradle.kts"),
                `
            gradlePlugin { plugins { create("p") { id = "ee.schimke.composeai.preview" } } }
        `,
            );
            assert.strictEqual(
                findPluginAppliedAncestor(
                    path.join(dir, "gradle-plugin", "src", "Foo.kt"),
                ),
                null,
            );
        }),
    );

    it(
        "finds an ancestor that applies the plugin via Groovy build.gradle",
        withTempDir((dir) => {
            fs.mkdirSync(path.join(dir, "app", "src"), { recursive: true });
            fs.writeFileSync(
                path.join(dir, "app", "build.gradle"),
                "plugins { id 'ee.schimke.composeai.preview' }",
            );
            assert.strictEqual(
                findPluginAppliedAncestor(
                    path.join(dir, "app", "src", "Foo.kt"),
                ),
                path.join(dir, "app"),
            );
        }),
    );

    it(
        "prefers build.gradle.kts when both exist in the same directory",
        withTempDir((dir) => {
            fs.mkdirSync(path.join(dir, "app", "src"), { recursive: true });
            fs.writeFileSync(
                path.join(dir, "app", "build.gradle.kts"),
                'id("ee.schimke.composeai.preview")',
            );
            // Groovy script in the same directory does NOT apply — but the
            // .kts one does, so the ancestor should still be reported.
            fs.writeFileSync(
                path.join(dir, "app", "build.gradle"),
                "plugins { id 'org.jetbrains.kotlin.jvm' }",
            );
            assert.strictEqual(
                findPluginAppliedAncestor(
                    path.join(dir, "app", "src", "Foo.kt"),
                ),
                path.join(dir, "app"),
            );
        }),
    );

    it(
        "skips literal applications trailing `apply false`",
        withTempDir((dir) => {
            fs.mkdirSync(path.join(dir, "wearApp", "src"), { recursive: true });
            // Root-level declaration-only: module pulls the plugin in for
            // subprojects but does not apply it here.
            fs.writeFileSync(
                path.join(dir, "build.gradle.kts"),
                'plugins { id("ee.schimke.composeai.preview") apply false }',
            );
            fs.writeFileSync(
                path.join(dir, "wearApp", "build.gradle.kts"),
                'plugins { id("org.jetbrains.kotlin.jvm") }',
            );
            assert.strictEqual(
                findPluginAppliedAncestor(
                    path.join(dir, "wearApp", "src", "Foo.kt"),
                ),
                null,
            );
        }),
    );
});

describe("appliesPlugin", () => {
    it("matches the literal id form", () => {
        assert.ok(appliesPlugin('id("ee.schimke.composeai.preview")'));
        assert.ok(
            appliesPlugin('plugins { id("ee.schimke.composeai.preview") }'),
        );
        assert.ok(appliesPlugin("id 'ee.schimke.composeai.preview'"));
    });

    it("rejects a declaration-only snippet", () => {
        assert.ok(!appliesPlugin('id = "ee.schimke.composeai.preview"'));
    });

    it("rejects literal `apply false` on the same line", () => {
        assert.ok(
            !appliesPlugin('id("ee.schimke.composeai.preview") apply false'),
        );
    });

    it("does NOT match the version-catalog alias form — handled via marker", () => {
        // Intentional: alias detection would require parsing
        // `libs.versions.toml`. The `applied.json` marker written by
        // `composePreviewApplied` covers this case authoritatively.
        assert.ok(!appliesPlugin("alias(libs.plugins.composeai.preview)"));
    });
});

describe("APPLIES_PLUGIN_RE", () => {
    it("matches the literal application forms", () => {
        assert.ok(APPLIES_PLUGIN_RE.test('id("ee.schimke.composeai.preview")'));
        assert.ok(
            APPLIES_PLUGIN_RE.test(
                'plugins { id("ee.schimke.composeai.preview") }',
            ),
        );
        assert.ok(APPLIES_PLUGIN_RE.test("id 'ee.schimke.composeai.preview'"));
    });

    it("rejects declaration-only usage", () => {
        assert.ok(
            !APPLIES_PLUGIN_RE.test('id = "ee.schimke.composeai.preview"'),
        );
    });

    // Note: the raw regex is just the plugin-reference matcher. The `apply
    // false` exclusion happens at the line level inside [appliesPlugin] so
    // the raw regex alone does still match a `... apply false` line.
});

describe("appliesInjectableHostPlugin", () => {
    it("matches every id in INJECTABLE_HOST_PLUGIN_IDS", () => {
        for (const id of INJECTABLE_HOST_PLUGIN_IDS) {
            assert.ok(
                appliesInjectableHostPlugin(`plugins { id("${id}") }`),
                `expected to match ${id}`,
            );
        }
    });

    it("matches the Groovy DSL form", () => {
        assert.ok(
            appliesInjectableHostPlugin(
                "plugins { id 'com.android.application' }",
            ),
        );
    });

    it("ignores ee.schimke.composeai.preview itself (only host plugins count)", () => {
        // The whole point of the helper is to detect plugins the init script
        // can attach onto — our own plugin is detected via the existing
        // [appliesPlugin] path, not this one.
        assert.strictEqual(
            appliesInjectableHostPlugin(
                'plugins { id("ee.schimke.composeai.preview") }',
            ),
            false,
        );
    });

    it("ignores apply-false declarations", () => {
        // Root-build.gradle's `plugins { id(\"com.android.application\") apply false }`
        // declares the plugin for subprojects but doesn't apply it here; the
        // init script's `withPlugin` hook wouldn't fire either, so we shouldn't
        // flip into full mode based on this line.
        assert.strictEqual(
            appliesInjectableHostPlugin(
                'id("com.android.application") apply false',
            ),
            false,
        );
    });

    it("returns false on an empty / non-Gradle script", () => {
        assert.strictEqual(appliesInjectableHostPlugin(""), false);
        assert.strictEqual(
            appliesInjectableHostPlugin("// no plugins here"),
            false,
        );
    });

    it("rejects declaration-only id assignments (e.g. plugin DSL block in a buildSrc convention)", () => {
        assert.strictEqual(
            appliesInjectableHostPlugin('id = "com.android.application"'),
            false,
        );
    });
});
