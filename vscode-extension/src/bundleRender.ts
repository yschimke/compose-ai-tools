// Drives `compose-preview bundle render <bundle.png> -o <outDir>` from the
// extension host — used by the bundle viewer panel to re-render a dropped
// or command-opened bundle outside any Gradle workspace.
//
// The CLI's `BundleRenderer` already handles classpath resolution, the
// per-preview subprocess fan-out, and the manifest parsing — we just need
// to spawn the launcher, parse its summary lines, and surface progress to
// VS Code's notification UI.

import { spawn } from "child_process";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { BundleRenderResult, parseRenderOutput } from "./bundleRenderOutput";

export { BundleRenderResult, parseRenderOutput } from "./bundleRenderOutput";
export type {
    BundleFailedPreview,
    BundleRenderedPreview,
} from "./bundleRenderOutput";

/** Stand-in for the CLI not being on PATH (or the config override
 *  pointing at a non-executable file). Distinct from a launcher that
 *  exited non-zero — that surfaces as a regular `Error`. */
export class BundleCliNotFoundError extends Error {
    constructor(public readonly searched: string[]) {
        super(
            `compose-preview CLI not found. Looked at: ${searched.join(", ")}. ` +
                "Install the CLI via `scripts/install.sh` or set `composePreview.bundleCliPath`.",
        );
        this.name = "BundleCliNotFoundError";
    }
}

export interface BundleRenderOptions {
    bundlePath: string;
    outputDir: string;
    /** Resolved CLI launcher path. Use [locateBundleCli] to find one. */
    cliPath: string;
    /** Receives one decoded chunk of stdout/stderr per write. The host
     *  uses this to surface live progress in the notification body. */
    onOutput?: (chunk: string) => void;
    /** Cancellation token from `vscode.window.withProgress`. When fired,
     *  we SIGTERM the subprocess and reject. */
    cancellation?: vscode.CancellationToken;
}

/**
 * Spawns `compose-preview bundle render <bundlePath> -o <outputDir> -v`
 * and returns the parsed result. Throws on launcher failure (non-zero
 * exit, killed, or unparseable output); per-preview render failures are
 * reported via [BundleRenderResult.failed] and don't reject the promise.
 */
export async function renderBundle(
    opts: BundleRenderOptions,
): Promise<BundleRenderResult> {
    await fs.promises.mkdir(opts.outputDir, { recursive: true });
    const args = [
        "bundle",
        "render",
        opts.bundlePath,
        "-o",
        opts.outputDir,
        "-v",
    ];
    return new Promise<BundleRenderResult>((resolve, reject) => {
        const proc = spawn(opts.cliPath, args, {
            stdio: ["ignore", "pipe", "pipe"],
        });
        let cancelled = false;
        const cancel = opts.cancellation?.onCancellationRequested(() => {
            cancelled = true;
            proc.kill("SIGTERM");
        });
        let stdout = "";
        let stderr = "";
        proc.stdout.on("data", (chunk: Buffer) => {
            const text = chunk.toString("utf-8");
            stdout += text;
            opts.onOutput?.(text);
        });
        proc.stderr.on("data", (chunk: Buffer) => {
            const text = chunk.toString("utf-8");
            stderr += text;
            opts.onOutput?.(text);
        });
        proc.on("error", (err) => {
            cancel?.dispose();
            reject(err);
        });
        proc.on("close", (code) => {
            cancel?.dispose();
            if (cancelled) {
                reject(new Error("bundle render cancelled"));
                return;
            }
            const result = parseRenderOutput(stdout, opts.outputDir);
            if (result === null) {
                reject(
                    new Error(
                        `compose-preview bundle render exited with code ${code} and no summary on stdout.\n` +
                            tailLines(stderr || stdout, 20),
                    ),
                );
                return;
            }
            if (code !== 0 && result.succeeded.length === 0) {
                reject(
                    new Error(
                        `compose-preview bundle render exited with code ${code}.\n` +
                            tailLines(stderr || stdout, 20),
                    ),
                );
                return;
            }
            resolve(result);
        });
    });
}

function tailLines(text: string, count: number): string {
    const lines = text.split(/\r?\n/);
    return lines.slice(-count).join("\n");
}

/**
 * Locate a `compose-preview` launcher to use for `bundle render`. In
 * order:
 *
 * 1. `composePreview.bundleCliPath` setting — explicit user override.
 * 2. `COMPOSE_PREVIEW_CLI` environment variable — same purpose, useful
 *    for CI / dev shells that bake the path into a profile.
 * 3. `compose-preview` on PATH — the install script's default landing
 *    spot (`~/.local/bin/compose-preview`).
 *
 * Returns the absolute path of the first existing candidate, or throws
 * [BundleCliNotFoundError] listing what was tried.
 */
export async function locateBundleCli(): Promise<string> {
    const tried: string[] = [];
    const configured = vscode.workspace
        .getConfiguration("composePreview")
        .get<string>("bundleCliPath");
    if (configured && configured.trim().length > 0) {
        tried.push(`composePreview.bundleCliPath=${configured}`);
        if (await isExecutable(configured)) return configured;
    }
    const envCli = process.env.COMPOSE_PREVIEW_CLI;
    if (envCli && envCli.trim().length > 0) {
        tried.push(`COMPOSE_PREVIEW_CLI=${envCli}`);
        if (await isExecutable(envCli)) return envCli;
    }
    const fromPath = await findOnPath("compose-preview");
    if (fromPath) {
        tried.push(`PATH=${fromPath}`);
        return fromPath;
    }
    tried.push("PATH (no `compose-preview` entry)");
    throw new BundleCliNotFoundError(tried);
}

async function isExecutable(filePath: string): Promise<boolean> {
    try {
        const stat = await fs.promises.stat(filePath);
        if (!stat.isFile()) return false;
        await fs.promises.access(filePath, fs.constants.X_OK);
        return true;
    } catch {
        return false;
    }
}

async function findOnPath(binary: string): Promise<string | null> {
    const PATH = process.env.PATH ?? "";
    const sep = process.platform === "win32" ? ";" : ":";
    const exts =
        process.platform === "win32"
            ? (process.env.PATHEXT ?? ".EXE;.CMD;.BAT").split(";")
            : [""];
    for (const dir of PATH.split(sep)) {
        if (!dir) continue;
        for (const ext of exts) {
            const candidate = path.join(dir, binary + ext.toLowerCase());
            if (await isExecutable(candidate)) return candidate;
        }
    }
    return null;
}
