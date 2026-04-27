import * as cp from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { JsonRpcNotification } from './daemonProtocol';

// ---------------------------------------------------------------------------
// Preview daemon — JVM lifecycle.
//
// Reads the launch descriptor written by the Gradle plugin's
// `composePreviewDaemonStart` task (`build/compose-previews/daemon-launch.json`,
// schema in `gradle-plugin/.../DaemonClasspathDescriptor.kt`), spawns the
// daemon JVM, and owns the process handle until either:
//
//   1. The caller (C1.4 daemonGate) explicitly disposes us, or
//   2. The daemon emits `classpathDirty` and we restart it after re-running
//      `composePreviewDaemonStart`, or
//   3. The idle-timeout elapses with no activity.
//
// Wire-format / JSON-RPC framing happen one layer up (C1.3 daemonClient.ts).
// This module deliberately does NOT speak the protocol — it just hands the
// caller a `child_process.ChildProcess` (stdin/stdout/stderr ready for the
// client to attach a framer). Keeps the responsibilities crisp:
//
//   daemonProcess  ─owns→  ChildProcess + descriptor + restart policy
//   daemonClient   ─owns→  framing + JSON-RPC dispatch + typed events
//   daemonGate     ─owns→  isEnabled? alive? fall-back to gradleService
//
// ---------------------------------------------------------------------------

/**
 * Schema-versioned wire format of `daemon-launch.json`. Mirrors the Kotlin
 * `DaemonClasspathDescriptor` 1:1 (gradle-plugin/.../DaemonClasspathDescriptor.kt).
 *
 * **schemaVersion** is the canary: VS Code refuses to spawn when it doesn't
 * recognise the version, instead surfacing "re-run composePreviewDaemonStart"
 * to the user (DaemonClasspathDescriptor KDoc — VS Code gates on this and
 * forces a re-run on mismatch).
 */
export interface DaemonLaunchDescriptor {
    schemaVersion: number;
    modulePath: string;
    variant: string;
    /**
     * Mirrors `DaemonExtension.enabled`. When false the descriptor is still
     * valid (so VS Code can sniff that the consumer ran the task) but VS Code
     * MUST NOT spawn — see CONFIG.md.
     */
    enabled: boolean;
    mainClass: string;
    /** Absolute path to `java`, or `null` to fall back to extension JDK detection. */
    javaLauncher: string | null;
    classpath: string[];
    jvmArgs: string[];
    systemProperties: Record<string, string>;
    workingDirectory: string;
    manifestPath: string;
}

/** Current expected value of {@link DaemonLaunchDescriptor.schemaVersion}. */
export const EXPECTED_DESCRIPTOR_SCHEMA_VERSION = 1;

/**
 * Default idle timeout — matches `composeai.daemon.idleTimeoutMs` on the
 * daemon side (see JsonRpcServer.DEFAULT_IDLE_TIMEOUT_MS). The daemon JVM
 * already self-exits at this interval if stdin closes; this constant is the
 * VS Code timer that drives `shutdown`+`exit` for clean teardown after a
 * period of no activity.
 */
export const DEFAULT_IDLE_TIMEOUT_MS = 5_000;

export interface Logger {
    appendLine(value: string): void;
    append(value: string): void;
}

const nullLogger: Logger = { appendLine() { /* */ }, append() { /* */ } };

/** Errors surfaced to the C1.4 router so it can fall back to gradleService. */
export class DaemonProcessError extends Error {
    constructor(
        message: string,
        public readonly kind:
            | 'descriptor-missing'
            | 'descriptor-malformed'
            | 'descriptor-schema-mismatch'
            | 'descriptor-disabled'
            | 'spawn-failed'
            | 'crashed'
            | 'restart-failed',
    ) {
        super(message);
        this.name = 'DaemonProcessError';
    }
}

/**
 * One-shot Gradle re-bootstrap callback. The router (C1.4) wires this to
 * `gradleService.runTask(':${module}:composePreviewDaemonStart')`; we keep
 * the module decoupled from `GradleService` so the unit tests can mock it.
 */
export type RebootstrapFn = (modulePath: string) => Promise<void>;

/**
 * Pluggable child_process spawn for tests. Production wires the real
 * `child_process.spawn`; unit tests substitute a fake that returns a
 * stub ChildProcess.
 */
export type SpawnFn = (
    command: string,
    args: readonly string[],
    options: cp.SpawnOptions,
) => cp.ChildProcess;

