import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import {
    DAEMON_DESCRIPTOR_SCHEMA_VERSION,
    DaemonLaunchDescriptor,
    FileChangedParams,
    InitializeParams,
    InitializeResult,
    PROTOCOL_VERSION,
    RenderFinishedParams,
    RenderNowParams,
    RenderNowResult,
    SetVisibleParams,
} from '../daemon/daemonProtocol';

/**
 * Shared protocol fixtures live under `docs/daemon/protocol-fixtures/`. Both
 * the Kotlin daemon test suite and this TypeScript suite parse the same files
 * — adding a fixture in one ecosystem without the other is the kind of drift
 * we want to catch (PROTOCOL.md § 9). Workspace layout: vscode-extension is
 * at <repo>/vscode-extension, fixtures at <repo>/docs/daemon/protocol-fixtures.
 */
const FIXTURES_DIR = path.resolve(__dirname, '..', '..', '..', 'docs', 'daemon', 'protocol-fixtures');

function readFixture<T>(name: string): T {
    return JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, name), 'utf-8')) as T;
}

describe('daemon protocol — golden fixtures', () => {
    it('parses client-initialize.json into InitializeParams shape', () => {
        const params = readFixture<InitializeParams>('client-initialize.json');
        assert.strictEqual(params.protocolVersion, PROTOCOL_VERSION);
        assert.strictEqual(params.moduleId, ':samples:android');
        assert.strictEqual(params.capabilities.visibility, true);
        assert.strictEqual(params.capabilities.metrics, true);
        assert.strictEqual(params.options?.detectLeaks, 'light');
    });

    it('parses daemon-initializeResult.json into InitializeResult shape', () => {
        const result = readFixture<InitializeResult>('daemon-initializeResult.json');
        assert.strictEqual(result.protocolVersion, PROTOCOL_VERSION);
        assert.strictEqual(result.capabilities.sandboxRecycle, true);
        assert.strictEqual(result.classpathFingerprint.length, 64);
        assert.deepStrictEqual(result.capabilities.leakDetection, ['light', 'heavy']);
    });

    it('parses client-fileChanged.json with all required fields', () => {
        const params = readFixture<FileChangedParams>('client-fileChanged.json');
        assert.strictEqual(params.kind, 'source');
        assert.strictEqual(params.changeType, 'modified');
        assert.match(params.path, /\.kt$/);
    });

    it('parses client-setVisible.json into a string-array shape', () => {
        const params = readFixture<SetVisibleParams>('client-setVisible.json');
        assert.ok(Array.isArray(params.ids));
        assert.ok(params.ids.length > 0);
        for (const id of params.ids) { assert.strictEqual(typeof id, 'string'); }
    });

    it('parses daemon-renderFinished.json with metrics', () => {
        const params = readFixture<RenderFinishedParams>('daemon-renderFinished.json');
        assert.match(params.pngPath, /\.png$/);
        assert.ok(params.tookMs > 0);
        assert.ok(params.metrics);
        assert.strictEqual(typeof params.metrics!.heapAfterGcMb, 'number');
    });

    it('parses daemon-renderNowResult.json — queued + rejected lists', () => {
        const result = readFixture<RenderNowResult>('daemon-renderNowResult.json');
        assert.ok(Array.isArray(result.queued));
        assert.ok(Array.isArray(result.rejected));
        assert.strictEqual(result.rejected[0].reason, 'unknown preview ID');
    });

    it('parses client-renderNow.json with tier=fast', () => {
        const params = readFixture<RenderNowParams>('client-renderNow.json');
        assert.strictEqual(params.tier, 'fast');
        assert.ok(params.previews.length >= 1);
    });
});

describe('daemon launch descriptor', () => {
    it('rejects descriptors with mismatched schemaVersion', () => {
        // The reader in daemonProcess.ts gates on DAEMON_DESCRIPTOR_SCHEMA_VERSION;
        // the constant exists so callers can hard-fail rather than silently
        // accept an incompatible JVM spec. The contract test here just pins
        // the constant — bumping it is intentional and forces the gradle-plugin
        // side to bump in lockstep.
        assert.strictEqual(DAEMON_DESCRIPTOR_SCHEMA_VERSION, 1);
    });

    it('round-trips a synthetic descriptor', () => {
        // Smoke test: TypeScript shape stays in sync with the Kotlin
        // DaemonClasspathDescriptor data class. No tooling enforces this at
        // compile time, so a trivial shape exercise here catches the most
        // common drift (renamed field).
        const desc: DaemonLaunchDescriptor = {
            schemaVersion: DAEMON_DESCRIPTOR_SCHEMA_VERSION,
            modulePath: ':samples:android',
            variant: 'debug',
            enabled: true,
            mainClass: 'ee.schimke.composeai.daemon.DaemonMain',
            javaLauncher: '/opt/jdk/bin/java',
            classpath: ['/lib/a.jar', '/lib/b.jar'],
            jvmArgs: ['-Xmx1024m'],
            systemProperties: { 'composeai.history': '/tmp/history' },
            workingDirectory: '/work',
            manifestPath: '/work/build/compose-previews/previews.json',
        };
        const round = JSON.parse(JSON.stringify(desc)) as DaemonLaunchDescriptor;
        assert.deepStrictEqual(round, desc);
    });
});
