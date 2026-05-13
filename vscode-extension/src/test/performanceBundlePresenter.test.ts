// Performance bundle presenter (#1060 Cluster G). Pins the parsing of
// all three wire kinds the bundle covers — `compose/recomposition`,
// `render/trace`, and `render/composeAiTrace` — against empty,
// populated, and key-field round-trip shapes. Layout/DOM lives behind
// `renderPerformanceSections` and is exercised by the smoke harness;
// these unit tests only assert the parsed payload shape since that's
// what every other bundle test does (see `a11yBundlePresenter.test.ts`).

import * as assert from "assert";
import {
    computePerformanceBundleData,
    performanceTableColumns,
} from "../webview/preview/performanceBundlePresenter";

describe("computePerformanceBundleData — recomposition", () => {
    it("returns null when payload is missing", () => {
        const data = computePerformanceBundleData(null, null, null);
        assert.strictEqual(data.recomposition, null);
    });

    it("returns null when nodes is empty (nothing recomposed)", () => {
        const data = computePerformanceBundleData(
            { mode: "delta", inputSeq: 7, nodes: [] },
            null,
            null,
        );
        assert.strictEqual(data.recomposition, null);
    });

    it("sorts rows by count desc and caps at TOP_N (10)", () => {
        const nodes = Array.from({ length: 15 }, (_, i) => ({
            nodeId: "node-" + i,
            count: i + 1, // 1..15
        }));
        const data = computePerformanceBundleData(
            { mode: "snapshot", nodes },
            null,
            null,
        );
        const r = data.recomposition;
        assert.ok(r, "recomposition section must be present");
        assert.strictEqual(r!.rows.length, 10);
        // Sorted desc: top entry is the highest count.
        assert.strictEqual(r!.rows[0].count, 15);
        assert.strictEqual(r!.rows[0].nodeId, "node-14");
        // Truncation surfaces the leftover for the "+N more" footer.
        assert.strictEqual(r!.totalNodes, 15);
        assert.strictEqual(r!.truncated, 5);
    });

    it("round-trips mode + inputSeq onto every row", () => {
        const data = computePerformanceBundleData(
            {
                mode: "delta",
                inputSeq: 42,
                nodes: [
                    { nodeId: "a", count: 3 },
                    { nodeId: "b", count: 1 },
                ],
            },
            null,
            null,
        );
        const r = data.recomposition!;
        assert.strictEqual(r.mode, "delta");
        assert.strictEqual(r.inputSeq, 42);
        for (const row of r.rows) {
            assert.strictEqual(row.mode, "delta");
            assert.strictEqual(row.inputSeq, 42);
        }
    });

    it("drops nodes with non-finite count or empty nodeId", () => {
        const data = computePerformanceBundleData(
            {
                mode: "snapshot",
                nodes: [
                    { nodeId: "good", count: 5 },
                    { nodeId: "", count: 9 },
                    { nodeId: "nan", count: Number.NaN },
                    { count: 1 }, // missing nodeId
                ],
            },
            null,
            null,
        );
        const r = data.recomposition!;
        assert.strictEqual(r.rows.length, 1);
        assert.strictEqual(r.rows[0].nodeId, "good");
    });
});

describe("computePerformanceBundleData — render/trace", () => {
    it("returns null when payload is missing", () => {
        const data = computePerformanceBundleData(null, null, null);
        assert.strictEqual(data.renderTrace, null);
    });

    it("returns null when all fields are empty", () => {
        const data = computePerformanceBundleData(
            null,
            { phases: [], metrics: {} },
            null,
        );
        assert.strictEqual(data.renderTrace, null);
    });

    it("derives per-phase widthPct from totalMs as the chart scale", () => {
        const data = computePerformanceBundleData(
            null,
            {
                totalMs: 100,
                phases: [
                    { name: "compose", startMs: 0, durationMs: 25 },
                    { name: "measure", startMs: 25, durationMs: 50 },
                    { name: "draw", startMs: 75, durationMs: 25 },
                ],
                metrics: { frames: 1 },
            },
            null,
        );
        const rt = data.renderTrace!;
        assert.strictEqual(rt.totalMs, 100);
        assert.strictEqual(rt.phases.length, 3);
        assert.strictEqual(rt.phases[0].widthPct, 25);
        assert.strictEqual(rt.phases[1].widthPct, 50);
        assert.strictEqual(rt.phases[2].widthPct, 25);
        // tookMs would be redundant with totalMs, but `frames` survives.
        assert.deepStrictEqual(
            rt.metrics.map((m) => m.key),
            ["frames"],
        );
    });

    it("suppresses metrics.tookMs (redundant with totalMs)", () => {
        const data = computePerformanceBundleData(
            null,
            {
                totalMs: 10,
                phases: [],
                metrics: { tookMs: 10, otherStat: "yes" },
            },
            null,
        );
        const rt = data.renderTrace!;
        const keys = rt.metrics.map((m) => m.key);
        assert.ok(!keys.includes("tookMs"), "tookMs must be suppressed");
        assert.deepStrictEqual(keys, ["otherStat"]);
    });
});

describe("computePerformanceBundleData — composeAiTrace", () => {
    it("returns null when payload is missing", () => {
        const data = computePerformanceBundleData(null, null, null);
        assert.strictEqual(data.composeAiTrace, null);
    });

    it("returns null when traceEvents is empty and no totalMs", () => {
        const data = computePerformanceBundleData(null, null, {
            traceEvents: [],
        });
        assert.strictEqual(data.composeAiTrace, null);
    });

    it("aggregates phase counts and exposes top-3 names", () => {
        const data = computePerformanceBundleData(null, null, {
            totalMs: 50,
            traceEvents: [
                { name: "compose", ph: "X" },
                { name: "compose", ph: "X" },
                { name: "measure", ph: "X" },
                { name: "draw", ph: "X" },
                { name: "compose", ph: "X" },
                { name: "noise", ph: "I" }, // instant — filtered out
                { ph: "X" }, // missing name — filtered out
                "not-an-object",
            ],
        });
        const summary = data.composeAiTrace!;
        assert.strictEqual(summary.phaseCount, 3);
        assert.strictEqual(summary.totalMs, 50);
        // "compose" wins with 3, then measure(1), draw(1) — order
        // between the 1-count phases is map-insertion stable.
        assert.strictEqual(summary.topPhases[0], "compose (3)");
        assert.strictEqual(summary.topPhases.length, 3);
        // The raw payload rides along for the Open-in-Perfetto button
        // so we don't redact it down to just the summary.
        assert.ok(summary.rawPayload);
    });

    it("keeps rawPayload identity so the copy button ships the full JSON", () => {
        const raw = {
            traceEvents: [{ name: "compose", ph: "X" }],
            totalMs: 12,
        };
        const data = computePerformanceBundleData(null, null, raw);
        assert.strictEqual(data.composeAiTrace!.rawPayload, raw);
    });
});

describe("performanceTableColumns", () => {
    it("exposes the recomposition columns (#, Node, Count, Mode)", () => {
        const cols = performanceTableColumns();
        assert.deepStrictEqual(
            cols.map((c) => c.header),
            ["#", "Node", "Count", "Mode"],
        );
    });

    it("renders the rank as the row index + 1", () => {
        const cols = performanceTableColumns();
        const rankCol = cols[0];
        const out = rankCol.render(
            {
                id: "perf-recomp-0",
                nodeId: "node",
                count: 1,
                mode: "",
                inputSeq: null,
            },
            4,
        );
        assert.strictEqual(out, "5");
    });
});
