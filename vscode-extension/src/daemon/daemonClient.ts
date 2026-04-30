import { Readable, Writable } from 'stream';
import { FrameDecoder, encodeFrame } from './daemonFraming';
import {
    ClasspathDirtyParams,
    DaemonWarmingParams,
    DiscoveryUpdatedParams,
    FileChangedParams,
    InitializeParams,
    InitializeResult,
    JsonRpcError,
    JsonRpcNotification,
    JsonRpcRequest,
    JsonRpcResponse,
    LogParams,
    PROTOCOL_VERSION,
    RenderFailedParams,
    RenderFinishedParams,
    RenderNowParams,
    RenderNowResult,
    RenderStartedParams,
    SandboxRecycleParams,
    SetFocusParams,
    SetVisibleParams,
} from './daemonProtocol';

export interface DaemonClientLogger {
    appendLine(value: string): void;
}

const nullLogger: DaemonClientLogger = { appendLine() { /* noop */ } };

/**
 * Daemon → client notification handlers. All optional; ignore what you don't
 * care about. Unknown notifications from the daemon are dropped silently per
 * PROTOCOL.md § 7 (additive notifications must not break old clients).
 */
export interface DaemonClientEvents {
    onDiscoveryUpdated?: (params: DiscoveryUpdatedParams) => void;
    onRenderStarted?: (params: RenderStartedParams) => void;
    onRenderFinished?: (params: RenderFinishedParams) => void;
    onRenderFailed?: (params: RenderFailedParams) => void;
    onClasspathDirty?: (params: ClasspathDirtyParams) => void;
    onSandboxRecycle?: (params: SandboxRecycleParams) => void;
    onDaemonWarming?: (params: DaemonWarmingParams) => void;
    onDaemonReady?: () => void;
    onLog?: (params: LogParams) => void;
    /** Stream end / framing collapse. After this fires, the client is dead. */
    onChannelClosed?: (err?: Error) => void;
}

export class DaemonRpcError extends Error {
    constructor(public readonly rpc: JsonRpcError) {
        super(`${rpc.code} ${rpc.message}`);
        this.name = 'DaemonRpcError';
    }
}

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (err: Error) => void;
    method: string;
}

/**
 * JSON-RPC 2.0 client with LSP-style `Content-Length` framing, speaking to a
 * preview-daemon JVM over its stdin/stdout. See `daemonProcess.ts` for the
 * spawn-and-supervise side; this class is transport-agnostic so unit tests
 * can drive it from in-memory streams.
 */
export class DaemonClient {
    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private readonly decoder: FrameDecoder;
    private closed = false;

    constructor(
        private readonly stdin: Writable,
        stdout: Readable,
        private readonly events: DaemonClientEvents,
        private readonly logger: DaemonClientLogger = nullLogger,
    ) {
        this.decoder = new FrameDecoder({
            onMessage: (json) => this.handleIncoming(json),
            onError: (err) => this.fail(err),
        });

        stdout.on('data', (chunk: Buffer | string) => {
            const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            this.decoder.push(buf);
        });
        stdout.on('end', () => this.fail());
        stdout.on('error', (err: Error) => this.fail(err));
        stdin.on('error', (err: Error) => this.fail(err));
    }

    initialize(params: Omit<InitializeParams, 'protocolVersion'>): Promise<InitializeResult> {
        return this.request<InitializeResult>('initialize', {
            ...params,
            protocolVersion: PROTOCOL_VERSION,
        });
    }

    initialized(): void {
        this.notify('initialized', undefined);
    }

    setVisible(params: SetVisibleParams): void {
        this.notify('setVisible', params);
    }

    setFocus(params: SetFocusParams): void {
        this.notify('setFocus', params);
    }

    fileChanged(params: FileChangedParams): void {
        this.notify('fileChanged', params);
    }

    renderNow(params: RenderNowParams): Promise<RenderNowResult> {
        return this.request<RenderNowResult>('renderNow', params);
    }

