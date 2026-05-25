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
     *                  `gradleService.ts`.
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

        // Under e2e-external the underlying gradleService routes Gradle output
        // to the extension's `outputChannel` (via `logger.append`), which
        // doesn't surface in CI stdout. That makes a silent `composePreviewApplied`
        // failure or a missing-marker fan-out invisible — the test fails with
        // "resolveModule returned null" and no Gradle context. Forward stderr
        // verbatim and stdout selectively (build status + task names + errors)
        // to console.log so the CI log has enough to triage. Cheap: this is a
        // test-only realGradleApi, not the production GradleApi path.
        const diagE2e = process.env.COMPOSE_PREVIEW_E2E_EXTERNAL === "1";
        const stdoutDiagRe =
            /BUILD\s+(SUCCESSFUL|FAILED)|composePreviewApplied|FAILURE|^Configuring |Could not resolve|Exception/m;
        return new Promise((resolve, reject) => {
            const child = spawn(gradlewPath, gradleArgs, {
                cwd: opts.projectFolder,
                env: { ...process.env },
                stdio: ["ignore", "pipe", "pipe"],
            });
            child.stdout.on("data", (chunk: Buffer) => {
                if (diagE2e) {
                    const text = chunk.toString("utf-8");
                    for (const line of text.split("\n")) {
                        if (stdoutDiagRe.test(line))
                            console.log(`[gradle stdout] ${line}`);
                    }
                }
                opts.onOutput?.({
                    getOutputBytes: () => new Uint8Array(chunk),
                    // 0 = stdout, matches the bytes-shaped contract the
                    // production GradleApi consumer (gradleService.ts) uses.
                    getOutputType: () => 0,
                });
            });
            child.stderr.on("data", (chunk: Buffer) => {
                if (diagE2e) {
                    process.stderr.write(`[gradle stderr] ${chunk}`);
                }
                opts.onOutput?.({
                    getOutputBytes: () => new Uint8Array(chunk),
                    getOutputType: () => 1,
                });
            });
            child.once("error", reject);
            child.once("close", (code) => {
                if (diagE2e) {
                    console.log(
                        `[gradle exit] code=${code} args=${gradleArgs.join(" ")}`,
                    );
                }
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
