import { ChildProcess, spawn } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { DaemonClient, DaemonClientEvents, DaemonClientLogger } from './daemonClient';
import {
    DAEMON_DESCRIPTOR_SCHEMA_VERSION,
    DaemonLaunchDescriptor,
    InitializeResult,
} from './daemonProtocol';

/**
 * Reads `<module>/build/compose-previews/daemon-launch.json` and returns the
 * descriptor. Returns null when the file doesn't exist (the consumer hasn't
 * run `composePreviewDaemonStart` yet, or the plugin doesn't apply), or when
 * the schema version doesn't match what this extension knows how to read.
 */
export function readLaunchDescriptor(
    workspaceRoot: string,
    moduleId: string,
    logger?: DaemonClientLogger,
): DaemonLaunchDescriptor | null {
    const file = path.join(
        workspaceRoot, moduleId, 'build', 'compose-previews', 'daemon-launch.json',
    );
    if (!fs.existsSync(file)) { return null; }
    try {
        const raw = fs.readFileSync(file, 'utf-8');
        const parsed = JSON.parse(raw) as DaemonLaunchDescriptor;
        if (parsed.schemaVersion !== DAEMON_DESCRIPTOR_SCHEMA_VERSION) {
            logger?.appendLine(
                `[daemon] descriptor schema mismatch at ${file}: ` +
                `got ${parsed.schemaVersion}, expected ${DAEMON_DESCRIPTOR_SCHEMA_VERSION}`,
            );
            return null;
        }
        return parsed;
    } catch (err) {
        logger?.appendLine(`[daemon] failed to read ${file}: ${(err as Error).message}`);
        return null;
    }
}

export interface SpawnedDaemon {
    client: DaemonClient;
    process: ChildProcess;
    initializeResult: InitializeResult;
    /** Resolves once the JVM has exited. */
    exited: Promise<number | null>;
}

export interface SpawnOptions {
    workspaceRoot: string;
    descriptor: DaemonLaunchDescriptor;
    clientVersion: string;
    events: DaemonClientEvents;
    logger?: DaemonClientLogger;
    /** Defaults to `metrics: true, visibility: true`. */
    capabilities?: { visibility: boolean; metrics: boolean };
}

/**
 * Spawns the daemon JVM described by [descriptor], opens a [DaemonClient] over
 * its stdio, and runs the `initialize` handshake. Resolves once the daemon
 * acknowledged `initialize`; the caller can then send notifications and
 * `renderNow` requests.
 *
 * Throws when:
 *   - `descriptor.enabled === false` (the user hasn't opted in via
 *     `composePreview { experimental { daemon { enabled = true } } }`),
 *   - the JVM exits before `initialize` completes,
 *   - `initialize` returns an error.
 */
export async function spawnDaemon(opts: SpawnOptions): Promise<SpawnedDaemon> {
    const { descriptor, clientVersion, events, workspaceRoot } = opts;
    const logger = opts.logger;

    if (!descriptor.enabled) {
        throw new Error(
            'Daemon disabled in build config: set composePreview.experimental.daemon.enabled = true',
        );
    }

    const javaPath = descriptor.javaLauncher ?? findJavaOnPath();
    if (!javaPath) {
        throw new Error('No java executable: set javaLauncher via the toolchain or put java on PATH');
    }

    const sysProps = Object.entries(descriptor.systemProperties)
        .map(([k, v]) => `-D${k}=${v}`);
    const args = [
        ...descriptor.jvmArgs,
        ...sysProps,
        '-cp',
        descriptor.classpath.join(path.delimiter),
        descriptor.mainClass,
    ];

    logger?.appendLine(
        `[daemon] spawning ${descriptor.mainClass} for ${descriptor.modulePath} ` +
        `(${descriptor.classpath.length} classpath entries, java=${javaPath})`,
    );

    const child = spawn(javaPath, args, {
        cwd: descriptor.workingDirectory,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
    });

    if (!child.stdin || !child.stdout || !child.stderr) {
        throw new Error('Daemon spawn produced no stdio streams');
    }

    // Stderr is a free-form log channel per PROTOCOL.md § 1 — surface to the
    // logger so daemon `System.err.println` lands in the user's output panel.
    child.stderr.setEncoding('utf-8');
    child.stderr.on('data', (chunk: string) => {
        for (const line of chunk.split(/\r?\n/)) {
            if (line.length > 0) { logger?.appendLine(`[daemon stderr] ${line}`); }
        }
    });

    const exited = new Promise<number | null>((resolve) => {
        child.on('exit', (code) => {
            logger?.appendLine(`[daemon] process exited with code=${code}`);
            resolve(code);
        });
    });

    const client = new DaemonClient(child.stdin, child.stdout, events, logger);

    let initializeResult: InitializeResult;
    try {
        initializeResult = await client.initialize({
            clientVersion,
            workspaceRoot,
            moduleId: descriptor.modulePath,
            moduleProjectDir: descriptor.workingDirectory,
            capabilities: opts.capabilities ?? { visibility: true, metrics: true },
        });
    } catch (err) {
        try { child.kill('SIGTERM'); } catch { /* ignore */ }
        throw err;
    }

    client.initialized();
    return { client, process: child, initializeResult, exited };
}

/**
 * Best-effort lookup for a `java` binary on PATH. Used only when the launch
 * descriptor's `javaLauncher` is null — typically AGP exposes a toolchain
 * launcher and we never reach this path.
 */
function findJavaOnPath(): string | null {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    const dirs = (process.env.PATH ?? '').split(path.delimiter);
    for (const dir of dirs) {
        if (!dir) { continue; }
        const candidate = path.join(dir, exe);
        try {
            if (fs.statSync(candidate).isFile()) { return candidate; }
        } catch { /* skip */ }
    }
    return null;
}
