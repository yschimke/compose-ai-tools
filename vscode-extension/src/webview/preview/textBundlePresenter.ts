// Text / i18n bundle presenter — fills the "Text / i18n" tab body in
// `<data-tabs>` using three stacked `<data-table>` sub-sections, mirroring
// the Performance bundle's "stacked sub-sections under one expander" shape.
// Combines:
//
//   * `text/strings`       — drawn text per node (default-ON)
//   * `fonts/used`         — fonts the renderer resolved (default-ON)
//   * `i18n/translations`  — visible-string ↔ resource-id mapping
//                            (expander-only / default-OFF)
//
// The presenter is **stateless**: given the three wire payloads it
// returns the rows for each sub-table plus the overlay boxes the Text
// chip paints over the focused preview (`truncated` / `didOverflow*`
// rows light up as warnings). The caller (host shell wiring in
// `main.ts`) is responsible for re-running this whenever the focused
// preview, the cache, or the bundle's enabled-kinds set changes.
//
// See `docs/design/EXTENSION_DATA_EXPOSURE.md` § Text / i18n.

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";
import type { OverlayBox } from "./components/BoxOverlay";
import { parseBounds } from "./cardData";

// -------------------------------------------------------------------------
// Row shapes
// -------------------------------------------------------------------------

export interface DrawnTextRow {
    id: string;
    nodeId: string;
    text: string;
    localeTag: string;
    fontScale: number;
    fontSize: string;
    foreground: string | null;
    background: string | null;
    overflow: string | null;
    truncated: boolean;
    didOverflowWidth: boolean;
    didOverflowHeight: boolean;
    boundsInScreen: string;
}

export interface FontRow {
    id: string;
    requestedFamily: string;
    resolvedFamily: string;
    weight: number;
    style: string;
    sourceFile: string | null;
    fellBackFromChain: string;
    consumerCount: number;
    /**
     * True when the font is either tagged `provider="google"` by the
     * producer (schema v2+) or — when the producer hasn't populated
     * `provider` — the `requestedFamily` matches the bundled allowlist.
     * The Google Fonts cell renders as a button that posts an
     * `openExternal` message with the specimen URL.
     */
    isGoogleFont: boolean;
    /**
     * Source the renderer reported for the resolved font. Open-ended
     * string; today's known values are `"google"`, `"asset"`, `"system"`,
     * and `null` (omitted). Surfaced as a quiet chip when present so
     * the user can tell a system fallback apart from a Google match.
     */
    provider: string | null;
}

export interface TranslationRow {
    id: string;
    rendered: string;
    resourceName: string | null;
    sourceFile: string | null;
    supportedLocaleCount: number;
    untranslatedLocaleCount: number;
    untranslatedLocales: readonly string[];
    /**
     * Locales that DO have a translation — surfaced through the row's
     * `(translated)` chip tooltip. Sorted alphabetically for stable test
     * fixtures.
     */
    translatedLocales: readonly string[];
}

export interface TextBundleData {
    drawnText: readonly DrawnTextRow[];
    fonts: readonly FontRow[];
    translations: readonly TranslationRow[];
    overlay: readonly OverlayBox[];
    jsonPayload: {
        textStrings: unknown;
        fontsUsed: unknown;
        i18nTranslations: unknown;
    };
}

// -------------------------------------------------------------------------
// Bundled Google Fonts allowlist — used when the producer hasn't filled
// in `FontUsedEntry.provider`. Curated from the Google Fonts "Popularity"
// ranking; the list is intentionally small (top-N) so the bundle's JS
// size stays trivial and a missed match falls back to a plain text cell
// rather than the wrong external link. When the renderer learns to
// detect provenance (#1057 follow-up) the producer will set
// `provider = "google"` directly and this allowlist becomes a fallback.
// -------------------------------------------------------------------------

