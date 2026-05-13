// Theming bundle presenter — fills the "Theming" tab body in
// `<data-tabs>` using the shared `<data-table>` primitive. Combines
// `compose/theme` (resolved Material 3 tokens grouped into Colors /
// Typography / Shapes) with `compose/wallpaper` (seed colour + derived
// scheme prepended to the Colors section).
//
// The presenter is **stateless** — given the latest payloads from
// `dataProductsByPreview` for the focused preview, it produces table
// rows. Caller wiring in `main.ts` is responsible for re-running this
// whenever the focused preview, the bundle's active set, or an
// incoming `compose/theme` / `compose/wallpaper` payload changes.
//
// Theme tokens are global, so this presenter emits NO overlay boxes —
// `<box-overlay>` only paints rows with `boxes`, so a stable per-row
// id is enough to keep `setOverlayId` happy for the shared primitive.

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";

/** Wire shape for one Material 3 typography token. Mirrors
 *  `TypographyToken` in `Material3ThemeModels.kt`. */
export interface TypographyToken {
    fontFamily?: string | null;
    fontSize?: number | null;
    fontSizeUnit?: string | null;
    fontWeight?: string | null;
    fontStyle?: string | null;
    lineHeight?: number | null;
    lineHeightUnit?: string | null;
    letterSpacing?: number | null;
    letterSpacingUnit?: string | null;
}

/** Wire shape for one consumer node referencing one or more theme
 *  tokens. Mirrors `ThemeConsumer` in `Material3ThemeModels.kt`. */
export interface ThemeConsumer {
    nodeId: string;
    tokens: string[];
}

/** Wire shape for the resolved-tokens map. Mirrors
 *  `ResolvedThemeTokens` in `Material3ThemeModels.kt`. */
export interface ResolvedThemeTokens {
    colorScheme: Record<string, string>;
    typography: Record<string, TypographyToken>;
    shapes: Record<string, string>;
}

/** Wire shape returned by `data/fetch?kind=compose/theme`. Mirrors
 *  `ThemePayload` in `Material3ThemeModels.kt`. */
export interface ThemePayload {
    resolvedTokens: ResolvedThemeTokens;
    consumers?: ThemeConsumer[];
}

/** Wire shape returned by `data/fetch?kind=compose/wallpaper`.
 *  Mirrors `WallpaperPayload` in `WallpaperModels.kt`. */
export interface WallpaperPayload {
    seedColor: string;
    isDark: boolean;
    paletteStyle?: string;
    contrastLevel?: number;
    derivedColorScheme: Record<string, string>;
}

/** Discriminator for the table sections. Each row knows which section
 *  heading it belongs under; the `<data-table>` primitive renders it
 *  via the `section` column. */
export type ThemingSection = "Colors" | "Typography" | "Shapes";

/** Source of a Color row — used to badge wallpaper-derived rows
 *  separately from the theme's own `colorScheme`. */
export type ThemingColorSource = "theme" | "wallpaper" | "seed";

/** Row union — Colors / Typography / Shapes share a single `<data-table>`
 *  so the column factory can render mixed sections without juggling
 *  three tables. Each variant tags itself with `kind` for the renderers. */
export type ThemingRow =
    | ThemingColorRow
    | ThemingTypographyRow
    | ThemingShapeRow
    | ThemingSeedRow;

export interface ThemingColorRow {
    id: string;
    section: "Colors";
    kind: "color";
    /** Token name (e.g. "primary", "onSurface"). */
    name: string;
    /** Hex string as published on the wire — typically `#AARRGGBB`. */
    hex: string;
    /** CSS-safe background colour derived from `hex`. */
    swatchCss: string;
    /** Number of `consumers[].nodeId` referencing this token; 0 when
     *  the wallpaper-derived scheme has no consumer data. */
    consumerCount: number;
    /** Provenance of this row — `theme` from `compose/theme`,
     *  `wallpaper` from the derived scheme. */
    source: ThemingColorSource;
}

export interface ThemingTypographyRow {
    id: string;
    section: "Typography";
    kind: "typography";
    name: string;
    family: string;
    size: string;
    weight: string;
    style: string;
    lineHeight: string;
    letterSpacing: string;
}

export interface ThemingShapeRow {
    id: string;
    section: "Shapes";
    kind: "shape";
    name: string;
    value: string;
}

/** Seed-color summary row prepended to Colors when wallpaper is
 *  present. Carries the scalar wallpaper inputs (isDark / palette /
 *  contrast) so callers don't have to dig them out of the JSON copy. */
