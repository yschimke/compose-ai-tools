import * as vscode from 'vscode';
import { GradleService } from '../gradleService';
import { LogFilter } from '../logFilter';
import { DaemonClient, DaemonClientEvents, DaemonClientLogger } from './daemonClient';
import { readLaunchDescriptor, spawnDaemon, SpawnedDaemon } from './daemonProcess';

const SETTING_ENABLED = 'experimental.daemon.enabled';

/**
 * Source-of-truth for "is the daemon path live for this user/workspace?"
 *
 * Controlled by the `composePreview.experimental.daemon.enabled` setting
 * (true by default). Even when enabled, the gate falls back to
 * `gradleService.renderPreviews` whenever the daemon isn't healthy:
 * descriptor missing, descriptor disabled by the build config, JVM died,
 * classpath dirty, or RPC failed. Failures are logged but never thrown to
 * the caller — the rest of the extension is unaware whether a render came
 * from the daemon or from Gradle.
 *
 * One daemon per Gradle module (per `:samples:android`, etc.). Modules are
 * spawned lazily on first use and shut down on extension dispose.
 */
export class DaemonGate {
    private readonly daemons = new Map<string, ManagedDaemon>();
    private disposed = false;

    constructor(
        private readonly workspaceRoot: string,
        private readonly clientVersion: string,
        private readonly logger: DaemonClientLogger,
        private readonly logFilter: LogFilter = new LogFilter(),
    ) {}

    /** Reads the user setting freshly each time so toggles don't need a reload. */
    isEnabled(): boolean {
        return vscode.workspace
            .getConfiguration('composePreview')
            .get<boolean>(SETTING_ENABLED, true);
    }

    /**
     * Returns a healthy daemon for [moduleId], spawning one if needed. Returns
     * null when the daemon path isn't usable for this module — caller must
     * fall back to [GradleService]. Reasons null is returned:
     *   - Setting disabled.
     *   - `daemon-launch.json` missing (consumer hasn't run
     *     `composePreviewDaemonStart` yet, or the plugin isn't applied).
     *   - Descriptor's `enabled: false` (user didn't opt in at the build level).
     *   - Spawn failed.
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

        const descriptor = readLaunchDescriptor(this.workspaceRoot, moduleId, this.logger);
        if (!descriptor) {
            this.logger.appendLine(
                `[daemon] no launch descriptor for ${moduleId}; ` +
                `run :${moduleId.replace(/\//g, ':')}:composePreviewDaemonStart first`,
            );
            return null;
        }
        if (!descriptor.enabled) {
            this.logger.appendLine(
                `[daemon] descriptor for ${moduleId} has enabled=false; ` +
                'set composePreview.experimental.daemon.enabled = true in build.gradle.kts',
            );
            return null;
        }

        try {
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
        } catch (err) {
            this.logger.appendLine(
                `[daemon] spawn failed for ${moduleId}: ${(err as Error).message}`,
            );
            return null;
        }
    }

    /**
     * Ensures the consumer's `daemon-launch.json` exists by running the
     * Gradle bootstrap task once per module. Cheap and cacheable. Swallows
     * failures — a missing descriptor on first launch just means we silently
     * fall back to Gradle, which is the safe default.
     */
    async bootstrap(gradleService: GradleService, moduleId: string): Promise<void> {
        if (!this.isEnabled()) { return; }
        try {
            await gradleService.runDaemonBootstrap(moduleId);
        } catch (err) {
            this.logger.appendLine(
                `[daemon] bootstrap task failed for ${moduleId}: ${(err as Error).message}`,
            );
        }
    }

    /** True iff a healthy daemon is already up for this module — warm path
     *  short-circuit. */
    isDaemonReady(moduleId: string): boolean {
        const existing = this.daemons.get(moduleId);
        return existing != null && !existing.client.isClosed();
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