const GOOGLE_FONTS_ALLOWLIST: readonly string[] = [
    "Roboto",
    "Open Sans",
    "Noto Sans",
    "Montserrat",
    "Lato",
    "Poppins",
    "Source Sans 3",
    "Roboto Condensed",
    "Inter",
    "Material Icons",
    "Material Symbols Outlined",
    "Material Symbols Rounded",
    "Material Symbols Sharp",
    "Oswald",
    "Raleway",
    "Roboto Mono",
    "Nunito",
    "Nunito Sans",
    "Roboto Slab",
    "Ubuntu",
    "Merriweather",
    "Playfair Display",
    "PT Sans",
    "Rubik",
    "Work Sans",
    "Mukta",
    "Fira Sans",
    "Quicksand",
    "Noto Serif",
    "Hind",
    "Heebo",
    "Titillium Web",
    "Karla",
    "PT Serif",
    "Barlow",
    "Mulish",
    "Dosis",
    "Cabin",
    "DM Sans",
    "DM Serif Display",
    "Manrope",
    "Bebas Neue",
    "Anton",
    "Lora",
    "IBM Plex Sans",
    "IBM Plex Serif",
    "IBM Plex Mono",
    "Source Code Pro",
    "Fira Code",
    "JetBrains Mono",
];

const GOOGLE_FONTS_ALLOWLIST_LOWER = new Set(
    GOOGLE_FONTS_ALLOWLIST.map((f) => f.toLowerCase()),
);

/** Allowlist size exposed for tests + the cluster-report. */
export function googleFontsAllowlistSize(): number {
    return GOOGLE_FONTS_ALLOWLIST.length;
}

/**
 * True when the given font entry should render the Google Fonts
 * external-link affordance. Honours the producer's `provider` field
 * first; falls back to the bundled allowlist when it's missing.
 */
export function isGoogleFontFamily(
    requestedFamily: string,
    provider: string | null | undefined,
): boolean {
    if (provider === "google") return true;
    if (provider) return false; // explicit non-google provider wins
    return GOOGLE_FONTS_ALLOWLIST_LOWER.has(
        (requestedFamily ?? "").toLowerCase(),
    );
}

/**
 * Build the Google Fonts specimen URL for a family. Exposed for tests
 * + for the `openExternal` payload assembly.
 */
export function googleFontsSpecimenUrl(family: string): string {
    return "https://fonts.google.com/specimen/" + encodeURIComponent(family);
}

// -------------------------------------------------------------------------
// computeTextBundleData
// -------------------------------------------------------------------------

export function computeTextBundleData(
    stringsPayload: unknown,
    fontsPayload: unknown,
    translationsPayload: unknown,
): TextBundleData {
    const drawnText = parseDrawnText(stringsPayload);
    const fonts = parseFonts(fontsPayload);
    const translations = parseTranslations(translationsPayload);
    const overlay: OverlayBox[] = [];
    for (const row of drawnText) {
        const bounds = parseBounds(row.boundsInScreen);
        if (!bounds) continue;
        const flagged =
            row.truncated || row.didOverflowWidth || row.didOverflowHeight;
        overlay.push({
            id: row.id,
            bounds,
            level: flagged ? "warning" : "info",
            tooltip: tooltipFor(row),
        });
    }
    return {
        drawnText,
        fonts,
        translations,
        overlay,
        jsonPayload: {
            textStrings: stringsPayload ?? null,
            fontsUsed: fontsPayload ?? null,
            i18nTranslations: translationsPayload ?? null,
        },
    };
}

function tooltipFor(row: DrawnTextRow): string {
    const flags: string[] = [];
    if (row.truncated) flags.push("truncated");
    if (row.didOverflowWidth) flags.push("overflow-w");
    if (row.didOverflowHeight) flags.push("overflow-h");
    const head = row.text || "(empty)";
    if (flags.length === 0) return head;
    return head + " · " + flags.join(", ");
}

// -------------------------------------------------------------------------
// Column factories (consumed by the main.ts host shell)
// -------------------------------------------------------------------------