export interface DaemonProcessOptions {
    /**
     * Workspace root (absolute). Used to resolve the per-module
     * `<module>/build/compose-previews/daemon-launch.json`.
     */
    workspaceRoot: string;
    /** The module the daemon serves, e.g. `samples/android` or `:samples:android`. */
    modulePath: string;
    /** Re-runs `composePreviewDaemonStart`. Required for {@link restart}. */
    rebootstrap: RebootstrapFn;
    /** Defaults to {@link DEFAULT_IDLE_TIMEOUT_MS}. */
    idleTimeoutMs?: number;
    logger?: Logger;
    /** Test seam — defaults to `child_process.spawn`. */
    spawn?: SpawnFn;
    /** Test seam — defaults to `fs.readFileSync`. */
    readFile?: (path: string) => string;
    /**
     * Override of the JDK fallback launcher. Production uses the descriptor's
     * `javaLauncher`; if null we fall back to PATH `java`. Tests inject this
     * to avoid host-environment dependence.
     */
    javaFallback?: string;
}

/**
 * Lifecycle states. `disposed` is terminal — the caller must construct a new
 * instance to spawn again.
 */
export type DaemonState =
    | 'idle'
    | 'starting'
    | 'running'
    | 'restarting'
    | 'shutting-down'
    | 'crashed'
    | 'disposed';

const PATH_SEPARATOR = process.platform === 'win32' ? ';' : ':';

/**
 * Owns the daemon JVM child process. Single-instance per daemon — the caller
 * (C1.4) keeps one DaemonProcess per module, mirroring the daemon's
 * one-process-per-module design (DESIGN § 4 — "Multi-module support is
 * multi-process").
 */
export class DaemonProcess {

    private readonly options: Required<Omit<DaemonProcessOptions, 'javaFallback' | 'logger' | 'spawn' | 'readFile' | 'idleTimeoutMs'>>
        & Pick<DaemonProcessOptions, 'javaFallback'>
        & {
            logger: Logger;
            spawn: SpawnFn;
            readFile: (p: string) => string;
            idleTimeoutMs: number;
        };

    private state: DaemonState = 'idle';
    private child: cp.ChildProcess | null = null;
    private descriptor: DaemonLaunchDescriptor | null = null;
    private idleTimer: NodeJS.Timeout | null = null;
    private exitCode: number | null = null;

    constructor(options: DaemonProcessOptions) {
        this.options = {
            workspaceRoot: options.workspaceRoot,
            modulePath: options.modulePath,
            rebootstrap: options.rebootstrap,
            idleTimeoutMs: options.idleTimeoutMs ?? DEFAULT_IDLE_TIMEOUT_MS,
            logger: options.logger ?? nullLogger,
            spawn: options.spawn ?? cp.spawn,
            readFile: options.readFile ?? ((p) => fs.readFileSync(p, 'utf-8')),
            javaFallback: options.javaFallback,
        };
    }

    /**
     * Resolves the per-module `daemon-launch.json` path. The `modulePath` may
     * be either a Gradle path (`:samples:android`) or a directory-relative
     * path (`samples/android`); both shapes are normalised to a directory.
     */
    private descriptorPath(): string {
        const normalised = this.options.modulePath
            .replace(/^:+/, '')
            .replace(/:/g, path.sep);
        return path.join(
            this.options.workspaceRoot,
            normalised,
            'build',
            'compose-previews',
            'daemon-launch.json',
        );
    }

    /**
     * Read + validate `daemon-launch.json`. Throws {@link DaemonProcessError}
     * on missing file, malformed JSON, schema-version mismatch, or
     * `enabled === false`. Caller (C1.4) catches and falls back.
     */
    private loadDescriptor(): DaemonLaunchDescriptor {
        const descriptorPath = this.descriptorPath();
        let raw: string;
        try {
            raw = this.options.readFile(descriptorPath);
        } catch (e) {
            throw new DaemonProcessError(
                `descriptor not found at ${descriptorPath}: ${(e as Error).message}`,
                'descriptor-missing',
            );
        }
        let descriptor: DaemonLaunchDescriptor;
        try {
            descriptor = JSON.parse(raw) as DaemonLaunchDescriptor;
        } catch (e) {
            throw new DaemonProcessError(
                `descriptor at ${descriptorPath} is not valid JSON: ${(e as Error).message}`,
                'descriptor-malformed',
            );
        }
        if (descriptor.schemaVersion !== EXPECTED_DESCRIPTOR_SCHEMA_VERSION) {
            throw new DaemonProcessError(
                `descriptor schemaVersion ${descriptor.schemaVersion} != ` +
                `${EXPECTED_DESCRIPTOR_SCHEMA_VERSION}; re-run composePreviewDaemonStart`,
                'descriptor-schema-mismatch',
            );
        }
        if (!descriptor.enabled) {
            throw new DaemonProcessError(
                `descriptor at ${descriptorPath} has enabled=false; ` +
                `flip composePreview.experimental.daemon.enabled in build.gradle.kts`,
                'descriptor-disabled',
            );
        }
        return descriptor;
    }

    /** Called by tests + by `start()`; exposed so C1.3/C1.4 can inspect. */
    get currentState(): DaemonState {
        return this.state;
    }

