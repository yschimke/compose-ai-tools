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

    /** Idempotent — second call for the same module is a no-op. */
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