export function textBundleStringColumns(): readonly DataTableColumn<DrawnTextRow>[] {
    return [
        {
            header: "Text",
            cellClass: "text-bundle-text-cell",
            render: (row) =>
                html`<div class="text-bundle-text-stack">
                    <strong class="text-bundle-text-rendered"
                        >${row.text || "(empty)"}</strong
                    >
                    ${row.truncated ||
                    row.didOverflowWidth ||
                    row.didOverflowHeight
                        ? html`<span
                              class="text-bundle-flag"
                              data-level="warning"
                              >${overflowBadge(row)}</span
                          >`
                        : ""}
                </div>`,
        },
        {
            header: "Locale",
            cellClass: "text-bundle-locale-cell",
            render: (row) => row.localeTag || "—",
        },
        {
            header: "Scale",
            cellClass: "text-bundle-scale-cell",
            render: (row) =>
                Number.isFinite(row.fontScale) ? row.fontScale.toFixed(2) : "—",
        },
        {
            header: "Size",
            cellClass: "text-bundle-size-cell",
            render: (row) => row.fontSize || "—",
        },
        {
            header: "Fg",
            cellClass: "text-bundle-swatch-cell",
            render: (row) => swatch(row.foreground, "fg"),
        },
        {
            header: "Bg",
            cellClass: "text-bundle-swatch-cell",
            render: (row) => swatch(row.background, "bg"),
        },
        {
            header: "Overflow",
            cellClass: "text-bundle-overflow-cell",
            render: (row) => row.overflow || "—",
        },
        {
            header: "Node",
            cellClass: "text-bundle-nodeid-cell",
            render: (row) =>
                html`<code class="text-bundle-nodeid">${row.nodeId}</code>`,
        },
    ];
}

function overflowBadge(row: DrawnTextRow): string {
    const parts: string[] = [];
    if (row.truncated) parts.push("truncated");
    if (row.didOverflowWidth) parts.push("overflow-w");
    if (row.didOverflowHeight) parts.push("overflow-h");
    return parts.join(" · ");
}

function swatch(
    value: string | null,
    kind: "fg" | "bg",
): TemplateResult | string {
    if (!value) return "—";
    return html`<span
        class="text-bundle-swatch"
        data-kind=${kind}
        style="background:${value}"
        title=${kind === "fg" ? "Foreground " + value : "Background " + value}
    ></span>`;
}

export interface TextBundleFontColumnCallbacks {
    /** Webview-style external-link callback. Forwarded by main.ts so the
     *  host can post `openExternal` to the extension. */
    openExternal(url: string): void;
}

export function textBundleFontColumns(
    callbacks: TextBundleFontColumnCallbacks,
): readonly DataTableColumn<FontRow>[] {
    return [
        {
            header: "Requested",
            cellClass: "text-bundle-font-requested",
            render: (row) => requestedFamilyCell(row, callbacks),
        },
        {
            header: "Resolved",
            cellClass: "text-bundle-font-resolved",
            render: (row) =>
                html`<span
                    class="text-bundle-font-preview"
                    style="font-family: ${cssFontStack(row.resolvedFamily)}"
                    title=${row.resolvedFamily}
                    >${row.resolvedFamily}</span
                >`,
        },
        {
            header: "Weight",
            cellClass: "text-bundle-font-weight",
            render: (row) => String(row.weight),
        },
        {
            header: "Style",
            cellClass: "text-bundle-font-style",
            render: (row) => row.style || "normal",
        },
        {
            header: "Source",
            cellClass: "text-bundle-font-source",
            render: (row) => row.sourceFile || "—",
        },
        {
            header: "Fallback",
            cellClass: "text-bundle-font-fallback",
            render: (row) =>
                row.fellBackFromChain
                    ? html`<span class="text-bundle-fallback-chain"
                          >${row.fellBackFromChain}</span
                      >`
                    : "—",
        },
        {
            header: "Used by",
            cellClass: "text-bundle-font-consumers",
            render: (row) =>
                html`<span class="text-bundle-consumer-count"
                    >${row.consumerCount}</span
                >`,
        },
    ];
}

