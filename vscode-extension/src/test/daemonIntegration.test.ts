import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { PassThrough } from 'stream';
import { DaemonClient } from '../daemon/daemonClient';
import { encodeFrame, FrameDecoder } from '../daemon/daemonFraming';
import {
    InitializeParams,
    JsonRpcRequest,
    PROTOCOL_VERSION,
    RenderNowParams,
} from '../daemon/daemonProtocol';

/**
 * Tiny in-process fake daemon. Speaks the v1 wire format over the same kind
 * of stdio pair that the real DaemonClient uses against a spawned JVM.
 * Lets us exercise the whole framing + JSON-RPC + dispatch pipeline without
 * actually launching Java.
 */
class FakeDaemon {
    public readonly toClient: PassThrough;
    public readonly fromClient: PassThrough;
    public readonly seenRequests: JsonRpcRequest[] = [];
    public readonly seenNotifications: { method: string; params: unknown }[] = [];

    private readonly decoder: FrameDecoder;
    private onInitialize: ((req: JsonRpcRequest) => void) | null = null;
    private onRenderNow: ((req: JsonRpcRequest, params: RenderNowParams) => void) | null = null;

    constructor() {
        this.toClient = new PassThrough();
        this.fromClient = new PassThrough();
        this.decoder = new FrameDecoder({
            onMessage: (json) => this.handle(JSON.parse(json)),
            onError: (err) => assert.fail(`fake daemon framing: ${err.message}`),
        });
        this.fromClient.on('data', (chunk: Buffer) => this.decoder.push(chunk));
    }

    onInit(handler: (req: JsonRpcRequest) => void): void { this.onInitialize = handler; }
    onRender(handler: (req: JsonRpcRequest, params: RenderNowParams) => void): void {
        this.onRenderNow = handler;
    }

    sendNotification(method: string, params: unknown): void {
        this.toClient.write(encodeFrame({ jsonrpc: '2.0', method, params }));
    }

    sendResult(id: number, result: unknown): void {
        this.toClient.write(encodeFrame({ jsonrpc: '2.0', id, result }));
    }

    close(): void {
        this.toClient.end();
        this.fromClient.end();
    }

    private handle(msg: JsonRpcRequest | { jsonrpc: '2.0'; method: string; params: unknown }): void {
        const m = msg as JsonRpcRequest;
        if (typeof m.id === 'number') {
            this.seenRequests.push(m);
            if (m.method === 'initialize' && this.onInitialize) { this.onInitialize(m); }
            else if (m.method === 'renderNow' && this.onRenderNow) {
                this.onRenderNow(m, m.params as RenderNowParams);
            } else if (m.method === 'shutdown') {
                this.sendResult(m.id, null);
            }
        } else {
            this.seenNotifications.push({ method: m.method, params: m.params });
        }
    }
}

function defaultInitResult(): unknown {
    return {
        protocolVersion: PROTOCOL_VERSION,
        daemonVersion: '0.0.0-fake',
        pid: 4321,
        capabilities: {
            incrementalDiscovery: false,
            sandboxRecycle: true,
            leakDetection: ['light'],
        },
        classpathFingerprint: 'a'.repeat(64),
        manifest: { path: '/m', previewCount: 5 },
    };
}

