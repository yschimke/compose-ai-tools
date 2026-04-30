import * as path from 'path';
import * as fs from 'fs';
import { GradleService } from '../gradleService';
import { DaemonGate } from './daemonGate';
import {
    FileChangeType,
    FileKind,
    RenderFinishedParams,
    RenderTier,
} from './daemonProtocol';

export type WarmProgress = (state: WarmState) => void;
export type WarmState = 'bootstrapping' | 'spawning' | 'ready' | 'fallback';

export interface SchedulerEvents {
    /**
     * A render finished and we resolved the on-disk PNG. Wire to the panel
     * so the user's visible card swaps in the new image with sub-second
     * latency on a hot Robolectric sandbox.
     */
    onPreviewImageReady: (
        moduleId: string,
        previewId: string,
        imageBase64: string,
        pngPath: string,
    ) => void;
    onRenderFailed: (moduleId: string, previewId: string, message: string) => void;
    /**
     * Daemon told us the classpath drifted (e.g. `libs.versions.toml`
     * bumped). The daemon will exit shortly; the scheduler stops issuing
     * renders for this module and the caller should re-run Gradle.
     */
    onClasspathDirty: (moduleId: string, detail: string) => void;
}

const HEAVY_TIER_DEFAULT: RenderTier = 'fast';
/**
 * Cards we'll pre-render ahead of the visible viewport on each scroll-ahead
 * push from the webview. Bounded so a fast scroll past 50 cards doesn't
 * stack 50 speculative renders — see PREDICTIVE.md § 4 (default 4).
 */
const SPECULATIVE_BUDGET = 4;

/**
 * Drives the daemon end of the editor loop:
 *   - File saves → `fileChanged` notifications (Tier-2 trigger).
 *   - Active editor change → `setFocus` for that file's previews.
 *   - Webview reports visible card IDs and the next-page IDs it predicts
 *     will scroll into view → `setVisible` plus speculative `renderNow`
 *     calls bounded by [SPECULATIVE_BUDGET].
 *   - Daemon `renderFinished` → load PNG, base64, hand to caller.
 *
 * The scheduler does not own the panel state; it speaks only in protocol
 * primitives + image bytes. Wiring sits above in `extension.ts`.
 *
 * **History is intentionally out of scope here.** The user-facing history
 * timeline ships separately and will key off `docs/daemon/HISTORY.md` once
 * that design lands. Wire-up point: the `onPreviewImageReady` callback is
 * the right hook for an additional "snapshot-on-render" sink — it already
 * has the moduleId, previewId, and pngPath that a history archiver needs.
 */
export class DaemonScheduler {
    /** Last-known-visible IDs per module — let us dedup setVisible spam. */
    private lastVisible = new Map<string, string[]>();
    /** Last `setFocus` per module so editor refocus on the same file is a no-op. */
    private lastFocus = new Map<string, string[]>();
    /** Cards we've already speculatively requested so scrolling back over
     *  them doesn't re-queue identical work. Keyed by `${moduleId}::${id}`. */
    private speculated = new Set<string>();

    constructor(
        private readonly gate: DaemonGate,
        private readonly events: SchedulerEvents,
        private readonly logger: { appendLine(s: string): void } = { appendLine() {} },
    ) {}

    /**
     * Bring up the daemon for a module if necessary and ensure subsequent
     * notifications go to the right client. Returns true iff a client is
     * available (caller can use the daemon path); false means "fall back
     * to Gradle for this module."
     */
    async ensureModule(moduleId: string): Promise<boolean> {
        const client = await this.gate.getOrSpawn(moduleId, this.daemonEvents(moduleId));
        return client !== null;
    }

    /**
     * Pre-warms a module: runs the Gradle bootstrap task (writes
     * `daemon-launch.json`) and spawns the JVM if not already up. Intended
     * to be called when the user navigates to a Kotlin file in a
     * daemon-enabled module, so the daemon is alive by the time they hit
     * save — the first save then collapses to "kotlinc + render" instead
     * of "Gradle bootstrap + JVM spawn + sandbox init + render".
     *
     * `progress` fires through `'bootstrapping'` while the Gradle task
     * runs, `'spawning'` while the JVM comes up, and `'ready'` once
     * `initialize` is acknowledged. `'fallback'` fires on any failure —
     * the next save will run Gradle as today. No-op when the gate is
     * disabled.
     */
    async warmModule(
        gradleService: GradleService,
        moduleId: string,
        progress?: WarmProgress,
    ): Promise<boolean> {
        if (!this.gate.isEnabled()) { return false; }
        if (this.gate.isDaemonReady(moduleId)) {
            progress?.('ready');
            return true;
        }
        try {
            progress?.('bootstrapping');
            await gradleService.runDaemonBootstrap(moduleId);
        } catch (err) {
            this.logger.appendLine(
                `[daemon] bootstrap task failed for ${moduleId}: ${(err as Error).message}`,
            );
            progress?.('fallback');
            return false;
        }
        progress?.('spawning');
        const ok = await this.ensureModule(moduleId);
        progress?.(ok ? 'ready' : 'fallback');
        return ok;
    }

    /**
     * Called from the file-watcher / save events. The daemon classifies the
     * file internally per PROTOCOL.md § 4; we send a hint based on the path.
     */
    async fileChanged(
        moduleId: string,
        absPath: string,
        changeType: FileChangeType = 'modified',
    ): Promise<void> {
        const client = await this.gate.getOrSpawn(moduleId, this.daemonEvents(moduleId));
        if (!client) { return; }
        client.fileChanged({
            path: absPath,
            kind: classifyKind(absPath),
            changeType,
        });
    }