function requestedFamilyCell(
    row: FontRow,
    callbacks: TextBundleFontColumnCallbacks,
): TemplateResult {
    if (!row.isGoogleFont) {
        return html`<span class="text-bundle-font-family"
            >${row.requestedFamily}</span
        >`;
    }
    const url = googleFontsSpecimenUrl(row.requestedFamily);
    return html`<button
        type="button"
        class="text-bundle-google-link"
        title=${"Open " + row.requestedFamily + " on fonts.google.com"}
        @click=${(ev: Event) => {
            ev.preventDefault();
            ev.stopPropagation();
            callbacks.openExternal(url);
        }}
    >
        <i class="codicon codicon-link-external" aria-hidden="true"></i>
        <span>${row.requestedFamily}</span>
    </button>`;
}

/**
 * CSS `font-family` stack for the resolved-family preview cell. The
 * family may contain spaces, so we quote it and chain a couple of
 * generic fallbacks so the preview never paints in the panel's monospace
 * default when the family is unavailable to the webview.
 */
export function cssFontStack(resolved: string): string {
    const trimmed = (resolved ?? "").trim();
    if (!trimmed) return "system-ui, sans-serif";
    // Already a generic — no quoting.
    if (
        trimmed === "sans-serif" ||
        trimmed === "serif" ||
        trimmed === "monospace" ||
        trimmed === "cursive" ||
        trimmed === "fantasy"
    ) {
        return trimmed;
    }
    // CSS strings need both backslashes and double-quotes escaped —
    // a font family containing `\\` or `"` would otherwise break out
    // of the quoted literal and produce malformed CSS (CodeQL flagged
    // backslashes specifically). Backslash MUST be replaced first so
    // we don't double-escape the backslashes we introduce for `"`.
    const safe = trimmed.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
    return '"' + safe + '", system-ui, sans-serif';
}

export interface TextBundleTranslationColumnCallbacks {
    /** Open the underlying resource definition in the workspace. */
    openResource(row: TranslationRow): void;
}

export function textBundleTranslationColumns(
    callbacks: TextBundleTranslationColumnCallbacks,
): readonly DataTableColumn<TranslationRow>[] {
    return [
        {
            header: "Rendered",
            cellClass: "text-bundle-translation-rendered",
            render: (row) =>
                html`<strong class="text-bundle-translation-text"
                    >${row.rendered || "(empty)"}</strong
                >`,
        },
        {
            header: "Resource",
            cellClass: "text-bundle-translation-resource",
            render: (row) => resourceCell(row, callbacks),
        },
        {
            header: "Locales",
            cellClass: "text-bundle-translation-locales",
            render: (row) =>
                html`<span
                    class="text-bundle-locale-chip"
                    data-tone="supported"
                    title=${row.translatedLocales.length === 0
                        ? "No translations recorded"
                        : "Translated: " + row.translatedLocales.join(", ")}
                    >${row.supportedLocaleCount}</span
                >`,
        },
        {
            header: "Untranslated",
            cellClass: "text-bundle-translation-untranslated",
            render: (row) =>
                row.untranslatedLocaleCount === 0
                    ? html`<span
                          class="text-bundle-locale-chip"
                          data-tone="ok"
                          title="Fully translated"
                          >0</span
                      >`
                    : html`<span
                          class="text-bundle-locale-chip"
                          data-tone="warning"
                          title=${"Missing: " +
                          row.untranslatedLocales.join(", ")}
                          >${row.untranslatedLocaleCount}</span
                      >`,
        },
    ];
}

function resourceCell(
    row: TranslationRow,
    callbacks: TextBundleTranslationColumnCallbacks,
): TemplateResult | string {
    if (!row.resourceName) return "—";
    const canOpen = !!row.sourceFile;
    if (!canOpen) {
        return html`<span class="text-bundle-translation-resource-name"
            >${row.resourceName}</span
        >`;
    }
    return html`<button
        type="button"
        class="text-bundle-resource-link"
        title=${"Open " + row.sourceFile}
        @click=${(ev: Event) => {
            ev.preventDefault();
            ev.stopPropagation();
            callbacks.openResource(row);
        }}
    >
        <i class="codicon codicon-go-to-file" aria-hidden="true"></i>
        <span>${row.resourceName}</span>
    </button>`;
}

