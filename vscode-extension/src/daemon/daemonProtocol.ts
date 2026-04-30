/**
 * TypeScript mirrors of the locked v1 protocol from
 * `daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt`.
 * Kept hand-rolled (no generator) so the round-trip golden fixtures under
 * `docs/daemon/protocol-fixtures/` are the only authority both sides depend on.
 *
 * Spec: docs/daemon/PROTOCOL.md (v1).
 */

export const PROTOCOL_VERSION = 1;

// JSON-RPC envelopes (PROTOCOL.md § 2)

export interface JsonRpcRequest<P = unknown> {
    jsonrpc: '2.0';
    id: number;
    method: string;
    params?: P;
}

export interface JsonRpcResponse<R = unknown> {
    jsonrpc: '2.0';
    id: number;
    result?: R;
    error?: JsonRpcError;
}

export interface JsonRpcNotification<P = unknown> {
    jsonrpc: '2.0';
    method: string;
    params?: P;
}

export interface JsonRpcError {
    code: number;
    message: string;
    data?: { kind?: string } & Record<string, unknown>;
}

export const ERROR_PARSE = -32700;
export const ERROR_INVALID_REQUEST = -32600;
export const ERROR_METHOD_NOT_FOUND = -32601;
export const ERROR_INVALID_PARAMS = -32602;
export const ERROR_INTERNAL = -32603;
export const ERROR_NOT_INITIALIZED = -32001;
export const ERROR_CLASSPATH_DIRTY = -32002;
export const ERROR_SANDBOX_RECYCLING = -32003;
export const ERROR_UNKNOWN_PREVIEW = -32004;
export const ERROR_RENDER_FAILED = -32005;

// initialize (PROTOCOL.md § 3)

export interface InitializeParams {
    protocolVersion: number;
    clientVersion: string;
    workspaceRoot: string;
    moduleId: string;
    moduleProjectDir: string;
    capabilities: { visibility: boolean; metrics: boolean };
    options?: {
        maxHeapMb?: number;
        warmSpare?: boolean;
        detectLeaks?: 'off' | 'light' | 'heavy';
        foreground?: boolean;
    };
}

export interface InitializeResult {
    protocolVersion: number;
    daemonVersion: string;
    pid: number;
    capabilities: {
        incrementalDiscovery: boolean;
        sandboxRecycle: boolean;
        leakDetection: ('light' | 'heavy')[];
    };
    classpathFingerprint: string;
    manifest: { path: string; previewCount: number };
}

// Client → daemon notifications (PROTOCOL.md § 4)

export interface SetVisibleParams { ids: string[] }
export interface SetFocusParams { ids: string[] }

export type FileKind = 'source' | 'resource' | 'classpath';
export type FileChangeType = 'modified' | 'created' | 'deleted';

export interface FileChangedParams {
    path: string;
    kind: FileKind;
    changeType: FileChangeType;
}

// Client → daemon requests (PROTOCOL.md § 5)

export type RenderTier = 'fast' | 'full';

export interface RenderNowParams {
    previews: string[];
    tier: RenderTier;
    reason?: string;
}

export interface RenderNowResult {
    queued: string[];
    rejected: { id: string; reason: string }[];
}

// Daemon → client notifications (PROTOCOL.md § 6)

export interface DiscoveryUpdatedParams {
    added: unknown[];
    removed: string[];
    changed: unknown[];
    totalPreviews: number;
}

export interface RenderStartedParams { id: string; queuedMs: number }

export interface RenderMetrics {
    heapAfterGcMb: number;
    nativeHeapMb: number;
    sandboxAgeRenders: number;
    sandboxAgeMs: number;
}

export interface RenderFinishedParams {
    id: string;
    pngPath: string;
    tookMs: number;
    metrics?: RenderMetrics;
}

export interface RenderError {
    kind: 'compile' | 'runtime' | 'capture' | 'timeout' | 'internal';
    message: string;
    stackTrace?: string;
}

export interface RenderFailedParams { id: string; error: RenderError }

export interface ClasspathDirtyParams {
    reason: 'fingerprintMismatch' | 'fileChanged' | 'manifestMissing';
    detail: string;
    changedPaths?: string[];
}

export interface SandboxRecycleParams {
    reason: 'heapCeiling' | 'heapDrift' | 'renderTimeDrift' | 'histogramDrift'
          | 'renderCount' | 'leakSuspected' | 'manual';
    ageMs: number;
    renderCount: number;
    warmSpareReady: boolean;
}

export interface DaemonWarmingParams { etaMs: number }

export interface LogParams {
    level: 'debug' | 'info' | 'warn' | 'error';
    message: string;
    category?: string;
    context?: Record<string, unknown>;
}

/**
 * Wire format of `<module>/build/compose-previews/daemon-launch.json`,
 * authored by `DaemonBootstrapTask`. See
 * `gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/daemon/DaemonClasspathDescriptor.kt`.
 */
export interface DaemonLaunchDescriptor {
    schemaVersion: number;
    modulePath: string;
    variant: string;
    enabled: boolean;
    mainClass: string;
    javaLauncher: string | null;
    classpath: string[];
    jvmArgs: string[];
    systemProperties: Record<string, string>;
    workingDirectory: string;
    manifestPath: string;
}

export const DAEMON_DESCRIPTOR_SCHEMA_VERSION = 1;