    get currentDescriptor(): DaemonLaunchDescriptor | null {
        return this.descriptor;
    }

    /**
     * Spawn the daemon JVM. Idempotent if already running. Returns the
     * spawned ChildProcess so the caller (C1.3 daemonClient) can attach
     * stdin/stdout framing.
     */
    start(): cp.ChildProcess {
        if (this.state === 'disposed') {
            throw new DaemonProcessError(
                'cannot start a disposed DaemonProcess',
                'spawn-failed',
            );
        }
        if (this.state === 'running' || this.state === 'starting') {
            if (this.child) {
                return this.child;
            }
        }
        this.state = 'starting';
        const descriptor = this.loadDescriptor();
        this.descriptor = descriptor;

        const launcher = descriptor.javaLauncher
            ?? this.options.javaFallback
            ?? 'java';
        const classpathArg = descriptor.classpath.join(PATH_SEPARATOR);
        const sysPropArgs = Object.entries(descriptor.systemProperties)
            .map(([k, v]) => `-D${k}=${v}`);
        const args = [
            ...descriptor.jvmArgs,
            ...sysPropArgs,
            '-cp',
            classpathArg,
            descriptor.mainClass,
        ];

        this.options.logger.appendLine(
            `[daemon] spawn ${launcher} ${descriptor.mainClass} ` +
            `(cwd=${descriptor.workingDirectory}, classpath=${descriptor.classpath.length} entries)`,
        );

        let child: cp.ChildProcess;
        try {
            child = this.options.spawn(launcher, args, {
                cwd: descriptor.workingDirectory,
                stdio: ['pipe', 'pipe', 'pipe'],
                // detached:false => the daemon dies with the extension host
                // (POSIX: same process group; the tree gets SIGHUP on host
                // exit). The daemon also has its own parent-PID watchdog
                // available via composeai.daemon.parentPid — that's the Stream
                // B side; for now this is sufficient.
                detached: false,
                windowsHide: true,
            });
        } catch (e) {
            this.state = 'crashed';
            throw new DaemonProcessError(
                `spawn failed for ${launcher}: ${(e as Error).message}`,
                'spawn-failed',
            );
        }
        this.child = child;
        this.state = 'running';
        this.exitCode = null;

        child.on('exit', (code, signal) => {
            this.exitCode = code;
            this.options.logger.appendLine(
                `[daemon] exited code=${code} signal=${signal} (was ${this.state})`,
            );
            // shutting-down → expected; otherwise crash.
            if (this.state === 'shutting-down') {
                this.state = 'idle';
            } else if (this.state !== 'restarting' && this.state !== 'disposed') {
                this.state = 'crashed';
            }
            this.child = null;
            this.clearIdleTimer();
        });

        child.on('error', (err) => {
            this.options.logger.appendLine(`[daemon] child error: ${err.message}`);
        });

        // stderr is the unstructured log channel per PROTOCOL.md § 1; the
        // structured `log` notification is handled in daemonClient (C1.3).
        // Surface stderr lines verbatim for debugging.
        child.stderr?.on('data', (chunk: Buffer | string) => {
            this.options.logger.append(typeof chunk === 'string' ? chunk : chunk.toString('utf-8'));
        });

        this.armIdleTimer();
        return child;
    }

    /**
     * Reset the idle timer. C1.3's daemonClient calls this on every
     * outgoing message; sustained inactivity past the timeout triggers
     * shutdown+exit.
     */
    bumpActivity(): void {
        if (this.state !== 'running') {
            return;
        }
        this.armIdleTimer();
    }

    private armIdleTimer(): void {
        this.clearIdleTimer();
        const ms = this.options.idleTimeoutMs;
        if (ms <= 0) {
            return;
        }
        this.idleTimer = setTimeout(() => {
            this.idleTimer = null;
            this.options.logger.appendLine(`[daemon] idle ${ms}ms — initiating shutdown`);
            // Caller (C1.3) is expected to drive the protocol-level
            // shutdown sequence; we just kill the process if no client is
            // attached, otherwise we let `dispose()` run after the client
            // sends shutdown+exit.
            void this.dispose().catch((e) => {
                this.options.logger.appendLine(`[daemon] idle dispose failed: ${(e as Error).message}`);
            });
        }, ms);
    }

    private clearIdleTimer(): void {
        if (this.idleTimer) {
            clearTimeout(this.idleTimer);
            this.idleTimer = null;
        }
    }

