// Tests for `buildHistoryRowDetail`. The bundle row carries the
// Euclidean Δ magnitude only; the detail helper re-joins against
// the source payload to surface per-channel deltas and shared
// baseline-history context.

import * as assert from "assert";
import { buildHistoryRowDetail } from "../webview/preview/historyRowDetail";
import type {
    HistoryDiffPayload,
    HistoryDiffRow,
} from "../webview/preview/historyDiffBundlePresenter";

function row(over: Partial<HistoryDiffRow>): HistoryDiffRow {
    return {
        id: over.id ?? "history-diff-region-0",
        bounds:
            over.bounds === undefined
                ? { left: 0, top: 0, right: 100, bottom: 80 }
                : over.bounds,
        boundsLabel: over.boundsLabel ?? "0,0,100,80",
        pixelCount: over.pixelCount ?? 8000,
        deltaMagnitude: over.deltaMagnitude ?? 40.3,
        intensity: over.intensity ?? 0.12,
    };
}

function payload(over: Partial<HistoryDiffPayload>): HistoryDiffPayload {
    // Honour `undefined` explicitly so tests can drop the
    // baselineHistoryId / regions to exercise the no-context paths.
    return {
        baselineHistoryId:
            "baselineHistoryId" in over
                ? over.baselineHistoryId
                : "main@a1b2c3d4",
        totalPixelsChanged: over.totalPixelsChanged ?? 42240,
        changedFraction: over.changedFraction ?? 0.1956,
        regions:
            "regions" in over
                ? over.regions
                : [
                      {
                          bounds: "0,0,100,80",
                          pixelCount: 8000,
                          avgDelta: { r: 12.4, g: 18.2, b: 33.7, a: 0.0 },
                      },
                  ],
    };
}

describe("buildHistoryRowDetail", () => {
    it("always emits a Region section with bounds + pixels + Δ + intensity", () => {
        const sections = buildHistoryRowDetail(row({}), payload({}), 0);
        const region = sections.find((s) => s.heading === "Region");
        assert.ok(region);
        const labels = region!.entries.map((e) => e.label);
        assert.ok(labels.includes("Bounds"));
        assert.ok(labels.includes("Pixels changed"));
        assert.ok(labels.includes("Δ magnitude"));
        assert.ok(labels.includes("Intensity"));
    });

    it("formats Δ magnitude to one decimal place and intensity as a percentage", () => {
        const sections = buildHistoryRowDetail(
            row({ deltaMagnitude: 43.85, intensity: 0.234 }),
            payload({}),
            0,
        );
        const region = sections.find((s) => s.heading === "Region")!;
        const delta = region.entries.find((e) => e.label === "Δ magnitude");
        const intensity = region.entries.find((e) => e.label === "Intensity");
        assert.strictEqual(delta?.value, "43.9");
        assert.strictEqual(intensity?.value, "23%");
    });

    it("pulls per-channel deltas from the payload by row index", () => {
        const sections = buildHistoryRowDetail(
            row({}),
            payload({
                regions: [
                    {
                        bounds: "0,0,100,80",
                        pixelCount: 8000,
                        avgDelta: { r: 12.4, g: 18.2, b: 33.7, a: 0.0 },
                    },
                    {
                        bounds: "100,0,200,80",
                        pixelCount: 4000,
                        avgDelta: { r: 5.5, g: 5.0, b: 5.0, a: 0.0 },
                    },
                ],
            }),
            1,
        );
        const channels = sections.find((s) => s.heading === "Channel deltas");
        assert.ok(channels);
        const red = channels!.entries.find((e) => e.label === "Δ red");
        assert.strictEqual(red?.value, "5.5");
    });

    it("skips the Channel deltas section when no avgDelta is present", () => {
        const sections = buildHistoryRowDetail(
            row({}),
            payload({
                regions: [{ bounds: "0,0,100,80", pixelCount: 8000 }],
            }),
            0,
        );
        assert.strictEqual(
            sections.find((s) => s.heading === "Channel deltas"),
            undefined,
        );
    });

    it("emits a Baseline section with the history id + changed fraction when present", () => {
        const sections = buildHistoryRowDetail(
            row({}),
            payload({
                baselineHistoryId: "main@d34db33f",
                changedFraction: 0.42,
            }),
            0,
        );
        const baseline = sections.find((s) => s.heading === "Baseline");
        assert.ok(baseline);
        const fraction = baseline!.entries.find(
            (e) => e.label === "Changed fraction",
        );
        assert.strictEqual(fraction?.value, "42.00%");
    });

    it("skips the Baseline section when baselineHistoryId is absent", () => {
        const sections = buildHistoryRowDetail(
            row({}),
            payload({ baselineHistoryId: undefined }),
            0,
        );
        assert.strictEqual(
            sections.find((s) => s.heading === "Baseline"),
            undefined,
        );
    });

    it("renders pixel counts with thousands separators", () => {
        const sections = buildHistoryRowDetail(
            row({ pixelCount: 28800 }),
            payload({}),
            0,
        );
        const px = sections
            .find((s) => s.heading === "Region")!
            .entries.find((e) => e.label === "Pixels changed");
        assert.strictEqual(px?.value, (28800).toLocaleString());
    });
});
