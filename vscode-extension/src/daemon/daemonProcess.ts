import { ChildProcess, spawn } from "child_process";
import * as fs from "fs";
import * as os from "os";
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

/**
 * Splits a stream of arbitrary text chunks into newline-delimited lines, holding
 * the trailing fragment of the last chunk until the next chunk (or `flush`)
 * completes it. Node's `Readable.on("data", ...)` flushes when the writer flushes
 * — not on line boundaries — so a naive `chunk.split(/\r?\n/)` treats a partial
 * line as complete and emits the prefix on its own. For the daemon's stderr that
 * silently corrupts uncaught-exception output: the JVM writes
 * `Exception in thread "main" ` *then* invokes `printStackTrace`, and any pipe
 * boundary between those two writes emits the prefix alone, while the
 * `java.lang.X: msg` continuation arrives in the next chunk and gets dropped by
 * the quiet-level allow-list filter.
 */
export class ChunkLineSplitter {
    private buffer = "";

    /** Append `chunk` and return every complete line it produced. */
    feed(chunk: string): string[] {
        this.buffer += chunk;
        const lines: string[] = [];
        let newlineIndex: number;
        while ((newlineIndex = this.buffer.search(/\r?\n/)) !== -1) {
            lines.push(this.buffer.slice(0, newlineIndex));
            const matchLength = this.buffer[newlineIndex] === "\r" ? 2 : 1;
            this.buffer = this.buffer.slice(newlineIndex + matchLength);
        }
        return lines;
    }

    /** Return any pending fragment (no trailing newline) and clear the buffer. */
    flush(): string | null {
        if (this.buffer.length === 0) {
            return null;
        }
        const tail = this.buffer;
        this.buffer = "";
        return tail;
    }
}

export interface SpawnedDaemon {
    client: DaemonClient;
    process: ChildProcess;
    initializeResult: InitializeResult;
    /** Resolves once the JVM has exited. */
    exited: Promise<number | null>;
}

/**
 * Issue #1326 — recent-stderr tail size carried into spawn-failure messages.
 * Sized for the typical Robolectric / Compose boot crash: the JVM prints
 * `Exception in thread "main" ...` plus a stack trace that fits comfortably
 * under 50 lines, and a fresh sandbox bootstrap prints ~20 informational
 * lines before that, so 50 lines is enough to anchor the crash in its
 * surrounding context without making test failures unscannable.
 */
const RECENT_STDERR_LIMIT = 50;

/**
 * Issue #1326 — build a spawn-failure message that includes the JVM exit code
 * (when known) and the tail of the daemon's stderr, so callers that only see
 * the rejected promise — most importantly the e2e test runner — can see why
 * the JVM died instead of a bare `Daemon channel closed`. Exported for unit
 * coverage; callers go through [spawnDaemon].
 */
