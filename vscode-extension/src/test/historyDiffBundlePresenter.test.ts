// History-diff bundle presenter (#1061 Cluster H). Pins the
// payload → rows / overlay / header derivation, including the
// colour-ramp intensity for the swatch / overlay.

import * as assert from "assert";
import {
    computeHistoryDiffBundleData,
    type HistoryDiffPayload,
} from "../webview/preview/historyDiffBundlePresenter";

describe("computeHistoryDiffBundleData", () => {
    it("returns an empty data set for a null payload", () => {
        const data = computeHistoryDiffBundleData(null);
        assert.strictEqual(data.rows.length, 0);
        assert.strictEqual(data.overlay.length, 0);
        assert.strictEqual(data.header.baselineHistoryId, "");
        assert.strictEqual(data.header.totalPixelsChanged, 0);
        assert.strictEqual(data.header.regionCount, 0);
    });

    it("emits one row + one overlay box per parsed region", () => {
        const payload: HistoryDiffPayload = {
            baselineHistoryId: "h-001",
            totalPixelsChanged: 4200,
            changedFraction: 0.012,
            regions: [
                {
                    bounds: "0,0,100,100",
                    pixelCount: 4000,
                    avgDelta: { r: 16, g: 24, b: 8, a: 0 },
                },
                {
                    bounds: "200,200,300,300",
                    pixelCount: 200,
                    avgDelta: { r: 1, g: 1, b: 1, a: 0 },
                },
            ],
        };
        const data = computeHistoryDiffBundleData(payload);
        assert.strictEqual(data.rows.length, 2);
        assert.strictEqual(data.overlay.length, 2);
        assert.strictEqual(data.header.baselineHistoryId, "h-001");
        assert.strictEqual(data.header.regionCount, 2);
        assert.strictEqual(data.header.totalPixelsChanged, 4200);
        // First row has larger deltas → higher intensity.
        assert.ok(data.rows[0].intensity > data.rows[1].intensity);
        // Bounds parsed onto the row for downstream consumers.
        assert.deepStrictEqual(data.rows[0].bounds, {
            left: 0,
            top: 0,
            right: 100,
            bottom: 100,
        });
    });

    it("skips overlay boxes for regions whose bounds did not parse", () => {
        const data = computeHistoryDiffBundleData({
            baselineHistoryId: "h-002",
            totalPixelsChanged: 0,
            changedFraction: 0,
            regions: [
                {
                    bounds: "not-a-rect",
                    pixelCount: 0,
                    avgDelta: { r: 0, g: 0, b: 0, a: 0 },
                },
            ],
        });
        // Row is preserved so the user still sees there was *something*
        // — overlay layer just doesn't paint it.
        assert.strictEqual(data.rows.length, 1);
        assert.strictEqual(data.overlay.length, 0);
    });

    it("clamps intensity into [0,1] even with absurd deltas", () => {
        const data = computeHistoryDiffBundleData({
            baselineHistoryId: "h-003",
            totalPixelsChanged: 1,
            changedFraction: 0,
            regions: [
                {
                    bounds: "0,0,10,10",
                    pixelCount: 1,
                    avgDelta: { r: 1000, g: 1000, b: 1000, a: 1000 },
                },
                {
                    bounds: "0,0,10,10",
                    pixelCount: 1,
                    // signed deltas — the presenter uses magnitude so
                    // negative values still ramp toward 1.
                    avgDelta: { r: -255, g: -255, b: -255, a: -255 },
                },
            ],
        });
        for (const row of data.rows) {
            assert.ok(row.intensity >= 0 && row.intensity <= 1);
        }
    });

    it("emits a stable overlay id that the row carries too", () => {
        const data = computeHistoryDiffBundleData({
            baselineHistoryId: "h-004",
            totalPixelsChanged: 1,
            changedFraction: 0,
            regions: [
                {
                    bounds: "0,0,10,10",
                    pixelCount: 1,
                    avgDelta: { r: 10, g: 10, b: 10, a: 0 },
                },
            ],
        });
        assert.strictEqual(data.rows[0].id, data.overlay[0].id);
        assert.ok(data.rows[0].id.startsWith("history-diff-region-"));
    });
});