describe('daemon integration — fake daemon round trip', () => {
    it('initialize → setVisible → renderNow → renderFinished → close', async () => {
        const daemon = new FakeDaemon();
        daemon.onInit((req) => daemon.sendResult(req.id, defaultInitResult()));

        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'daemon-int-'));
        try {
            const pngPath = path.join(tmpDir, 'render.png');
            const pngBytes = Buffer.from('PNG-bytes-stand-in', 'utf-8');
            fs.writeFileSync(pngPath, pngBytes);

            const finishedSeen: { id: string; pngPath: string }[] = [];
            const startedSeen: string[] = [];
            const client = new DaemonClient(daemon.fromClient, daemon.toClient, {
                onRenderStarted: (p) => startedSeen.push(p.id),
                onRenderFinished: (p) => finishedSeen.push({ id: p.id, pngPath: p.pngPath }),
            });

            // 1. initialize handshake
            const initParams: Omit<InitializeParams, 'protocolVersion'> = {
                clientVersion: '0.0.0',
                workspaceRoot: '/w',
                moduleId: ':m',
                moduleProjectDir: '/w/m',
                capabilities: { visibility: true, metrics: true },
            };
            const init = await client.initialize(initParams);
            assert.strictEqual(init.daemonVersion, '0.0.0-fake');
            client.initialized();

            // 2. setVisible / setFocus notifications — verified by side
            //    inspection in daemon.seenNotifications.
            client.setVisible({ ids: ['p1', 'p2'] });
            client.setFocus({ ids: ['p1'] });

            // 3. renderNow → daemon responds with queued result, then
            //    pushes renderStarted + renderFinished as a side-effect.
            daemon.onRender((req, params) => {
                daemon.sendResult(req.id, { queued: params.previews, rejected: [] });
                daemon.sendNotification('renderStarted', { id: 'p1', queuedMs: 1 });
                daemon.sendNotification('renderFinished', {
                    id: 'p1',
                    pngPath,
                    tookMs: 99,
                });
            });
            const result = await client.renderNow({ previews: ['p1'], tier: 'fast' });
            assert.deepStrictEqual(result.queued, ['p1']);

            // Microtask drain so the notifications dispatched on the
            // toClient stream reach our handlers.
            await new Promise((r) => setImmediate(r));
            assert.deepStrictEqual(startedSeen, ['p1']);
            assert.deepStrictEqual(finishedSeen, [{ id: 'p1', pngPath }]);

            // 4. Wire-side assertions on what the daemon saw.
            const methods = daemon.seenRequests.map((r) => r.method);
            assert.deepStrictEqual(methods, ['initialize', 'renderNow']);
            const notifMethods = daemon.seenNotifications.map((n) => n.method);
            assert.deepStrictEqual(notifMethods, ['initialized', 'setVisible', 'setFocus']);

            // 5. Clean shutdown — daemon resolves the request, client is
            //    free to call exit() (a notification with no response).
            await client.shutdown();
            client.exit();
            assert.ok(daemon.seenNotifications.some((n) => n.method === 'exit'));
        } finally {
            daemon.close();
            fs.rmSync(tmpDir, { recursive: true });
        }
    });

    it('survives the daemon emitting renderFailed instead of renderFinished', async () => {
        const daemon = new FakeDaemon();
        daemon.onInit((req) => daemon.sendResult(req.id, defaultInitResult()));
        const failures: string[] = [];
        const client = new DaemonClient(daemon.fromClient, daemon.toClient, {
            onRenderFailed: (p) => failures.push(p.error.message),
        });
        try {
            await client.initialize({
                clientVersion: '0.0.0',
                workspaceRoot: '/w',
                moduleId: ':m',
                moduleProjectDir: '/w/m',
                capabilities: { visibility: true, metrics: true },
            });
            client.initialized();
            daemon.onRender((req) => {
                daemon.sendResult(req.id, { queued: ['p1'], rejected: [] });
                daemon.sendNotification('renderFailed', {
                    id: 'p1',
                    error: { kind: 'runtime', message: 'NPE in composable' },
                });
            });
            await client.renderNow({ previews: ['p1'], tier: 'fast' });
            await new Promise((r) => setImmediate(r));
            assert.deepStrictEqual(failures, ['NPE in composable']);
        } finally {
            daemon.close();
        }
    });

    it('classpathDirty notification reaches the client and the channel stays open', async () => {
        // PROTOCOL.md § 6: classpathDirty is delivered before the daemon
        // exits within classpathDirtyGraceMs. Until the stream closes the
        // client should still process notifications — for example, it's
        // legal for the daemon to flush a final `log` before exiting.
        const daemon = new FakeDaemon();
        daemon.onInit((req) => daemon.sendResult(req.id, defaultInitResult()));
        const dirtySeen: string[] = [];
        const logsSeen: string[] = [];
        const client = new DaemonClient(daemon.fromClient, daemon.toClient, {
            onClasspathDirty: (p) => dirtySeen.push(p.detail),
            onLog: (p) => logsSeen.push(p.message),
        });
        try {
            await client.initialize({
                clientVersion: '0.0.0',
                workspaceRoot: '/w',
                moduleId: ':m',
                moduleProjectDir: '/w/m',
                capabilities: { visibility: true, metrics: true },
            });
            client.initialized();
            daemon.sendNotification('classpathDirty', {
                reason: 'fingerprintMismatch',
                detail: 'libs.versions.toml SHA changed',
            });
            daemon.sendNotification('log', {
                level: 'info',
                message: 'exiting in 2s',
            });
            await new Promise((r) => setImmediate(r));
            assert.deepStrictEqual(dirtySeen, ['libs.versions.toml SHA changed']);
            assert.deepStrictEqual(logsSeen, ['exiting in 2s']);
        } finally {
            daemon.close();
        }
    });

    it('handles a rapid burst of renderFinished notifications without losing any', async () => {
        // The render-watcher thread on the daemon side can emit notifications
        // back-to-back; the framing layer must not coalesce or lose any.
        const daemon = new FakeDaemon();
        daemon.onInit((req) => daemon.sendResult(req.id, defaultInitResult()));
        const seen: string[] = [];
        const client = new DaemonClient(daemon.fromClient, daemon.toClient, {
            onRenderFinished: (p) => seen.push(p.id),
        });
        try {
            await client.initialize({
                clientVersion: '0.0.0',
                workspaceRoot: '/w',
                moduleId: ':m',
                moduleProjectDir: '/w/m',
                capabilities: { visibility: true, metrics: true },
            });
            client.initialized();
            const ids = Array.from({ length: 20 }, (_, i) => `p${i}`);
            for (const id of ids) {
                daemon.sendNotification('renderFinished', { id, pngPath: '/x.png', tookMs: 1 });
            }
            await new Promise((r) => setImmediate(r));
            assert.deepStrictEqual(seen, ids);
        } finally {
            daemon.close();
        }
    });
});
