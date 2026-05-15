// Build `<bundle-row-detail>` sections for the Text / i18n bundle.
// One pure helper per row shape (`DrawnTextRow`, `FontRow`,
// `TranslationRow`); the host wires each table's `row-clicked` event
// to the matching helper and pipes the result through
// `BundleRowDetail.setDetail`.

import { html } from "lit";
import type {
    DrawnTextRow,
    FontRow,
    TranslationRow,
} from "./textBundlePresenter";
import type { BundleRowDetailSection } from "./components/BundleRowDetail";

export function buildDrawnTextRowDetail(
    row: DrawnTextRow,
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    // ---- Text ---------------------------------------------------------
    sections.push({
        heading: "Text",
        entries: [
            { label: "Rendered", value: row.text || "—" },
            { label: "Node id", value: html`<code>${row.nodeId}</code>` },
            { label: "Locale", value: row.localeTag || "—" },
            {
                label: "Bounds",
                value: row.boundsInScreen
                    ? html`<code>${row.boundsInScreen}</code>`
                    : "—",
            },
        ],
    });

    // ---- Style --------------------------------------------------------
    const styleEntries: { label: string; value: string }[] = [];
    if (row.fontSize)
        styleEntries.push({ label: "Font size", value: row.fontSize });
    if (Number.isFinite(row.fontScale) && row.fontScale !== 1) {
        styleEntries.push({
            label: "Font scale",
            value: row.fontScale.toString() + "×",
        });
    }
    if (row.foreground !== null) {
        styleEntries.push({ label: "Foreground", value: row.foreground });
    }
    if (row.background !== null) {
        styleEntries.push({ label: "Background", value: row.background });
    }
    if (styleEntries.length > 0) {
        sections.push({ heading: "Style", entries: styleEntries });
    }

    // ---- Layout / overflow -------------------------------------------
    const layoutEntries: { label: string; value: string }[] = [];
    if (row.overflow) {
        layoutEntries.push({ label: "Overflow mode", value: row.overflow });
    }
    if (row.truncated) {
        layoutEntries.push({ label: "Truncated", value: "yes" });
    }
    if (row.didOverflowWidth) {
        layoutEntries.push({ label: "Overflowed width", value: "yes" });
    }
    if (row.didOverflowHeight) {
        layoutEntries.push({ label: "Overflowed height", value: "yes" });
    }
    if (layoutEntries.length > 0) {
        sections.push({ heading: "Layout", entries: layoutEntries });
    }

    return sections;
}

export function buildFontRowDetail(
    row: FontRow,
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    // ---- Family -------------------------------------------------------
    const familyEntries: { label: string; value: string }[] = [
        { label: "Requested", value: row.requestedFamily },
        { label: "Resolved", value: row.resolvedFamily },
    ];
    if (row.resolvedFamily !== row.requestedFamily) {
        familyEntries.push({
            label: "Fallback",
            value:
                row.fellBackFromChain ||
                "(no chain reported; resolved family differs from request)",
        });
    } else if (row.fellBackFromChain) {
        familyEntries.push({
            label: "Fallback chain",
            value: row.fellBackFromChain,
        });
    }
    sections.push({ heading: "Family", entries: familyEntries });

    // ---- Variant ------------------------------------------------------
    sections.push({
        heading: "Variant",
        entries: [
            { label: "Weight", value: row.weight.toString() },
            { label: "Style", value: row.style },
        ],
    });

    // ---- Source -------------------------------------------------------
    const sourceEntries: {
        label: string;
        value: string | import("lit").TemplateResult;
    }[] = [];
    if (row.sourceFile) {
        sourceEntries.push({
            label: "Source file",
            value: html`<code>${row.sourceFile}</code>`,
        });
    }
    sourceEntries.push({
        label: "Google Fonts",
        value: row.isGoogleFont ? "yes" : "no",
    });
    sourceEntries.push({
        label: "Used by",
        value:
            row.consumerCount === 1 ? "1 node" : row.consumerCount + " nodes",
    });
    sections.push({ heading: "Source", entries: sourceEntries });

    return sections;
}

export function buildTranslationRowDetail(
    row: TranslationRow,
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    // ---- Entry --------------------------------------------------------
    const entryEntries: {
        label: string;
        value: string | import("lit").TemplateResult;
    }[] = [{ label: "Rendered", value: row.rendered || "—" }];
    if (row.resourceName) {
        entryEntries.push({
            label: "Resource",
            value: html`<code>${row.resourceName}</code>`,
        });
    }
    if (row.sourceFile) {
        entryEntries.push({
            label: "Source file",
            value: html`<code>${row.sourceFile}</code>`,
        });
    }
    sections.push({ heading: "Entry", entries: entryEntries });

    // ---- Locales ------------------------------------------------------
    const localeEntries: { label: string; value: string }[] = [
        {
            label: "Supported",
            value: row.supportedLocaleCount.toString(),
        },
    ];
    if (row.translatedLocales.length > 0) {
        localeEntries.push({
            label: "Translated",
            value: row.translatedLocales.join(", "),
        });
    }
    if (row.untranslatedLocaleCount > 0) {
        localeEntries.push({
            label: "Untranslated",
            value:
                row.untranslatedLocales.length > 0
                    ? row.untranslatedLocales.join(", ")
                    : row.untranslatedLocaleCount.toString(),
        });
    }
    sections.push({ heading: "Locales", entries: localeEntries });

    return sections;
}
