import * as assert from "assert";
import { manifestReportsView, PreviewManifest } from "../types";

/**
 * Mirrors `PreviewManifestReportsViewTest` on the CLI side. The Gradle plugin emits both the v2
 * `dataExtensionReports` map and the legacy v1 `accessibilityReport` field for one release of
 * transition; this helper unifies them so every other consumer in the extension only deals with
 * the map. Tests pin all three on-disk shapes (v1-only, v2-only, both) plus the empty case.
 */
describe("manifestReportsView", () => {
    const baseManifest: PreviewManifest = {
        module: "sample",
        variant: "debug",
        previews: [],
    };

    it("returns the dataExtensionReports map verbatim when only v2 is set", () => {
        const view = manifestReportsView({
            ...baseManifest,
            dataExtensionReports: { a11y: "accessibility.json" },
        });
        assert.deepStrictEqual(view, { a11y: "accessibility.json" });
    });

    it("synthesises an a11y entry from the legacy accessibilityReport field", () => {
        // Mimics a manifest written by a pre-v2 plugin: only `accessibilityReport` populated.
        // The extension must still surface findings — `gradleService.refreshManifest` reads
        // through this view and would otherwise leave `a11yFindings` null.
        const view = manifestReportsView({
            ...baseManifest,
            accessibilityReport: "accessibility.json",
        });
        assert.deepStrictEqual(view, { a11y: "accessibility.json" });
    });

    it("returns an empty object when neither field is set", () => {
        const view = manifestReportsView(baseManifest);
        assert.deepStrictEqual(view, {});
    });

    it("returns an empty object when v2 is an empty map and v1 is absent", () => {
        const view = manifestReportsView({
            ...baseManifest,
            dataExtensionReports: {},
        });
        assert.deepStrictEqual(view, {});
    });

    it("prefers the v2 map over the legacy field when both are set", () => {
        // The Gradle plugin sets both during the v1→v2 mirror window. If they ever drift (e.g. a
        // pre-release plugin with a buggy mirror), the v2 map is the source of truth — same
        // semantics as the CLI's `PreviewManifest.reportsView` getter.
        const view = manifestReportsView({
            ...baseManifest,
            dataExtensionReports: { a11y: "v2-path.json" },
            accessibilityReport: "v1-path.json",
        });
        assert.deepStrictEqual(view, { a11y: "v2-path.json" });
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