// -------------------------------------------------------------------------
// Parsers — defensive `typeof` / `Array.isArray` checks so a daemon
// schema drift drops the offending row rather than NaN-ing the body.
// -------------------------------------------------------------------------

function parseDrawnText(raw: unknown): DrawnTextRow[] {
    if (!raw || typeof raw !== "object") return [];
    const payload = raw as { texts?: unknown };
    const rawTexts = Array.isArray(payload.texts) ? payload.texts : [];
    const rows: DrawnTextRow[] = [];
    rawTexts.forEach((t, idx) => {
        if (!t || typeof t !== "object") return;
        const e = t as {
            text?: unknown;
            semanticsText?: unknown;
            semanticsLabel?: unknown;
            inputText?: unknown;
            editableText?: unknown;
            localeTag?: unknown;
            fontScale?: unknown;
            fontSize?: unknown;
            foregroundColor?: unknown;
            backgroundColor?: unknown;
            overflow?: unknown;
            truncated?: unknown;
            didOverflowWidth?: unknown;
            didOverflowHeight?: unknown;
            nodeId?: unknown;
            boundsInScreen?: unknown;
        };
        // Prefer the rendered `text` first; fall back to semantics /
        // input text when the daemon couldn't extract drawn glyphs
        // (rare, but seen on `BasicTextField`). Stringly-typed so an
        // unexpected non-string value gets treated as missing.
        const text =
            firstString([
                e.text,
                e.semanticsText,
                e.semanticsLabel,
                e.editableText,
                e.inputText,
            ]) ?? "";
        const nodeId = typeof e.nodeId === "string" ? e.nodeId : "";
        if (!nodeId) return;
        const boundsInScreen =
            typeof e.boundsInScreen === "string" ? e.boundsInScreen : "";
        rows.push({
            id: "text-string-" + idx,
            nodeId,
            text,
            localeTag: typeof e.localeTag === "string" ? e.localeTag : "",
            fontScale:
                typeof e.fontScale === "number" && Number.isFinite(e.fontScale)
                    ? e.fontScale
                    : 1,
            fontSize: typeof e.fontSize === "string" ? e.fontSize : "",
            foreground:
                typeof e.foregroundColor === "string"
                    ? e.foregroundColor
                    : null,
            background:
                typeof e.backgroundColor === "string"
                    ? e.backgroundColor
                    : null,
            overflow: typeof e.overflow === "string" ? e.overflow : null,
            truncated: e.truncated === true,
            didOverflowWidth: e.didOverflowWidth === true,
            didOverflowHeight: e.didOverflowHeight === true,
            boundsInScreen,
        });
    });
    return rows;
}

function firstString(values: readonly unknown[]): string | null {
    for (const v of values) {
        if (typeof v === "string" && v.length > 0) return v;
    }
    return null;
}