    /**
     * The user moved focus to (or saved a file scoped to) a set of preview
     * IDs. These are rendered first when the queue drains.
     */
    async setFocus(moduleId: string, previewIds: string[]): Promise<void> {
        if (sameSet(this.lastFocus.get(moduleId), previewIds)) { return; }
        this.lastFocus.set(moduleId, [...previewIds]);
        const client = await this.gate.getOrSpawn(moduleId, this.daemonEvents(moduleId));
        if (!client) { return; }
        client.setFocus({ ids: previewIds });
    }

    /**
     * Cards currently rendered in the panel (true geometric viewport, not
     * just the file scope). The daemon uses this to filter Tier-3 stale sets
     * — see DESIGN.md § 8 Tier 4. `predicted` are the IDs the webview
     * believes the user is about to scroll into; speculative renders kick
     * off for them up to [SPECULATIVE_BUDGET].
     */
    async setVisible(
        moduleId: string,
        visible: string[],
        predicted: string[] = [],
    ): Promise<void> {
        if (sameSet(this.lastVisible.get(moduleId), visible) && predicted.length === 0) {
            return;
        }
        this.lastVisible.set(moduleId, [...visible]);
        const client = await this.gate.getOrSpawn(moduleId, this.daemonEvents(moduleId));
        if (!client) { return; }
        client.setVisible({ ids: visible });

        if (predicted.length === 0) { return; }
        // Bound the speculative request so a flick-scroll past 50 cards
        // doesn't queue 50 renders. Visible IDs trump predicted ones —
        // they're already in the daemon's reactive queue.
        const visibleSet = new Set(visible);
        const fresh = predicted
            .filter(id => !visibleSet.has(id))
            .filter(id => !this.speculated.has(specKey(moduleId, id)))
            .slice(0, SPECULATIVE_BUDGET);
        if (fresh.length === 0) { return; }
        for (const id of fresh) { this.speculated.add(specKey(moduleId, id)); }
        try {
            await client.renderNow({
                previews: fresh,
                tier: HEAVY_TIER_DEFAULT,
                reason: 'scroll-ahead',
            });
        } catch (err) {
            this.logger.appendLine(
                `[daemon] scroll-ahead renderNow failed for ${moduleId}: ${(err as Error).message}`,
            );
        }
    }

    /**
     * Explicit user-triggered render (Refresh button / first scope-in).
     * Returns when the daemon has accepted the work; the actual PNG arrives
     * via `onPreviewImageReady`.
     */
    async renderNow(
        moduleId: string,
        previewIds: string[],
        tier: RenderTier = 'fast',
        reason?: string,
    ): Promise<boolean> {
        const client = await this.gate.getOrSpawn(moduleId, this.daemonEvents(moduleId));
        if (!client) { return false; }
        try {
            const result = await client.renderNow({
                previews: previewIds,
                tier,
                reason,
            });
            for (const r of result.rejected) {
                this.logger.appendLine(`[daemon] renderNow rejected ${r.id}: ${r.reason}`);
            }
            return true;
        } catch (err) {
            this.logger.appendLine(
                `[daemon] renderNow failed for ${moduleId}: ${(err as Error).message}`,
            );
            return false;
        }
    }

    private daemonEvents(moduleId: string) {
        return {
            onRenderFinished: (params: RenderFinishedParams) => {
                this.handleRenderFinished(moduleId, params);
            },
            onRenderFailed: (params: { id: string; error: { message: string } }) => {
                this.events.onRenderFailed(moduleId, params.id, params.error.message);
            },
            onClasspathDirty: (params: { detail: string }) => {
                // Drop the speculative cache for this module so a re-spawned
                // daemon doesn't think we already pre-warmed those IDs.
                for (const k of [...this.speculated]) {
                    if (k.startsWith(`${moduleId}::`)) { this.speculated.delete(k); }
                }
                this.events.onClasspathDirty(moduleId, params.detail);
            },
            onChannelClosed: () => {
                // Daemon died; clear caches so the next call re-issues them
                // against a fresh JVM.
                this.lastVisible.delete(moduleId);
                this.lastFocus.delete(moduleId);
                for (const k of [...this.speculated]) {
                    if (k.startsWith(`${moduleId}::`)) { this.speculated.delete(k); }
                }
            },
        };
    }

    private handleRenderFinished(moduleId: string, params: RenderFinishedParams): void {
        try {
            // Speculative entries graduate to "real" once they actually render —
            // drop them from the dedup set so a subsequent fileChanged for the
            // same preview can re-render via the reactive path.
            this.speculated.delete(specKey(moduleId, params.id));

            const buf = fs.readFileSync(params.pngPath);
            this.events.onPreviewImageReady(
                moduleId,
                params.id,
                buf.toString('base64'),
                params.pngPath,
            );
        } catch (err) {
            this.logger.appendLine(
                `[daemon] failed to read ${params.pngPath} for ${params.id}: ${(err as Error).message}`,
            );
            this.events.onRenderFailed(
                moduleId,
                params.id,
                'render finished but PNG was unreadable',
            );
        }
    }
}

function classifyKind(absPath: string): FileKind {
    const lower = absPath.toLowerCase();
    if (
        lower.endsWith('libs.versions.toml')
        || lower.endsWith('.gradle.kts')
        || lower.endsWith('gradle.properties')
        || lower.endsWith('local.properties')
        || lower.endsWith('settings.gradle.kts')
    ) {
        return 'classpath';
    }
    if (lower.includes(`${path.sep}res${path.sep}`) || lower.includes('/res/')) {
        return 'resource';
    }
    return 'source';
}

function sameSet(a: string[] | undefined, b: string[]): boolean {
    if (!a || a.length !== b.length) { return false; }
    const set = new Set(a);
    return b.every(id => set.has(id));
}

function specKey(moduleId: string, previewId: string): string {
    return `${moduleId}::${previewId}`;
}
