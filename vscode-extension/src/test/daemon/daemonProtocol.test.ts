import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import {
    ClasspathDirtyParams,
    DaemonReadyParams,
    DaemonWarmingParams,
    DiscoveryUpdatedParams,
    FileChangedParams,
    InitializeParams,
    InitializeResult,
    JsonRpcNotification,
    JsonRpcRequest,
    JsonRpcResponse,
    JSON_RPC_ERROR_CODES,
    LogParams,
    METHODS,
    PROTOCOL_VERSION,
    RejectedRender,
    RenderFailedParams,
    RenderFinishedParams,
    RenderNowParams,
    RenderNowResult,
    RenderStartedParams,
    SandboxRecycleParams,
    SetFocusParams,
    SetVisibleParams,
} from '../../daemon/daemonProtocol';

/**
 * Fixture corpus lives at docs/daemon/protocol-fixtures/. The Kotlin daemon
 * test suite loads the same files (PROTOCOL.md § 9). Both sides are the
 * canonical "the corpus parses cleanly" test.
 *
 * Resolved relative to this file's compiled location at
 * `vscode-extension/out/test/daemon/`. Walk up four levels to the workspace
 * root, then down into docs/.
 */
const FIXTURES_DIR = path.resolve(
    __dirname,
    '..', // out/test
    '..', // out
    '..', // vscode-extension
    '..', // workspace root
    'docs',
    'daemon',
    'protocol-fixtures',
);

function loadFixture<T>(name: string): T {
    const filePath = path.join(FIXTURES_DIR, name);
    const raw = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(raw) as T;
}

