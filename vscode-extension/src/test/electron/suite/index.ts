import * as path from 'path';
import Mocha from 'mocha';
import { glob } from 'glob';

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
        ui: 'bdd',
        color: true,
        // Generous default — VS Code activation + view focus + a stub
        // Gradle round-trip lands well under this on a warm host, but a
        // cold-start CI run can spend several seconds on the
        // `downloadAndUnzipVSCode` cache miss path. 60s also gives the
        // first test enough head room to drive the activation-time
        // bookkeeping (extension-host event-loop spinup) before the
        // assertions run.
        timeout: 60_000,
        reporter: 'spec',
    });

    const testsRoot = __dirname;
    const files = await glob('**/*.test.js', { cwd: testsRoot });
    console.log(`[suite] discovered ${files.length} test file(s) in ${testsRoot}`);
    for (const f of files) {
        const abs = path.resolve(testsRoot, f);
        console.log(`[suite] add ${abs}`);
        mocha.addFile(abs);
    }

    return new Promise((resolve, reject) => {
        try {
            mocha.run(failures => {
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