export function formatDaemonSpawnFailure(opts: {
    cause: string;
    modulePath: string;
    exitCode: number | null;
    recentStderr: readonly string[];
}): string {
    const exitSuffix =
        opts.exitCode !== null ? ` (JVM exit code=${opts.exitCode})` : "";
    const head = `${opts.cause}${exitSuffix}`;
    if (opts.recentStderr.length === 0) {
        return head;
    }
    const tail = opts.recentStderr.slice(-RECENT_STDERR_LIMIT);
    return [head, `[daemon stderr tail for ${opts.modulePath}]`, ...tail].join(
        "\n",
    );
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
    // The daemon classpath is owned by `descriptor.classpath` and applied via
    // the `@argfile` below; `jvmArgs` is only meant to carry tuning flags
    // (`--add-opens`, `-Xmx`, …). A classpath option or an argfile-disabling
    // flag smuggled into `jvmArgs` — only reachable through a hand-written
    // descriptor or the public daemon-launch-builder `--jvm-arg` CLI — would
    // sabotage the launch: a later `-cp`/`-classpath`/`--class-path` shadows
    // the daemon classpath (last one wins), and `--disable-@files` stops Java
    // expanding the argfile so `@…argfile` is taken as the main class. Strip
    // them so neither argv order nor caller misuse can detach the daemon
    // classpath; the warning surfaces the dropped flag instead of failing
    // mysteriously at class-load time.
    const jvmArgs = sanitizeJvmArgs(descriptor.jvmArgs, (dropped) => {
        logger?.appendLine(
            `[daemon] ignoring classpath/argfile-control option in jvmArgs ` +
                `for ${descriptor.modulePath}: ${dropped} ` +
                `(the daemon classpath is fixed by the launch descriptor)`,
        );
    });
    // A large module classpath joined into a single `-cp` argument overflows
    // the OS argument limits — Linux caps a single argv entry at
    // MAX_ARG_STRLEN (128 KB) — so `spawn` fails with E2BIG before the JVM
    // even starts. Hand the classpath to `java` through an `@argfile` instead:
    // the launcher reads it from disk, so the argv stays tiny regardless of
    // how many jars the consumer's classpath carries. Supported since Java 9,
    // which the daemon already requires.
    //
    // The argfile reference goes first, ahead of `jvmArgs`: Java honours
    // `--disable-@files` from the point it appears onward, so a stray copy
    // would otherwise leave `@…/daemon-classpath.argfile` unexpanded and
    // treated as the main class. Expanding it before any user-supplied arg
    // keeps `-cp` applied regardless of later launcher flags (and the sanitise
    // pass above already removed any such flag).
    const argfilePath = writeClasspathArgfile(descriptor.classpath);
    const args = [
        `@${argfilePath}`,
        ...jvmArgs,
        ...sysProps,
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
    //
    // Buffer carry-over across chunk boundaries: pipes flush whenever the
    // writer happens to flush, not on line boundaries. Java's uncaught-exception
    // handler writes `Exception in thread "main" ` and then calls
    // `printStackTrace`, and the pipe can land its boundary between those two
    // writes — splitting on `\r?\n` per chunk would treat the partial prefix
    // as a complete line and emit `Exception in thread "main" ` with no class
    // name. The quiet-level filter (allow-listed to `^Exception ` / `^Caused
    // by: ` / `^\s+at`) would then drop the `java.lang.X: ...` continuation
    // line that arrives in the next chunk because it doesn't start with one
    // of those prefixes, making every link/initialiser failure look like the
    // exception had no message.
    child.stderr.setEncoding("utf-8");
    // Issue #1326 — keep the raw (unfiltered) tail so a spawn-time crash can
    // surface its stderr through the rejected promise, not just the output
    // channel. The display path below still applies the user's log-level
    // filter; the diagnostic buffer is independent so the dropped Roborazzi
    // banner doesn't hide the exception that landed on the next line.
    const recentStderr: string[] = [];
    const captureStderrLine = (line: string): void => {
        recentStderr.push(line);
        if (recentStderr.length > RECENT_STDERR_LIMIT * 2) {
            recentStderr.splice(0, recentStderr.length - RECENT_STDERR_LIMIT);
        }
    };
    const emitStderrLine = (line: string): void => {
        if (line.length === 0) {
            return;
        }
        const filtered = logFilter.filterDaemonStderrLine(line);
        if (filtered === null) {
            return;
        }
        logger?.appendLine(`[daemon stderr] ${filtered}`);
    };
    const stderrSplitter = new ChunkLineSplitter();
    child.stderr.on("data", (chunk: string) => {
        for (const line of stderrSplitter.feed(chunk)) {
            captureStderrLine(line);
            emitStderrLine(line);
        }
    });
    child.stderr.on("end", () => {
        const tail = stderrSplitter.flush();
        if (tail !== null) {
            captureStderrLine(tail);
            emitStderrLine(tail);
        }
    });

    let exitCode: number | null = null;
    const exited = new Promise<number | null>((resolve) => {
        child.on("exit", (code) => {
            exitCode = code;
            // Exit messages always print — the JVM exiting unexpectedly is
            // never noise. The successful exit case (code=0 on user-driven
            // shutdown) is rare enough that it's still useful at quiet.
            logger?.appendLine(`[daemon] process exited with code=${code}`);
            resolve(code);
        });
    });
    // `java` reads the @argfile during launch, so it's safe to remove once the
    // process exits — whether that's a clean shutdown, a crash, or the SIGTERM
    // from a failed initialize handshake below. Best-effort: a leaked temp file
    // is harmless and the OS reclaims it eventually.
    void exited.then(() => {
        try {
            fs.rmSync(path.dirname(argfilePath), {
                recursive: true,
                force: true,
            });
        } catch {
            /* best-effort cleanup */
        }
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
        // Issue #1326 — channel-closed rejections race the process exit
        // event, and the stderr stream typically still has bytes buffered
        // when initialize rejects. Wait a short grace period so the exit
        // code and the final stderr lines are in hand before we build the
        // failure message; cap at 500ms so a wedged JVM doesn't hang the
        // warm path.
        await Promise.race([
            exited,
            new Promise<void>((resolve) => setTimeout(resolve, 500)),
        ]);
        const cause = (err as Error)?.message ?? String(err);
        throw new Error(
            formatDaemonSpawnFailure({
                cause,
                modulePath: descriptor.modulePath,
                exitCode,
                recentStderr,
            }),
        );
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
 * Java classpath options. Any of these in `jvmArgs` would override the daemon
 * classpath the launch descriptor owns (the JVM applies the last `-cp` it
 * sees), so they're stripped before launch. Both the `-cp value` /
 * `--class-path value` two-token forms and the `--class-path=value` glued form
 * are covered.
 */
const CLASSPATH_OPTION_NAMES = new Set(["-cp", "-classpath", "--class-path"]);

/**
 * Drops classpath options and `--disable-@files` from `jvmArgs` so neither the
 * daemon classpath nor the classpath `@argfile` can be detached by a flag
 * smuggled into the descriptor's `jvmArgs`. `onDropped` is invoked once per
 * removed token (including the consumed value of a two-token classpath option)
 * so the caller can log it. Exported for unit coverage; callers go through
 * [spawnDaemon].
 */
export function sanitizeJvmArgs(
    jvmArgs: readonly string[],
    onDropped?: (arg: string) => void,
): string[] {
    const out: string[] = [];
    for (let i = 0; i < jvmArgs.length; i++) {
        const arg = jvmArgs[i];
        // `--disable-@files` would stop Java expanding the classpath argfile.
        if (arg === "--disable-@files") {
            onDropped?.(arg);
            continue;
        }
        // Glued form, e.g. `-cp=…` / `--class-path=…`.
        const eq = arg.indexOf("=");
        const head = eq === -1 ? arg : arg.slice(0, eq);
        if (CLASSPATH_OPTION_NAMES.has(head)) {
            onDropped?.(arg);
            // Two-token form (`-cp <value>`): also swallow the value so it
            // can't be left behind as a stray positional / main class.
            if (eq === -1 && i + 1 < jvmArgs.length) {
                i++;
                onDropped?.(jvmArgs[i]);
            }
            continue;
        }
        out.push(arg);
    }
    return out;
}

/**
 * Builds the contents of a Java `@argfile` carrying the daemon classpath.
 * Exported for unit coverage; callers go through [spawnDaemon].
 *
 * The joined classpath is wrapped in double quotes so entries containing
 * spaces (e.g. `C:\Program Files\...`) survive the JDK argfile tokenizer, and
 * backslashes are rewritten to forward slashes: the tokenizer treats `\` as an
 * escape character, so a raw Windows path would be silently mangled, while
 * `java` accepts `/` separators in classpath entries on every platform.
 */
export function formatClasspathArgfileContent(classpath: string[]): string {
    const joined = classpath.join(path.delimiter).replace(/\\/g, "/");
    return `-cp "${joined}"\n`;
}

/**
 * Writes the classpath `@argfile` to a private temp directory and returns its
 * path. The directory (not just the file) is removed once the daemon exits;
 * see the cleanup hook in [spawnDaemon].
 */
function writeClasspathArgfile(classpath: string[]): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "compose-preview-cp-"));
    const file = path.join(dir, "daemon-classpath.argfile");
    fs.writeFileSync(file, formatClasspathArgfileContent(classpath), "utf-8");
    return file;
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
