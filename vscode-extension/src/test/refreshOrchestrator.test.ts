import * as assert from "assert";
import {
    PhaseChange,
    RefreshOrchestrator,
    RefreshOrchestratorDeps,
    RefreshSubPhase,
} from "../refreshOrchestrator";

/** Deferred promise — lets a test pause a dep mid-way and resolve it on demand,
 *  so we can assert orchestrator state while the in-flight work is suspended. */
function deferred<T>(): {
    promise: Promise<T>;
    resolve: (value: T) => void;
} {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((r) => {
        resolve = r;
    });
    return { promise, resolve };
}

interface ScriptedDeps extends RefreshOrchestratorDeps {
    aborts: number;
    /** Whichever refresh is currently in flight — its onPhase callback. The test
     *  uses this to inject discover/reconcile/render sub-phase events. */
    currentOnPhase: ((sub: RefreshSubPhase) => void) | null;
    /** Resolvers keyed by step+filePath so tests can release them in any order. */
    preloadResolvers: Map<string, (painted: boolean) => void>;
    refreshResolvers: Map<string, () => void>;
    warmResolvers: Map<string, () => void>;
}

function scriptedDeps(): ScriptedDeps {
    const aborts = { count: 0 };
    const preloadResolvers = new Map<string, (p: boolean) => void>();
    const refreshResolvers = new Map<string, () => void>();
    const warmResolvers = new Map<string, () => void>();
    const state: { onPhase: ((sub: RefreshSubPhase) => void) | null } = {
        onPhase: null,
    };
    const deps: ScriptedDeps = {
        get aborts() {
            return aborts.count;
        },
        get currentOnPhase() {
            return state.onPhase;
        },
        preloadResolvers,
        refreshResolvers,
        warmResolvers,
        abortPendingRefresh: () => {
            aborts.count += 1;
        },
        preloadCachedPreviews: (filePath) => {
            const { promise, resolve } = deferred<boolean>();
            preloadResolvers.set(filePath, resolve);
            return promise;
        },
        refresh: async (_force, filePath, _tier, opts) => {
            state.onPhase = opts.onPhase ?? null;
            const { promise, resolve } = deferred<void>();
            refreshResolvers.set(filePath, resolve);
            await promise;
            state.onPhase = null;
            return "completed";
        },
        warmDaemonForFile: (filePath) => {
            const { promise, resolve } = deferred<void>();
            warmResolvers.set(filePath, resolve);
            return promise;
        },
    };
    return deps;
}

/** Yield to the microtask queue so awaited dep promises settle. */
async function tick(times = 1): Promise<void> {
    for (let i = 0; i < times; i++) {
        await Promise.resolve();
    }
}

describe("RefreshOrchestrator — phase sequence for one transition", () => {
    it("traverses preload → discover → reconcile → render → warm → idle in order", async () => {
        const deps = scriptedDeps();
        const orchestrator = new RefreshOrchestrator(deps);
        const phases: PhaseChange[] = [];
        orchestrator.onPhaseChange((c) => phases.push(c));
        const done = orchestrator.transitionToFile("/ws/a/A.kt");

        // preload latches synchronously before the first await.
        assert.strictEqual(orchestrator.phase, "preload");

        // Painted preload → refresh proceeds.
        deps.preloadResolvers.get("/ws/a/A.kt")!(true);
        await tick(2);
        deps.currentOnPhase!("discover");
        assert.strictEqual(orchestrator.phase, "discover");
        deps.currentOnPhase!("reconcile");
        assert.strictEqual(orchestrator.phase, "reconcile");
        deps.currentOnPhase!("render");
        assert.strictEqual(orchestrator.phase, "render");

        // Refresh resolves → warm starts.
        deps.refreshResolvers.get("/ws/a/A.kt")!();
        await tick(2);
        assert.strictEqual(orchestrator.phase, "warm");

        // Warm resolves → idle.
        deps.warmResolvers.get("/ws/a/A.kt")!();
        await done;
        assert.strictEqual(orchestrator.phase, "idle");

        assert.deepStrictEqual(
            phases.map((c) => c.phase),
            ["preload", "discover", "reconcile", "render", "warm", "idle"],
        );
    });

    it("aborts the previous refresh and starts preload BEFORE any other dep runs", async () => {
        // The motivating bug: a stale refresh continuation winning over the new
        // preload's setPreviews. abortPendingRefresh must fire before preload's
        // disk read yields the loop so the previous transition's discover can't
        // resume on top of our new paint.
        const deps = scriptedDeps();
        const orchestrator = new RefreshOrchestrator(deps);
        void orchestrator.transitionToFile("/ws/a/A.kt");
        assert.strictEqual(deps.aborts, 1);
        // preload was called for A; refresh has not yet been called.
        assert.ok(deps.preloadResolvers.has("/ws/a/A.kt"));
        assert.ok(!deps.refreshResolvers.has("/ws/a/A.kt"));
    });
});

