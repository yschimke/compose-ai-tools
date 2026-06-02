// Android SDK resolution for the bundle viewer's daemon launch.
//
// An `backend="android"` bundle launches the Robolectric daemon, which needs
// `android.jar` resolved from a local Android SDK (see the CLI's
// `BundleDaemonCommand.androidDaemonLaunch`). When VS Code is launched from the
// GUI (Finder/Dock on macOS, the Start menu on Windows) the spawned daemon does
// NOT inherit the shell's `ANDROID_HOME`, so the launch fails with an opaque
// error. We resolve an SDK path here and forward it into the daemon's env.
//
// Pure module (no `vscode` import) so the precedence logic is unit-testable; the
// panel reads the `composePreview.androidSdkPath` setting + workspace root and
// hands them in.
//
// Distinct from `launchOnDevice.ts`'s `findAndroidSdkRoot` on purpose: this adds
// the `composePreview.androidSdkPath` setting at the top, takes an injectable
// `env` (so the precedence is testable without mutating `process.env`), and
// deliberately mirrors the CLI's resolution contract exactly (setting → env →
// local.properties, no `~/.../Android/sdk` probing) so the env we forward to the
// daemon matches what the daemon would resolve on its own.

import * as fs from "fs";
import * as path from "path";

export type AndroidSdkSource =
    | "setting"
    | "ANDROID_HOME"
    | "ANDROID_SDK_ROOT"
    | "local.properties";

export interface AndroidSdkResolution {
    /** Absolute path to the Android SDK root (an existing directory). */
    sdkDir: string;
    /** Where the path came from — surfaced in the extension log. */
    source: AndroidSdkSource;
}

export interface ResolveAndroidSdkOptions {
    /** Value of the `composePreview.androidSdkPath` setting (may be empty). */
    settingPath?: string;
    /** Environment to read `ANDROID_HOME` / `ANDROID_SDK_ROOT` from. Defaults to `process.env`. */
    env?: NodeJS.ProcessEnv;
    /** Workspace root to look for `local.properties` (`sdk.dir`). */
    workspaceRoot?: string;
}

/**
 * Resolve an Android SDK location for the bundle daemon. Mirrors the precedence
 * the CLI uses (`ANDROID_HOME` → `ANDROID_SDK_ROOT` → `local.properties`) but
 * adds the `composePreview.androidSdkPath` setting at the top so users can point
 * the panel at an SDK without touching their shell env. Each candidate must
 * resolve to an existing directory; the first that does wins. Returns
 * `undefined` when none resolves.
 */
export function resolveAndroidSdk(
    opts: ResolveAndroidSdkOptions = {},
): AndroidSdkResolution | undefined {
    const env = opts.env ?? process.env;
    const candidates: Array<{
        value: string | undefined;
        source: AndroidSdkSource;
    }> = [
        { value: opts.settingPath, source: "setting" },
        { value: env.ANDROID_HOME, source: "ANDROID_HOME" },
        { value: env.ANDROID_SDK_ROOT, source: "ANDROID_SDK_ROOT" },
        {
            value: readLocalPropertiesSdkDir(opts.workspaceRoot),
            source: "local.properties",
        },
    ];
    for (const candidate of candidates) {
        const trimmed = candidate.value?.trim();
        if (trimmed && isDirectory(trimmed)) {
            return { sdkDir: trimmed, source: candidate.source };
        }
    }
    return undefined;
}

/**
 * Env overrides handed to the daemon subprocess so its `android.jar` resolution
 * finds [resolution]. Sets both `ANDROID_HOME` and `ANDROID_SDK_ROOT` since the
 * CLI checks them in that order.
 */
export function androidSdkEnv(
    resolution: AndroidSdkResolution,
): Record<string, string> {
    return {
        ANDROID_HOME: resolution.sdkDir,
        ANDROID_SDK_ROOT: resolution.sdkDir,
    };
}

function isDirectory(candidate: string): boolean {
    try {
        return fs.statSync(candidate).isDirectory();
    } catch {
        return false;
    }
}

function readLocalPropertiesSdkDir(workspaceRoot?: string): string | undefined {
    if (!workspaceRoot) return undefined;
    let text: string;
    try {
        text = fs.readFileSync(
            path.join(workspaceRoot, "local.properties"),
            "utf-8",
        );
    } catch {
        return undefined;
    }
    for (const raw of text.split(/\r?\n/)) {
        const line = raw.trim();
        if (line.startsWith("#") || line.startsWith("!")) continue;
        if (!line.startsWith("sdk.dir")) continue;
        const eq = line.indexOf("=");
        if (eq < 0) continue;
        // Java `.properties` escapes `:` and `\` as `\:` / `\\` — common in a
        // Windows `sdk.dir=C\:\\Users\\…`. Unescape so the path is usable.
        const value = line
            .slice(eq + 1)
            .trim()
            .replace(/\\(.)/g, "$1");
        if (value) return value;
    }
    return undefined;
}
