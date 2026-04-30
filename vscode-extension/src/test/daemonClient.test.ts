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
});
