// Tests for the Text / i18n bundle row-detail helpers. Each helper
// is a pure function on the row shape — no DOM needed — so the
// assertions match against the produced `BundleRowDetailSection[]`
// directly.

import * as assert from "assert";
import {
    buildDrawnTextRowDetail,
    buildFontRowDetail,
    buildTranslationRowDetail,
} from "../webview/preview/textRowDetail";
import type {
    DrawnTextRow,
    FontRow,
    TranslationRow,
} from "../webview/preview/textBundlePresenter";

function drawnText(over: Partial<DrawnTextRow>): DrawnTextRow {
    return {
        id: over.id ?? "text-0",
        nodeId: over.nodeId ?? "node-1",
        text: over.text ?? "Welcome",
        localeTag: over.localeTag ?? "en-US",
        fontScale: over.fontScale ?? 1,
        fontSize: over.fontSize ?? "16.0sp",
        foreground: over.foreground ?? "#FF111111",
        background: over.background ?? "#FFFFFFFF",
        overflow: over.overflow ?? null,
        truncated: over.truncated ?? false,
        didOverflowWidth: over.didOverflowWidth ?? false,
        didOverflowHeight: over.didOverflowHeight ?? false,
        boundsInScreen: over.boundsInScreen ?? "0,0,100,40",
    };
}

function font(over: Partial<FontRow>): FontRow {
    return {
        id: over.id ?? "font-0",
        requestedFamily: over.requestedFamily ?? "Inter",
        resolvedFamily: over.resolvedFamily ?? "Inter",
        weight: over.weight ?? 400,
        style: over.style ?? "normal",
        sourceFile: over.sourceFile ?? "fonts/Inter-Regular.ttf",
        fellBackFromChain: over.fellBackFromChain ?? "",
        consumerCount: over.consumerCount ?? 1,
        isGoogleFont: over.isGoogleFont ?? false,
    };
}

function translation(over: Partial<TranslationRow>): TranslationRow {
    return {
        id: over.id ?? "tx-0",
        rendered: over.rendered ?? "Welcome",
        resourceName: over.resourceName ?? "app_welcome",
        sourceFile: over.sourceFile ?? "res/values/strings.xml",
        supportedLocaleCount: over.supportedLocaleCount ?? 3,
        untranslatedLocaleCount: over.untranslatedLocaleCount ?? 0,
        untranslatedLocales: over.untranslatedLocales ?? [],
        translatedLocales: over.translatedLocales ?? [
            "en-US",
            "es-ES",
            "ja-JP",
        ],
    };
}

describe("buildDrawnTextRowDetail", () => {
    it("always emits a Text section with the rendered string + node id + locale + bounds", () => {
        const sections = buildDrawnTextRowDetail(
            drawnText({ text: "Hi", nodeId: "n7", localeTag: "fr-FR" }),
        );
        const text = sections.find((s) => s.heading === "Text");
        assert.ok(text);
        const labels = text!.entries.map((e) => e.label);
        assert.ok(labels.includes("Rendered"));
        assert.ok(labels.includes("Node id"));
        assert.ok(labels.includes("Locale"));
        assert.ok(labels.includes("Bounds"));
    });

    it("emits a Style section only when at least one style field is non-default", () => {
        const sections = buildDrawnTextRowDetail(
            drawnText({ fontSize: "24.0sp", foreground: "#FF000000" }),
        );
        assert.ok(sections.find((s) => s.heading === "Style"));
    });

    it("omits the Layout section when no overflow / truncation flag is set", () => {
        const sections = buildDrawnTextRowDetail(drawnText({}));
        assert.strictEqual(
            sections.find((s) => s.heading === "Layout"),
            undefined,
        );
    });

    it("emits a Layout section listing each tripped flag", () => {
        const sections = buildDrawnTextRowDetail(
            drawnText({
                truncated: true,
                didOverflowWidth: true,
                overflow: "ellipsis",
            }),
        );
        const layout = sections.find((s) => s.heading === "Layout");
        assert.ok(layout);
        const labels = layout!.entries.map((e) => e.label);
        assert.ok(labels.includes("Truncated"));
        assert.ok(labels.includes("Overflowed width"));
        assert.ok(labels.includes("Overflow mode"));
    });

    it("includes Font scale only when it differs from 1.0×", () => {
        const noScale = buildDrawnTextRowDetail(drawnText({ fontScale: 1 }));
        assert.ok(
            !noScale
                .find((s) => s.heading === "Style")!
                .entries.some((e) => e.label === "Font scale"),
        );
        const scaled = buildDrawnTextRowDetail(drawnText({ fontScale: 1.3 }));
        assert.ok(
            scaled
                .find((s) => s.heading === "Style")!
                .entries.some((e) => e.label === "Font scale"),
        );
    });
});

