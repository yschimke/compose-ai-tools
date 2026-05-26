import * as assert from "assert";
import { transitionToFile, TransitionToFileDeps } from "../fileTransition";

interface Call {
    op: "abort" | "preload" | "refresh" | "warm";
    filePath?: string;
    showLoadingOverlay?: boolean;
}

function makeDeps(opts: {
    preloadResult: boolean | ((filePath: string) => Promise<boolean>);
    calls?: Call[];
}): TransitionToFileDeps {
    const calls = opts.calls ?? [];
    return {
        abortPendingRefresh: () => {
            calls.push({ op: "abort" });
        },
        preloadCachedPreviews: async (filePath) => {
            calls.push({ op: "preload", filePath });
            return typeof opts.preloadResult === "function"
                ? await opts.preloadResult(filePath)
                : opts.preloadResult;
        },
        refresh: async (_force, filePath, _tier, refreshOpts) => {
            calls.push({
                op: "refresh",
                filePath,
                showLoadingOverlay: refreshOpts.showLoadingOverlay,
            });
            return "completed";
        },
        warmDaemonForFile: async (filePath) => {
            calls.push({ op: "warm", filePath });
        },
    };
}

describe("transitionToFile", () => {
    it("aborts then preloads then refreshes then warms — in that order", async () => {
        // The order is the invariant: a stale refresh continuation winning over the
        // new preload's setPreviews is exactly the race that motivated extracting this
        // helper. abort must run first, before preload's async I/O yields the loop.
        const calls: Call[] = [];
        await transitionToFile(
            "/ws/app/Previews.kt",
            makeDeps({ preloadResult: true, calls }),
        );
        assert.deepStrictEqual(
            calls.map((c) => c.op),
            ["abort", "preload", "refresh", "warm"],
        );
    });

    it("hides the loading overlay when preload painted (cards already on screen)", async () => {
        const calls: Call[] = [];
        await transitionToFile(
            "/ws/app/Previews.kt",
            makeDeps({ preloadResult: true, calls }),
        );
        const refresh = calls.find((c) => c.op === "refresh")!;
        assert.strictEqual(refresh.showLoadingOverlay, false);
    });

    it("shows the loading overlay when preload skipped (no cached cards to look at)", async () => {
        // Without the overlay the panel would sit blank while Gradle warms up for
        // 15-25s on a cold daemon. Showing it explicitly tells the user the system
        // is doing work rather than wedged.
        const calls: Call[] = [];
        await transitionToFile(
            "/ws/app/Previews.kt",
            makeDeps({ preloadResult: false, calls }),
        );
        const refresh = calls.find((c) => c.op === "refresh")!;
        assert.strictEqual(refresh.showLoadingOverlay, true);
    });

    it("forwards the filePath unchanged through preload, refresh, and warm", async () => {
        const calls: Call[] = [];
        await transitionToFile(
            "/ws/wear/Previews.kt",
            makeDeps({ preloadResult: true, calls }),
        );
        for (const op of ["preload", "refresh", "warm"] as const) {
            assert.strictEqual(
                calls.find((c) => c.op === op)?.filePath,
                "/ws/wear/Previews.kt",
                op,
            );
        }
    });

    it("preserves previous module's cards across cmp → android → wear focus changes (no clearAll between transitions)", async () => {
        // Regression for the user-reported flicker: each transition's `abortPendingRefresh`
        // fires before the new preload starts. `loadCachedPreviews` posts setPreviews
        // FIRST and then async imageJobs — so the previous module's cards stay on screen
        // until the new module's setPreviews lands. The test verifies that no `clearAll`
        // ever fires from this helper: refresh's no-module clear path is the only thing
        // that posts clearAll, and we keep refresh running with a real filePath every time.
        const calls: Call[] = [];
        const transitions = [
            "/ws/cmp/CmpPreviews.kt",
            "/ws/android/AndroidPreviews.kt",
            "/ws/wear/WearPreviews.kt",
        ];
        for (const filePath of transitions) {
            await transitionToFile(
                filePath,
                makeDeps({ preloadResult: true, calls }),
            );
        }
        // Three transitions = three (abort, preload, refresh, warm) tuples in order.
        assert.deepStrictEqual(
            calls.map((c) => c.op),
            [
                "abort",
                "preload",
                "refresh",
                "warm",
                "abort",
                "preload",
                "refresh",
                "warm",
                "abort",
                "preload",
                "refresh",
                "warm",
            ],
        );
        // Each refresh ran with a real filePath — refresh's no-module clearAll branch
        // is the only thing that wipes the grid, and it requires `!activeFile || !module`.
        for (const c of calls.filter((c) => c.op === "refresh")) {
            assert.ok(c.filePath, "refresh must receive a filePath");
        }
        // And preload painted on every transition, so refresh stayed in stealth mode
        // (no loading overlay tearing down the previous module's content).
        for (const c of calls.filter((c) => c.op === "refresh")) {
            assert.strictEqual(c.showLoadingOverlay, false);
        }
    });

    it("hands the overlay back when a transition's preload can't paint (no cached manifest for the new module)", async () => {
        // E.g. switching to a Kotlin file in a module that's never been rendered. The
        // previous module's cards stay (refresh holds them via `hasPreviewsLoaded`)
        // and the overlay surfaces so the user knows the new module is loading.
        const calls: Call[] = [];
        await transitionToFile(
            "/ws/cmp/Previews.kt",
            makeDeps({ preloadResult: true, calls }),
        );
        await transitionToFile(
            "/ws/never-rendered/Previews.kt",
            makeDeps({ preloadResult: false, calls }),
        );
        const refreshes = calls.filter((c) => c.op === "refresh");
        assert.strictEqual(refreshes[0].showLoadingOverlay, false);
        assert.strictEqual(refreshes[1].showLoadingOverlay, true);
    });
});
