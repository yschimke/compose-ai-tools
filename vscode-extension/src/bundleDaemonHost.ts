// Spawns `compose-preview bundle daemon <bundle.png>` as a subprocess and
// wraps its stdio in a `DaemonClient`. Lets `BundleViewerPanel` reach the
// same protocol surface the sidebar panel uses for in-workspace modules
// (renderNow, data/subscribe, interactive/*, composestream/1, …) without
// owning anything beyond "feed this preview id to the daemon, surface
// what comes back."
//
// One host per bundle viewer panel. Initialised lazily on first use so
// the bundle can be opened even when the CLI isn't installed — the
// panel falls back to a "daemon unavailable" toolbar in that case.

import { ChildProcess, spawn } from "child_process";
import * as fs from "fs";
import { DaemonClient, DaemonClientEvents } from "./daemon/daemonClient";
import {
    DataSubscribeParams,
    InitializeResult,
    RenderNowParams,
} from "./daemon/daemonProtocol";

export interface BundleDaemonOptions {
    /** Absolute path to the bundle file to mount. */
    bundlePath: string;
    /** Absolute path to the `compose-preview` launcher (resolved via
     *  `bundleRender.locateBundleCli` upstream). */
    cliPath: string;
    /** Forwarded into `DaemonClient` for stderr / RPC tracing. */
    logger?: { appendLine(line: string): void };
    /** Daemon-event callbacks. The panel populates this with handlers that
     *  forward renderFinished / updateA11y / updateDataProducts straight
     *  to its webview. */
    events: DaemonClientEvents;
    /** Initialize payload's `clientVersion` field. Mirrors the sidebar
     *  panel's value so the daemon's compatibility check passes
     *  identically. */
    clientVersion: string;
}

export interface BundleDaemonHandle {
    /** JSON-RPC surface for caller-side requests (renderNow, dataSubscribe,
     *  …). The client is wired to the spawned process's stdio. */
    client: DaemonClient;
    /** Capabilities + advertised extensions, returned by `initialize`
     *  + `extensions/enable`. The panel uses
     *  `capabilities.dataExtensions` to decide which chips to surface. */
    initializeResult: InitializeResult;
    /** Resolves with the JVM's exit code once it terminates. */
    exited: Promise<number | null>;
    /** Drops the subprocess (SIGTERM, then SIGKILL after a grace period
     *  if the daemon hangs on stdout flush). Idempotent. */
    dispose(): void;
}

export class BundleDaemonSpawnError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "BundleDaemonSpawnError";
    }
}

/**
 * Spawns the CLI subcommand, opens a [DaemonClient] over its stdio, and
 * runs `initialize` + `initialized` + `extensions/enable` (with every
 * advertised extension id) so the panel can start subscribing to data
 * products immediately. Throws [BundleDaemonSpawnError] when the
 * launcher exits before `initialize` resolves.
 */