export interface ThemingSeedRow {
    id: string;
    section: "Colors";
    kind: "seed";
    name: string;
    hex: string;
    swatchCss: string;
    isDark: boolean;
    paletteStyle: string;
    contrastLevel: number;
}

export interface ThemingBundleData {
    rows: readonly ThemingRow[];
    jsonPayload: {
        previewId: string | null;
        theme: ThemePayload | null;
        wallpaper: WallpaperPayload | null;
    };
}

/**
 * Build the row + copy-JSON shape for the Theming bundle tab.
 *
 * Either payload may be null. When `compose/theme` is null but
 * wallpaper is present, the derived scheme still surfaces under
 * Colors (with a Seed row prepended) so the user gets feedback that
 * the wallpaper override is live. When both are null the result has
 * an empty `rows` array — `<data-table>` paints its empty-state
 * placeholder in that case.
 */
export function computeThemingBundleData(
    theme: ThemePayload | null | undefined,
    wallpaper: WallpaperPayload | null | undefined,
    previewId: string | null = null,
): ThemingBundleData {
    const rows: ThemingRow[] = [];
    const consumerCount = countConsumersByToken(theme?.consumers ?? []);

    // Seed + wallpaper-derived colours go first so the user reads top
    // to bottom: "this seed produced this scheme, layered into these
    // theme tokens." When wallpaper is absent we skip both rows.
    if (wallpaper) {
        rows.push({
            id: "theming-seed",
            section: "Colors",
            kind: "seed",
            name: "Wallpaper seed",
            hex: wallpaper.seedColor,
            swatchCss: cssColor(wallpaper.seedColor),
            isDark: wallpaper.isDark,
            paletteStyle: wallpaper.paletteStyle ?? "TONAL_SPOT",
            contrastLevel:
                typeof wallpaper.contrastLevel === "number"
                    ? wallpaper.contrastLevel
                    : 0,
        });
        const derived = wallpaper.derivedColorScheme ?? {};
        for (const name of Object.keys(derived).sort()) {
            const hex = derived[name];
            rows.push({
                id: "theming-wallpaper-color-" + name,
                section: "Colors",
                kind: "color",
                name,
                hex,
                swatchCss: cssColor(hex),
                consumerCount: 0,
                source: "wallpaper",
            });
        }
    }

    // Theme `colorScheme` — one row per token. Sorted to keep the
    // table stable across reorderings inside the wire payload (the
    // Map serialisation order isn't load-bearing).
    const scheme = theme?.resolvedTokens?.colorScheme ?? {};
    for (const name of Object.keys(scheme).sort()) {
        const hex = scheme[name];
        rows.push({
            id: "theming-color-" + name,
            section: "Colors",
            kind: "color",
            name,
            hex,
            swatchCss: cssColor(hex),
            consumerCount: consumerCount.get(name) ?? 0,
            source: "theme",
        });
    }

    const typography = theme?.resolvedTokens?.typography ?? {};
    for (const name of Object.keys(typography).sort()) {
        const tok = typography[name];
        rows.push({
            id: "theming-typography-" + name,
            section: "Typography",
            kind: "typography",
            name,
            family: tok.fontFamily ?? "—",
            size: formatScalar(tok.fontSize, tok.fontSizeUnit),
            weight: tok.fontWeight ?? "—",
            style: tok.fontStyle ?? "—",
            lineHeight: formatScalar(tok.lineHeight, tok.lineHeightUnit),
            letterSpacing: formatScalar(
                tok.letterSpacing,
                tok.letterSpacingUnit,
            ),
        });
    }

    const shapes = theme?.resolvedTokens?.shapes ?? {};
    for (const name of Object.keys(shapes).sort()) {
        rows.push({
            id: "theming-shape-" + name,
            section: "Shapes",
            kind: "shape",
            name,
            value: shapes[name],
        });
    }

    return {
        rows,
        jsonPayload: {
            previewId,
            theme: theme ?? null,
            wallpaper: wallpaper ?? null,
        },
    };
}

/**
 * Column definitions for the Theming bundle table. Cells switch on
 * the row's `kind` tag so a single `<data-table>` can paint Colors,
 * Typography, Shapes, and the seed summary without three separate
 * tables (the design doc treats the sections as a single tab body
 * with sub-headings).
 */
export function themingTableColumns(): readonly DataTableColumn<ThemingRow>[] {
    return [
        {
            header: "Section",
            cellClass: "theming-section-cell",
            render: (row) => sectionLabel(row),
        },
        {
            header: "",
            cellClass: "theming-swatch-cell",
            render: (row) => renderSwatch(row),
        },
        {
            header: "Name",
            cellClass: "theming-name-cell",
            render: (row) => renderName(row),
        },
        {
            header: "Value",
            cellClass: "theming-value-cell",
            render: (row) => renderValue(row),
        },
        {
            header: "Consumers",
            cellClass: "theming-consumers-cell",
            render: (row) => renderConsumers(row),
        },
    ];
}

