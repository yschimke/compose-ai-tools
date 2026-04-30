import * as assert from 'assert';
import { PassThrough } from 'stream';
import { DaemonClient, DaemonRpcError } from '../daemon/daemonClient';
import { encodeFrame, FrameDecoder } from '../daemon/daemonFraming';
import {
    JsonRpcRequest,
    PROTOCOL_VERSION,
} from '../daemon/daemonProtocol';

/**
 * A lightweight bidirectional pair: client.stdin → toServer; toClient → client.stdout.
 * Test code observes `toServer` for outbound traffic and writes responses /
 * notifications via `toClient`.
 */
function bidiPair() {
    const toServer = new PassThrough();
    const toClient = new PassThrough();
    return { toServer, toClient };
}

/** Drains decoded frames off `toServer` (what the client writes) into a queue. */
function captureFrames(stream: PassThrough): { take(): Promise<unknown> } {
    const queue: unknown[] = [];
    const waiters: ((v: unknown) => void)[] = [];
    const decoder = new FrameDecoder({
        onMessage: (json) => {
            const parsed = JSON.parse(json);
            if (waiters.length > 0) { waiters.shift()!(parsed); }
            else { queue.push(parsed); }
        },
        onError: (err) => assert.fail(err.message),
    });
    stream.on('data', (chunk: Buffer) => decoder.push(chunk));
    return {
        take(): Promise<unknown> {
            if (queue.length > 0) { return Promise.resolve(queue.shift()!); }
            return new Promise((resolve) => waiters.push(resolve));
        },
    };
}

