import { spawn } from "child_process";
import * as path from "path";
import type { GradleApi } from "../../gradleService";

/**
 * Real {@link GradleApi} that shells out to the repo's `./gradlew` wrapper.
 * Used by the daily/manual e2e suite to drive the *actual* Gradle plugin
 * end-to-end, instead of the recording stub the fast suite uses.
 *
 * Stays out of `src/main/` because it pulls `child_process` and resolves
 * paths assuming the test layout — production extension code reaches
 * Gradle through the official `vscjava.vscode-gradle` API, never directly.
 */
export class RealGradleApi implements GradleApi {
    /**
     * @param gradlewDir Absolute path to the directory containing the
     *                   `gradlew` script (the repo root).
     * @param onLog Optional sink for human-readable progress lines.
     * @param extraArgs Extra CLI arguments appended to every `gradlew`
     *                  invocation. Used by suites that need to set Gradle
     *                  properties (`-Pfoo=bar`) without plumbing through
     *                  `gradleService.ts`. Example: the wear a11y e2e
     *                  passes `-PcomposePreview.previewExtensions.a11y.enableAllChecks=true`
     *                  until #1009 lands the always-on daemon registry.
     */
    constructor(
        private readonly gradlewDir: string,
        private readonly onLog: (line: string) => void = () => {},
        private readonly extraArgs: ReadonlyArray<string> = [],
    ) {}

    runTask(opts: {
        projectFolder: string;
        taskName: string;
        args?: ReadonlyArray<string>;
        showOutputColors: boolean;
        onOutput?: (output: {
            getOutputBytes(): Uint8Array;
            getOutputType(): number;
        }) => void;
        cancellationKey?: string;
    }): Promise<void> {
        const gradlewPath =
            process.platform === "win32"
                ? path.join(this.gradlewDir, "gradlew.bat")
                : path.join(this.gradlewDir, "gradlew");
        const gradleArgs = [
            opts.taskName,
            ...(opts.args ?? []),
            ...this.extraArgs,
        ];
        this.onLog(
            `[realGradleApi] ${gradlewPath} ${gradleArgs.join(" ")} (cwd=${opts.projectFolder})`,
        );

        return new Promise((resolve, reject) => {
            const child = spawn(gradlewPath, gradleArgs, {
                cwd: opts.projectFolder,
                env: { ...process.env },
                stdio: ["ignore", "pipe", "pipe"],
            });
            child.stdout.on("data", (chunk: Buffer) => {
                opts.onOutput?.({
                    getOutputBytes: () => new Uint8Array(chunk),
                    // 0 = stdout, matches the bytes-shaped contract the
                    // production GradleApi consumer (gradleService.ts) uses.
                    getOutputType: () => 0,
                });
            });
            child.stderr.on("data", (chunk: Buffer) => {
                opts.onOutput?.({
                    getOutputBytes: () => new Uint8Array(chunk),
                    getOutputType: () => 1,
                });
            });
            child.once("error", reject);
            child.once("close", (code) => {
                if (code === 0) {
                    resolve();
                } else {
                    reject(
                        new Error(
                            `gradlew ${gradleArgs.join(" ")} exited with ${code}`,
                        ),
                    );
                }
            });
        });
    }

    async cancelRunTask(): Promise<void> {
        // The e2e suite runs each gradle invocation to completion; the
        // extension never cancels mid-run in this harness. If we ever want
        // to exercise cancellation we'd track the live child here.
    }
}