describe("RefreshOrchestrator — re-entrant transitions and cancellation", () => {
    it("switching to file B during A's warm phase replaces the orchestrator's public state with B's preload", async () => {
        // Direct encoding of the race the user wrote up: while phase=warm for A,
        // switching to B must update the orchestrator immediately to B's preload —
        // A's still-pending warm shouldn't pin the orchestrator on A.
        const deps = scriptedDeps();
        const orchestrator = new RefreshOrchestrator(deps);
        const phases: PhaseChange[] = [];
        orchestrator.onPhaseChange((c) => phases.push(c));

        // Drive A all the way to warm.
        const aDone = orchestrator.transitionToFile("/ws/a/A.kt");
        deps.preloadResolvers.get("/ws/a/A.kt")!(true);
        await tick(2);
        deps.currentOnPhase!("discover");
        deps.currentOnPhase!("reconcile");
        deps.currentOnPhase!("render");
        deps.refreshResolvers.get("/ws/a/A.kt")!();
        await tick(2);
        assert.strictEqual(orchestrator.phase, "warm");
        assert.strictEqual(orchestrator.currentFile, "/ws/a/A.kt");

        // Now switch to B while A's warm is still pending.
        const bDone = orchestrator.transitionToFile("/ws/b/B.kt");
        // The orchestrator's public phase flips to preload(B) immediately —
        // A's warm is fire-and-forget and continues in the background but no
        // longer drives observable state.
        assert.strictEqual(orchestrator.phase, "preload");
        assert.strictEqual(orchestrator.currentFile, "/ws/b/B.kt");
        // And the host's pendingRefresh got aborted as part of the new transition.
        assert.strictEqual(deps.aborts, 2);

        // Drive B to completion.
        deps.preloadResolvers.get("/ws/b/B.kt")!(true);
        await tick(2);
        deps.currentOnPhase!("discover");
        deps.currentOnPhase!("reconcile");
        deps.currentOnPhase!("render");
        deps.refreshResolvers.get("/ws/b/B.kt")!();
        await tick(2);
        deps.warmResolvers.get("/ws/b/B.kt")!();
        await bDone;

        // A's warm hasn't resolved yet — but the orchestrator already idle'd from B.
        assert.strictEqual(orchestrator.phase, "idle");

        // Resolving A's warm now is a no-op for the orchestrator (id mismatch).
        deps.warmResolvers.get("/ws/a/A.kt")!();
        await aDone;
        assert.strictEqual(orchestrator.phase, "idle");
    });

    it("a stale refresh's onPhase callback after supersession does NOT update the orchestrator", async () => {
        // Regression: without the transition-id guard, an aborted refresh's late
        // discover/reconcile/render fire would paint A's phase onto B's UI.
        const deps = scriptedDeps();
        const orchestrator = new RefreshOrchestrator(deps);

        // Start A, get into refresh, capture A's onPhase callback.
        void orchestrator.transitionToFile("/ws/a/A.kt");
        deps.preloadResolvers.get("/ws/a/A.kt")!(true);
        await tick(2);
        const aOnPhase = deps.currentOnPhase!;

        // Start B mid-refresh of A. B latches preload immediately.
        void orchestrator.transitionToFile("/ws/b/B.kt");
        assert.strictEqual(orchestrator.phase, "preload");
        assert.strictEqual(orchestrator.currentFile, "/ws/b/B.kt");

        // A's stale refresh now fires its discover callback late. Must not move
        // the orchestrator off B's preload.
        aOnPhase("discover");
        assert.strictEqual(orchestrator.phase, "preload");
        assert.strictEqual(orchestrator.currentFile, "/ws/b/B.kt");
    });

    it("listener stops receiving events from a superseded transition (only the latest drives observable state)", async () => {
        const deps = scriptedDeps();
        const orchestrator = new RefreshOrchestrator(deps);
        const phases: PhaseChange[] = [];
        orchestrator.onPhaseChange((c) => phases.push(c));

        void orchestrator.transitionToFile("/ws/a/A.kt");
        deps.preloadResolvers.get("/ws/a/A.kt")!(true);
        await tick(2);
        const aOnPhase = deps.currentOnPhase!;

        void orchestrator.transitionToFile("/ws/b/B.kt");
        // A's late discover callback — dropped.
        aOnPhase("discover");
        // Only B's preload event should be the most recent emission.
        assert.strictEqual(phases[phases.length - 1].phase, "preload");
        assert.strictEqual(phases[phases.length - 1].filePath, "/ws/b/B.kt");
    });
});

describe("RefreshOrchestrator — subscription lifecycle", () => {
    it("dispose removes the listener so it stops receiving subsequent phase changes", async () => {
        const deps = scriptedDeps();
        const orchestrator = new RefreshOrchestrator(deps);
        const phases: PhaseChange[] = [];
        const sub = orchestrator.onPhaseChange((c) => phases.push(c));

        void orchestrator.transitionToFile("/ws/a/A.kt");
        sub.dispose();
        // After dispose, no further phase events should be observed by this listener.
        deps.preloadResolvers.get("/ws/a/A.kt")!(true);
        await tick(2);
        deps.currentOnPhase!("discover");
        // The only phase the listener saw was the synchronous initial preload
        // emission BEFORE dispose.
        assert.deepStrictEqual(
            phases.map((c) => c.phase),
            ["preload"],
        );
    });
});
