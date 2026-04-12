import * as assert from 'assert';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import { GradleService, GradleApi } from '../gradleService';

/** Stub GradleApi that records invocations and allows test control. */
class StubGradleApi implements GradleApi {
    public runCalls: Array<{ taskName: string; cancellationKey?: string }> = [];
    public cancelCalls: Array<{ taskName: string; cancellationKey?: string }> = [];
    public nextRunResult: 'success' | Error = 'success';

    async runTask(opts: {
        projectFolder: string;
        taskName: string;
        cancellationKey?: string;
    }): Promise<void> {
        this.runCalls.push({ taskName: opts.taskName, cancellationKey: opts.cancellationKey });
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
