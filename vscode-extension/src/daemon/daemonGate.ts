import * as vscode from 'vscode';
import { GradleService } from '../gradleService';
import { LogFilter } from '../logFilter';
import { DaemonClient, DaemonClientEvents, DaemonClientLogger } from './daemonClient';
import { readLaunchDescriptor, spawnDaemon, SpawnedDaemon } from './daemonProcess';
import { DaemonLaunchDescriptor } from './daemonProtocol';

const SETTING_ENABLED = 'daemon.enabled';
const LEGACY_SETTING_ENABLED = 'experimental.daemon.enabled';

/**
 * Source-of-truth for "is the daemon path live for this user/workspace?"
 *
 * Controlled by the `composePreview.daemon.enabled` setting (true by default).
 * When enabled, daemon startup/render failures are surfaced to the user rather
 * than silently falling back to `renderPreviews`. Users can explicitly disable
 * the daemon setting to use the Gradle path temporarily.
 *
 * One daemon per Gradle module (per `:samples:android`, etc.). Modules are
 * spawned lazily on first use and shut down on extension dispose.
 */
export class DaemonGate {
    private readonly daemons = new Map<string, ManagedDaemon>();
    private readonly spawns = new Map<string, Promise<DaemonClient>>();
    private disposed = false;
    private warnedUserDisabled = false;
    private readonly warnedBuildDisabled = new Set<string>();

    constructor(
        private readonly workspaceRoot: string,
        private readonly clientVersion: string,
        private readonly logger: DaemonClientLogger,
        private readonly logFilter: LogFilter = new LogFilter(),
    ) {}

    /** Reads the user setting freshly each time so toggles don't need a reload. */
    isEnabled(): boolean {
        const config = vscode.workspace.getConfiguration('composePreview');
        const current = config.inspect<boolean>(SETTING_ENABLED);
        const legacy = config.inspect<boolean>(LEGACY_SETTING_ENABLED);
        const value =
            current?.workspaceFolderValue ??
            current?.workspaceValue ??
            current?.globalValue ??
            legacy?.workspaceFolderValue ??
            legacy?.workspaceValue ??
            legacy?.globalValue ??
            true;
        if (!value && !this.warnedUserDisabled) {
            this.warnedUserDisabled = true;
            this.logger.appendLine(
                '[daemon] WARNING: composePreview.daemon.enabled is false; using the Gradle render path. ' +
                'The daemon will become required by the VS Code extension in a future release.',
            );
        }
        return value;
    }

    /**
     * Returns a healthy daemon for [moduleId], spawning one if needed. Returns null when the daemon
     * path has been explicitly disabled by user or build config. Throws when the daemon is enabled
     * but unavailable, so callers can surface the failure instead of silently rendering via Gradle.
     * Reasons null is returned:
     *   - Setting disabled.
     *   - Descriptor's `enabled: false` (user didn't opt in at the build level).
     */
    async getOrSpawn(
        moduleId: string,
        events: DaemonClientEvents,
    ): Promise<DaemonClient | null> {
        if (this.disposed || !this.isEnabled()) { return null; }

        const existing = this.daemons.get(moduleId);
        if (existing && !existing.client.isClosed()) { return existing.client; }
        if (existing && existing.client.isClosed()) {
            this.daemons.delete(moduleId);
        }
        const inFlight = this.spawns.get(moduleId);
        if (inFlight) { return inFlight; }

        const descriptor = readLaunchDescriptor(this.workspaceRoot, moduleId, this.logger);
        if (!descriptor) {
            const message =
                `[daemon] no launch descriptor for ${moduleId}; ` +
                `run :${moduleId.replace(/\//g, ':')}:composePreviewDaemonStart first`;
            this.logger.appendLine(message);
            throw new Error(message);
        }
        if (!descriptor.enabled) {
            if (!this.warnedBuildDisabled.has(moduleId)) {
                this.warnedBuildDisabled.add(moduleId);
                this.logger.appendLine(
                    `[daemon] WARNING: descriptor for ${moduleId} has enabled=false; ` +
                    'using the Gradle render path for now. The daemon will become required by ' +
                    'the VS Code extension in a future release. Configure composePreview { daemon { enabled = true } }.',
                );
            }
            return null;
        }

        try {
            const spawn = this.spawn(moduleId, descriptor, events)
                .finally(() => this.spawns.delete(moduleId));
            this.spawns.set(moduleId, spawn);
            return await spawn;
        } catch (err) {
            const message = `[daemon] spawn failed for ${moduleId}: ${(err as Error).message}`;
            this.logger.appendLine(message);
            throw new Error(message);
        }
    }

    isBuildDisabled(moduleId: string): boolean {
        const descriptor = readLaunchDescriptor(this.workspaceRoot, moduleId, this.logger);
        if (descriptor?.enabled === false) {
            if (!this.warnedBuildDisabled.has(moduleId)) {
                this.warnedBuildDisabled.add(moduleId);
                this.logger.appendLine(
                    `[daemon] WARNING: descriptor for ${moduleId} has enabled=false; ` +
                    'using the Gradle render path for now. The daemon will become required by ' +
                    'the VS Code extension in a future release. Configure composePreview { daemon { enabled = true } }.',
                );
            }
            return true;
        }
        return false;
    }

