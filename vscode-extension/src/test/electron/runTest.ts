import * as path from 'path';
import { downloadAndUnzipVSCode, runTests } from '@vscode/test-electron';

/**
 * Entry point for `npm run test:electron`.
 *
 * Downloads a stable VS Code if needed, installs the test-only fake
 * `vscjava.vscode-gradle` extension so our extensionDependency check
 * passes, then launches VS Code with our extension under development
 * pointing at the fixture workspace. The Mocha suite at
 * [./suite/index] runs inside the extension host and ends the process.
 *
 * Setting `COMPOSE_PREVIEW_TEST_MODE=1` flips `extension.ts` into the
 * branch that returns its [ComposePreviewTestApi] from `activate` and
 * skips the activation-time auto-refresh. Tests reach for the API via
 * `vscode.extensions.getExtension('yuri-schimke.compose-preview').exports`.
 */
async function main(): Promise<void> {
    const extensionDevelopmentPath = path.resolve(__dirname, '../../..');
    const extensionTestsPath = path.resolve(__dirname, './suite/index');
    // Fixtures live in `src/` (raw assets — JSON manifest + PNG/GIF + a
    // build.gradle.kts), not `out/`, so resolve relative to the source tree.
    // `__dirname` here is `out/test/electron/`, hence the `../../../src/...`
    // walk-back to land in `src/test/electron/fixtures/`.
    const fixturesRoot = path.resolve(__dirname, '../../../src/test/electron/fixtures');
    const workspacePath = path.join(fixturesRoot, 'workspace');
    const fakeGradleExtensionPath = path.join(fixturesRoot, 'fake-vscode-gradle');

    console.log(`[runTest] extensionDevelopmentPath=${extensionDevelopmentPath}`);
    console.log(`[runTest] extensionTestsPath=${extensionTestsPath}`);
    console.log(`[runTest] workspacePath=${workspacePath}`);
    console.log(`[runTest] fakeGradleExtensionPath=${fakeGradleExtensionPath}`);

    const vscodeExecutablePath = await downloadAndUnzipVSCode('stable');
    console.log(`[runTest] vscodeExecutablePath=${vscodeExecutablePath}`);

    // Load the fake `vscjava.vscode-gradle` stub alongside our extension by
    // passing both paths to `extensionDevelopmentPath`. VS Code's
    // `--install-extension` flag only accepts a `.vsix` or a marketplace
    // ID, not an unpacked directory — pre-installing via the CLI fails
    // with "Extension not found". Side-loading via
    // `extensionDevelopmentPath` works for both extensions and is the
    // standard pattern for satisfying an `extensionDependency` in tests.
    console.log(`[runTest] launching tests…`);
    await runTests({
        vscodeExecutablePath,
        extensionDevelopmentPath: [extensionDevelopmentPath, fakeGradleExtensionPath],
        extensionTestsPath,
        // CI-friendly launch args:
        //  - `--disable-workspace-trust` skips the trust modal that would
        //    otherwise block activation on the fixture workspace.
        //  - `--no-sandbox` is required when running Electron under a
        //    headless Linux CI without privileged kernel features (the
        //    default chromium sandbox needs SUID helpers we don't ship).
        //  - `--disable-gpu` avoids GL fallback noise on xvfb.
        //  - `--disable-updates` keeps the host from spawning the update
        //    check during a 30s test window.
        launchArgs: [
            workspacePath,
            '--disable-workspace-trust',
            '--no-sandbox',
            '--disable-gpu',
            '--disable-updates',
        ],
        extensionTestsEnv: {
            COMPOSE_PREVIEW_TEST_MODE: '1',
        },
    });
    console.log(`[runTest] tests complete`);
}

main().catch(err => {
    console.error('Failed to run tests:', err);
    process.exit(1);
});