describe('daemonProtocol fixtures', () => {

    // -----------------------------------------------------------------------
    // Lifecycle (PROTOCOL.md § 3)
    // -----------------------------------------------------------------------

    it('client-initialize.json parses as InitializeParams', () => {
        const params = loadFixture<InitializeParams>('client-initialize.json');
        assert.strictEqual(params.protocolVersion, PROTOCOL_VERSION);
        assert.strictEqual(typeof params.clientVersion, 'string');
        assert.strictEqual(typeof params.workspaceRoot, 'string');
        assert.strictEqual(typeof params.moduleId, 'string');
        assert.strictEqual(typeof params.moduleProjectDir, 'string');
        assert.strictEqual(typeof params.capabilities.visibility, 'boolean');
        assert.strictEqual(typeof params.capabilities.metrics, 'boolean');
        // options fixture exercises every optional field.
        assert.ok(params.options);
        assert.strictEqual(typeof params.options.maxHeapMb, 'number');
        assert.strictEqual(typeof params.options.warmSpare, 'boolean');
        assert.ok(['off', 'light', 'heavy'].includes(params.options.detectLeaks!));
        assert.strictEqual(typeof params.options.foreground, 'boolean');
    });

    it('daemon-initializeResult.json parses as InitializeResult', () => {
        const result = loadFixture<InitializeResult>('daemon-initializeResult.json');
        assert.strictEqual(result.protocolVersion, PROTOCOL_VERSION);
        assert.strictEqual(typeof result.daemonVersion, 'string');
        assert.strictEqual(typeof result.pid, 'number');
        assert.strictEqual(typeof result.capabilities.incrementalDiscovery, 'boolean');
        assert.strictEqual(typeof result.capabilities.sandboxRecycle, 'boolean');
        assert.ok(Array.isArray(result.capabilities.leakDetection));
        for (const mode of result.capabilities.leakDetection) {
            assert.ok(['light', 'heavy'].includes(mode));
        }
        assert.strictEqual(typeof result.classpathFingerprint, 'string');
        assert.strictEqual(typeof result.manifest.path, 'string');
        assert.strictEqual(typeof result.manifest.previewCount, 'number');
    });

    // -----------------------------------------------------------------------
    // Client → daemon notifications (§ 4)
    // -----------------------------------------------------------------------

    it('client-setVisible.json parses as SetVisibleParams', () => {
        const params = loadFixture<SetVisibleParams>('client-setVisible.json');
        assert.ok(Array.isArray(params.ids));
        for (const id of params.ids) {
            assert.strictEqual(typeof id, 'string');
        }
    });

    it('client-setFocus.json parses as SetFocusParams', () => {
        const params = loadFixture<SetFocusParams>('client-setFocus.json');
        assert.ok(Array.isArray(params.ids));
        for (const id of params.ids) {
            assert.strictEqual(typeof id, 'string');
        }
    });

    it('client-fileChanged.json parses as FileChangedParams', () => {
        const params = loadFixture<FileChangedParams>('client-fileChanged.json');
        assert.strictEqual(typeof params.path, 'string');
        assert.ok(['source', 'resource', 'classpath'].includes(params.kind));
        assert.ok(['modified', 'created', 'deleted'].includes(params.changeType));
    });

    // -----------------------------------------------------------------------
    // Client → daemon requests (§ 5)
    // -----------------------------------------------------------------------

    it('client-renderNow.json parses as RenderNowParams', () => {
        const params = loadFixture<RenderNowParams>('client-renderNow.json');
        assert.ok(Array.isArray(params.previews));
        for (const id of params.previews) {
            assert.strictEqual(typeof id, 'string');
        }
        assert.ok(['fast', 'full'].includes(params.tier));
        if (params.reason !== undefined) {
            assert.strictEqual(typeof params.reason, 'string');
        }
    });

    it('daemon-renderNowResult.json parses as RenderNowResult', () => {
        const result = loadFixture<RenderNowResult>('daemon-renderNowResult.json');
        assert.ok(Array.isArray(result.queued));
        assert.ok(Array.isArray(result.rejected));
        for (const r of result.rejected as RejectedRender[]) {
            assert.strictEqual(typeof r.id, 'string');
            assert.strictEqual(typeof r.reason, 'string');
        }
    });

    // -----------------------------------------------------------------------
    // Daemon → client notifications (§ 6)
    // -----------------------------------------------------------------------

    it('daemon-discoveryUpdated.json parses as DiscoveryUpdatedParams', () => {
        const params = loadFixture<DiscoveryUpdatedParams>('daemon-discoveryUpdated.json');
        assert.ok(Array.isArray(params.added));
        assert.ok(Array.isArray(params.removed));
        assert.ok(Array.isArray(params.changed));
        assert.strictEqual(typeof params.totalPreviews, 'number');
        for (const id of params.removed) {
            assert.strictEqual(typeof id, 'string');
        }
    });

    it('daemon-renderStarted.json parses as RenderStartedParams', () => {
        const params = loadFixture<RenderStartedParams>('daemon-renderStarted.json');
        assert.strictEqual(typeof params.id, 'string');
        assert.strictEqual(typeof params.queuedMs, 'number');
    });

    it('daemon-renderFinished.json parses as RenderFinishedParams', () => {
        const params = loadFixture<RenderFinishedParams>('daemon-renderFinished.json');
        assert.strictEqual(typeof params.id, 'string');
        assert.strictEqual(typeof params.pngPath, 'string');
        assert.strictEqual(typeof params.tookMs, 'number');
        assert.ok(params.metrics);
        assert.strictEqual(typeof params.metrics.heapAfterGcMb, 'number');
        assert.strictEqual(typeof params.metrics.nativeHeapMb, 'number');
        assert.strictEqual(typeof params.metrics.sandboxAgeRenders, 'number');
        assert.strictEqual(typeof params.metrics.sandboxAgeMs, 'number');
    });

    it('daemon-renderFailed.json parses as RenderFailedParams', () => {
        const params = loadFixture<RenderFailedParams>('daemon-renderFailed.json');
        assert.strictEqual(typeof params.id, 'string');
        assert.ok(['compile', 'runtime', 'capture', 'timeout', 'internal']
            .includes(params.error.kind));
        assert.strictEqual(typeof params.error.message, 'string');
        if (params.error.stackTrace !== undefined) {
            assert.strictEqual(typeof params.error.stackTrace, 'string');
        }
    });

    it('daemon-classpathDirty.json parses as ClasspathDirtyParams', () => {
        const params = loadFixture<ClasspathDirtyParams>('daemon-classpathDirty.json');
        assert.ok(['fingerprintMismatch', 'fileChanged', 'manifestMissing']
            .includes(params.reason));
        assert.strictEqual(typeof params.detail, 'string');
        if (params.changedPaths !== undefined) {
            assert.ok(Array.isArray(params.changedPaths));
            for (const p of params.changedPaths) {
                assert.strictEqual(typeof p, 'string');
            }
        }
    });

    it('daemon-sandboxRecycle.json parses as SandboxRecycleParams', () => {
        const params = loadFixture<SandboxRecycleParams>('daemon-sandboxRecycle.json');
        assert.ok([
            'heapCeiling',
            'heapDrift',
            'renderTimeDrift',
            'histogramDrift',
            'renderCount',
            'leakSuspected',
            'manual',
        ].includes(params.reason));
        assert.strictEqual(typeof params.ageMs, 'number');
        assert.strictEqual(typeof params.renderCount, 'number');
        assert.strictEqual(typeof params.warmSpareReady, 'boolean');
    });

    it('daemon-daemonWarming.json parses as DaemonWarmingParams', () => {
        const params = loadFixture<DaemonWarmingParams>('daemon-daemonWarming.json');
        assert.strictEqual(typeof params.etaMs, 'number');
    });

    it('daemon-daemonReady.json parses as DaemonReadyParams', () => {
        // Empty object — no fields. Just assert the parse succeeds and the
        // result is an empty object literal per PROTOCOL.md § 6.
        const params = loadFixture<DaemonReadyParams>('daemon-daemonReady.json');
        assert.deepStrictEqual(params, {});
    });

    it('daemon-log.json parses as LogParams', () => {
        const params = loadFixture<LogParams>('daemon-log.json');
        assert.ok(['debug', 'info', 'warn', 'error'].includes(params.level));
        assert.strictEqual(typeof params.message, 'string');
        if (params.category !== undefined) {
            assert.strictEqual(typeof params.category, 'string');
        }
        if (params.context !== undefined) {
            assert.strictEqual(typeof params.context, 'object');
        }
    });

    // -----------------------------------------------------------------------
    // Envelope (§ 2) — exercise the JSON-RPC types end-to-end.
    // -----------------------------------------------------------------------

    it('envelope-request.json parses as JsonRpcRequest<RenderNowParams>', () => {
        const env = loadFixture<JsonRpcRequest<RenderNowParams>>('envelope-request.json');
        assert.strictEqual(env.jsonrpc, '2.0');
        assert.strictEqual(typeof env.id, 'number');
        assert.strictEqual(env.method, METHODS.RenderNow);
        assert.ok(env.params);
        assert.ok(Array.isArray(env.params.previews));
        assert.ok(['fast', 'full'].includes(env.params.tier));
    });

    it('envelope-response.json parses as JsonRpcResponse<RenderNowResult>', () => {
        const env = loadFixture<JsonRpcResponse<RenderNowResult>>('envelope-response.json');
        assert.strictEqual(env.jsonrpc, '2.0');
        assert.strictEqual(typeof env.id, 'number');
        assert.ok(env.result);
        assert.ok(Array.isArray(env.result.queued));
        assert.ok(Array.isArray(env.result.rejected));
        assert.strictEqual(env.error, undefined);
    });

    it('envelope-notification.json parses as JsonRpcNotification<RenderStartedParams>', () => {
        const env = loadFixture<JsonRpcNotification<RenderStartedParams>>(
            'envelope-notification.json',
        );
        assert.strictEqual(env.jsonrpc, '2.0');
        assert.strictEqual(env.method, METHODS.RenderStarted);
        assert.ok(env.params);
        assert.strictEqual(typeof env.params.id, 'string');
        assert.strictEqual(typeof env.params.queuedMs, 'number');
        assert.ok(!('id' in env));
    });

    it('envelope-errorResponse.json parses as JsonRpcResponse with daemon-specific code', () => {
        const env = loadFixture<JsonRpcResponse>('envelope-errorResponse.json');
        assert.strictEqual(env.jsonrpc, '2.0');
        assert.strictEqual(typeof env.id, 'number');
        assert.ok(env.error);
        assert.strictEqual(env.error.code, JSON_RPC_ERROR_CODES.ClasspathDirty);
        assert.strictEqual(typeof env.error.message, 'string');
        assert.strictEqual(env.error.data?.kind, 'ClasspathDirty');
    });

    // -----------------------------------------------------------------------
    // Sanity: the error-code map has the full set from PROTOCOL.md § 2.
    // -----------------------------------------------------------------------

    it('JSON_RPC_ERROR_CODES covers every code in PROTOCOL.md § 2', () => {
        assert.strictEqual(JSON_RPC_ERROR_CODES.ParseError, -32700);
        assert.strictEqual(JSON_RPC_ERROR_CODES.InvalidRequest, -32600);
        assert.strictEqual(JSON_RPC_ERROR_CODES.MethodNotFound, -32601);
        assert.strictEqual(JSON_RPC_ERROR_CODES.InvalidParams, -32602);
        assert.strictEqual(JSON_RPC_ERROR_CODES.InternalError, -32603);
        assert.strictEqual(JSON_RPC_ERROR_CODES.NotInitialized, -32001);
        assert.strictEqual(JSON_RPC_ERROR_CODES.ClasspathDirty, -32002);
        assert.strictEqual(JSON_RPC_ERROR_CODES.SandboxRecycling, -32003);
        assert.strictEqual(JSON_RPC_ERROR_CODES.UnknownPreview, -32004);
        assert.strictEqual(JSON_RPC_ERROR_CODES.RenderFailed, -32005);
    });
});
