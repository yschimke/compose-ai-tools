// `vscode` is intentionally NOT statically imported here. This module is
// pulled in by Mocha unit tests under `out/test/daemon/`, which run in plain
// Node where the `vscode` API isn't resolvable (matches the convention used
// by `gradleService.ts` and other test-reachable source files). The two
// fallback functions in the constructor below lazily `require('vscode')`
// only when they actually need it — i.e., when running inside the extension
// host with no test seam supplied.
import { GradleService } from '../gradleService';
import { DaemonClient, Renderer } from './daemonClient';
import {
    DaemonProcess,
    DaemonProcessError,
    RebootstrapFn,
} from './daemonProcess';

// ---------------------------------------------------------------------------
// Preview daemon — router shim.
//
// One call site in extension.ts where the existing
// `gradleService.renderPreviews(...)` invocation lives. The gate decides:
//
//   if (isEnabled(config) && await isHealthy())  → daemonClient
//   else                                          → gradleService
//
// And, on classpathDirty / crash / spawn failure, logs + notifies + auto-
// falls back to gradleService for the rest of the session. The shim's whole
// job is to keep extension.ts ignorant of which path is in use.
//
// Both `DaemonClient` and `GradleService` implement {@link Renderer}; the
// gate exposes itself as one too so the call site is `gate.renderPreviews(...)`.
// ---------------------------------------------------------------------------

/**
 * VS Code setting key for the master switch. Mirrors the Gradle DSL flag
 * (`composePreview.experimental.daemon.enabled` in build.gradle.kts) but
 * read from VS Code's own configuration store so users can flip the
 * extension-side path without touching their build script. CONFIG.md
 * documents that VS Code MUST refuse to spawn when the descriptor says
 * `enabled = false`; this is the inverse — VS Code ALSO refuses to spawn
 * when the user opted out from the editor side.
 */
export const DAEMON_SETTING_KEY = 'composePreview.experimental.daemon.enabled';

/**
 * Per-session record of modules that fell back to gradleService. Once a
 * module's daemon spawns/crashes/refuses-to-classpathDirty, we don't keep
 * trying — the user gets a one-shot notification and the gate routes
 * subsequent calls through gradleService until VS Code reloads.
 */
type ModuleId = string;

export interface DaemonGateOptions {
    workspaceRoot: string;
    /** The fall-back renderer. Used when the daemon path is disabled or unhealthy. */
    gradleService: GradleService;
    /**
     * Re-runs `composePreviewDaemonStart` for a module. The router (this
     * gate) wires this through to GradleService.runTask, but as a callback
     * so unit tests can inject a no-op.
     */
    rebootstrap: RebootstrapFn;
    /** Extension semver, forwarded into every DaemonClient.initialize. */
    clientVersion: string;
    logger?: { appendLine(msg: string): void; append(msg: string): void };
    /**
     * Test seam — defaults to vscode.workspace.getConfiguration. Lets unit
     * tests bypass the vscode runtime.
     */
    readSetting?: () => boolean;
    /**
     * Test seam — defaults to vscode.window.showWarningMessage. Lets unit
     * tests count user-facing notifications.
     */
    notify?: (msg: string) => void;
}

interface DaemonSlot {
    process: DaemonProcess;
    client: DaemonClient;
    /** Set true once spawn + initialize have succeeded. */
    ready: boolean;
}

/**
 * Owns the per-module daemons. Caller-facing interface is {@link Renderer}
 * so the extension.ts call site doesn't change shape based on which
 * path is in use.
 */
export class DaemonGate implements Renderer {

    private readonly options: Required<Omit<DaemonGateOptions,
        'logger' | 'readSetting' | 'notify'>>
        & {
            logger: { appendLine(msg: string): void; append(msg: string): void };
            readSetting: () => boolean;
            notify: (msg: string) => void;
        };

    private readonly daemons = new Map<ModuleId, DaemonSlot>();
    private readonly disabledModules = new Set<ModuleId>();
    private notifiedFallback = false;

    constructor(options: DaemonGateOptions) {
        this.options = {
            workspaceRoot: options.workspaceRoot,
            gradleService: options.gradleService,
            rebootstrap: options.rebootstrap,
            clientVersion: options.clientVersion,
            logger: options.logger ?? { appendLine() { /* */ }, append() { /* */ } },
            readSetting: options.readSetting ?? (() => {
                // Lazy require — see top-of-file note on why `vscode` is not statically imported.
                // eslint-disable-next-line @typescript-eslint/no-require-imports
                const vscode = require('vscode') as typeof import('vscode');
                return vscode.workspace.getConfiguration().get<boolean>(DAEMON_SETTING_KEY) ?? false;
            }),
            notify: options.notify ?? ((msg) => {
                // eslint-disable-next-line @typescript-eslint/no-require-imports
                const vscode = require('vscode') as typeof import('vscode');
                void vscode.window.showWarningMessage(msg);
            }),
        };
    }

    /** True iff the user has opted into the daemon path via VS Code settings. */
    isEnabled(): boolean {
        return this.options.readSetting();
    }

    /**
     * True iff the daemon path is enabled, the per-module daemon is alive,
     * and initialize completed. Async because the first call may need to
     * spawn + initialize (lazy-start).
     */
    async isHealthy(module: ModuleId): Promise<boolean> {
        if (!this.isEnabled()) {
            return false;
        }
        if (this.disabledModules.has(module)) {
            return false;
        }
        const slot = this.daemons.get(module);
        if (slot && slot.ready && slot.client.isHealthy() && slot.process.isHealthy()) {
            return true;
        }
        // Try to spawn lazily.
        try {
            await this.ensureRunning(module);
            return true;
        } catch (e) {
            this.fallback(module, `daemon spawn failed: ${(e as Error).message}`);
            return false;
        }
    }

