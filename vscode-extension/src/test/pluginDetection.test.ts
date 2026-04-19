import * as assert from 'assert';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import {
    APPLIES_PLUGIN_RE,
    appliesPlugin,
    buildAliasAppliesRegex,
    findPluginAppliedAncestor,
    parsePluginAliases,
    readCatalogPluginAliases,
} from '../pluginDetection';

function withTempDir(fn: (dir: string) => void | Promise<void>): () => Promise<void> {
    return async () => {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'compose-preview-test-'));
        try {
            await fn(dir);
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    };
}

describe('findPluginAppliedAncestor', () => {
    it('finds a direct ancestor that applies the plugin', withTempDir((dir) => {
        fs.mkdirSync(path.join(dir, 'app', 'src'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'app', 'build.gradle.kts'),
            'id("ee.schimke.composeai.preview")');
        assert.strictEqual(
            findPluginAppliedAncestor(path.join(dir, 'app', 'src', 'Foo.kt')),
            path.join(dir, 'app'),
        );
    }));

    it('finds a deeply-nested (worktree-like) ancestor', withTempDir((dir) => {
        const wt = path.join(dir, '.claude', 'worktrees', 'wt1', 'sample-android');
        fs.mkdirSync(path.join(wt, 'src', 'main', 'kotlin'), { recursive: true });
        fs.writeFileSync(path.join(wt, 'build.gradle.kts'),
            'plugins { id("ee.schimke.composeai.preview") }');
        assert.strictEqual(
            findPluginAppliedAncestor(path.join(wt, 'src', 'main', 'kotlin', 'Foo.kt')),
            wt,
        );
    }));

    it('returns null when no ancestor applies the plugin', withTempDir((dir) => {
        fs.mkdirSync(path.join(dir, 'lib', 'src'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'lib', 'build.gradle.kts'),
            'id("org.jetbrains.kotlin.jvm")');
        assert.strictEqual(
            findPluginAppliedAncestor(path.join(dir, 'lib', 'src', 'Foo.kt')),
            null,
        );
    }));

    it('skips build scripts that only declare the plugin', withTempDir((dir) => {
        fs.mkdirSync(path.join(dir, 'gradle-plugin', 'src'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'gradle-plugin', 'build.gradle.kts'), `
            gradlePlugin { plugins { create("p") { id = "ee.schimke.composeai.preview" } } }
        `);
        assert.strictEqual(
            findPluginAppliedAncestor(path.join(dir, 'gradle-plugin', 'src', 'Foo.kt')),
            null,
        );
    }));

    it('finds an ancestor that applies via version-catalog alias', withTempDir((dir) => {
        fs.mkdirSync(path.join(dir, 'gradle'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'gradle', 'libs.versions.toml'), `
            [plugins]
            composeai-preview = { id = "ee.schimke.composeai.preview", version = "0.7.1" }
        `);
        fs.mkdirSync(path.join(dir, 'wearApp', 'src'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'wearApp', 'build.gradle.kts'),
            'plugins { alias(libs.plugins.composeai.preview) }');
        assert.strictEqual(
            findPluginAppliedAncestor(path.join(dir, 'wearApp', 'src', 'Foo.kt')),
            path.join(dir, 'wearApp'),
        );
    }));

    it('skips alias applications trailing `apply false`', withTempDir((dir) => {
        fs.mkdirSync(path.join(dir, 'gradle'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'gradle', 'libs.versions.toml'), `
            [plugins]
            composeai-preview = { id = "ee.schimke.composeai.preview", version = "0.7.1" }
        `);
        // Root-level declaration that doesn't actually apply here.
        fs.writeFileSync(path.join(dir, 'build.gradle.kts'),
            'plugins { alias(libs.plugins.composeai.preview) apply false }');
        fs.mkdirSync(path.join(dir, 'wearApp', 'src'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'wearApp', 'build.gradle.kts'),
            'plugins { id("org.jetbrains.kotlin.jvm") }');
        assert.strictEqual(
            findPluginAppliedAncestor(path.join(dir, 'wearApp', 'src', 'Foo.kt')),
            null,
        );
    }));
});

