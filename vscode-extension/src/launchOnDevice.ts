import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { spawn } from "child_process";

/**
 * Helpers for the "Launch on Device" panel button. This module is
 * intentionally `vscode`-free so it can be unit-tested under plain mocha.
 *
 * The flow is: detect Android-application modules among the workspace's
 * preview modules, resolve their `applicationId`, then drive `adb shell
 * monkey -p <applicationId> -c android.intent.category.LAUNCHER 1` to
 * start the consumer's app on a connected device. Build + install is
 * handled separately via Gradle's `:<module>:installDebug` task.
 */

const ANDROID_APPLICATION_PLUGIN_ID_RE =
    /\bid\s*[(\s]\s*["']com\.android\.application["']/;
const ANDROID_APPLICATION_ALIAS_RE =
    /\balias\s*\(\s*libs\.plugins\.android\.application\s*\)/;
const APPLICATION_ID_RE = /\bapplicationId\s*=\s*["']([^"']+)["']/;
const APPLY_FALSE_RE = /\bapply\s+false\b/;

export interface AndroidApplicationInfo {
    /** Fully-qualified `applicationId` declared in `defaultConfig`. */
    applicationId: string;
}

/**
 * Parse `build.gradle.kts` content to determine whether the module is an
 * Android *application* (not a library) and, if so, extract its
 * `applicationId`. Returns `null` when the module isn't an Android app
 * or when no `applicationId` is declared.
 *
 * Only the literal-id form (`id("com.android.application")`) and the
 * version-catalog alias form (`alias(libs.plugins.android.application)`)
 * are recognised — that covers every sample in this repo and the typical
 * AGP-bootstrapped consumer project. Lines containing `apply false` are
 * skipped (root-build pattern where the plugin is declared but not
 * applied in the current module).
 */
export function parseAndroidApplicationInfo(
    buildGradleContent: string,
): AndroidApplicationInfo | null {
    const lines = buildGradleContent.split(/\r?\n/);
    let isAndroidApp = false;
    for (const line of lines) {
        if (APPLY_FALSE_RE.test(line)) {
            continue;
        }
        if (
            ANDROID_APPLICATION_PLUGIN_ID_RE.test(line) ||
            ANDROID_APPLICATION_ALIAS_RE.test(line)
        ) {
            isAndroidApp = true;
            break;
        }
    }
    if (!isAndroidApp) {
        return null;
    }
    const m = APPLICATION_ID_RE.exec(buildGradleContent);
    if (!m) {
        return null;
    }
    return { applicationId: m[1] };
}

/**
 * Resolve the Android SDK root, in the order Android Studio / Gradle
 * themselves use:
 *
 *   1. `$ANDROID_HOME` / `$ANDROID_SDK_ROOT`
 *   2. `local.properties`'s `sdk.dir=` line at the workspace root
 *   3. `~/Library/Android/sdk` (macOS) or `~/Android/Sdk` (Linux), if it exists
 *
 * Returns null when no path is found — callers should fall back to
 * resolving `adb` from `PATH`.
 */
export function findAndroidSdkRoot(workspaceRoot: string): string | null {
    const fromEnv = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    if (fromEnv && fs.existsSync(fromEnv)) {
        return fromEnv;
    }
    const localProps = path.join(workspaceRoot, "local.properties");
    try {
        const content = fs.readFileSync(localProps, "utf-8");
        for (const line of content.split(/\r?\n/)) {
            const m = /^\s*sdk\.dir\s*=\s*(.+?)\s*$/.exec(line);
            if (m) {
                const dir = m[1].trim();
                if (fs.existsSync(dir)) {
                    return dir;
                }
            }
        }
    } catch {
        /* no local.properties — fall through */
    }
    const home = os.homedir();
    const candidates = [
        path.join(home, "Library", "Android", "sdk"),
        path.join(home, "Android", "Sdk"),
    ];
    for (const c of candidates) {
        if (fs.existsSync(c)) {
            return c;
        }
    }
    return null;
}

/**
 * Resolve the path to `adb`. When [sdkRoot] is provided we point at
 * `<sdk>/platform-tools/adb` (or `adb.exe` on Windows); otherwise we
 * return the bare name and trust the caller's `PATH`.
 */
export function resolveAdbPath(sdkRoot: string | null): string {
    if (!sdkRoot) {
        return "adb";
    }
    const exe = process.platform === "win32" ? "adb.exe" : "adb";
    return path.join(sdkRoot, "platform-tools", exe);
}

export interface AdbResult {
    code: number;
    stdout: string;
    stderr: string;
}

/**
 * Run `adb` with the given arguments. Resolves on exit regardless of
 * status code — callers inspect [AdbResult.code] to distinguish success
 * from failure (adb returns non-zero on "no devices", "no app installed",
 * etc., and we want to surface those as readable messages rather than
 * generic spawn failures).
 *
 * Rejects only when `adb` itself can't be spawned (binary not found).
 */
export function runAdb(adbPath: string, args: string[]): Promise<AdbResult> {
    return new Promise((resolve, reject) => {
        const proc = spawn(adbPath, args, {
            stdio: ["ignore", "pipe", "pipe"],
        });
        let stdout = "";
        let stderr = "";
        proc.stdout.on("data", (chunk) => {
            stdout += chunk.toString();
        });
        proc.stderr.on("data", (chunk) => {
            stderr += chunk.toString();
        });
        proc.on("error", reject);
        proc.on("close", (code) => {
            resolve({ code: code ?? -1, stdout, stderr });
        });
    });
}

/**
 * Build the `adb shell am start` argv that hands a single preview to
 * `androidx.compose.ui.tooling.PreviewActivity` — the same activity
 * Android Studio drives for "Deploy Preview to Device". The activity
 * ships in `androidx.compose.ui:ui-tooling` (typically pulled in as
 * `debugImplementation`) and reflects on the FQN passed via the
 * `composable` extra to render that one composable on screen.
 *
 * Extras key contract (matches Android Studio's deploy-preview path):
 *
 *   - `composable` — fully qualified function name including the file
 *     class, e.g. `com.example.PreviewsKt.MyPreview`.
 *   - `parameterProviderClassName` — FQN of the
 *     `PreviewParameterProvider` for `@PreviewParameter` previews.
 *     Omitted for static previews. We don't pass `parameterProviderIndex`
 *     here because our preview manifest IDs encode the suffix in the
 *     filename, not the integer index — landing on index 0 (the default)
 *     is the closest reproducible behaviour.
 */
export function buildPreviewActivityAmStartArgs(opts: {
    applicationId: string;
    composableFqn: string;
    parameterProviderClassName: string | null;
}): string[] {
    const component = `${opts.applicationId}/androidx.compose.ui.tooling.PreviewActivity`;
    const args = [
        "shell",
        "am",
        "start",
        "-n",
        component,
        "--es",
        "composable",
        opts.composableFqn,
    ];
    if (opts.parameterProviderClassName) {
        args.push(
            "--es",
            "parameterProviderClassName",
            opts.parameterProviderClassName,
        );
    }
    return args;
}

/**
 * Filter [modules] to those whose `build.gradle.kts` applies the
 * Android-application plugin and declares an `applicationId`. Returns a
 * sorted list of `{module, applicationId}` pairs. Modules that can't be
 * read or aren't Android applications are silently skipped.
 */
export function collectAndroidApplicationModules(
    workspaceRoot: string,
    modules: Iterable<string>,
): Array<{ module: string; applicationId: string }> {
    const out: Array<{ module: string; applicationId: string }> = [];
    for (const module of modules) {
        const buildFile = path.join(workspaceRoot, module, "build.gradle.kts");
        let content: string;
        try {
            content = fs.readFileSync(buildFile, "utf-8");
        } catch {
            continue;
        }
        const info = parseAndroidApplicationInfo(content);
        if (info) {
            out.push({ module, applicationId: info.applicationId });
        }
    }
    out.sort((a, b) => a.module.localeCompare(b.module));
    return out;
}
