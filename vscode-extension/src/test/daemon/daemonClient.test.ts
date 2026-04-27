import * as assert from 'assert';
import { PassThrough } from 'stream';
import { DaemonClient, DaemonRpcError } from '../../daemon/daemonClient';
import {
    InitializeResult,
    JSON_RPC_ERROR_CODES,
    JsonRpcNotification,
    JsonRpcRequest,
    JsonRpcResponse,
    METHODS,
    PROTOCOL_VERSION,
    RenderFinishedParams,
    RenderNowResult,
    RenderStartedParams,
} from '../../daemon/daemonProtocol';

/**
 * **Why in-process instead of a real subprocess.**
 *
 * The C1.3 DoD says "integration test against the real daemon JAR" with the
 * explicit fallback: "If real-subprocess testing is awkward (CI environment
 * without `:renderer-android-daemon` built), in-process via piped streams +
 * a fake stdio is acceptable; document why."
 *
 * Spawning the real `:renderer-android-daemon` JAR requires:
 *   - The Gradle build to have produced the daemon JAR + every transitive
 *     classpath entry the consumer module needs (Compose, Robolectric,
 *     AndroidX, ~hundreds of MB).
 *   - A working `composePreviewDaemonStart` run on `samples/android` to
 *     emit `daemon-launch.json`.
 *   - The descriptor's `enabled = true` (currently false by default —
 *     see CONFIG.md).
 *   - A JDK with jlink available (the existing extension's JdkImageError
 *     case demonstrates this is non-trivial in CI).
 *
 * None of that is in the Node-driven `npm test` runtime. The
 * `JsonRpcServerIntegrationTest` on the daemon side already drives
 * initialize → initialized → renderNow → renderStarted → renderFinished →
 * shutdown → exit end-to-end against the real DaemonHost (B1.5 commit
 * 7436b98). What the TypeScript side needs to verify is that our
 * framing + dispatch + lifecycle logic talks the same protocol — and
 * that's exactly what these in-process tests do, by playing the daemon
 * role over piped streams against the real DaemonClient.
 *
 * If we ever produce a self-contained daemon stub JAR (no Robolectric, no
 * Compose — just the JsonRpcServer wired to a synthetic host), this test
 * gets upgraded to spawn it.
 */

interface ParsedFrame {
    headers: Map<string, string>;
    payload: string;
}

/**
 * Strips Content-Length frames out of a Buffer of bytes the client wrote
 * via the LSP framer. Returns parsed frames in order.
 */
function* parseFrames(buf: Buffer): Generator<ParsedFrame> {
    let offset = 0;
    while (offset < buf.length) {
        // Find header terminator (\r\n\r\n).
        let term = -1;
        for (let i = offset; i + 3 < buf.length; i++) {
            if (buf[i] === 0x0d && buf[i + 1] === 0x0a
                && buf[i + 2] === 0x0d && buf[i + 3] === 0x0a) {
                term = i;
                break;
            }
        }
        if (term < 0) { return; }
        const headerText = buf.slice(offset, term).toString('ascii');
        const headers = new Map<string, string>();
        for (const line of headerText.split(/\r?\n/)) {
            if (!line) { continue; }
            const c = line.indexOf(':');
            if (c <= 0) { continue; }
            headers.set(line.slice(0, c).trim().toLowerCase(), line.slice(c + 1).trim());
        }
        const length = parseInt(headers.get('content-length') ?? '-1', 10);
        if (length < 0 || term + 4 + length > buf.length) { return; }
        const payload = buf.slice(term + 4, term + 4 + length).toString('utf-8');
        offset = term + 4 + length;
        yield { headers, payload };
    }
}

/**
 * Test harness — gives you a DaemonClient wired to two PassThrough streams
 * and a `daemon` actor that can read what the client sent and reply.
 */