describe('parsePluginAliases', () => {
    it('returns aliases whose id matches the plugin', () => {
        const aliases = parsePluginAliases(`
            [versions]
            composeai-preview = "0.7.1"

            [plugins]
            composeai-preview = { id = "ee.schimke.composeai.preview", version.ref = "composeai-preview" }
            kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = "2.0.0" }
        `);
        assert.deepStrictEqual(aliases, ['composeai.preview']);
    });

    it('ignores entries outside the [plugins] table', () => {
        // Not that this would ever be valid Gradle catalog content, but we
        // still want the scanner to be strict about the section it reads.
        const aliases = parsePluginAliases(`
            [libraries]
            something = { id = "ee.schimke.composeai.preview", version = "1.0" }
        `);
        assert.deepStrictEqual(aliases, []);
    });

    it('normalizes `-` and `_` to `.` in accessor paths', () => {
        const aliases = parsePluginAliases(`
            [plugins]
            compose_ai-preview = { id = "ee.schimke.composeai.preview", version = "0.7.1" }
        `);
        assert.deepStrictEqual(aliases, ['compose.ai.preview']);
    });

    it('tolerates inline # comments in non-matching entries', () => {
        const aliases = parsePluginAliases(`
            [plugins]
            composeai-preview = { id = "ee.schimke.composeai.preview", version = "0.7.1" } # in use
            other = { id = "other.plugin", version = "1.0" } # unused
        `);
        assert.deepStrictEqual(aliases, ['composeai.preview']);
    });

    it('returns empty when no alias matches', () => {
        const aliases = parsePluginAliases(`
            [plugins]
            kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = "2.0.0" }
        `);
        assert.deepStrictEqual(aliases, []);
    });
});

describe('readCatalogPluginAliases', () => {
    it('reads gradle/libs.versions.toml under the project root', withTempDir((dir) => {
        fs.mkdirSync(path.join(dir, 'gradle'), { recursive: true });
        fs.writeFileSync(path.join(dir, 'gradle', 'libs.versions.toml'), `
            [plugins]
            composeai-preview = { id = "ee.schimke.composeai.preview", version = "0.7.1" }
        `);
        assert.deepStrictEqual(readCatalogPluginAliases(dir), ['composeai.preview']);
    }));

    it('returns [] when the catalog is missing', withTempDir((dir) => {
        assert.deepStrictEqual(readCatalogPluginAliases(dir), []);
    }));
});

describe('buildAliasAppliesRegex', () => {
    it('returns null for an empty alias list', () => {
        assert.strictEqual(buildAliasAppliesRegex([]), null);
    });

    it('matches `alias(libs.plugins.<name>)`', () => {
        const re = buildAliasAppliesRegex(['composeai.preview'])!;
        assert.ok(re.test('plugins { alias(libs.plugins.composeai.preview) }'));
    });

    // `apply false` handling is the responsibility of [appliesPlugin], not
    // the raw alias regex — see that describe block above for coverage.

    it('rejects accessors under a different catalog name by default', () => {
        // Catalog name defaults to `libs`; non-default names aren't supported
        // by the scan fallback (users fall back to the literal-id form).
        const re = buildAliasAppliesRegex(['composeai.preview'])!;
        assert.ok(!re.test('alias(myLibs.plugins.composeai.preview)'));
    });
});

describe('appliesPlugin', () => {
    it('matches the literal id form regardless of alias regex', () => {
        assert.ok(appliesPlugin('id("ee.schimke.composeai.preview")', null));
    });

    it('matches the alias form when the alias regex allows it', () => {
        const re = buildAliasAppliesRegex(['composeai.preview'])!;
        assert.ok(appliesPlugin('alias(libs.plugins.composeai.preview)', re));
    });

    it('rejects a declaration-only snippet', () => {
        assert.ok(!appliesPlugin('id = "ee.schimke.composeai.preview"', null));
    });

    it('rejects `apply false` on either form', () => {
        const re = buildAliasAppliesRegex(['composeai.preview'])!;
        assert.ok(!appliesPlugin('id("ee.schimke.composeai.preview") apply false', re));
        assert.ok(!appliesPlugin('alias(libs.plugins.composeai.preview) apply false', re));
    });
});

describe('APPLIES_PLUGIN_RE', () => {
    it('still matches the existing literal application forms', () => {
        assert.ok(APPLIES_PLUGIN_RE.test('id("ee.schimke.composeai.preview")'));
        assert.ok(APPLIES_PLUGIN_RE.test('plugins { id("ee.schimke.composeai.preview") }'));
        assert.ok(APPLIES_PLUGIN_RE.test("id 'ee.schimke.composeai.preview'"));
    });

    it('still rejects declaration-only usage', () => {
        assert.ok(!APPLIES_PLUGIN_RE.test('id = "ee.schimke.composeai.preview"'));
    });

    // Note: the raw regex is just the plugin-reference matcher. The `apply
    // false` exclusion happens at the line level inside [appliesPlugin] so
    // the raw regex alone does still match a `... apply false` line —
    // that's by design and covered by appliesPlugin's own tests above.
});
