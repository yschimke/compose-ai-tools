import { ChildProcess, spawn } from "child_process";
import * as fs from "fs";
import * as path from "path";
import {
    DaemonClient,
    DaemonClientEvents,
    DaemonClientLogger,
} from "./daemonClient";
import {
    DAEMON_DESCRIPTOR_SCHEMA_VERSION,
    DaemonLaunchDescriptor,
    InitializeResult,
} from "./daemonProtocol";
import { LogFilter } from "../logFilter";
import { ModuleInfo } from "../gradleService";

/**
 * Reads `<module>/build/compose-previews/daemon-launch.json` and returns the
 * descriptor. Returns null when the file doesn't exist (the consumer hasn't
 * run `composePreviewDaemonStart` yet, or the plugin doesn't apply), or when
 * the schema version doesn't match what this extension knows how to read.
 */
export function readLaunchDescriptor(
    workspaceRoot: string,
    module: ModuleInfo,
    logger?: DaemonClientLogger,
): DaemonLaunchDescriptor | null {
    const file = path.join(
        workspaceRoot,
        module.projectDir,
        "build",
        "compose-previews",
        "daemon-launch.json",
    );
    if (!fs.existsSync(file)) {
        return null;
    }
    try {
        const raw = fs.readFileSync(file, "utf-8");
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
        logger?.appendLine(
            `[daemon] failed to read ${file}: ${(err as Error).message}`,
        );
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
    /** Filters daemon stderr lines and the spawn/exit progress messages.
     *  Defaults to a verbose pass-through so legacy callers and tests are
     *  unchanged. */
    logFilter?: LogFilter;
}

/**
 * Spawns the daemon JVM described by [descriptor], opens a [DaemonClient] over
 * its stdio, and runs the `initialize` handshake. Resolves once the daemon
 * acknowledged `initialize`; the caller can then send notifications and
 * `renderNow` requests.
 *
 * Throws when:
 *   - `descriptor.enabled === false` (the user opted out via
 *     `composePreview { daemon { enabled = false } }`),
 *   - the JVM exits before `initialize` completes,
 *   - `initialize` returns an error.
 */
export async function spawnDaemon(opts: SpawnOptions): Promise<SpawnedDaemon> {
    const { descriptor, clientVersion, events, workspaceRoot } = opts;
    const logger = opts.logger;
    const logFilter = opts.logFilter ?? new LogFilter(() => "verbose");

    if (!descriptor.enabled) {
        throw new Error(
            "Daemon disabled in build config: set `composePreview { daemon { enabled = true } }`",
        );
    }

    const javaPath = descriptor.javaLauncher ?? findJavaOnPath();
    if (!javaPath) {
        throw new Error(
            "No java executable: set javaLauncher via the toolchain or put java on PATH",
        );
    }

    const sysProps = Object.entries(descriptor.systemProperties).map(
        ([k, v]) => `-D${k}=${v}`,
    );
    const args = [
        ...descriptor.jvmArgs,
        ...sysProps,
        "-cp",
        descriptor.classpath.join(path.delimiter),
        descriptor.mainClass,
    ];

    const spawnLine =
        `[daemon] spawning ${descriptor.mainClass} for ${descriptor.modulePath} ` +
        `(${descriptor.classpath.length} classpath entries, java=${javaPath})`;
    if (logFilter.shouldEmitInformational(spawnLine)) {
        logger?.appendLine(spawnLine);
    }

    const child = spawn(javaPath, args, {
        cwd: descriptor.workingDirectory,
        stdio: ["pipe", "pipe", "pipe"],
        windowsHide: true,
    });

    if (!child.stdin || !child.stdout || !child.stderr) {
        throw new Error("Daemon spawn produced no stdio streams");
    }

    // Stderr is a free-form log channel per PROTOCOL.md § 1 — surface to the
    // logger so daemon `System.err.println` lands in the user's output panel.
    // The filter dedupes the Roborazzi ActionBar banner and drops the boot
    // diagnostics at normal level; verbose passes everything through.
    child.stderr.setEncoding("utf-8");
    child.stderr.on("data", (chunk: string) => {
        for (const line of chunk.split(/\r?\n/)) {
            if (line.length === 0) {
                continue;
            }
            const filtered = logFilter.filterDaemonStderrLine(line);
            if (filtered === null) {
                continue;
            }
            logger?.appendLine(`[daemon stderr] ${filtered}`);
        }
    });

    const exited = new Promise<number | null>((resolve) => {
        child.on("exit", (code) => {
            // Exit messages always print — the JVM exiting unexpectedly is
            // never noise. The successful exit case (code=0 on user-driven
            // shutdown) is rare enough that it's still useful at quiet.
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
            capabilities: opts.capabilities ?? {
                visibility: true,
                metrics: true,
            },
        });
    } catch (err) {
        try {
            child.kill("SIGTERM");
        } catch {
            /* ignore */
        }
        throw err;
    }

    // PROTOCOL.md § 3 — `initialized` MUST land before any further request, otherwise the
    // daemon rejects with -32001 ("received '<method>' before 'initialized' notification").
    // We had `extensions/list+enable` running before this notification, which silently broke
    // the whole capability handshake — the catch below swallowed the rejection and every
    // subsequent `data/subscribe` came back as "kind not advertised". Fix is to fire
    // `initialized` as soon as the initialize round-trip resolves.
    client.initialized();

    // PROTOCOL.md § 3a — daemons advertise an empty capability surface until the client
    // opts in via `extensions/enable`. The panel doesn't yet do per-card lifecycle
    // (open card → enable extension → subscribe → close → unsubscribe), so as a
    // transitional step we enable every extension the daemon registered, restoring the
    // pre-v2 "everything advertised" behaviour. Lean opt-in is a follow-up.
    try {
        const list = await client.extensionsList();
        const ids = (list.extensions ?? []).map((info) => info.id);
        if (ids.length > 0) {
            const enabled = await client.extensionsEnable({ ids });
            initializeResult = {
                ...initializeResult,
                capabilities: {
                    ...initializeResult.capabilities,
                    dataProducts: enabled.dataProducts ?? [],
                    dataExtensions: enabled.dataExtensions ?? [],
                    previewExtensions: enabled.previewExtensions ?? [],
                },
            };
            const unknown = enabled.unknown ?? [];
            if (unknown.length > 0) {
                opts.logger?.appendLine(
                    `[daemon] extensions/enable skipped unknown ids ${unknown.join(", ")}`,
                );
            }
        }
    } catch (err) {
        // Non-fatal — daemons that don't speak v2 of this method still serve the
        // initialize round-trip; the panel just sees empty capabilities and degrades.
        opts.logger?.appendLine(
            `[daemon] extensions/list+enable failed: ${(err as Error).message}`,
        );
    }

    return { client, process: child, initializeResult, exited };
}

/**
 * Best-effort lookup for a `java` binary on PATH. Used only when the launch
 * descriptor's `javaLauncher` is null — typically AGP exposes a toolchain
 * launcher and we never reach this path.
 */
function findJavaOnPath(): string | null {
    const exe = process.platform === "win32" ? "java.exe" : "java";
    const dirs = (process.env.PATH ?? "").split(path.delimiter);
    for (const dir of dirs) {
        if (!dir) {
            continue;
        }
        const candidate = path.join(dir, exe);
        try {
            if (fs.statSync(candidate).isFile()) {
                return candidate;
            }
        } catch {
            /* skip */
        }
    }
    return null;
}