describe("buildFontRowDetail", () => {
    it("emits Family / Variant / Source sections in that order", () => {
        const sections = buildFontRowDetail(font({}));
        const headings = sections.map((s) => s.heading);
        assert.deepStrictEqual(headings, ["Family", "Variant", "Source"]);
    });

    it("adds a Fallback entry when the resolved family differs from the request", () => {
        const sections = buildFontRowDetail(
            font({
                requestedFamily: "MissingFont",
                resolvedFamily: "Roboto",
                fellBackFromChain: "MissingFont → Roboto",
            }),
        );
        const family = sections.find((s) => s.heading === "Family")!;
        const fallback = family.entries.find((e) => e.label === "Fallback");
        assert.ok(fallback);
        assert.ok(String(fallback!.value).includes("MissingFont"));
    });

    it("pluralises the consumer count", () => {
        const one = buildFontRowDetail(font({ consumerCount: 1 }));
        const two = buildFontRowDetail(font({ consumerCount: 4 }));
        const oneVal = one
            .find((s) => s.heading === "Source")!
            .entries.find((e) => e.label === "Used by")!.value;
        const twoVal = two
            .find((s) => s.heading === "Source")!
            .entries.find((e) => e.label === "Used by")!.value;
        assert.strictEqual(oneVal, "1 node");
        assert.strictEqual(twoVal, "4 nodes");
    });

    it("flags Google Fonts membership", () => {
        const sections = buildFontRowDetail(font({ isGoogleFont: true }));
        const gf = sections
            .find((s) => s.heading === "Source")!
            .entries.find((e) => e.label === "Google Fonts");
        assert.strictEqual(gf?.value, "yes");
    });
});

describe("buildTranslationRowDetail", () => {
    it("emits Entry / Locales sections in that order", () => {
        const sections = buildTranslationRowDetail(translation({}));
        const headings = sections.map((s) => s.heading);
        assert.deepStrictEqual(headings, ["Entry", "Locales"]);
    });

    it("lists translated locales joined when present", () => {
        const sections = buildTranslationRowDetail(
            translation({
                translatedLocales: ["en-US", "fr-FR"],
            }),
        );
        const translated = sections
            .find((s) => s.heading === "Locales")!
            .entries.find((e) => e.label === "Translated");
        assert.strictEqual(translated?.value, "en-US, fr-FR");
    });

    it("falls back to the untranslated count when the list is empty", () => {
        const sections = buildTranslationRowDetail(
            translation({
                untranslatedLocaleCount: 5,
                untranslatedLocales: [],
            }),
        );
        const untranslated = sections
            .find((s) => s.heading === "Locales")!
            .entries.find((e) => e.label === "Untranslated");
        assert.strictEqual(untranslated?.value, "5");
    });

    it("includes a Source file row when the payload supplies one", () => {
        const sections = buildTranslationRowDetail(
            translation({ sourceFile: "res/values/strings.xml" }),
        );
        const sourceFile = sections
            .find((s) => s.heading === "Entry")!
            .entries.find((e) => e.label === "Source file");
        assert.ok(sourceFile);
    });
});