describe('DaemonClient', () => {
    it('sends initialize and resolves on response', async () => {
        const { toServer, toClient } = bidiPair();
        const frames = captureFrames(toServer);
        const client = new DaemonClient(toServer, toClient, {});

        const promise = client.initialize({
            clientVersion: '0.0.0',
            workspaceRoot: '/work',
            moduleId: ':samples:android',
            moduleProjectDir: '/work/samples/android',
            capabilities: { visibility: true, metrics: true },
        });

        const sent = (await frames.take()) as JsonRpcRequest;
        assert.strictEqual(sent.method, 'initialize');
        assert.strictEqual((sent.params as { protocolVersion: number }).protocolVersion, PROTOCOL_VERSION);

        // Server responds with an InitializeResult shaped object.
        toClient.write(encodeFrame({
            jsonrpc: '2.0',
            id: sent.id,
            result: {
                protocolVersion: PROTOCOL_VERSION,
                daemonVersion: '0.1.0',
                pid: 4321,
                capabilities: {
                    incrementalDiscovery: false,
                    sandboxRecycle: true,
                    leakDetection: [],
                },
                classpathFingerprint: 'a'.repeat(64),
                manifest: { path: '/m', previewCount: 0 },
            },
        }));

        const result = await promise;
        assert.strictEqual(result.daemonVersion, '0.1.0');
        assert.strictEqual(result.pid, 4321);
    });

    it('rejects pending request with DaemonRpcError on error response', async () => {
        const { toServer, toClient } = bidiPair();
        const frames = captureFrames(toServer);
        const client = new DaemonClient(toServer, toClient, {});

        const p = client.renderNow({ previews: ['foo'], tier: 'fast' });
        const req = (await frames.take()) as JsonRpcRequest;
        toClient.write(encodeFrame({
            jsonrpc: '2.0',
            id: req.id,
            error: { code: -32002, message: 'classpath dirty', data: { kind: 'ClasspathDirty' } },
        }));

        await assert.rejects(p, (err: Error) => {
            return err instanceof DaemonRpcError
                && err.message.includes('classpath dirty')
                && (err as DaemonRpcError).rpc.code === -32002;
        });
    });

    it('dispatches notifications to the right handler', async () => {
        const { toServer, toClient } = bidiPair();
        let renderFinishedCount = 0;
        let lastPng = '';
        const client = new DaemonClient(toServer, toClient, {
            onRenderFinished: (params) => {
                renderFinishedCount++;
                lastPng = params.pngPath;
            },
        });
        toClient.write(encodeFrame({
            jsonrpc: '2.0',
            method: 'renderFinished',
            params: { id: 'p1', pngPath: '/tmp/a.png', tookMs: 42 },
        }));
        // Drain the writer queue + decoder microtasks before asserting.
        await new Promise((r) => setImmediate(r));
        assert.strictEqual(renderFinishedCount, 1);
        assert.strictEqual(lastPng, '/tmp/a.png');
        client.exit();
    });

    it('rejects in-flight requests when the channel closes', async () => {
        const { toServer, toClient } = bidiPair();
        let closedErr: Error | undefined;
        const client = new DaemonClient(toServer, toClient, {
            onChannelClosed: (err) => { closedErr = err; },
        });
        const p = client.shutdown();
        toClient.end();
        await assert.rejects(p);
        // We end without an error so the close handler fires with no err arg.
        assert.strictEqual(closedErr, undefined);
        assert.strictEqual(client.isClosed(), true);
    });

    it('encodes a notification with no result tracking', async () => {
        const { toServer, toClient } = bidiPair();
        const frames = captureFrames(toServer);
        const client = new DaemonClient(toServer, toClient, {});
        client.setFocus({ ids: ['a', 'b'] });
        const sent = (await frames.take()) as JsonRpcRequest;
        assert.strictEqual(sent.method, 'setFocus');
        assert.strictEqual((sent as unknown as { id?: number }).id, undefined);
        assert.deepStrictEqual((sent.params as { ids: string[] }).ids, ['a', 'b']);
    });

    it('correlates out-of-order responses to the right pending request', async () => {
        const { toServer, toClient } = bidiPair();
        const frames = captureFrames(toServer);
        const client = new DaemonClient(toServer, toClient, {});

        // Two concurrent renderNow requests. Server responds to the
        // SECOND one first to verify id-based correlation.
        const p1 = client.renderNow({ previews: ['first'], tier: 'fast' });
        const p2 = client.renderNow({ previews: ['second'], tier: 'fast' });
        const r1 = (await frames.take()) as JsonRpcRequest;
        const r2 = (await frames.take()) as JsonRpcRequest;

        toClient.write(encodeFrame({
            jsonrpc: '2.0', id: r2.id,
            result: { queued: ['second'], rejected: [] },
        }));
        toClient.write(encodeFrame({
            jsonrpc: '2.0', id: r1.id,
            result: { queued: ['first'], rejected: [] },
        }));

        const [a, b] = await Promise.all([p1, p2]);
        assert.deepStrictEqual(a.queued, ['first']);
        assert.deepStrictEqual(b.queued, ['second']);
    });

    it('issues monotonically increasing ids', async () => {
        const { toServer, toClient } = bidiPair();
        const frames = captureFrames(toServer);
        const client = new DaemonClient(toServer, toClient, {});
        // Send three requests, never resolve them, just inspect the ids.
        client.renderNow({ previews: [], tier: 'fast' });
        client.renderNow({ previews: [], tier: 'fast' });
        client.renderNow({ previews: [], tier: 'fast' });
        const ids = [
            ((await frames.take()) as JsonRpcRequest).id,
            ((await frames.take()) as JsonRpcRequest).id,
            ((await frames.take()) as JsonRpcRequest).id,
        ];
        assert.ok(ids[0] < ids[1] && ids[1] < ids[2], `ids not monotonic: ${ids.join(',')}`);
    });

    it('rejects new requests issued after the channel is closed', async () => {
        const { toServer, toClient } = bidiPair();
        const client = new DaemonClient(toServer, toClient, {});
        toClient.end();
        // Give Node a tick to propagate the 'end' event into the client.
        await new Promise((r) => setImmediate(r));
        await assert.rejects(
            client.renderNow({ previews: [], tier: 'fast' }),
            /closed/i,
        );
    });

    it('silently drops notifications sent after the channel is closed', async () => {
        const { toServer, toClient } = bidiPair();
        const client = new DaemonClient(toServer, toClient, {});
        toClient.end();
        await new Promise((r) => setImmediate(r));
        // Should not throw; no test assertion needed beyond "no exception."
        client.setFocus({ ids: ['a'] });
        client.fileChanged({ path: '/x.kt', kind: 'source', changeType: 'modified' });
        client.exit();
    });

    it('drops unknown daemon notifications without erroring', async () => {
        // PROTOCOL.md § 7: additive notifications must not break old clients.
        // The dispatcher logs and continues — verifying via no exception
        // and no handler invocation.
        const { toServer, toClient } = bidiPair();
        let sawAnyKnownEvent = false;
        const client = new DaemonClient(toServer, toClient, {
            onRenderFinished: () => { sawAnyKnownEvent = true; },
        });
        toClient.write(encodeFrame({
            jsonrpc: '2.0',
            method: 'futureMessage_v2',
            params: { whatever: true },
        }));
        await new Promise((r) => setImmediate(r));
        assert.strictEqual(sawAnyKnownEvent, false);
        assert.strictEqual(client.isClosed(), false);
        client.exit();
    });

    it('survives non-JSON garbage on the wire by dropping the message', async () => {
        // The daemon SHOULDN'T emit invalid JSON, but if it does (e.g. a
        // stray println from an undisciplined library) the client logs and
        // keeps going — channel stays open.
        const logs: string[] = [];
        const { toServer, toClient } = bidiPair();
        const client = new DaemonClient(toServer, toClient, {}, { appendLine: (s) => logs.push(s) });
        // Send a syntactically valid LSP frame whose body is invalid JSON.
        const body = Buffer.from('not-json-at-all', 'utf-8');
        const header = Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, 'ascii');
        toClient.write(Buffer.concat([header, body]));
        await new Promise((r) => setImmediate(r));
        assert.strictEqual(client.isClosed(), false);
        assert.ok(logs.some(l => l.includes('non-JSON')), `expected non-JSON log, got: ${logs.join(' / ')}`);
        client.exit();
    });

    it('surfaces stray responses (no pending request) without crashing', async () => {
        // Late or duplicate response after the request was already
        // resolved. Logged and dropped.
        const logs: string[] = [];
        const { toServer, toClient } = bidiPair();
        const client = new DaemonClient(toServer, toClient, {}, { appendLine: (s) => logs.push(s) });
        toClient.write(encodeFrame({ jsonrpc: '2.0', id: 9999, result: null }));
        await new Promise((r) => setImmediate(r));
        assert.ok(logs.some(l => l.includes('no pending request')));
        client.exit();
    });

    it('rejects all in-flight requests when stdout closes mid-flight', async () => {
        const { toServer, toClient } = bidiPair();
        const client = new DaemonClient(toServer, toClient, {});
        const p1 = client.renderNow({ previews: ['a'], tier: 'fast' });
        const p2 = client.shutdown();
        toClient.destroy(new Error('pipe broken'));
        await assert.rejects(p1, /pipe broken/);
        await assert.rejects(p2, /pipe broken/);
        assert.strictEqual(client.isClosed(), true);
    });

    it('initialize stamps protocolVersion = 1 and sends initialized after', async () => {
        const { toServer, toClient } = bidiPair();
        const frames = captureFrames(toServer);
        const client = new DaemonClient(toServer, toClient, {});
        const p = client.initialize({
            clientVersion: '0.0.0',
            workspaceRoot: '/w',
            moduleId: ':m',
            moduleProjectDir: '/w/m',
            capabilities: { visibility: true, metrics: true },
        });
        const init = (await frames.take()) as JsonRpcRequest;
        assert.strictEqual((init.params as { protocolVersion: number }).protocolVersion, PROTOCOL_VERSION);
        toClient.write(encodeFrame({
            jsonrpc: '2.0', id: init.id,
            result: {
                protocolVersion: PROTOCOL_VERSION,
                daemonVersion: '0.1.0',
                pid: 1,
                capabilities: { incrementalDiscovery: false, sandboxRecycle: true, leakDetection: [] },
                classpathFingerprint: '0'.repeat(64),
                manifest: { path: '/m', previewCount: 0 },
            },
        }));
        await p;
        client.initialized();
        const initd = (await frames.take()) as JsonRpcRequest;
        assert.strictEqual(initd.method, 'initialized');
        assert.strictEqual((initd as unknown as { id?: number }).id, undefined);
    });
});
