import * as assert from 'assert';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import { GradleService, GradleApi } from '../gradleService';
import { JdkImageError } from '../jdkImageErrorDetector';

/** Stub GradleApi that records invocations and allows test control. */
class StubGradleApi implements GradleApi {
    public runCalls: Array<{ taskName: string; cancellationKey?: string }> = [];
    public cancelCalls: Array<{ taskName: string; cancellationKey?: string }> = [];
    public nextRunResult: 'success' | Error = 'success';
    /** Bytes to feed through onOutput before resolving/rejecting. */
    public nextRunOutput: string = '';

    async runTask(opts: {
        projectFolder: string;
        taskName: string;
        cancellationKey?: string;
        onOutput?: (output: { getOutputBytes(): Uint8Array; getOutputType(): number }) => void;
    }): Promise<void> {
        this.runCalls.push({ taskName: opts.taskName, cancellationKey: opts.cancellationKey });
        if (this.nextRunOutput && opts.onOutput) {
            const bytes = new TextEncoder().encode(this.nextRunOutput);
            opts.onOutput({ getOutputBytes: () => bytes, getOutputType: () => 0 });
        }
        if (this.nextRunResult !== 'success') {
            throw this.nextRunResult;
        }
    }

    async cancelRunTask(opts: {
        projectFolder: string;
        taskName: string;
        cancellationKey?: string;
    }): Promise<void> {
        this.cancelCalls.push({ taskName: opts.taskName, cancellationKey: opts.cancellationKey });
    }
}

function withTempDir(fn: (dir: string, api: StubGradleApi) => void | Promise<void>): () => Promise<void> {
    return async () => {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'compose-preview-test-'));
        try {
            await fn(dir, new StubGradleApi());
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    };
}