    /**
     * Restart on `classpathDirty`. C1.3 dispatches the notification to a
     * caller-supplied handler that wires through to this method. Honours the
     * no-mid-render-cancellation invariant by waiting for the existing
     * process to finish draining (it dies on its own after emitting
     * classpathDirty per PROTOCOL.md § 6) before bootstrapping the new one.
     */
    async restart(): Promise<cp.ChildProcess> {
        if (this.state === 'disposed') {
            throw new DaemonProcessError(
                'cannot restart a disposed DaemonProcess',
                'restart-failed',
            );
        }
        this.state = 'restarting';
        this.options.logger.appendLine(`[daemon] restart for ${this.options.modulePath}`);
        // Wait for the existing process to exit (the daemon emits
        // `classpathDirty` then exits within `daemon.classpathDirtyGraceMs`,
        // PROTOCOL.md § 6). Bounded by 5s so we don't hang forever if the
        // daemon mis-behaves.
        if (this.child) {
            await this.waitForExit(5_000);
        }
        // Re-bootstrap the descriptor (classpath probably changed).
        try {
            await this.options.rebootstrap(this.options.modulePath);
        } catch (e) {
            this.state = 'crashed';
            throw new DaemonProcessError(
                `composePreviewDaemonStart failed: ${(e as Error).message}`,
                'restart-failed',
            );
        }
        // start() resets state to running.
        return this.start();
    }

    /**
     * Returns false if the process exited unexpectedly OR was never started.
     * C1.4 uses this to gate fallback to gradleService.
     */
    isHealthy(): boolean {
        return this.state === 'running' && this.child !== null && this.child.exitCode === null;
    }

    /**
     * Cleanly shut the daemon down. Called by the idle timer and by C1.4's
     * extension-deactivate hook. The protocol-level `shutdown` request +
     * `exit` notification are owned by C1.3's daemonClient; this method's
     * job is to fall through to SIGTERM after `shutdownGraceMs` if the
     * client never speaks the protocol exit (e.g. the daemon process is
     * already dead, or no client ever attached).
     *
     * The honoured invariant: never SIGKILL while a render is in-flight;
     * the daemon itself enforces drain-before-exit (DESIGN § 9, B1.5a).
     * `dispose()` waits up to 5 seconds for natural exit before falling
     * back to SIGTERM, which gives the daemon's own drain logic time to
     * run.
     */
    async dispose(shutdownGraceMs = 5_000): Promise<void> {
        if (this.state === 'disposed') {
            return;
        }
        this.clearIdleTimer();
        this.state = 'shutting-down';
        const child = this.child;
        if (!child) {
            this.state = 'disposed';
            return;
        }
        // Close stdin first — the daemon's read loop sees EOF and runs the
        // idle-timeout exit path (PROTOCOL.md § 3 — "If the client closes
        // stdin without `shutdown`+`exit`, the daemon exits with code 1
        // within `daemon.idleTimeoutMs`"). C1.3 normally sends `shutdown`
        // + `exit` first; this is the fallback for the no-client case.
        try {
            child.stdin?.end();
        } catch {
            /* swallow */
        }
        const exited = await this.waitForExit(shutdownGraceMs);
        if (!exited && this.child) {
            this.options.logger.appendLine(
                `[daemon] grace expired, sending SIGTERM`,
            );
            try {
                this.child.kill('SIGTERM');
            } catch {
                /* swallow */
            }
            await this.waitForExit(2_000);
        }
        if (this.child) {
            try {
                this.child.kill('SIGKILL');
            } catch {
                /* swallow */
            }
        }
        this.state = 'disposed';
        this.child = null;
    }

    /**
     * Resolves true if the child exited within `timeoutMs`, false on
     * timeout. Never throws — caller decides what to do with a stubborn
     * process.
     */
    private waitForExit(timeoutMs: number): Promise<boolean> {
        const child = this.child;
        if (!child) {
            return Promise.resolve(true);
        }
        if (child.exitCode !== null || child.signalCode !== null) {
            return Promise.resolve(true);
        }
        return new Promise<boolean>((resolve) => {
            const timer = setTimeout(() => {
                child.removeListener('exit', onExit);
                resolve(false);
            }, timeoutMs);
            const onExit = () => {
                clearTimeout(timer);
                resolve(true);
            };
            child.once('exit', onExit);
        });
    }

    /**
     * Notification routing helper for C1.3. The client decodes the
     * incoming `classpathDirty` notification and calls this so we can
     * trigger `restart()` from a single seam.
     *
     * Kept here (not in daemonClient) because the restart policy is a
     * lifecycle concern, not a protocol concern.
     */
    onNotification(notification: JsonRpcNotification): void {
        if (notification.method === 'classpathDirty') {
            this.options.logger.appendLine(
                `[daemon] classpathDirty — scheduling restart`,
            );
            void this.restart().catch((e) => {
                this.options.logger.appendLine(
                    `[daemon] restart failed: ${(e as Error).message}`,
                );
            });
        }
    }

    /** Test/inspection helper. */
    get lastExitCode(): number | null {
        return this.exitCode;
    }
}
