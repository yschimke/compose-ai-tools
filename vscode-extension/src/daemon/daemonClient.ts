import { ChildProcess } from 'child_process';
import { EventEmitter } from 'events';
import { Readable, Writable } from 'stream';
import {
    ClasspathDirtyParams,
    ClientCapabilities,
    DaemonReadyParams,
    DaemonWarmingParams,
    DiscoveryUpdatedParams,
    InitializeParams,
    InitializeResult,
    JsonRpcError,
    JsonRpcNotification,
    JsonRpcRequest,
    JsonRpcResponse,
    JSON_RPC_ERROR_CODES,
    LogParams,
    METHODS,
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
import { DaemonProcess } from './daemonProcess';

// ---------------------------------------------------------------------------
// Preview daemon — JSON-RPC client over the spawned process from C1.2.
//
// This is the TypeScript counterpart of
// renderer-android-daemon/src/main/kotlin/ee/schimke/composeai/daemon/
// JsonRpcServer.kt — same wire format, same lifecycle, same invariants.
//
// **No mid-render cancellation invariant** (DESIGN § 9, PROTOCOL.md § 3,
// B1.5a). The disconnect path sends `shutdown` (a request, NOT a
// notification), waits for the response, and only then sends the `exit`
// notification. We never send anything that could trigger a Thread.interrupt
// or otherwise abort an in-flight render.
// ---------------------------------------------------------------------------

/**
 * Unified renderer interface — both this DaemonClient and the existing
 * GradleService implement it so the C1.4 router can hold either.
 *
 * Kept narrow on purpose: the V1 router only needs `renderPreviews` +
 * `discoverPreviews`. Everything else (history, doctor, image bytes) stays
 * on GradleService for now; the daemon ships PNG bytes by path on disk
 * (PROTOCOL.md § 8 — "Streaming render output ... is out of scope; the
 * client reads pngPath from disk").
 *
 * For the daemon, `renderPreviews` returns immediately after `renderNow`
 * resolves with the queued/rejected lists. Per-render outcomes flow through
 * the {@link DaemonClient} event emitter; callers that want PNGs read them
 * from disk via the existing `gradleService.readPreviewImage` (or, in the
 * future, a daemon-aware variant that watches `renderFinished` for paths).
 */
export interface Renderer {
    /**
     * Returns a manifest-like object so the existing `extension.ts` consumer
     * keeps working. The daemon path returns null in v1 (the manifest is
     * delivered via `discoveryUpdated` notifications, cached on the client);
     * the GradleService path returns the parsed `previews.json`.
     */
    renderPreviews(module: string, tier: 'fast' | 'full'): Promise<unknown | null>;
    discoverPreviews(module: string): Promise<unknown | null>;
}

export interface Logger {
    appendLine(value: string): void;
    append(value: string): void;
}

const nullLogger: Logger = { appendLine() { /* */ }, append() { /* */ } };

/**
 * Typed event payloads mirroring PROTOCOL.md § 6. EventEmitter (as opposed
 * to async iterators) was the choice because it composes naturally with
 * the rest of the extension's vscode-style event APIs and lets the
 * webview / panel react to per-render notifications without owning a
 * pull loop.
 */
export interface DaemonClientEvents {
    discoveryUpdated: (params: DiscoveryUpdatedParams) => void;
    renderStarted: (params: RenderStartedParams) => void;
    renderFinished: (params: RenderFinishedParams) => void;
    renderFailed: (params: RenderFailedParams) => void;
    classpathDirty: (params: ClasspathDirtyParams) => void;
    sandboxRecycle: (params: SandboxRecycleParams) => void;
    daemonWarming: (params: DaemonWarmingParams) => void;
    daemonReady: (params: DaemonReadyParams) => void;
    log: (params: LogParams) => void;
    /** Emitted when the underlying process dies unexpectedly. */
    closed: (reason: string) => void;
}

/** Typed wrapper around EventEmitter for {@link DaemonClient}. */
export interface DaemonClientEmitter {
    on<K extends keyof DaemonClientEvents>(event: K, listener: DaemonClientEvents[K]): this;
    off<K extends keyof DaemonClientEvents>(event: K, listener: DaemonClientEvents[K]): this;
    once<K extends keyof DaemonClientEvents>(event: K, listener: DaemonClientEvents[K]): this;
}

/**
 * Promise + reject pair held while a request is in flight.
 */
interface PendingRequest {
    resolve: (result: unknown) => void;
    reject: (error: Error | DaemonRpcError) => void;
    method: string;
}

/** Thrown when the daemon returns a JSON-RPC error response. */
export class DaemonRpcError extends Error {
    constructor(public readonly rpcError: JsonRpcError, public readonly method: string) {
        super(`daemon ${method} failed: ${rpcError.message} (code ${rpcError.code})`);
        this.name = 'DaemonRpcError';
    }
}

export interface DaemonClientOptions {
    workspaceRoot: string;
    moduleId: string;
    moduleProjectDir: string;
    /** Extension semver (e.g. "0.8.6"). */
    clientVersion: string;
    capabilities?: ClientCapabilities;
    logger?: Logger;
    /**
     * Called when the daemon emits `classpathDirty`. The router (C1.4)
     * passes this through to {@link DaemonProcess.restart} after surfacing a
     * notification to the user. We expose it as a callback (rather than
     * tightly coupling to DaemonProcess) so this client stays unit-testable
     * against piped streams without spawning a real process.
     */
    onClasspathDirty?: (params: ClasspathDirtyParams) => void;
}

/**
 * JSON-RPC 2.0 client over the daemon's stdio. Mirrors
 * `JsonRpcServer.kt` 1:1 on the wire; the difference is that the client
 * speaks the request side (initialize/renderNow/shutdown/exit) and consumes
 * notifications.
 */
export class DaemonClient implements Renderer {

    private readonly options: Required<Omit<DaemonClientOptions, 'logger' | 'capabilities' | 'onClasspathDirty'>>
        & {
            logger: Logger;
            capabilities: ClientCapabilities;
            onClasspathDirty: ((params: ClasspathDirtyParams) => void) | undefined;
        };

    private input: Readable | null = null;
    private output: Writable | null = null;
    private process: DaemonProcess | null = null;

    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private readonly emitter = new EventEmitter();

    /** Set true after `initialize` resolves and `initialized` is sent. */
    private initializeComplete = false;
    /** Set true once `shutdown` is sent; we refuse new requests after this. */
    private shutdownInitiated = false;
    private closed = false;

    /** Cache of the most recent discovery state. Updated on every
     *  `discoveryUpdated` notification; seeded by the manifest path the
     *  daemon advertises in its `initialize` response. */
    private cachedManifestPath: string | null = null;

    constructor(options: DaemonClientOptions) {
        this.options = {
            workspaceRoot: options.workspaceRoot,
            moduleId: options.moduleId,
            moduleProjectDir: options.moduleProjectDir,
            clientVersion: options.clientVersion,
            logger: options.logger ?? nullLogger,
            capabilities: options.capabilities ?? { visibility: true, metrics: true },
            onClasspathDirty: options.onClasspathDirty,
        };
    }

    /** Typed event emitter — see {@link DaemonClientEvents}. */
    get events(): DaemonClientEmitter {
        return this.emitter as unknown as DaemonClientEmitter;
    }

    /**
     * Attach to a spawned daemon (typically `process.start()`). For tests
     * the streams can be plain piped streams — see daemonClient.test.ts.
     */
    attach(input: Readable, output: Writable, daemonProcess?: DaemonProcess): void {
        this.input = input;
        this.output = output;
        this.process = daemonProcess ?? null;
        this.installFramer();
        input.once('close', () => this.handleClosed('stream-close'));
        input.once('end', () => this.handleClosed('stream-end'));
        input.once('error', (e) => this.handleClosed(`stream-error: ${e.message}`));
    }

    /**
     * Convenience: attach the spawned ChildProcess directly. Verifies stdio
     * pipes are present, then delegates to `attach`.
     */
    attachChild(child: ChildProcess, daemonProcess?: DaemonProcess): void {
        if (!child.stdout || !child.stdin) {
            throw new Error('child process must have stdout and stdin pipes');
        }
        this.attach(child.stdout, child.stdin, daemonProcess);
    }

    /**
     * Send `initialize`, await the response, then send `initialized`.
     * Returns the daemon's `InitializeResult` so the caller can read
     * capabilities / classpathFingerprint / manifest path.
     */
    async initialize(): Promise<InitializeResult> {
        const params: InitializeParams = {
            protocolVersion: PROTOCOL_VERSION,
            clientVersion: this.options.clientVersion,
            workspaceRoot: this.options.workspaceRoot,
            moduleId: this.options.moduleId,
            moduleProjectDir: this.options.moduleProjectDir,
            capabilities: this.options.capabilities,
        };
        const result = await this.sendRequest<InitializeResult>(METHODS.Initialize, params);
        this.cachedManifestPath = result.manifest?.path ?? null;
        // Send the `initialized` notification per PROTOCOL.md § 3 — the
        // daemon must not emit notifications until it sees this.
        this.sendNotification(METHODS.Initialized, undefined);
        this.initializeComplete = true;
        return result;
    }

    // ------------------------------------------------------------------
    // Renderer interface (used by C1.4)
    // ------------------------------------------------------------------

    /**
     * Maps to `renderNow({ previews: [], tier })` — empty preview list means
     * "render all visible-and-stale" per PROTOCOL.md § 5. Resolves with the
     * `RenderNowResult` (queued + rejected lists). Per-render outcomes
     * flow through `events.renderStarted/Finished/Failed`.
     *
     * Returns null for the `Renderer` interface symmetry with
     * GradleService.renderPreviews — the daemon doesn't return a manifest
     * here; the manifest is the cached one from `discoveryUpdated`.
     */
    async renderPreviews(module: string, tier: 'fast' | 'full' = 'full'): Promise<unknown | null> {
        // module is informational only — one DaemonClient instance per
        // module (DESIGN § 4). We accept it so the Renderer interface
        // matches GradleService and surface a defensive log on mismatch.
        if (module && this.options.moduleId && module !== this.options.moduleId &&
            !this.options.moduleId.endsWith(`:${module}`)) {
            this.options.logger.appendLine(
                `[daemon] renderPreviews module mismatch: requested=${module} ` +
                `bound=${this.options.moduleId}`,
            );
        }
        const params: RenderNowParams = { previews: [], tier };
        await this.sendRequest<RenderNowResult>(METHODS.RenderNow, params);
        return this.cachedManifest();
    }

    /**
     * Discovery is push-based on the daemon (`discoveryUpdated` notification).
     * Returns the cached manifest snapshot — the path is from the
     * `initialize` result; the contents are a Renderer-interface placeholder
     * for now (the existing extension.ts consumer reads through GradleService
     * for the parsed manifest, not through this method).
     */
    async discoverPreviews(_module: string): Promise<unknown | null> {
        return this.cachedManifest();
    }

    private cachedManifest(): unknown | null {
        if (!this.cachedManifestPath) {
            return null;
        }
        return { manifestPath: this.cachedManifestPath };
    }

    // ------------------------------------------------------------------
    // Visibility / focus / file change forwarding (PROTOCOL.md § 4)
    //
    // These are exposed for C2.1 / C2.2 / C2.3 (Phase 2) — wired here
    // already so the C1.4 router has a stable surface to expand later.
    // ------------------------------------------------------------------

    setVisible(ids: string[]): void {
        this.sendNotification(METHODS.SetVisible, { ids } as SetVisibleParams);
    }

    setFocus(ids: string[]): void {
        this.sendNotification(METHODS.SetFocus, { ids } as SetFocusParams);
    }

    fileChanged(path: string, kind: 'source' | 'resource' | 'classpath',
        changeType: 'modified' | 'created' | 'deleted'): void {
        this.sendNotification(METHODS.FileChanged, { path, kind, changeType });
    }

    // ------------------------------------------------------------------
    // Lifecycle teardown
    // ------------------------------------------------------------------

    /**
     * Send `shutdown` request, wait for the response (which the daemon only
     * resolves AFTER draining in-flight renders, per PROTOCOL.md § 3 +
     * DESIGN § 9), then send `exit` notification. Idempotent.
     */
    async dispose(timeoutMs = 60_000): Promise<void> {
        if (this.closed || this.shutdownInitiated) {
            return;
        }
        this.shutdownInitiated = true;
        if (!this.output || this.output.destroyed) {
            this.handleClosed('output-destroyed-before-shutdown');
            return;
        }
        try {
            await Promise.race([
                this.sendRequest<unknown>(METHODS.Shutdown, undefined),
                new Promise<unknown>((_, reject) => setTimeout(
                    () => reject(new Error('shutdown timed out')),
                    timeoutMs,
                )),
            ]);
        } catch (e) {
            this.options.logger.appendLine(
                `[daemon-client] shutdown failed: ${(e as Error).message}`,
            );
            // Continue to exit anyway — best-effort.
        }
        try {
            this.sendNotification(METHODS.Exit, undefined);
        } catch {
            /* swallow */
        }
        this.handleClosed('disposed');
    }

    // ------------------------------------------------------------------
    // JSON-RPC plumbing
    // ------------------------------------------------------------------

    private sendRequest<R>(method: string, params: unknown): Promise<R> {
        if (this.closed) {
            return Promise.reject(new Error(`daemon client is closed; cannot send ${method}`));
        }
        if (this.shutdownInitiated && method !== METHODS.Shutdown) {
            return Promise.reject(new Error(`shutdown initiated; refusing ${method}`));
        }
        const id = this.nextId++;
        const request: JsonRpcRequest = { jsonrpc: '2.0', id, method, params };
        return new Promise<R>((resolve, reject) => {
            this.pending.set(id, {
                resolve: (r) => resolve(r as R),
                reject,
                method,
            });
            try {
                this.writeFrame(JSON.stringify(request));
            } catch (e) {
                this.pending.delete(id);
                reject(e as Error);
            }
        });
    }

    private sendNotification(method: string, params: unknown): void {
        if (this.closed) {
            return;
        }
        const notification: JsonRpcNotification = { jsonrpc: '2.0', method, params };
        this.writeFrame(JSON.stringify(notification));
    }

    private writeFrame(json: string): void {
        if (!this.output) {
            throw new Error('client not attached');
        }
        const payload = Buffer.from(json, 'utf-8');
        const header = Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`, 'ascii');
        this.output.write(Buffer.concat([header, payload]));
        this.process?.bumpActivity();
    }

    // ------------------------------------------------------------------
    // LSP-style Content-Length framer.
    //
    // Mirrors ContentLengthFramer in JsonRpcServer.kt: case-insensitive
    // header names, ignore Content-Type and other headers, blank line ends
    // the headers, payload is exactly `Content-Length` UTF-8 bytes.
    //
    // Hand-rolled because (a) bringing in `vscode-jsonrpc` adds ~80KB of
    // runtime deps for what fits in 80 LOC, and (b) the server side is also
    // hand-rolled so symmetric debugging is easier when something drifts.
    // ------------------------------------------------------------------

    private installFramer(): void {
        let buffer = Buffer.alloc(0);
        let contentLength = -1;
        let inHeaders = true;
        if (!this.input) {
            throw new Error('client not attached');
        }
        this.input.on('data', (chunk: Buffer) => {
            buffer = Buffer.concat([buffer, chunk]);
            // Loop because one chunk may contain multiple frames or the tail
            // of a previous frame plus headers of a new one.
            // eslint-disable-next-line no-constant-condition
            while (true) {
                if (inHeaders) {
                    const headerEnd = this.findHeaderEnd(buffer);
                    if (headerEnd < 0) {
                        return; // wait for more bytes
                    }
                    const headerText = buffer.slice(0, headerEnd).toString('ascii');
                    contentLength = -1;
                    for (const line of headerText.split(/\r?\n/)) {
                        if (!line) { continue; }
                        const colon = line.indexOf(':');
                        if (colon <= 0) { continue; }
                        const name = line.slice(0, colon).trim().toLowerCase();
                        const value = line.slice(colon + 1).trim();
                        if (name === 'content-length') {
                            const n = parseInt(value, 10);
                            if (!Number.isNaN(n) && n >= 0) {
                                contentLength = n;
                            }
                        }
                    }
                    if (contentLength < 0) {
                        this.options.logger.appendLine(
                            `[daemon-client] missing/invalid Content-Length`,
                        );
                        // Skip past the malformed header block to avoid
                        // looping forever on the same bytes.
                        buffer = buffer.slice(headerEnd + (
                            buffer[headerEnd + 1] === 0x0a /* \n */ &&
                                buffer[headerEnd] === 0x0d /* \r */ ? 4 : 2));
                        inHeaders = true;
                        continue;
                    }
                    // Strip header block + the blank-line separator. The
                    // separator is either \r\n\r\n (4 bytes) or \n\n (2).
                    const sepLen = buffer[headerEnd] === 0x0d ? 4 : 2;
                    buffer = buffer.slice(headerEnd + sepLen);
                    inHeaders = false;
                }
                if (buffer.length < contentLength) {
                    return; // wait for more bytes
                }
                const payload = buffer.slice(0, contentLength);
                buffer = buffer.slice(contentLength);
                inHeaders = true;
                this.handleFrame(payload);
            }
        });
    }

    /**
     * Returns the index of the first byte of the header-terminator sequence
     * (`\r\n\r\n` or `\n\n`), or -1 if not present.
     */
    private findHeaderEnd(buf: Buffer): number {
        for (let i = 0; i < buf.length - 1; i++) {
            if (buf[i] === 0x0a && buf[i + 1] === 0x0a) {
                return i; // \n\n
            }
            if (i + 3 < buf.length && buf[i] === 0x0d && buf[i + 1] === 0x0a
                && buf[i + 2] === 0x0d && buf[i + 3] === 0x0a) {
                return i; // \r\n\r\n
            }
        }
        return -1;
    }

    private handleFrame(payload: Buffer): void {
        let envelope: unknown;
        try {
            envelope = JSON.parse(payload.toString('utf-8'));
        } catch (e) {
            this.options.logger.appendLine(
                `[daemon-client] invalid JSON frame: ${(e as Error).message}`,
            );
            return;
        }
        if (typeof envelope !== 'object' || envelope === null) {
            return;
        }
        const env = envelope as JsonRpcResponse | JsonRpcNotification;
        if ('id' in env && env.id !== undefined && env.id !== null) {
            this.handleResponse(env as JsonRpcResponse);
        } else {
            this.handleNotification(env as JsonRpcNotification);
        }
    }

    private handleResponse(response: JsonRpcResponse): void {
        const id = typeof response.id === 'number' ? response.id : Number(response.id);
        const pending = this.pending.get(id);
        if (!pending) {
            this.options.logger.appendLine(
                `[daemon-client] unexpected response id=${id}`,
            );
            return;
        }
        this.pending.delete(id);
        if (response.error) {
            pending.reject(new DaemonRpcError(response.error, pending.method));
        } else {
            pending.resolve(response.result);
        }
    }

    private handleNotification(notification: JsonRpcNotification): void {
        const params = notification.params ?? {};
        switch (notification.method) {
            case METHODS.DiscoveryUpdated:
                this.emitter.emit('discoveryUpdated', params as DiscoveryUpdatedParams);
                break;
            case METHODS.RenderStarted:
                this.emitter.emit('renderStarted', params as RenderStartedParams);
                break;
            case METHODS.RenderFinished:
                this.emitter.emit('renderFinished', params as RenderFinishedParams);
                break;
            case METHODS.RenderFailed:
                this.emitter.emit('renderFailed', params as RenderFailedParams);
                break;
            case METHODS.ClasspathDirty: {
                const cp = params as ClasspathDirtyParams;
                this.emitter.emit('classpathDirty', cp);
                this.options.onClasspathDirty?.(cp);
                break;
            }
            case METHODS.SandboxRecycle:
                this.emitter.emit('sandboxRecycle', params as SandboxRecycleParams);
                break;
            case METHODS.DaemonWarming:
                this.emitter.emit('daemonWarming', params as DaemonWarmingParams);
                break;
            case METHODS.DaemonReady:
                this.emitter.emit('daemonReady', params as DaemonReadyParams);
                break;
            case METHODS.Log:
                this.emitter.emit('log', params as LogParams);
                break;
            default:
                this.options.logger.appendLine(
                    `[daemon-client] unknown notification: ${notification.method}`,
                );
        }
    }

    private handleClosed(reason: string): void {
        if (this.closed) {
            return;
        }
        this.closed = true;
        // Reject any in-flight requests so callers don't hang forever.
        for (const [id, pending] of this.pending) {
            pending.reject(new Error(
                `daemon connection closed (${reason}) while ${pending.method} was in flight`,
            ));
            this.pending.delete(id);
        }
        this.emitter.emit('closed', reason);
    }

    /** Test helper — exposes `initialized` state. */
    get isInitialized(): boolean {
        return this.initializeComplete;
    }

    /** True iff initialize completed and connection isn't closed. */
    isHealthy(): boolean {
        return this.initializeComplete && !this.closed && !this.shutdownInitiated;
    }
}

// Re-export for the router's convenience.
export { JSON_RPC_ERROR_CODES };
