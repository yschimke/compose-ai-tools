import * as assert from "assert";
import {
    formatDiffStatsLabel,
    type DiffStats,
} from "../webview/shared/diffStatsLabel";

describe("formatDiffStatsLabel", () => {
    it("returns empty text + null state for null stats (still computing)", () => {
        assert.deepStrictEqual(formatDiffStatsLabel(null), {
            text: "",
            state: null,
        });
    });

    it("renders the error text for an error variant, no state", () => {
        const stats: DiffStats = { error: "image failed to load" };
        assert.deepStrictEqual(formatDiffStatsLabel(stats), {
            text: "image failed to load",
            state: null,
        });
    });

    it("renders 'sizes differ' for size-mismatch", () => {
        const stats: DiffStats = {
            sameSize: false,
            leftW: 100,
            leftH: 200,
            rightW: 110,
            rightH: 210,
        };
        assert.deepStrictEqual(formatDiffStatsLabel(stats), {
            text: "sizes differ — 100×200 vs 110×210",
            state: "size-mismatch",
        });
    });

    it("renders 'identical · WxH' when diffPx is 0", () => {
        const stats: DiffStats = {
            sameSize: true,
            w: 360,
            h: 640,
            diffPx: 0,
            total: 230_400,
            percent: 0,
        };
        assert.deepStrictEqual(formatDiffStatsLabel(stats), {
            text: "identical · 360×640",
            state: "identical",
        });
    });

    it("uses 2-decimal percent for >= 0.01% diffs", () => {
        // 1000 px out of 100_000 = 1% — comfortably above 0.01.
        const stats: DiffStats = {
            sameSize: true,
            w: 200,
            h: 500,
            diffPx: 1000,
            total: 100_000,
            percent: 0.01, // 1.00 after × 100
        };
        const out = formatDiffStatsLabel(stats);
        assert.strictEqual(out.state, "changed");
        assert.match(out.text, /1,000 px \(1\.00%\) · 200×500/);
    });

    it("uses 3-decimal percent for sub-0.01% diffs", () => {
        // 1 px out of 1_000_000 = 0.0001%.
        const stats: DiffStats = {
            sameSize: true,
            w: 1000,
            h: 1000,
            diffPx: 1,
            total: 1_000_000,
            percent: 0.000_001, // 0.0001 after × 100
        };
        const out = formatDiffStatsLabel(stats);
        assert.strictEqual(out.state, "changed");
        assert.match(out.text, /1 px \(0\.000%\) · 1000×1000/);
        // Sanity: 3-decimal precision means we get "0.000" not "0.00".
        assert.ok(
            out.text.includes("0.000%"),
            "expected 3-decimal pct, got " + out.text,
        );
    });

    it("uses locale formatting on diffPx (thousands separators)", () => {
        const stats: DiffStats = {
            sameSize: true,
            w: 1000,
            h: 1000,
            diffPx: 12_345,
            total: 1_000_000,
            percent: 0.012_345, // 1.2345% after × 100
        };
        const out = formatDiffStatsLabel(stats);
        // toLocaleString defaults to en-US-ish thousands separators in
        // most environments — accept either "12,345" or "12.345" so the
        // test is locale-tolerant.
        assert.ok(
            /12[,.]345 px/.test(out.text),
            "expected locale-formatted diffPx, got " + out.text,
        );
        assert.match(out.text, /1\.23%/);
    });
});
