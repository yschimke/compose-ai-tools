import * as path from "path";
import Mocha from "mocha";
import { glob } from "glob";

/**
 * Mocha entry point loaded by `@vscode/test-electron` inside the spawned
 * extension host. Discovers every `*.test.js` under the same directory
 * tree (compiled output of `src/test/electron/suite/`) and runs them.
 *
 * The runner forwards exit status — a non-zero failure count rejects the
 * promise so `runTest.ts` can `process.exit(1)` and CI sees the failure.
 */
export async function run(): Promise<void> {
    const mocha = new Mocha({
        ui: "bdd",
        color: true,
        // Generous default — VS Code activation + view focus + a stub
        // Gradle round-trip lands well under this on a warm host, but a
        // cold-start CI run can spend several seconds on the
        // `downloadAndUnzipVSCode` cache miss path. 60s also gives the
        // first test enough head room to drive the activation-time
        // bookkeeping (extension-host event-loop spinup) before the
        // assertions run.
        timeout: 60_000,
        reporter: "spec",
    });

    const testsRoot = __dirname;
    // E2E mode (COMPOSE_PREVIEW_E2E=1, set by runTest.ts when launched via
    // `npm run test:e2e`) runs only the slow real-Gradle suite; the fast
    // suite excludes it. Pattern selection here so each mode's run output
    // doesn't list "skipped" entries for the other mode's tests.
    const e2eMode = process.env.COMPOSE_PREVIEW_E2E === "1";
    // Match every `e2e*.test.js` so the slow suite can be split across
    // files (`e2e.test.js`, `e2eA11y.test.js`, …) without having to
    // restate file names here.
    const pattern = e2eMode ? "**/e2e*.test.js" : "**/*.test.js";
    const ignore = e2eMode ? [] : ["**/e2e*.test.js"];
    const files = await glob(pattern, { cwd: testsRoot, ignore });
    console.log(
        `[suite] discovered ${files.length} test file(s) in ${testsRoot} (e2e=${e2eMode})`,
    );
    for (const f of files) {
        const abs = path.resolve(testsRoot, f);
        console.log(`[suite] add ${abs}`);
        mocha.addFile(abs);
    }

    return new Promise((resolve, reject) => {
        try {
            mocha.run((failures) => {
                if (failures > 0) {
                    reject(new Error(`${failures} test(s) failed`));
                } else {
                    resolve();
                }
            });
        } catch (err) {
            reject(err as Error);
        }
    });
}