    /** Drains in-flight renders, then resolves. Daemon will not exit until `exit` fires. */
    shutdown(): Promise<null> {
        return this.request<null>('shutdown', undefined);
    }

    /** Daemon exits after this. Best-effort; the channel may be closed already. */
    exit(): void {
        try {
            this.notify('exit', undefined);
        } catch {
            /* channel already gone */
        }
    }

    isClosed(): boolean { return this.closed; }

    private request<T>(method: string, params: unknown): Promise<T> {
        if (this.closed) {
            return Promise.reject(new Error(`Daemon channel closed; cannot send ${method}`));
        }
        const id = this.nextId++;
        const envelope: JsonRpcRequest = { jsonrpc: '2.0', id, method, params: params ?? null };
        return new Promise<T>((resolve, reject) => {
            this.pending.set(id, {
                resolve: (v) => resolve(v as T),
                reject,
                method,
            });
            try {
                this.stdin.write(encodeFrame(envelope));
            } catch (err) {
                this.pending.delete(id);
                reject(err instanceof Error ? err : new Error(String(err)));
            }
        });
    }

    private notify(method: string, params: unknown): void {
        if (this.closed) { return; }
        const envelope: JsonRpcNotification = {
            jsonrpc: '2.0',
            method,
            params: params === undefined ? null : params,
        };
        try {
            this.stdin.write(encodeFrame(envelope));
        } catch (err) {
            this.fail(err instanceof Error ? err : new Error(String(err)));
        }
    }

    private handleIncoming(json: string): void {
        let msg: JsonRpcResponse | JsonRpcNotification;
        try {
            msg = JSON.parse(json);
        } catch (err) {
            this.logger.appendLine(`[daemon] dropped non-JSON message: ${(err as Error).message}`);
            return;
        }

        if (typeof (msg as JsonRpcResponse).id === 'number') {
            const response = msg as JsonRpcResponse;
            const pending = this.pending.get(response.id);
            if (!pending) {
                this.logger.appendLine(`[daemon] response with no pending request: id=${response.id}`);
                return;
            }
            this.pending.delete(response.id);
            if (response.error) {
                pending.reject(new DaemonRpcError(response.error));
            } else {
                pending.resolve(response.result ?? null);
            }
            return;
        }

        const notification = msg as JsonRpcNotification;
        this.dispatchNotification(notification.method, notification.params);
    }

    private dispatchNotification(method: string, params: unknown): void {
        switch (method) {
            case 'discoveryUpdated':
                this.events.onDiscoveryUpdated?.(params as DiscoveryUpdatedParams);
                break;
            case 'renderStarted':
                this.events.onRenderStarted?.(params as RenderStartedParams);
                break;
            case 'renderFinished':
                this.events.onRenderFinished?.(params as RenderFinishedParams);
                break;
            case 'renderFailed':
                this.events.onRenderFailed?.(params as RenderFailedParams);
                break;
            case 'classpathDirty':
                this.events.onClasspathDirty?.(params as ClasspathDirtyParams);
                break;
            case 'sandboxRecycle':
                this.events.onSandboxRecycle?.(params as SandboxRecycleParams);
                break;
            case 'daemonWarming':
                this.events.onDaemonWarming?.(params as DaemonWarmingParams);
                break;
            case 'daemonReady':
                this.events.onDaemonReady?.();
                break;
            case 'log':
                this.events.onLog?.(params as LogParams);
                break;
            default:
                this.logger.appendLine(`[daemon] ignoring unknown notification: ${method}`);
                break;
        }
    }

    private fail(err?: Error): void {
        if (this.closed) { return; }
        this.closed = true;
        const reason = err ?? new Error('Daemon channel closed');
        for (const [, pending] of this.pending) {
            pending.reject(reason);
        }
        this.pending.clear();
        this.events.onChannelClosed?.(err);
    }
}
