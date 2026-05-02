import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import {
    DAEMON_DESCRIPTOR_SCHEMA_VERSION,
    DaemonLaunchDescriptor,
    DataFetchParams,
    DataFetchResult,
    DataSubscribeParams,
    DataSubscribeResult,
    ERROR_DATA_PRODUCT_BUDGET_EXCEEDED,
    ERROR_DATA_PRODUCT_FETCH_FAILED,
    ERROR_DATA_PRODUCT_NOT_AVAILABLE,
    ERROR_DATA_PRODUCT_UNKNOWN,
    ERROR_HISTORY_DIFF_MISMATCH,
    ERROR_HISTORY_ENTRY_NOT_FOUND,
    ERROR_HISTORY_PIXEL_NOT_IMPLEMENTED,
    FileChangedParams,
    HistoryAddedParams,
    HistoryDiffParams,
    HistoryDiffResult,
    HistoryListParams,
    HistoryListResult,
    HistoryReadParams,
    HistoryReadResult,
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
        // PROTOCOL.md § 3 — `knownDevices` is the daemon's catalog of `@Preview(device = ...)`
        // ids, projected to wire shape so panels can build a picker without re-bundling.
        const known = result.capabilities.knownDevices ?? [];
        assert.ok(known.length >= 1, 'fixture should advertise at least one known device');
        const pixel5 = known.find(d => d.id === 'id:pixel_5');
        assert.ok(pixel5, 'fixture should include id:pixel_5');
        assert.strictEqual(pixel5!.widthDp, 393);
        assert.strictEqual(pixel5!.density, 2.75);
        // PROTOCOL.md § 3 — supportedOverrides advertises which `PreviewOverrides` fields
        // this host actually applies. Desktop omits `localeTag` and `orientation`; the
        // fixture mirrors a desktop daemon so those should be absent.
        const supported = result.capabilities.supportedOverrides ?? [];
        assert.ok(supported.includes('widthPx'), 'desktop should advertise widthPx');
        assert.ok(supported.includes('uiMode'), 'desktop should advertise uiMode');
        assert.ok(supported.includes('device'), 'desktop should advertise device');
        assert.ok(!supported.includes('localeTag'), 'desktop should NOT advertise localeTag');
        assert.ok(!supported.includes('orientation'), 'desktop should NOT advertise orientation');
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

    it('parses client-renderNow-overrides.json with display-property overrides', () => {
        // PROTOCOL.md § 5 — `renderNow.overrides` extends the request shape with optional
        // per-call display overrides. The Kotlin counterpart round-trips the same fixture in
        // MessagesTest, which is the cross-language source of truth for this feature.
        const params = readFixture<RenderNowParams>('client-renderNow-overrides.json');
        assert.strictEqual(params.tier, 'fast');
        assert.ok(params.overrides);
        assert.strictEqual(params.overrides!.widthPx, 600);
        assert.strictEqual(params.overrides!.uiMode, 'dark');
        assert.strictEqual(params.overrides!.localeTag, 'fr-FR');
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

/**
 * H1+H2+H3 wire shapes. No fixtures shipped under
 * `docs/daemon/protocol-fixtures/` for history methods yet (the Kotlin side
 * keeps `entry`/`previewMetadata` as JsonElement and there's no golden
 * corpus); we instead pin the documented sidecar JSON structure from
 * HISTORY.md § "Sidecar metadata schema" so any drift on the doc surface is
 * a test failure here.
 */
describe('history protocol shapes', () => {
    it('error codes match PROTOCOL.md § 5', () => {
        // Pinned constants — change in lockstep with the Kotlin side. The
        // daemon's `JsonRpcServer` returns these literal codes from
        // history/list / history/read / history/diff dispatch.
        assert.strictEqual(ERROR_HISTORY_ENTRY_NOT_FOUND, -32010);
        assert.strictEqual(ERROR_HISTORY_DIFF_MISMATCH, -32011);
        assert.strictEqual(ERROR_HISTORY_PIXEL_NOT_IMPLEMENTED, -32012);
    });

    it('history/list params accept every documented filter', () => {
        // Compile-time + runtime check: every filter listed in PROTOCOL.md
        // § 5 ("history/list") must be assignable to HistoryListParams.
        const params: HistoryListParams = {
            previewId: 'com.example.RedSquare',
            since: '2026-04-29T00:00:00Z',
            until: '2026-04-30T23:59:59Z',
            limit: 50,
            cursor: 'opaque-token',
            branch: 'main',
            branchPattern: '^agent/.*',
            commit: '6af6b8c',
            worktreePath: '/home/yuri/workspace/compose-ai-tools',
            agentId: 'claude-code',
            sourceKind: 'git',
            sourceId: 'git:preview/main@abc123',
        };
        const round = JSON.parse(JSON.stringify(params));
        assert.deepStrictEqual(round, params);
    });

    it('history/list result mirrors the documented field set', () => {
        const result: HistoryListResult = {
            entries: [{ id: '20260430-101234-a1b2c3d4', previewId: 'com.example.X' }],
            nextCursor: 'next-page',
            totalCount: 142,
        };
        assert.strictEqual(result.entries.length, 1);
        assert.strictEqual(result.totalCount, 142);
    });

    it('history/read params + result round-trip', () => {
        const params: HistoryReadParams = { id: '20260430-101234-a1b2c3d4', inline: false };
        const result: HistoryReadResult = {
            entry: { id: params.id, previewId: 'com.example.X' },
            previewMetadata: { displayName: 'X', sourceFile: '/abs/X.kt' },
            pngPath: '/abs/.compose-preview-history/com.example.X/20260430-101234-a1b2c3d4.png',
        };
        assert.strictEqual(result.pngPath.endsWith('.png'), true);
        assert.strictEqual(result.pngBytes, undefined,
            'inline=false omits pngBytes; the client reads from disk');
        assert.deepStrictEqual(JSON.parse(JSON.stringify(params)), params);
    });

    it('history/diff metadata-mode result keeps pixel fields nullable', () => {
        // Per PROTOCOL.md § 5: `diffPx` / `ssim` / `diffPngPath` are reserved
        // for phase H5; in metadata mode they are always undefined or null.
        // A result asserting them as required would lock us into H5 prematurely.
        const params: HistoryDiffParams = { from: 'a1', to: 'a2', mode: 'metadata' };
        const result: HistoryDiffResult = {
            pngHashChanged: true,
            fromMetadata: { id: 'a1' },
            toMetadata: { id: 'a2' },
        };
        assert.strictEqual(params.mode, 'metadata');
        assert.strictEqual(result.diffPx, undefined);
        assert.strictEqual(result.ssim, undefined);
        assert.strictEqual(result.diffPngPath, undefined);
    });

    it('historyAdded notification carries an entry payload', () => {
        // The wire shape from PROTOCOL.md § 6 ("historyAdded"): a single
        // `entry` field whose value is the sidecar JSON. We don't pin the
        // sidecar's full shape here — that lives in HISTORY.md and changes
        // additively — but we do pin the envelope.
        const params: HistoryAddedParams = {
            entry: {
                id: '20260430-101234-a1b2c3d4',
                previewId: 'com.example.RedSquare',
                pngHash: 'a1b2c3d4e5f6789',
            },
        };
        const round = JSON.parse(JSON.stringify(params)) as HistoryAddedParams;
        assert.deepStrictEqual(round, params);
    });
});

/**
 * D1 — data product wire shapes. See `docs/daemon/DATA-PRODUCTS.md` and
 * the round-trip golden fixtures under `docs/daemon/protocol-fixtures/`.
 */
describe('data product protocol shapes', () => {
    it('error codes match DATA-PRODUCTS.md § "Error codes"', () => {
        assert.strictEqual(ERROR_DATA_PRODUCT_UNKNOWN, -32020);
        assert.strictEqual(ERROR_DATA_PRODUCT_NOT_AVAILABLE, -32021);
        assert.strictEqual(ERROR_DATA_PRODUCT_FETCH_FAILED, -32022);
        assert.strictEqual(ERROR_DATA_PRODUCT_BUDGET_EXCEEDED, -32023);
    });

    it('parses client-dataFetch.json into DataFetchParams shape', () => {
        const params = readFixture<DataFetchParams>('client-dataFetch.json');
        assert.strictEqual(params.kind, 'a11y/hierarchy');
        assert.strictEqual(params.previewId, 'com.example.HomeKt#HomePreview');
        // `inline` defaults to false on the wire; the fixture omits it so the
        // Kotlin round-trip with `encodeDefaults = false` stays clean.
        assert.strictEqual(params.inline, undefined);
    });

    it('parses daemon-dataFetchResult.json into DataFetchResult shape', () => {
        const result = readFixture<DataFetchResult>('daemon-dataFetchResult.json');
        assert.strictEqual(result.kind, 'a11y/hierarchy');
        assert.strictEqual(result.schemaVersion, 1);
        assert.ok(result.path && result.path.endsWith('a11y-hierarchy.json'));
        assert.strictEqual(result.payload, undefined);
        assert.strictEqual(result.bytes, undefined);
    });

    it('subscribe / unsubscribe share params shape', () => {
        const params = readFixture<DataSubscribeParams>('client-dataSubscribe.json');
        assert.strictEqual(params.kind, 'a11y/hierarchy');
        assert.strictEqual(params.previewId, 'com.example.HomeKt#HomePreview');
        const result = readFixture<DataSubscribeResult>('daemon-dataSubscribeResult.json');
        assert.strictEqual(result.ok, true);
    });

    it('initialize result advertises data product capabilities', () => {
        // Lock the field's wire spelling — the renderFinished attach path
        // depends on the daemon listing each kind it can produce here.
        const result = readFixture<InitializeResult>('daemon-initializeResult.json');
        assert.ok(Array.isArray(result.capabilities.dataProducts));
        const kinds = result.capabilities.dataProducts.map((c) => c.kind);
        assert.ok(kinds.includes('a11y/atf'));
        assert.ok(kinds.includes('a11y/hierarchy'));
    });

    it('renderFinished can carry per-kind data product attachments', () => {
        const params = readFixture<RenderFinishedParams>(
            'daemon-renderFinished-withDataProducts.json',
        );
        assert.ok(Array.isArray(params.dataProducts));
        const atf = params.dataProducts!.find((p) => p.kind === 'a11y/atf');
        const hierarchy = params.dataProducts!.find((p) => p.kind === 'a11y/hierarchy');
        // Inline payload for atf, path for hierarchy — exercises both transports.
        assert.ok(atf?.payload);
        assert.ok(hierarchy?.path);
    });
});
