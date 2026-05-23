import type { spawn } from "child_process";
import {
    ContinuousCompileWorker,
    ContinuousCompileWorkerLogger,
} from "./continuousCompileWorker";
import { GradleService, ModuleInfo } from "../gradleService";

/**
 * Owns the per-module [ContinuousCompileWorker] processes spawned under
 * the `composePreview.daemon.continuousCompile` opt-in flag.
 *
 * Activation lifecycle:
 *   - The extension instantiates one manager when the flag is enabled.
 *   - `ensureWorker(module)` is called after the daemon has warmed for
 *     that module (the same seam `daemonBootstrappedModules` uses) — by
 *     then we know the daemon-launch descriptor is valid, so the same
 *     classpath assumptions hold for the long-running gradle process.
 *   - `disposeAll()` runs in `deactivate()` so the subprocess gets a
 *     chance to shut down cleanly via EOF-to-stdin before VS Code
 *     reclaims the window.
 *
 * Workers are registered on the [GradleService] via
 * [GradleService.setContinuousCompileWorker]; `compileOnly()` looks them
 * up there at save time. The manager keeps the lookup map authoritative
 * so a failed startup (subprocess exits immediately) cleanly unregisters
 * the worker and the next save falls back to the one-shot Gradle path.
 *
 * Crash handling: once a worker's subprocess emits `exit`, the manager
 * drops it from [workers] and the GradleService registration. A later
 * [ensureWorker] call for the same module will spawn a fresh worker —
 * without this, a Gradle-daemon crash or immediate task failure would
 * leave the entry stuck in the map and the early-return in `ensureWorker`
 * would silently degrade `compileOnly()` to the one-shot fallback forever.
 */
export class ContinuousCompileManager {
    private readonly workers = new Map<string, ContinuousCompileWorker>();

    constructor(
        private readonly workspaceRoot: string,
        private readonly gradleService: GradleService,
        private readonly logger: ContinuousCompileWorkerLogger,
        private readonly extraArgs: ReadonlyArray<string> = [],
        /** Test seam — defaults to node's `child_process.spawn`. */
        private readonly spawnFn?: typeof spawn,
    ) {}

    /**
     * Idempotent — a second call for the same module while a worker is
     * still live is a no-op. If the previous worker's subprocess exited
     * (Gradle daemon crash, immediate task failure, etc.) the stale entry
     * has already been cleared by the `exit` listener, so this call
     * spawns a replacement instead of early-returning.
     */
    ensureWorker(module: ModuleInfo): void {
        const key = module.modulePath;
        if (this.workers.has(key)) {
            return;
        }
        const worker = new ContinuousCompileWorker({
            workspaceRoot: this.workspaceRoot,
            taskPath: `${key}:composePreviewCompile`,
            extraArgs: this.extraArgs,
            logger: this.logger,
            spawnFn: this.spawnFn,
        });
        try {
            worker.start();
        } catch (err) {
            this.logger.appendLine(
                `[continuous] failed to start worker for ${key}: ${(err as Error).message}`,
            );
            return;
        }
        this.workers.set(key, worker);
        this.gradleService.setContinuousCompileWorker(key, worker);
        // Clear the slot on subprocess exit so a subsequent `ensureWorker`
        // call can spawn a fresh subprocess instead of returning the
        // already-dead one. `disposeWorker` removes the entry before
        // calling `stop()`, so the guard against double-clearing keeps
        // the orderly-shutdown path a no-op here.
        worker.onceExit(() => {
            if (this.workers.get(key) === worker) {
                this.workers.delete(key);
                this.gradleService.setContinuousCompileWorker(key, null);
            }
        });
    }

    async disposeWorker(modulePath: string): Promise<void> {
        const worker = this.workers.get(modulePath);
        if (!worker) {
            return;
        }
        this.workers.delete(modulePath);
        this.gradleService.setContinuousCompileWorker(modulePath, null);
        await worker.stop();
    }

    async disposeAll(): Promise<void> {
        const keys = [...this.workers.keys()];
        await Promise.all(keys.map((k) => this.disposeWorker(k)));
    }

    /** Module paths with an active worker. Test/diagnostic surface. */
    activeModules(): readonly string[] {
        return [...this.workers.keys()];
    }
}