function makeHarness() {
    const clientToServer = new PassThrough();
    const serverToClient = new PassThrough();
    const client = new DaemonClient({
        workspaceRoot: '/ws',
        moduleId: ':samples:android',
        moduleProjectDir: '/ws/samples/android',
        clientVersion: '0.0.0-test',
    });
    client.attach(serverToClient, clientToServer);

    const sentBytes: Buffer[] = [];
    clientToServer.on('data', (chunk: Buffer) => sentBytes.push(chunk));

    const writeFrame = (envelope: object) => {
        const json = JSON.stringify(envelope);
        const payload = Buffer.from(json, 'utf-8');
        const header = Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`, 'ascii');
        serverToClient.write(Buffer.concat([header, payload]));
    };

    const readSent = (): ParsedFrame[] => {
        const all = Buffer.concat(sentBytes);
        return [...parseFrames(all)];
    };

    return { client, writeFrame, readSent, clientToServer, serverToClient };
}

/**
 * Wait for the next frame matching `predicate`. Polls every 5ms up to 1s.
 */
async function waitForFrame(
    readSent: () => ParsedFrame[],
    predicate: (f: ParsedFrame) => boolean,
    timeoutMs = 1000,
): Promise<ParsedFrame> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const frames = readSent();
        const match = frames.find(predicate);
        if (match) { return match; }
        await new Promise((r) => setTimeout(r, 5));
    }
    throw new Error('timed out waiting for frame');
}

describe('DaemonClient (in-process daemon)', () => {

    it('initialize → initialized → renderNow → renderStarted → renderFinished round-trip', async () => {
        const { client, writeFrame, readSent } = makeHarness();

        // --- initialize ---
        const initPromise = client.initialize();
        const initFrame = await waitForFrame(readSent, (f) =>
            JSON.parse(f.payload).method === METHODS.Initialize);
        const initReq = JSON.parse(initFrame.payload) as JsonRpcRequest;
        assert.strictEqual(initReq.jsonrpc, '2.0');
        assert.strictEqual(initReq.method, METHODS.Initialize);
        const initParams = initReq.params as Record<string, unknown>;
        assert.strictEqual(initParams.protocolVersion, PROTOCOL_VERSION);
        assert.strictEqual(initParams.moduleId, ':samples:android');
        assert.strictEqual(initParams.workspaceRoot, '/ws');
        assert.strictEqual(initParams.moduleProjectDir, '/ws/samples/android');

        const initResult: InitializeResult = {
            protocolVersion: PROTOCOL_VERSION,
            daemonVersion: '0.0.0-test',
            pid: 99999,
            capabilities: {
                incrementalDiscovery: false,
                sandboxRecycle: true,
                leakDetection: [],
            },
            classpathFingerprint: 'a'.repeat(64),
            manifest: {
                path: '/ws/samples/android/build/compose-previews/previews.json',
                previewCount: 1,
            },
        };
        writeFrame({ jsonrpc: '2.0', id: initReq.id, result: initResult } as JsonRpcResponse);

        const result = await initPromise;
        assert.strictEqual(result.daemonVersion, '0.0.0-test');
        assert.strictEqual(result.manifest.previewCount, 1);
        assert.strictEqual(client.isInitialized, true);
        assert.strictEqual(client.isHealthy(), true);

        // The client must have followed up with the `initialized` notification.
        await waitForFrame(readSent, (f) => {
            const env = JSON.parse(f.payload);
            return env.method === METHODS.Initialized && env.id === undefined;
        });

        // --- renderNow ---
        const renderPromise = client.renderPreviews(':samples:android', 'fast');
        const renderFrame = await waitForFrame(readSent, (f) =>
            JSON.parse(f.payload).method === METHODS.RenderNow);
        const renderReq = JSON.parse(renderFrame.payload) as JsonRpcRequest;
        const renderParams = renderReq.params as { previews: string[]; tier: string };
        assert.deepStrictEqual(renderParams.previews, []);
        assert.strictEqual(renderParams.tier, 'fast');

        const renderResult: RenderNowResult = { queued: ['preview1'], rejected: [] };
        writeFrame({ jsonrpc: '2.0', id: renderReq.id, result: renderResult });

        // The renderPreviews promise resolves with the cached manifest.
        const manifest = await renderPromise;
        assert.deepStrictEqual(manifest, {
            manifestPath: '/ws/samples/android/build/compose-previews/previews.json',
        });

        // --- renderStarted / renderFinished notifications ---
        const renderStartedSeen: RenderStartedParams[] = [];
        const renderFinishedSeen: RenderFinishedParams[] = [];
        client.events.on('renderStarted', (p) => renderStartedSeen.push(p));
        client.events.on('renderFinished', (p) => renderFinishedSeen.push(p));

        writeFrame({
            jsonrpc: '2.0',
            method: METHODS.RenderStarted,
            params: { id: 'preview1', queuedMs: 7 },
        } satisfies JsonRpcNotification);
        writeFrame({
            jsonrpc: '2.0',
            method: METHODS.RenderFinished,
            params: {
                id: 'preview1',
                pngPath: '/tmp/preview1.png',
                tookMs: 42,
            },
        } satisfies JsonRpcNotification);

        // Allow the framer 'data' callback to run.
        await new Promise((r) => setTimeout(r, 20));
        assert.strictEqual(renderStartedSeen.length, 1);
        assert.strictEqual(renderStartedSeen[0].id, 'preview1');
        assert.strictEqual(renderFinishedSeen.length, 1);
        assert.strictEqual(renderFinishedSeen[0].pngPath, '/tmp/preview1.png');
    });

    it('shutdown → exit drains in-flight renders before sending exit', async () => {
        const { client, writeFrame, readSent } = makeHarness();

        // Stub-initialize.
        const initPromise = client.initialize();
        const initFrame = await waitForFrame(readSent, (f) =>
            JSON.parse(f.payload).method === METHODS.Initialize);
        writeFrame({
            jsonrpc: '2.0',
            id: JSON.parse(initFrame.payload).id,
            result: {
                protocolVersion: PROTOCOL_VERSION,
                daemonVersion: 'test',
                pid: 1,
                capabilities: { incrementalDiscovery: false, sandboxRecycle: true, leakDetection: [] },
                classpathFingerprint: '',
                manifest: { path: '/m.json', previewCount: 0 },
            } satisfies InitializeResult,
        });
        await initPromise;

        // Kick off shutdown — it sends a request and waits for the reply.
        const disposePromise = client.dispose(2_000);

        const shutdownFrame = await waitForFrame(readSent, (f) =>
            JSON.parse(f.payload).method === METHODS.Shutdown);
        const shutdownReq = JSON.parse(shutdownFrame.payload) as JsonRpcRequest;

        // Before the daemon resolves shutdown, no `exit` should have been sent.
        // (Mirrors PROTOCOL.md § 3 — `shutdown` is a request so the client
        // can wait for in-flight renders to drain.)
        const beforeReply = readSent();
        assert.strictEqual(
            beforeReply.find((f) => JSON.parse(f.payload).method === METHODS.Exit),
            undefined,
            'exit must NOT be sent before shutdown reply',
        );

        // Daemon resolves shutdown after draining.
        writeFrame({ jsonrpc: '2.0', id: shutdownReq.id, result: null });

        await disposePromise;

        // Exit must have been sent after the shutdown reply.
        const afterReply = readSent();
        const exitFrame = afterReply.find((f) => JSON.parse(f.payload).method === METHODS.Exit);
        assert.ok(exitFrame, 'exit notification must be sent after shutdown reply');
        // Exit is a notification (no id).
        const exitEnv = JSON.parse(exitFrame!.payload);
        assert.strictEqual(exitEnv.id, undefined);
    });

    it('surfaces JSON-RPC errors as DaemonRpcError', async () => {
        const { client, writeFrame, readSent } = makeHarness();
        const initPromise = client.initialize();
        const initFrame = await waitForFrame(readSent, (f) =>
            JSON.parse(f.payload).method === METHODS.Initialize);
        writeFrame({
            jsonrpc: '2.0',
            id: JSON.parse(initFrame.payload).id,
            error: {
                code: JSON_RPC_ERROR_CODES.ClasspathDirty,
                message: 'classpath dirty',
                data: { kind: 'ClasspathDirty' },
            },
        });
        try {
            await initPromise;
            assert.fail('expected initialize to reject');
        } catch (e) {
            assert.ok(e instanceof DaemonRpcError);
            assert.strictEqual(e.rpcError.code, JSON_RPC_ERROR_CODES.ClasspathDirty);
            assert.strictEqual(e.method, METHODS.Initialize);
        }
    });

    it('classpathDirty notification fires onClasspathDirty callback', async () => {
        const dirtySeen: string[] = [];
        const clientToServer = new PassThrough();
        const serverToClient = new PassThrough();
        const client = new DaemonClient({
            workspaceRoot: '/ws',
            moduleId: ':samples:android',
            moduleProjectDir: '/ws/samples/android',
            clientVersion: 'test',
            onClasspathDirty: (p) => dirtySeen.push(p.detail),
        });
        client.attach(serverToClient, clientToServer);

        const writeFrame = (envelope: object) => {
            const payload = Buffer.from(JSON.stringify(envelope), 'utf-8');
            const header = Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`, 'ascii');
            serverToClient.write(Buffer.concat([header, payload]));
        };

        writeFrame({
            jsonrpc: '2.0',
            method: METHODS.ClasspathDirty,
            params: { reason: 'fingerprintMismatch', detail: 'libs.versions.toml SHA changed' },
        });
        await new Promise((r) => setTimeout(r, 20));
        assert.deepStrictEqual(dirtySeen, ['libs.versions.toml SHA changed']);
    });

    it('framer handles split frames (chunked stdout)', async () => {
        const clientToServer = new PassThrough();
        const serverToClient = new PassThrough();
        const client = new DaemonClient({
            workspaceRoot: '/ws',
            moduleId: ':a:b',
            moduleProjectDir: '/ws/a/b',
            clientVersion: 'test',
        });
        client.attach(serverToClient, clientToServer);

        const seen: string[] = [];
        client.events.on('log', (p) => seen.push(p.message));

        const env = JSON.stringify({
            jsonrpc: '2.0',
            method: METHODS.Log,
            params: { level: 'info', message: 'hello' },
        });
        const payload = Buffer.from(env, 'utf-8');
        const header = Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`, 'ascii');
        const full = Buffer.concat([header, payload]);
        // Trickle one byte at a time.
        for (let i = 0; i < full.length; i++) {
            serverToClient.write(full.slice(i, i + 1));
        }
        await new Promise((r) => setTimeout(r, 30));
        assert.deepStrictEqual(seen, ['hello']);
    });
});