    private async ensureRunning(module: ModuleId): Promise<DaemonSlot> {
        const existing = this.daemons.get(module);
        if (existing && existing.ready && existing.client.isHealthy()
            && existing.process.isHealthy()) {
            return existing;
        }
        // Old slot is stale — dispose, then re-spawn.
        if (existing) {
            await existing.client.dispose().catch(() => { /* */ });
            await existing.process.dispose().catch(() => { /* */ });
            this.daemons.delete(module);
        }

        const proc = new DaemonProcess({
            workspaceRoot: this.options.workspaceRoot,
            modulePath: module,
            rebootstrap: this.options.rebootstrap,
            logger: this.options.logger,
        });

        const child = proc.start();
        const client = new DaemonClient({
            workspaceRoot: this.options.workspaceRoot,
            moduleId: module,
            moduleProjectDir: this.moduleProjectDir(module),
            clientVersion: this.options.clientVersion,
            logger: this.options.logger,
            onClasspathDirty: (params) => {
                this.options.logger.appendLine(
                    `[daemon-gate] classpathDirty for ${module}: ${params.detail}`,
                );
                this.options.notify(
                    `Compose Preview: daemon classpath changed (${params.reason}). Restarting daemon for ${module}.`,
                );
                // Restart pipeline lives on the DaemonProcess.
                proc.onNotification({
                    jsonrpc: '2.0',
                    method: 'classpathDirty',
                    params,
                });
            },
        });
        client.attachChild(child, proc);

        // If the process dies before initialize completes, fall back.
        const slot: DaemonSlot = { process: proc, client, ready: false };
        this.daemons.set(module, slot);
        client.events.on('closed', (reason) => {
            this.options.logger.appendLine(`[daemon-gate] ${module} closed: ${reason}`);
            slot.ready = false;
            // If we weren't intentionally disposing, fall back.
            if (proc.currentState === 'crashed') {
                this.fallback(module, `daemon crashed (${reason})`);
            }
        });

        await client.initialize();
        slot.ready = true;
        return slot;
    }

    /**
     * Attempt to derive an absolute project directory from a module
     * identifier. Accepts both Gradle paths (`:samples:android`) and
     * relative paths (`samples/android`).
     */
    private moduleProjectDir(module: ModuleId): string {
        const normalised = module.replace(/^:+/, '').replace(/:/g, '/');
        return `${this.options.workspaceRoot}/${normalised}`;
    }

    private fallback(module: ModuleId, reason: string): void {
        this.options.logger.appendLine(
            `[daemon-gate] falling back to gradleService for ${module}: ${reason}`,
        );
        this.disabledModules.add(module);
        const slot = this.daemons.get(module);
        if (slot) {
            void slot.client.dispose().catch(() => { /* */ });
            void slot.process.dispose().catch(() => { /* */ });
            this.daemons.delete(module);
        }
        if (!this.notifiedFallback) {
            this.notifiedFallback = true;
            this.options.notify(
                `Compose Preview: the experimental daemon failed; falling back to Gradle for the rest of this session. See "Compose Preview" output for details.`,
            );
        }
    }

    // ------------------------------------------------------------------
    // Renderer interface — the single call site in extension.ts goes
    // through these.
    // ------------------------------------------------------------------

    async renderPreviews(module: ModuleId, tier: 'fast' | 'full' = 'full'): Promise<unknown | null> {
        if (await this.isHealthy(module)) {
            const slot = this.daemons.get(module);
            if (slot && slot.ready) {
                try {
                    await slot.client.renderPreviews(module, tier);
                    // Per PROTOCOL.md § 8 we don't ship PNGs over the wire; the
                    // existing extension.ts consumer reads them via
                    // gradleService.readPreviewImage. The render notifications
                    // (renderFinished) carry the on-disk path which will match
                    // what GradleService emits, since the daemon's RenderEngine
                    // writes into the same `<module>/build/compose-previews/
                    // renders/` tree (see B1.4 hook in JsonRpcServer.kt).
                    //
                    // For C1.4 we still return the GradleService manifest so
                    // the existing webview consumer keeps working unchanged.
                    // Once Phase 2's webview wiring (C2.x) is in, this
                    // method moves to streaming the daemon's notifications
                    // directly to the panel.
                    return await this.options.gradleService.readManifest(module);
                } catch (e) {
                    this.options.logger.appendLine(
                        `[daemon-gate] renderPreviews failed: ${(e as Error).message}`,
                    );
                    if (e instanceof DaemonProcessError || (e as Error).message.includes('classpath dirty')) {
                        this.fallback(module, (e as Error).message);
                    }
                    // Fall through to gradleService on any failure.
                }
            }
        }
        return this.options.gradleService.renderPreviews(module, tier);
    }

    async discoverPreviews(module: ModuleId): Promise<unknown | null> {
        if (await this.isHealthy(module)) {
            // Discovery via the daemon is push-based; for the V1 router the
            // GradleService manifest is still the source of truth, so we just
            // read it. A future iteration consumes `discoveryUpdated`
            // notifications directly.
            return this.options.gradleService.readManifest(module);
        }
        return this.options.gradleService.discoverPreviews(module);
    }

    /** Tear down all daemons. Called from extension deactivate(). */
    async dispose(): Promise<void> {
        const all = [...this.daemons.values()];
        this.daemons.clear();
        for (const slot of all) {
            try { await slot.client.dispose(); } catch { /* */ }
            try { await slot.process.dispose(); } catch { /* */ }
        }
    }

    /** Test/debug accessor. */
    get activeModules(): ModuleId[] {
        return [...this.daemons.keys()];
    }
}