function sectionLabel(row: ThemingRow): string {
    if (row.kind === "seed") return "Seed";
    return row.section;
}

function renderSwatch(row: ThemingRow): TemplateResult | string {
    if (row.kind === "color" || row.kind === "seed") {
        return html`<span
            class="theming-swatch"
            data-source=${row.kind === "seed"
                ? "seed"
                : (row as ThemingColorRow).source}
            style=${"background-color: " + row.swatchCss}
            title=${row.hex}
        ></span>`;
    }
    return "";
}

function renderName(row: ThemingRow): TemplateResult {
    if (row.kind === "seed") {
        return html`<div class="theming-name-stack">
            <strong>${row.name}</strong>
            <span class="theming-name-sub"
                >${row.isDark ? "dark" : "light"} ·
                ${row.paletteStyle.toLowerCase()} · contrast
                ${formatContrast(row.contrastLevel)}</span
            >
        </div>`;
    }
    if (row.kind === "color") {
        return html`<div class="theming-name-stack">
            <strong>${row.name}</strong>
            ${row.source === "wallpaper"
                ? html`<span class="theming-name-sub">from wallpaper</span>`
                : ""}
        </div>`;
    }
    return html`<strong>${row.name}</strong>`;
}

function renderValue(row: ThemingRow): TemplateResult | string {
    if (row.kind === "color" || row.kind === "seed") {
        return html`<code class="theming-hex">${row.hex}</code>`;
    }
    if (row.kind === "shape") {
        return row.value || "—";
    }
    // Typography: stack the scalar attributes so the column doesn't
    // sprawl. Tooltip shows the same info for narrow viewports.
    return html`<div class="theming-typography-grid">
        <span><em>family</em> ${row.family}</span>
        <span><em>size</em> ${row.size}</span>
        <span><em>weight</em> ${row.weight}</span>
        <span><em>style</em> ${row.style}</span>
        <span><em>line</em> ${row.lineHeight}</span>
        <span><em>letter</em> ${row.letterSpacing}</span>
    </div>`;
}

function renderConsumers(row: ThemingRow): string {
    if (row.kind !== "color") return "—";
    if (row.consumerCount === 0) return "—";
    return String(row.consumerCount);
}

function countConsumersByToken(
    consumers: readonly ThemeConsumer[],
): Map<string, number> {
    const counts = new Map<string, number>();
    for (const c of consumers) {
        for (const t of c.tokens ?? []) {
            counts.set(t, (counts.get(t) ?? 0) + 1);
        }
    }
    return counts;
}

function formatScalar(
    value: number | null | undefined,
    unit: string | null | undefined,
): string {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return "—";
    }
    // Strip trailing zeros so 16.0 reads as "16" but 16.5 stays
    // intact. Keeps the typography column scannable.
    const trimmed = Number.isInteger(value)
        ? value.toString()
        : value.toFixed(2).replace(/\.?0+$/, "");
    return unit ? trimmed + unit.toLowerCase() : trimmed;
}

function formatContrast(level: number): string {
    if (Number.isInteger(level)) return level.toFixed(1);
    return level.toFixed(2);
}

/**
 * Coerce a `#AARRGGBB` wire colour into a CSS-safe `rgba(...)` so
 * the swatch background renders identically regardless of whether
 * the browser parses ARGB as `#AARRGGBB` (it doesn't — CSS expects
 * `#RRGGBBAA`). Also tolerates `#RRGGBB` and `#RGB` shorthand for
 * forward compatibility with future payload shapes.
 */
function cssColor(hex: string): string {
    if (!hex || typeof hex !== "string") return "transparent";
    const s = hex.trim();
    if (!s.startsWith("#")) return s;
    const body = s.slice(1);
    if (body.length === 8) {
        // #AARRGGBB (Compose / Android convention).
        const a = parseInt(body.slice(0, 2), 16);
        const r = parseInt(body.slice(2, 4), 16);
        const g = parseInt(body.slice(4, 6), 16);
        const b = parseInt(body.slice(6, 8), 16);
        if ([a, r, g, b].some(Number.isNaN)) return s;
        return `rgba(${r}, ${g}, ${b}, ${(a / 255).toFixed(3)})`;
    }
    if (body.length === 6) {
        return s;
    }
    if (body.length === 3) {
        return s;
    }
    return s;
}