    private async spawn(
        moduleId: string,
        descriptor: DaemonLaunchDescriptor,
        events: DaemonClientEvents,
    ): Promise<DaemonClient> {
        const composed = composeEvents(events, () => {
            // On channel close drop the entry so the next caller spawns a
            // fresh JVM. Avoid awaiting `exited` here — events fires as
            // soon as the stream ends, exit code may lag by a tick.
            this.daemons.delete(moduleId);
        });
        const spawned = await spawnDaemon({
            workspaceRoot: this.workspaceRoot,
            descriptor,
            clientVersion: this.clientVersion,
            events: composed,
            logger: this.logger,
            logFilter: this.logFilter,
        });
        this.daemons.set(moduleId, { spawned, client: spawned.client });
        const readyLine =
            `[daemon] ready for ${moduleId} ` +
            `(daemonVersion=${spawned.initializeResult.daemonVersion}, ` +
            `pid=${spawned.initializeResult.pid}, ` +
            `previews=${spawned.initializeResult.manifest.previewCount})`;
        if (this.logFilter.shouldEmitInformational(readyLine)) {
            this.logger.appendLine(readyLine);
        }
        return spawned.client;
    }

    /**
     * Ensures the consumer's `daemon-launch.json` exists by running the
     * Gradle bootstrap task once per module. Cheap and cacheable. Propagates failures so callers
     * can surface daemon-enabled bootstrap errors instead of falling back to Gradle.
     */
    async bootstrap(gradleService: GradleService, moduleId: string): Promise<void> {
        if (!this.isEnabled()) { return; }
        await gradleService.runDaemonBootstrap(moduleId);
    }

    /** True iff a healthy daemon is already up for this module — warm path
     *  short-circuit. */
    isDaemonReady(moduleId: string): boolean {
        const existing = this.daemons.get(moduleId);
        return existing != null && !existing.client.isClosed();
    }

    /**
     * v2 interactive-mode support flag from the daemon's `initialize` response
     * (INTERACTIVE.md § 9 / `InitializeResult.capabilities.interactive`).
     * `true` means clicks dispatched via `interactive/input` actually mutate
     * composition state (desktop with v2 wiring); `false` means the daemon
     * accepts `interactive/start` but inputs trigger stateless re-renders (v1
     * fallback — Robolectric/Android, or any host that doesn't override
     * `acquireInteractiveSession`). Returns `false` when no daemon is up for
     * the module — the caller can't drive interactive mode either way.
     *
     * Pre-#425 daemons omit the capability bit entirely; we treat absent as
     * `false` (the safer default) so pre-v2 daemons surface the unsupported
     * hint instead of silently leaving the user wondering why clicks don't
     * mutate state.
     */
    isInteractiveSupported(moduleId: string): boolean {
        const existing = this.daemons.get(moduleId);
        if (existing == null || existing.client.isClosed()) { return false; }
        return existing.spawned.initializeResult.capabilities.interactive === true;
    }

    async dispose(): Promise<void> {
        this.disposed = true;
        const entries = [...this.daemons.values()];
        this.daemons.clear();
        await Promise.all(entries.map(async (entry) => {
            try {
                if (!entry.client.isClosed()) {
                    await Promise.race([
                        entry.client.shutdown(),
                        new Promise((resolve) => setTimeout(resolve, 2000)),
                    ]);
                    entry.client.exit();
                }
            } catch {
                /* best-effort shutdown */
            }
            try { entry.spawned.process.kill('SIGTERM'); } catch { /* ignore */ }
        }));
    }

    /**
     * Cleanly shuts down every running daemon JVM and clears the registry, so
     * the next `getOrSpawn` reads `daemon-launch.json` afresh and spawns a
     * brand-new JVM. Returns the moduleIds that were running so the caller can
     * report what was restarted.
     *
     * The same protocol as [dispose] (shutdown → exit → SIGTERM) but the gate
     * stays usable afterwards. This is the manual escape hatch the user
     * triggers via `composePreview.restartDaemon` after rebuilding the daemon
     * JAR — without it the running JVM keeps serving renders from the JAR it
     * was spawned with even after the on-disk JAR has been replaced.
     */
    async restartAll(): Promise<string[]> {
        if (this.disposed) { return []; }
        const entries = [...this.daemons.entries()];
        this.daemons.clear();
        await Promise.all(entries.map(async ([_moduleId, entry]) => {
            try {
                if (!entry.client.isClosed()) {
                    await Promise.race([
                        entry.client.shutdown(),
                        new Promise((resolve) => setTimeout(resolve, 2000)),
                    ]);
                    entry.client.exit();
                }
            } catch {
                /* best-effort shutdown */
            }
            try { entry.spawned.process.kill('SIGTERM'); } catch { /* ignore */ }
        }));
        return entries.map(([moduleId]) => moduleId);
    }
}

interface ManagedDaemon {
    spawned: SpawnedDaemon;
    client: DaemonClient;
}

/**
 * Wraps the caller's events so we observe channel close ourselves (to
 * evict from the registry) without losing the caller's handler.
 */
function composeEvents(
    base: DaemonClientEvents,
    onClose: (err?: Error) => void,
): DaemonClientEvents {
    return {
        ...base,
        onChannelClosed: (err) => {
            try { base.onChannelClosed?.(err); } finally { onClose(err); }
        },
    };
}