export async function spawnBundleDaemon(
    opts: BundleDaemonOptions,
): Promise<BundleDaemonHandle> {
    if (!fs.existsSync(opts.cliPath)) {
        throw new BundleDaemonSpawnError(
            `compose-preview launcher missing: ${opts.cliPath}`,
        );
    }
    const child = spawn(
        opts.cliPath,
        ["bundle", "daemon", opts.bundlePath, "-v"],
        {
            stdio: ["pipe", "pipe", "pipe"],
            windowsHide: true,
        },
    );
    if (!child.stdin || !child.stdout || !child.stderr) {
        throw new BundleDaemonSpawnError(
            "bundle daemon spawn produced no stdio streams",
        );
    }
    // The CLI emits a leading `[bundle-daemon] launching: …` block on
    // stderr before exec-ing the JVM; surface it (and subsequent daemon
    // stderr) into the extension log so spawn failures are debuggable.
    child.stderr.setEncoding("utf-8");
    child.stderr.on("data", (chunk: string) => {
        for (const line of chunk.split(/\r?\n/)) {
            if (line.length === 0) continue;
            opts.logger?.appendLine(`[bundle-daemon] ${line}`);
        }
    });

    const exited = new Promise<number | null>((resolve) => {
        child.on("exit", (code) => {
            opts.logger?.appendLine(
                `[bundle-daemon] process exited with code=${code}`,
            );
            resolve(code);
        });
    });

    // Reject fast if the launcher dies before `initialize` returns —
    // happens when the CLI can't find its sidecar jars or the bundle is
    // malformed beyond the manifest-read pre-flight.
    const earlyDeath = new Promise<never>((_, reject) => {
        child.once("exit", (code) => {
            reject(
                new BundleDaemonSpawnError(
                    `bundle daemon exited before initialize (code=${code})`,
                ),
            );
        });
    });

    const client = new DaemonClient(
        child.stdin,
        child.stdout,
        opts.events,
        opts.logger,
    );

    let initializeResult: InitializeResult;
    try {
        initializeResult = await Promise.race([
            client.initialize({
                clientVersion: opts.clientVersion,
                workspaceRoot: "",
                moduleId: `bundle:${opts.bundlePath}`,
                moduleProjectDir: "",
                capabilities: { visibility: true, metrics: true },
            }),
            earlyDeath,
        ]);
    } catch (err) {
        safeKill(child);
        throw err;
    }
    client.initialized();

    // Same transitional opt-in the sidebar daemon does — enable every
    // extension the daemon advertises so the panel's chip bar has
    // something to subscribe to without per-card lifecycle bookkeeping.
    try {
        const list = await client.extensionsList();
        const ids = (list.extensions ?? []).map((info) => info.id);
        if (ids.length > 0) {
            const enabled = await client.extensionsEnable({ ids });
            initializeResult = {
                ...initializeResult,
                capabilities: {
                    ...initializeResult.capabilities,
                    dataProducts:
                        enabled.dataProducts ??
                        initializeResult.capabilities.dataProducts,
                    dataExtensions:
                        enabled.dataExtensions ??
                        initializeResult.capabilities.dataExtensions,
                    previewExtensions:
                        enabled.previewExtensions ??
                        initializeResult.capabilities.previewExtensions,
                },
            };
        }
    } catch (err) {
        opts.logger?.appendLine(
            `[bundle-daemon] extensions/list+enable failed: ${(err as Error).message}`,
        );
    }

    return {
        client,
        initializeResult,
        exited,
        dispose: () => safeKill(child),
    };
}

function safeKill(child: ChildProcess): void {
    if (child.killed || child.exitCode !== null) return;
    try {
        child.kill("SIGTERM");
    } catch {
        /* ignore */
    }
    // Hard-kill grace period — the daemon should drain stdin and exit
    // within a second of SIGTERM; if it hangs (uncaught throwable in a
    // background thread), force the issue so the user's tab close
    // doesn't leave a zombie JVM.
    setTimeout(() => {
        if (child.exitCode === null && !child.killed) {
            try {
                child.kill("SIGKILL");
            } catch {
                /* ignore */
            }
        }
    }, 1500);
}

/**
 * Helpers around the daemon's data-extension surface — the bundle viewer
 * panel's webview messages map onto a small subset of the protocol and
 * we collect the mapping here so the panel stays a thin router.
 */
export const A11Y_DATA_KINDS = ["a11y/atf", "a11y/hierarchy"] as const;

/**
 * Subscribe / unsubscribe all kinds in [kinds] for [previewId] against
 * the supplied client, returning once every individual call resolves.
 * Failures are caught and reported via [onError]; the call set is
 * "best-effort" because the daemon may advertise a kind in
 * `extensions/list` but reject `data/subscribe` for it (depends on
 * connector readiness). Mirrors the sidebar panel's call shape.
 */
export async function setKindsSubscribed(
    client: DaemonClient,
    previewId: string,
    kinds: readonly string[],
    subscribed: boolean,
    onError?: (kind: string, err: Error) => void,
): Promise<void> {
    await Promise.all(
        kinds.map(async (kind) => {
            const params: DataSubscribeParams = { previewId, kind };
            try {
                if (subscribed) {
                    await client.dataSubscribe(params);
                } else {
                    await client.dataUnsubscribe(params);
                }
            } catch (err) {
                onError?.(kind, err as Error);
            }
        }),
    );
}

/** Convenience for the initial-render fan-out: ask the daemon to
 *  render every preview id in the bundle at full tier. */
export function renderAll(
    client: DaemonClient,
    previewIds: string[],
    reason = "bundle-open",
): Promise<unknown> {
    if (previewIds.length === 0) return Promise.resolve();
    const params: RenderNowParams = {
        previews: previewIds,
        tier: "full",
        reason,
    };
    return client.renderNow(params);
}
