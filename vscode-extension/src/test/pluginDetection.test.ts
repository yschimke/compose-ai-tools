import * as assert from 'assert';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import { findPluginAppliedAncestor } from '../pluginDetection';

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
});
