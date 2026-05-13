// Display-filter bundle presenter (#1061 Cluster H). Pins the
// kind→row derivation; the table is decorative (filter swap on click
// is out of scope for v1), so the assertions focus on the row id /
// filterId / label round-trip and the empty-entries path.

import * as assert from "assert";
import {
    computeDisplayFilterBundleData,
    type DisplayFilterEntry,
} from "../webview/preview/displayFilterBundlePresenter";

function entry(kind: string, label: string): DisplayFilterEntry {
    return { kind, label };
}

describe("computeDisplayFilterBundleData", () => {
    it("emits no rows for an empty entries list", () => {
        const data = computeDisplayFilterBundleData([]);
        assert.strictEqual(data.rows.length, 0);
    });

    it("derives filterId from the tail of the wire kind", () => {
        const data = computeDisplayFilterBundleData([
            entry("displayfilter/grayscale", "Grayscale"),
            entry("displayfilter/invert", "Invert"),
        ]);
        assert.strictEqual(data.rows.length, 2);
        assert.strictEqual(data.rows[0].filterId, "grayscale");
        assert.strictEqual(data.rows[1].filterId, "invert");
    });

    it("uses a stable id prefix per row for overlay correlation", () => {
        const data = computeDisplayFilterBundleData([
            entry("displayfilter/grayscale", "Grayscale"),
        ]);
        assert.ok(data.rows[0].id.startsWith("displayfilter-"));
        assert.strictEqual(data.rows[0].id, "displayfilter-grayscale");
    });

    it("preserves the original kind on each row", () => {
        const data = computeDisplayFilterBundleData([
            entry("displayfilter/deuteranopia", "Deuteranopia"),
        ]);
        assert.strictEqual(data.rows[0].kind, "displayfilter/deuteranopia");
        assert.strictEqual(data.rows[0].label, "Deuteranopia");
    });

    it("falls back to the whole kind when there is no slash", () => {
        const data = computeDisplayFilterBundleData([
            entry("grayscale", "Grayscale"),
        ]);
        assert.strictEqual(data.rows[0].filterId, "grayscale");
    });
});
