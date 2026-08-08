import * as path from "path";
import { downloadAndUnzipVSCode, runTests } from "@vscode/test-electron";

/**
 * Per-Gradle-task ceiling handed to the extension host for e2e runs, via
 * `COMPOSE_PREVIEW_GRADLE_TASK_TIMEOUT_MS` (see `gradleService.ts`).
 *
 * The production default is 5 minutes, tuned for an interactive user who
 * wants a wedged build abandoned quickly. E2E is a different workload: a
 * cold hosted runner compiles the `includeBuild` plugin and the sample
 * module before rendering anything, which routinely takes longer than that
 * — and when it does, the cap cancels the render and the suite fails with
 * no useful signal. Ten minutes clears the observed cold cost with room to
 * spare while staying well inside every suite's Mocha timeout and the
 * workflow's 60-minute per-shard cap, which remain the wedge backstops.
 *
 * Set `COMPOSE_PREVIEW_GRADLE_TASK_TIMEOUT_MS` in the environment to
 * override (e.g. to reproduce the production cap locally).
 */
const E2E_GRADLE_TASK_TIMEOUT_MS = 10 * 60_000;

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
    const extensionDevelopmentPath = path.resolve(__dirname, "../../..");
    const extensionTestsPath = path.resolve(__dirname, "./suite/index");
    // Fixtures live in `src/` (raw assets — JSON manifest + PNG/GIF + a
    // build.gradle.kts), not `out/`, so resolve relative to the source tree.
    // `__dirname` here is `out/test/electron/`, hence the `../../../src/...`
    // walk-back to land in `src/test/electron/fixtures/`.
    const fixturesRoot = path.resolve(
        __dirname,
        "../../../src/test/electron/fixtures",
    );
    // E2E mode (COMPOSE_PREVIEW_E2E=1, set by `npm run test:e2e` and the
    // daily/manual GitHub Actions workflow) opens the *repo root* as the
    // workspace. That gives the test a working `:samples:cmp` module wired
    // into the local plugin via `includeBuild("gradle-plugin")`, without
    // duplicating fixture build files. The fast suite keeps its tiny
    // pre-baked workspace.
    //
    // External-consumer e2e (COMPOSE_PREVIEW_E2E_EXTERNAL=1, set by `npm
    // run test:e2e-external` and the matching CI workflow) opens whatever
    // path COMPOSE_PREVIEW_E2E_WORKSPACE points at — typically a
    // setup-external-e2e.sh checkout of joreilly/Confetti. Exercises the
    // *published* plugin coordinate path against a real third-party
    // build, complementing the in-repo `includeBuild` suite.
    const e2eMode = process.env.COMPOSE_PREVIEW_E2E === "1";
    const e2eExternal = process.env.COMPOSE_PREVIEW_E2E_EXTERNAL === "1";
    let workspacePath: string;
    if (e2eExternal) {
        const external = process.env.COMPOSE_PREVIEW_E2E_WORKSPACE;
        if (!external) {
            throw new Error(
                "COMPOSE_PREVIEW_E2E_EXTERNAL=1 requires COMPOSE_PREVIEW_E2E_WORKSPACE " +
                    "(absolute path to the prepared external-consumer Gradle workspace; " +
                    "see vscode-extension/scripts/setup-external-e2e.sh)",
            );
        }
        workspacePath = external;
    } else if (e2eMode) {
        workspacePath = path.resolve(extensionDevelopmentPath, "..");
    } else {
        workspacePath = path.join(fixturesRoot, "workspace");
    }
    const fakeGradleExtensionPath = path.join(
        fixturesRoot,
        "fake-vscode-gradle",
    );
    // Contributes the `kotlin` languageId so .kt files in the e2e workspaces
    // resolve to it. The compose-preview extension's
    // `onDidChangeActiveTextEditor` preload path bails unless
    // `editor.document.languageId === "kotlin"`, and the test electron host
    // doesn't bundle a real Kotlin extension. Without this, the cached-preload
    // suite trips on `setTextDocumentLanguage(doc, "kotlin")` with
    // `Error: Unknown language id: kotlin`.
    const fakeKotlinLanguagePath = path.join(
        fixturesRoot,
        "fake-kotlin-language",
    );

    console.log(
        `[runTest] extensionDevelopmentPath=${extensionDevelopmentPath}`,
    );
    console.log(`[runTest] extensionTestsPath=${extensionTestsPath}`);
    console.log(`[runTest] workspacePath=${workspacePath}`);
    console.log(`[runTest] fakeGradleExtensionPath=${fakeGradleExtensionPath}`);
    console.log(`[runTest] fakeKotlinLanguagePath=${fakeKotlinLanguagePath}`);

    // Allow pointing at a pre-provisioned VS Code / VSCodium binary instead of
    // downloading from update.code.visualstudio.com. Cloud sandboxes whose
    // egress allowlist blocks the Microsoft download hosts (403) can set
    // VSCODE_TEST_EXECUTABLE to a GitHub-hosted VSCodium extracted locally —
    // @vscode/test-electron drives it the same way, and our extension plus the
    // fake gradle/kotlin stubs are side-loaded, so the marketplace is never hit.
    const preProvisioned = process.env.VSCODE_TEST_EXECUTABLE;
    const vscodeExecutablePath = preProvisioned
        ? preProvisioned
        : await downloadAndUnzipVSCode("stable");
    console.log(
        `[runTest] vscodeExecutablePath=${vscodeExecutablePath}` +
            (preProvisioned ? " (from VSCODE_TEST_EXECUTABLE)" : ""),
    );

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
        extensionDevelopmentPath: [
            extensionDevelopmentPath,
            fakeGradleExtensionPath,
            fakeKotlinLanguagePath,
        ],
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
            "--disable-workspace-trust",
            "--no-sandbox",
            "--disable-gpu",
            "--disable-updates",
        ],
        extensionTestsEnv: {
            ELECTRON_RUN_AS_NODE: undefined,
            COMPOSE_PREVIEW_TEST_MODE: "1",
            ...(e2eMode ? { COMPOSE_PREVIEW_E2E: "1" } : {}),
            // Both e2e modes drive real cold Gradle builds, so both need the
            // longer per-task ceiling. The fast suite keeps the production
            // default — its Gradle API is a stub and never blocks.
            ...(e2eMode || e2eExternal
                ? {
                      COMPOSE_PREVIEW_GRADLE_TASK_TIMEOUT_MS:
                          process.env.COMPOSE_PREVIEW_GRADLE_TASK_TIMEOUT_MS ??
                          String(E2E_GRADLE_TASK_TIMEOUT_MS),
                  }
                : {}),
            ...(process.env.COMPOSE_PREVIEW_E2E_FILES
                ? {
                      COMPOSE_PREVIEW_E2E_FILES:
                          process.env.COMPOSE_PREVIEW_E2E_FILES,
                  }
                : {}),
            ...(process.env.COMPOSE_PREVIEW_E2E_GREP
                ? {
                      COMPOSE_PREVIEW_E2E_GREP:
                          process.env.COMPOSE_PREVIEW_E2E_GREP,
                  }
                : {}),
            // Forward the external-consumer flag (and the workspace
            // path, for diagnostic logs) into the extension host so the
            // gated `describeExternal(...)` actually runs. The
            // `runTest.ts` parent only inspects the bare flag, but the
            // host needs the path so e2eExternal.test.ts can sanity-check
            // the workspace layout in `before()`.
            ...(e2eExternal
                ? {
                      COMPOSE_PREVIEW_E2E_EXTERNAL: "1",
                      COMPOSE_PREVIEW_E2E_WORKSPACE: workspacePath,
                  }
                : {}),
            // Pulled through so the renderer-resolving init script (see
            // initScript.ts → `useMavenLocal`) seeds mavenLocal into the
            // plugin classpath. Tests don't toggle this themselves —
            // the workflow + `npm run test:e2e-external` set it from
            // the outside, and forwarding it here keeps the contract
            // explicit instead of relying on environment inheritance
            // semantics of @vscode/test-electron.
            ...(process.env.COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL
                ? {
                      COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL:
                          process.env.COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL,
                  }
                : {}),
        },
    });
    console.log(`[runTest] tests complete`);
}

main().catch((err) => {
    console.error("Failed to run tests:", err);
    process.exit(1);
});