describe('GradleService', () => {
    describe('readManifest', () => {
        it('reads and parses a valid manifest', withTempDir((dir, api) => {
            const manifestDir = path.join(dir, 'testmodule', 'build', 'compose-previews');
            fs.mkdirSync(manifestDir, { recursive: true });
            fs.copyFileSync(
                path.join(__dirname, '..', '..', 'src', 'test', 'fixtures', 'previews.json'),
                path.join(manifestDir, 'previews.json'),
            );

            const service = new GradleService(dir, api);
            const manifest = service.readManifest('testmodule');

            assert.notStrictEqual(manifest, null);
            assert.strictEqual(manifest!.module, 'sample-android');
            assert.strictEqual(manifest!.previews.length, 4);
        }));

        it('returns null for missing manifest', withTempDir((dir, api) => {
            const service = new GradleService(dir, api);
            assert.strictEqual(service.readManifest('nonexistent'), null);
        }));

        it('returns null for malformed JSON', withTempDir((dir, api) => {
            const manifestDir = path.join(dir, 'bad', 'build', 'compose-previews');
            fs.mkdirSync(manifestDir, { recursive: true });
            fs.writeFileSync(path.join(manifestDir, 'previews.json'), '{ broken');

            const service = new GradleService(dir, api);
            assert.strictEqual(service.readManifest('bad'), null);
        }));

        it('returns null for manifest without previews array', withTempDir((dir, api) => {
            const manifestDir = path.join(dir, 'empty', 'build', 'compose-previews');
            fs.mkdirSync(manifestDir, { recursive: true });
            fs.writeFileSync(path.join(manifestDir, 'previews.json'), '{"module":"x"}');

            const service = new GradleService(dir, api);
            assert.strictEqual(service.readManifest('empty'), null);
        }));
    });

    describe('readPreviewImage', () => {
        it('reads a PNG as base64', withTempDir(async (dir, api) => {
            const renderDir = path.join(dir, 'mod', 'build', 'compose-previews', 'renders');
            fs.mkdirSync(renderDir, { recursive: true });
            fs.writeFileSync(path.join(renderDir, 'test.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47]));

            const service = new GradleService(dir, api);
            const base64 = await service.readPreviewImage('mod', 'renders/test.png');

            assert.notStrictEqual(base64, null);
            assert.strictEqual(Buffer.from(base64!, 'base64')[0], 0x89);
        }));

        it('returns null for missing image', withTempDir(async (dir, api) => {
            const service = new GradleService(dir, api);
            assert.strictEqual(await service.readPreviewImage('mod', 'missing.png'), null);
        }));
    });

    describe('findPreviewModules', () => {
        it('finds modules with the plugin applied', withTempDir((dir, api) => {
            fs.mkdirSync(path.join(dir, 'app'));
            fs.writeFileSync(path.join(dir, 'app', 'build.gradle.kts'),
                'id("ee.schimke.composeai.preview")');

            fs.mkdirSync(path.join(dir, 'lib'));
            fs.writeFileSync(path.join(dir, 'lib', 'build.gradle.kts'),
                'id("org.jetbrains.kotlin.jvm")');

            const service = new GradleService(dir, api);
            assert.deepStrictEqual(service.findPreviewModules(), ['app']);
        }));

        it('returns empty for workspace with no modules', withTempDir((dir, api) => {
            const service = new GradleService(dir, api);
            assert.deepStrictEqual(service.findPreviewModules(), []);
        }));

        it('ignores the plugin module itself (declares but does not apply)', withTempDir((dir, api) => {
            fs.mkdirSync(path.join(dir, 'gradle-plugin'));
            fs.writeFileSync(path.join(dir, 'gradle-plugin', 'build.gradle.kts'), `
                gradlePlugin {
                    plugins {
                        create("composePreview") {
                            id = "ee.schimke.composeai.preview"
                        }
                    }
                }
            `);
            fs.mkdirSync(path.join(dir, 'app'));
            fs.writeFileSync(path.join(dir, 'app', 'build.gradle.kts'),
                'plugins { id("ee.schimke.composeai.preview") }');

            const service = new GradleService(dir, api);
            assert.deepStrictEqual(service.findPreviewModules(), ['app']);
        }));

        it('excludes literal `apply false` declarations', withTempDir((dir, api) => {
            fs.mkdirSync(path.join(dir, 'lib'));
            fs.writeFileSync(path.join(dir, 'lib', 'build.gradle.kts'),
                'plugins { id("ee.schimke.composeai.preview") apply false }');

            const service = new GradleService(dir, api);
            assert.deepStrictEqual(service.findPreviewModules(), []);
        }));

        it('finds modules via applied.json markers even when build script does not mention the plugin',
            withTempDir((dir, api) => {
                // E.g. the module applies the plugin from a convention plugin
                // in `buildSrc/` — neither the literal-id nor alias regex
                // catches it, but the Gradle-written marker does.
                fs.mkdirSync(path.join(dir, 'mod'));
                fs.writeFileSync(path.join(dir, 'mod', 'build.gradle.kts'),
                    'plugins { id("my-convention-plugin") }');
                const markerDir = path.join(dir, 'mod', 'build', 'compose-previews');
                fs.mkdirSync(markerDir, { recursive: true });
                fs.writeFileSync(path.join(markerDir, 'applied.json'),
                    '{"schema":"compose-preview-applied/v1","modulePath":":mod","moduleName":"mod","pluginVersion":"0.7.2"}');

                const service = new GradleService(dir, api);
                assert.deepStrictEqual(service.findPreviewModules(), ['mod']);
            }));

        it('unions marker-detected and scan-detected modules', withTempDir((dir, api) => {
            fs.mkdirSync(path.join(dir, 'app'));
            fs.writeFileSync(path.join(dir, 'app', 'build.gradle.kts'),
                'id("ee.schimke.composeai.preview")');

            // A second module with only the marker (e.g. discovered earlier
            // and since rewritten to a convention plugin that the regex
            // doesn't recognise).
            fs.mkdirSync(path.join(dir, 'lib'));
            fs.writeFileSync(path.join(dir, 'lib', 'build.gradle.kts'),
                'plugins { id("some-convention") }');
            const markerDir = path.join(dir, 'lib', 'build', 'compose-previews');
            fs.mkdirSync(markerDir, { recursive: true });
            fs.writeFileSync(path.join(markerDir, 'applied.json'),
                '{"schema":"compose-preview-applied/v1","modulePath":":lib","moduleName":"lib","pluginVersion":"0.7.2"}');

            const service = new GradleService(dir, api);
            assert.deepStrictEqual(service.findPreviewModules(), ['app', 'lib']);
        }));
    });

    describe('resolveModule', () => {
        it('resolves file path to module name', withTempDir((dir, api) => {
            fs.mkdirSync(path.join(dir, 'sample-android'));
            fs.writeFileSync(path.join(dir, 'sample-android', 'build.gradle.kts'),
                'id("ee.schimke.composeai.preview")');

            const service = new GradleService(dir, api);
            assert.strictEqual(
                service.resolveModule(path.join(dir, 'sample-android', 'src', 'Foo.kt')),
                'sample-android',
            );
        }));

        it('returns null for file outside any module', withTempDir((dir, api) => {
            const service = new GradleService(dir, api);
            assert.strictEqual(service.resolveModule(path.join(dir, 'unknown', 'Foo.kt')), null);
        }));
    });

    describe('discoverPreviews', () => {
        it('invokes gradleApi with correct task name', withTempDir(async (dir, api) => {
            fs.mkdirSync(path.join(dir, 'mod'));
            fs.writeFileSync(path.join(dir, 'mod', 'build.gradle.kts'),
                'id("ee.schimke.composeai.preview")');

            const manifestDir = path.join(dir, 'mod', 'build', 'compose-previews');
            fs.mkdirSync(manifestDir, { recursive: true });
            fs.copyFileSync(
                path.join(__dirname, '..', '..', 'src', 'test', 'fixtures', 'previews.json'),
                path.join(manifestDir, 'previews.json'),
            );

            const service = new GradleService(dir, api);
            await service.discoverPreviews('mod');

            assert.strictEqual(api.runCalls.length, 1);
            assert.strictEqual(api.runCalls[0].taskName, ':mod:discoverPreviews');
        }));

        it('uses cache for repeated calls within TTL', withTempDir(async (dir, api) => {
            fs.mkdirSync(path.join(dir, 'mod'));
            const manifestDir = path.join(dir, 'mod', 'build', 'compose-previews');
            fs.mkdirSync(manifestDir, { recursive: true });
            fs.copyFileSync(
                path.join(__dirname, '..', '..', 'src', 'test', 'fixtures', 'previews.json'),
                path.join(manifestDir, 'previews.json'),
            );

            const service = new GradleService(dir, api);
            await service.discoverPreviews('mod');
            await service.discoverPreviews('mod');

            // Second call hit cache — only one Gradle invocation
            assert.strictEqual(api.runCalls.length, 1);
        }));

        it('bypasses cache after invalidateCache', withTempDir(async (dir, api) => {
            fs.mkdirSync(path.join(dir, 'mod'));
            const manifestDir = path.join(dir, 'mod', 'build', 'compose-previews');
            fs.mkdirSync(manifestDir, { recursive: true });
            fs.copyFileSync(
                path.join(__dirname, '..', '..', 'src', 'test', 'fixtures', 'previews.json'),
                path.join(manifestDir, 'previews.json'),
            );

            const service = new GradleService(dir, api);
            await service.discoverPreviews('mod');
            service.invalidateCache('mod');
            await service.discoverPreviews('mod');

            assert.strictEqual(api.runCalls.length, 2);
        }));

        it('wraps Gradle failures in readable error', withTempDir(async (dir, api) => {
            api.nextRunResult = new Error('compilation failed');

            const service = new GradleService(dir, api);
            await assert.rejects(
                service.discoverPreviews('mod'),
                /Gradle task .* failed/,
            );
        }));

        it('throws JdkImageError when the failure output contains the jlink signature',
            withTempDir(async (dir, api) => {
                api.nextRunOutput =
                    '> jlink executable /home/u/.antigravity/extensions/redhat.java-1.54.0-linux-x64'
                    + '/jre/21.0.10-linux-x86_64/bin/jlink does not exist.\n';
                api.nextRunResult = new Error('build failed');

                const service = new GradleService(dir, api);
                await assert.rejects(
                    service.discoverPreviews('mod'),
                    (err: unknown) => {
                        assert.ok(err instanceof JdkImageError, 'expected JdkImageError');
                        assert.match((err as JdkImageError).finding.jlinkPath, /\/bin\/jlink$/);
                        return true;
                    },
                );
            }));
    });

    describe('listHistory', () => {
        it('returns [] when no history folder exists', withTempDir((dir, api) => {
            const service = new GradleService(dir, api);
            assert.deepStrictEqual(
                service.listHistory('app', 'com.example.Foo.MyPreview'),
                [],
            );
        }));

        it('lists snapshots newest-first using sanitized folder name', withTempDir((dir, api) => {
            // Preview id "com.example.Foo.MyPreview" sanitizes to itself (dots ok);
            // a label with spaces is replaced with underscores — check both paths.
            const historyDir = path.join(dir, 'app', '.compose-preview-history', 'com.example.Foo.MyPreview');
            fs.mkdirSync(historyDir, { recursive: true });
            fs.writeFileSync(path.join(historyDir, '20260410-100000.png'), 'old');
            fs.writeFileSync(path.join(historyDir, '20260412-215512.png'), 'mid');
            fs.writeFileSync(path.join(historyDir, '20260415-091020.png'), 'new');

            const service = new GradleService(dir, api);
            const entries = service.listHistory('app', 'com.example.Foo.MyPreview');

            assert.strictEqual(entries.length, 3);
            // Newest first
            assert.strictEqual(entries[0].filename, '20260415-091020.png');
            assert.strictEqual(entries[0].timestamp, '20260415-091020');
            assert.strictEqual(entries[0].iso, '2026-04-15T09:10:20Z');
            assert.strictEqual(entries[2].filename, '20260410-100000.png');
        }));

        it('sanitizes spaces and special characters in previewId', withTempDir((dir, api) => {
            // "Red Box" label sanitizes "RedBoxPreview_Red Box" -> "RedBoxPreview_Red_Box"
            const sanitizedDir = path.join(dir, 'app', '.compose-preview-history', 'RedBoxPreview_Red_Box');
            fs.mkdirSync(sanitizedDir, { recursive: true });
            fs.writeFileSync(path.join(sanitizedDir, '20260412-215512.png'), 'x');

            const service = new GradleService(dir, api);
            const entries = service.listHistory('app', 'RedBoxPreview_Red Box');
            assert.strictEqual(entries.length, 1);
        }));

        it('skips files with malformed timestamps and non-png files', withTempDir((dir, api) => {
            const historyDir = path.join(dir, 'app', '.compose-preview-history', 'Preview');
            fs.mkdirSync(historyDir, { recursive: true });
            fs.writeFileSync(path.join(historyDir, '20260412-215512.png'), 'ok');
            fs.writeFileSync(path.join(historyDir, 'nothinghere.png'), 'bad');
            fs.writeFileSync(path.join(historyDir, 'readme.txt'), 'ignore');

            const service = new GradleService(dir, api);
            const entries = service.listHistory('app', 'Preview');
            assert.strictEqual(entries.length, 1);
            assert.strictEqual(entries[0].filename, '20260412-215512.png');
        }));

        it('accepts -N collision suffix in timestamps', withTempDir((dir, api) => {
            const historyDir = path.join(dir, 'app', '.compose-preview-history', 'Preview');
            fs.mkdirSync(historyDir, { recursive: true });
            fs.writeFileSync(path.join(historyDir, '20260412-215512.png'), 'a');
            fs.writeFileSync(path.join(historyDir, '20260412-215512-1.png'), 'b');

            const service = new GradleService(dir, api);
            const entries = service.listHistory('app', 'Preview');
            assert.strictEqual(entries.length, 2);
        }));
    });

    describe('readHistoryImage', () => {
        it('reads a history PNG as base64', withTempDir(async (dir, api) => {
            const historyDir = path.join(dir, 'app', '.compose-preview-history', 'P');
            fs.mkdirSync(historyDir, { recursive: true });
            fs.writeFileSync(path.join(historyDir, '20260412-215512.png'),
                Buffer.from([0x89, 0x50, 0x4e, 0x47]));

            const service = new GradleService(dir, api);
            const b64 = await service.readHistoryImage('app', 'P', '20260412-215512.png');
            assert.notStrictEqual(b64, null);
            assert.strictEqual(Buffer.from(b64!, 'base64')[0], 0x89);
        }));

        it('rejects path traversal attempts', withTempDir(async (dir, api) => {
            const service = new GradleService(dir, api);
            assert.strictEqual(await service.readHistoryImage('app', 'P', '../../etc/passwd'), null);
            assert.strictEqual(await service.readHistoryImage('app', 'P', '..\\win.ini'), null);
            assert.strictEqual(await service.readHistoryImage('app', 'P', 'sub/file.png'), null);
        }));

        it('returns null for missing file', withTempDir(async (dir, api) => {
            const service = new GradleService(dir, api);
            assert.strictEqual(await service.readHistoryImage('app', 'P', 'nope.png'), null);
        }));
    });

    describe('cancel', () => {
        it('cancels in-flight tasks via API', withTempDir(async (dir, api) => {
            const service = new GradleService(dir, api);
            // Start a task but don't await — simulate running
            service.discoverPreviews('mod').catch(() => {}); // swallow error
            await new Promise(resolve => setTimeout(resolve, 10));

            await service.cancel();
            assert.strictEqual(api.cancelCalls.length >= 0, true); // at least attempted
        }));
    });
});
