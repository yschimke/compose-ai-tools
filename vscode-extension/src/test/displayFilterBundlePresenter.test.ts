// Display-filter bundle presenter (#1061 Cluster H). Pins the
// manifest-payload → row derivation. The daemon's wire kind is
// `displayfilter/variants` and its payload enumerates one entry per
// enabled filter; the presenter turns each entry into one row.

import * as assert from "assert";
import {
    computeDisplayFilterBundleData,
    type DisplayFilterVariantsPayload,
} from "../webview/preview/displayFilterBundlePresenter";

function payload(
    variants: readonly { filter: string; path?: string; label?: string }[],
): DisplayFilterVariantsPayload {
    return { variants };
}

describe("computeDisplayFilterBundleData", () => {
    it("emits no rows for a null or empty payload", () => {
        assert.strictEqual(computeDisplayFilterBundleData(null).rows.length, 0);
        assert.strictEqual(
            computeDisplayFilterBundleData(undefined).rows.length,
            0,
        );
        assert.strictEqual(computeDisplayFilterBundleData({}).rows.length, 0);
        assert.strictEqual(
            computeDisplayFilterBundleData(payload([])).rows.length,
            0,
        );
    });

    it("emits one row per manifest variant", () => {
        const data = computeDisplayFilterBundleData(
            payload([{ filter: "grayscale" }, { filter: "invert" }]),
        );
        assert.strictEqual(data.rows.length, 2);
        assert.strictEqual(data.rows[0].filterId, "grayscale");
        assert.strictEqual(data.rows[1].filterId, "invert");
    });

    it("uses a stable id prefix per row for overlay correlation", () => {
        const data = computeDisplayFilterBundleData(
            payload([{ filter: "grayscale" }]),
        );
        assert.ok(data.rows[0].id.startsWith("displayfilter-"));
        assert.strictEqual(data.rows[0].id, "displayfilter-grayscale");
    });

    it("derives a human label from the filter id when none is supplied", () => {
        const data = computeDisplayFilterBundleData(
            payload([{ filter: "deuteranopia" }]),
        );
        assert.strictEqual(data.rows[0].label, "Deuteranopia");
    });

    it("prefers the manifest-supplied label when present", () => {
        const data = computeDisplayFilterBundleData(
            payload([{ filter: "grayscale", label: "Bedtime grayscale" }]),
        );
        assert.strictEqual(data.rows[0].label, "Bedtime grayscale");
    });

    it("carries the variant PNG path through to the row", () => {
        const data = computeDisplayFilterBundleData(
            payload([{ filter: "invert", path: "/tmp/data/p1/invert.png" }]),
        );
        assert.strictEqual(data.rows[0].path, "/tmp/data/p1/invert.png");
    });
});
