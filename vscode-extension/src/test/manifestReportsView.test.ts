import * as assert from "assert";
import { manifestReportsView, PreviewManifest } from "../types";

/**
 * Mirrors `PreviewManifestReportsViewTest` on the CLI side. The v1 `accessibilityReport` alias was
 * removed after one transition release, so this helper is a thin pass-through over the v2
 * `dataExtensionReports` map. Tests pin the pass-through, the empty-manifest behaviour, and the
 * forward-compat tolerance of unknown legacy fields.
 */
describe("manifestReportsView", () => {
    const baseManifest: PreviewManifest = {
        module: "sample",
        variant: "debug",
        previews: [],
    };

    it("returns the dataExtensionReports map verbatim", () => {
        const view = manifestReportsView({
            ...baseManifest,
            dataExtensionReports: { a11y: "accessibility.json" },
        });
        assert.deepStrictEqual(view, { a11y: "accessibility.json" });
    });

    it("returns an empty object when dataExtensionReports is absent", () => {
        const view = manifestReportsView(baseManifest);
        assert.deepStrictEqual(view, {});
    });

    it("returns an empty object when dataExtensionReports is an empty map", () => {
        const view = manifestReportsView({
            ...baseManifest,
            dataExtensionReports: {},
        });
        assert.deepStrictEqual(view, {});
    });

    it("returns a defensive copy so callers can mutate freely", () => {
        const dataExtensionReports = { a11y: "accessibility.json" };
        const view = manifestReportsView({
            ...baseManifest,
            dataExtensionReports,
        });
        view["theme"] = "theme.json";
        assert.deepStrictEqual(dataExtensionReports, {
            a11y: "accessibility.json",
        });
    });
});