function parseFonts(raw: unknown): FontRow[] {
    if (!raw || typeof raw !== "object") return [];
    const payload = raw as { fonts?: unknown };
    const rawFonts = Array.isArray(payload.fonts) ? payload.fonts : [];
    const rows: FontRow[] = [];
    rawFonts.forEach((f, idx) => {
        if (!f || typeof f !== "object") return;
        const e = f as {
            requestedFamily?: unknown;
            resolvedFamily?: unknown;
            weight?: unknown;
            style?: unknown;
            sourceFile?: unknown;
            fellBackFrom?: unknown;
            consumerNodeIds?: unknown;
            provider?: unknown;
        };
        const requestedFamily =
            typeof e.requestedFamily === "string" ? e.requestedFamily : "";
        if (!requestedFamily) return;
        const resolvedFamily =
            typeof e.resolvedFamily === "string"
                ? e.resolvedFamily
                : requestedFamily;
        const provider = typeof e.provider === "string" ? e.provider : null;
        const fellBack = Array.isArray(e.fellBackFrom)
            ? (e.fellBackFrom.filter((x) => typeof x === "string") as string[])
            : [];
        const consumers = Array.isArray(e.consumerNodeIds)
            ? e.consumerNodeIds.filter((x) => typeof x === "string").length
            : 0;
        rows.push({
            id: "font-" + idx,
            requestedFamily,
            resolvedFamily,
            weight:
                typeof e.weight === "number" && Number.isFinite(e.weight)
                    ? e.weight
                    : 400,
            style: typeof e.style === "string" ? e.style : "normal",
            sourceFile: typeof e.sourceFile === "string" ? e.sourceFile : null,
            fellBackFromChain: fellBack.join(" → "),
            consumerCount: consumers,
            isGoogleFont: isGoogleFontFamily(requestedFamily, provider),
            provider,
        });
    });
    return rows;
}

function parseTranslations(raw: unknown): TranslationRow[] {
    if (!raw || typeof raw !== "object") return [];
    const payload = raw as {
        strings?: unknown;
        supportedLocales?: unknown;
    };
    const rawSupported = Array.isArray(payload.supportedLocales)
        ? (payload.supportedLocales.filter(
              (x) => typeof x === "string",
          ) as string[])
        : [];
    const rawStrings = Array.isArray(payload.strings) ? payload.strings : [];
    const rows: TranslationRow[] = [];
    rawStrings.forEach((s, idx) => {
        if (!s || typeof s !== "object") return;
        const e = s as {
            rendered?: unknown;
            resourceName?: unknown;
            sourceFile?: unknown;
            translations?: unknown;
            untranslatedLocales?: unknown;
        };
        const rendered = typeof e.rendered === "string" ? e.rendered : "";
        const resourceName =
            typeof e.resourceName === "string" ? e.resourceName : null;
        const sourceFile =
            typeof e.sourceFile === "string" ? e.sourceFile : null;
        const translations =
            e.translations && typeof e.translations === "object"
                ? (e.translations as Record<string, unknown>)
                : {};
        const translatedLocales = Object.keys(translations)
            .filter((k) => typeof translations[k] === "string")
            .sort();
        const untranslated = Array.isArray(e.untranslatedLocales)
            ? (e.untranslatedLocales.filter(
                  (x) => typeof x === "string",
              ) as string[])
            : [];
        const supportedCount =
            rawSupported.length > 0
                ? rawSupported.length
                : translatedLocales.length + untranslated.length;
        rows.push({
            id: "i18n-" + idx,
            rendered,
            resourceName,
            sourceFile,
            supportedLocaleCount: supportedCount,
            untranslatedLocaleCount: untranslated.length,
            untranslatedLocales: untranslated,
            translatedLocales,
        });
    });
    return rows;
}

/**
 * Convenience for main.ts — assemble the `openResourceFile` wire payload
 * for a translation row. Centralised here so the host shell wiring
 * stays a one-liner.
 */
export function translationOpenResourcePayload(row: TranslationRow): {
    command: "openResourceFile";
    resourceType: "string";
    resourceName: string;
    resolvedFile: string | null;
    packageName: null;
} {
    // `i18n/translations` ships names as `R.string.<name>`, but the
    // extension-side resolver matches `<string name="…">` entries by
    // bare name. Strip the `R.string.` prefix so the jump-to-resource
    // action finds the entry; otherwise the resolver scans for
    // `<string name="R.string.sign_in">` and silently no-ops.
    const raw = row.resourceName ?? "";
    const bareName = raw.startsWith("R.string.")
        ? raw.slice("R.string.".length)
        : raw;
    return {
        command: "openResourceFile",
        resourceType: "string",
        resourceName: bareName,
        resolvedFile: row.sourceFile,
        packageName: null,
    };
}
