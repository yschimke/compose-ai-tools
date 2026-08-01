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
import { ref } from "lit/directives/ref.js";
import type { DataTableColumn } from "./components/DataTable";
import type { OverlayBox } from "./components/BoxOverlay";
import { parseBounds } from "./cardData";

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
    ThemingColorRow | ThemingTypographyRow | ThemingShapeRow | ThemingSeedRow;

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
    /** Per-row consumer node ids referencing this token, sourced from
     *  `theme.consumers[].nodeId` whose `tokens` array contains the
     *  row's `name`. Empty for `wallpaper`-sourced rows (the daemon
     *  doesn't ship per-derived-token consumers) and when the daemon
     *  hasn't attached `consumers`. The host uses this to paint a
     *  transient overlay on the focused card when the row is
     *  hovered — bounds join via `compose/semantics`. */
    consumerNodeIds: readonly string[];
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
    /** Per-row consumer node ids — same shape and role as
     *  `ThemingColorRow.consumerNodeIds`. */
    consumerNodeIds: readonly string[];
    /** CSS `font-family` stack derived from the daemon's `FontFamily.X`
     *  toString. Used by the inline sample so the user sees the actual
     *  glyph shape rather than just the token name. */
    cssFontFamily: string;
    /** Numeric CSS `font-weight` extracted from `FontWeight(weight=N)`,
     *  or null when the daemon couldn't resolve one. */
    cssFontWeight: number | null;
    /** `"italic"` when the resolved style was Italic; `"normal"`
     *  otherwise. */
    cssFontStyle: "italic" | "normal";
}

export interface ThemingShapeRow {
    id: string;
    section: "Shapes";
    kind: "shape";
    name: string;
    value: string;
    /** Per-row consumer node ids — same shape and role as
     *  `ThemingColorRow.consumerNodeIds`. */
    consumerNodeIds: readonly string[];
    /** CSS `border-radius` string derived from the shape value so the
     *  preview swatch paints the actual corner geometry. `null` when
     *  the shape couldn't be parsed (e.g. CutCornerShape, custom). */
    previewBorderRadius: string | null;
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
    const consumersByToken = indexConsumersByToken(theme?.consumers ?? []);

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
                consumerNodeIds: [],
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
        const ids = consumersByToken.get(name) ?? [];
        rows.push({
            id: "theming-color-" + name,
            section: "Colors",
            kind: "color",
            name,
            hex,
            swatchCss: cssColor(hex),
            consumerCount: ids.length,
            consumerNodeIds: ids,
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
            consumerNodeIds: consumersByToken.get(name) ?? [],
            cssFontFamily: cssFontFamily(tok.fontFamily),
            cssFontWeight: parseFontWeight(tok.fontWeight),
            cssFontStyle:
                typeof tok.fontStyle === "string" &&
                /italic/i.test(tok.fontStyle)
                    ? "italic"
                    : "normal",
        });
    }

    const shapes = theme?.resolvedTokens?.shapes ?? {};
    for (const name of Object.keys(shapes).sort()) {
        const value = shapes[name];
        rows.push({
            id: "theming-shape-" + name,
            section: "Shapes",
            kind: "shape",
            name,
            value,
            consumerNodeIds: consumersByToken.get(name) ?? [],
            previewBorderRadius: parseShapeBorderRadius(value),
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

/** Lightweight view of a `compose/semantics` node carrying just the
 *  fields the bounds-join needs. Mirrors the daemon's wire shape
 *  (`ComposeSemanticsNode`) so callers can pass a raw payload through
 *  without re-shaping. */
export interface SemanticsLookupNode {
    nodeId: string;
    boundsInRoot: string;
    children?: SemanticsLookupNode[];
}

/** Wire shape for `compose/semantics`; root + recursive children. */
export interface SemanticsLookupPayload {
    root: SemanticsLookupNode;
}

/**
 * Build a `nodeId → OverlayBox` map from a `compose/semantics`
 * payload. Used by the Theming and Resources bundles to paint
 * transient hover overlays on the focused card: hovering a token row
 * looks up the row's `consumerNodeIds` and asks this map for the
 * bounds.
 *
 * Nodes whose `boundsInRoot` doesn't parse are skipped silently —
 * the row's hover overlay simply leaves them out rather than
 * crashing the panel.
 *
 * `null` / missing payload returns an empty map; callers should
 * fall back to "no overlay" rather than rendering a phantom layer.
 */
export function buildSemanticsBoundsMap(
    payload: SemanticsLookupPayload | null | undefined,
    overlayIdPrefix: string,
): Map<string, OverlayBox> {
    const out = new Map<string, OverlayBox>();
    if (!payload || !payload.root) return out;
    const visit = (node: SemanticsLookupNode): void => {
        const bounds = parseBounds(node.boundsInRoot);
        if (bounds) {
            out.set(node.nodeId, {
                id: overlayIdPrefix + "-" + node.nodeId,
                bounds,
                level: "info",
            });
        }
        for (const child of node.children ?? []) visit(child);
    };
    visit(payload.root);
    return out;
}

/**
 * Look [nodeIds] up in [boundsMap] and return the OverlayBoxes that
 * have bounds. Ids without a hit are skipped silently — when the
 * `compose/semantics` tree shrinks faster than a stale theming
 * `consumers` array, an unmatched nodeId means "we don't know where
 * this consumer is" and is preferable to a phantom box at (0,0).
 */
export function consumerOverlayBoxes(
    boundsMap: ReadonlyMap<string, OverlayBox>,
    nodeIds: readonly string[],
): readonly OverlayBox[] {
    const out: OverlayBox[] = [];
    for (const id of nodeIds) {
        const box = boundsMap.get(id);
        if (box) out.push(box);
    }
    return out;
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
        // VS Code's webview CSP rejects inline `style=` attributes —
        // including the ones lit's `styleMap` directive emits — so the
        // swatch colour has to land via the CSSOM in a ref callback
        // instead. Same dance `BoxOverlay.renderBox` does for overlay
        // boxes; see that file for the CSP rationale.
        const css = row.swatchCss;
        const apply = (el: Element | undefined): void => {
            if (!el) return;
            (el as HTMLElement).style.backgroundColor = css;
        };
        return html`<span
            class="theming-swatch"
            data-source=${
                row.kind === "seed" ? "seed" : (row as ThemingColorRow).source
            }
            ${ref(apply)}
            title=${row.hex}
        ></span>`;
    }
    if (row.kind === "shape") {
        const radius = row.previewBorderRadius;
        if (radius === null) return "";
        const apply = (el: Element | undefined): void => {
            if (!el) return;
            (el as HTMLElement).style.borderRadius = radius;
        };
        return html`<span
            class="theming-shape-preview"
            ${ref(apply)}
            title=${row.value}
        ></span>`;
    }
    return "";
}

function applyTypographyFont(row: ThemingTypographyRow) {
    return (el: Element | undefined): void => {
        if (!el) return;
        const e = el as HTMLElement;
        e.style.fontFamily = row.cssFontFamily;
        if (row.cssFontWeight !== null) {
            e.style.fontWeight = String(row.cssFontWeight);
        }
        e.style.fontStyle = row.cssFontStyle;
    };
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
            ${
                row.source === "wallpaper"
                    ? html`<span class="theming-name-sub">from wallpaper</span>`
                    : ""
            }
        </div>`;
    }
    if (row.kind === "typography") {
        // Paint the token name itself in the resolved font so the user
        // sees the typeface at a glance. CSP blocks inline `style=` and
        // lit's `styleMap`, so the font is applied via a CSSOM ref
        // callback — same trick the swatch uses.
        return html`<strong
            class="theming-typography-name"
            ${ref(applyTypographyFont(row))}
            >${row.name}</strong
        >`;
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

function indexConsumersByToken(
    consumers: readonly ThemeConsumer[],
): Map<string, readonly string[]> {
    // First-seen order per token, deduped — a single consumer can
    // list the same token twice (e.g. a Text that reads both
    // `primary` and `onPrimary` from a wrapping container), but the
    // overlay only wants one box per node.
    const byToken = new Map<string, string[]>();
    for (const c of consumers) {
        if (!c.nodeId) continue;
        for (const t of c.tokens ?? []) {
            const existing = byToken.get(t);
            if (!existing) {
                byToken.set(t, [c.nodeId]);
            } else if (!existing.includes(c.nodeId)) {
                existing.push(c.nodeId);
            }
        }
    }
    return byToken;
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
 * Map the Kotlin `FontFamily.X.toString()` shapes the daemon emits to
 * a CSS font-family stack. The webview head loads Roboto / Roboto
 * Serif / Roboto Mono / Caveat from Google Fonts so these stacks
 * resolve to a real glyph rather than the system fallback.
 */
export function cssFontFamily(name: string | null | undefined): string {
    if (!name) return "var(--vscode-font-family)";
    const s = name.trim();
    if (/SansSerif/i.test(s)) return "'Roboto', sans-serif";
    if (/Serif/i.test(s) && !/SansSerif/i.test(s))
        return "'Roboto Serif', serif";
    if (/Monospace/i.test(s)) return "'Roboto Mono', monospace";
    if (/Cursive/i.test(s)) return "'Caveat', cursive";
    // Fall back to whatever the daemon reported — quoted defensively so
    // a custom family name with spaces still resolves.
    return `'${s.replace(/'/g, "")}', var(--vscode-font-family)`;
}

/**
 * Pull the numeric weight out of `FontWeight(weight=400)` (the
 * canonical Compose toString). Returns null when the shape doesn't
 * match — e.g. `Normal`, `Bold`, an em-dash placeholder.
 */
export function parseFontWeight(
    weight: string | null | undefined,
): number | null {
    if (!weight) return null;
    const m = weight.match(/weight\s*=\s*(\d{2,3})/);
    if (m) {
        const n = parseInt(m[1], 10);
        if (n >= 100 && n <= 900) return n;
    }
    // Named weights — Compose may toString to `Bold` / `Normal` for
    // some inputs. Surface the canonical CSS numeric so the sample
    // renders at the expected weight even without `weight=…`.
    const lookup: Record<string, number> = {
        thin: 100,
        extralight: 200,
        light: 300,
        normal: 400,
        regular: 400,
        medium: 500,
        semibold: 600,
        bold: 700,
        extrabold: 800,
        black: 900,
    };
    const key = weight.trim().toLowerCase();
    return lookup[key] ?? null;
}

/**
 * Best-effort parse of the Material 3 shape token's toString into a
 * CSS `border-radius` value. Handles `RoundedCornerShape(size.dp)`,
 * the four-corner form `RoundedCornerShape(topStart = CornerSize(size
 * = 8.0.dp), …)`, and the percent variants. Returns null for shapes
 * we can't safely visualise (CutCornerShape, custom polygons) — the
 * caller suppresses the preview swatch in that case.
 *
 * Numbers are emitted as `px` directly: the swatch is a fixed 20×20px
 * box in the webview, so 1 dp ≈ 1 px is the only sensible scale for a
 * scaled-down preview. Percent values pass through unchanged.
 */
export function parseShapeBorderRadius(
    value: string | null | undefined,
): string | null {
    if (!value || typeof value !== "string") return null;
    // CutCornerShape would render as bevelled corners, which CSS
    // border-radius can't express. Skip rather than mis-paint.
    if (/CutCornerShape/i.test(value)) return null;
    if (!/RoundedCornerShape/i.test(value)) return null;
    const corners: Record<string, string | null> = {
        topStart: null,
        topEnd: null,
        bottomEnd: null,
        bottomStart: null,
    };
    let matchedAnyCorner = false;
    for (const corner of Object.keys(corners)) {
        const re = new RegExp(
            corner +
                "\\s*=\\s*CornerSize\\(size\\s*=\\s*([0-9.]+)(\\.dp|dp|%)?",
            "i",
        );
        const m = value.match(re);
        if (m) {
            corners[corner] = formatCornerSize(m[1], m[2]);
            matchedAnyCorner = true;
        }
    }
    if (matchedAnyCorner) {
        // CSS order: top-left top-right bottom-right bottom-left. The
        // Compose corner names map onto LTR layout (start = left).
        const tl = corners.topStart ?? "0";
        const tr = corners.topEnd ?? "0";
        const br = corners.bottomEnd ?? "0";
        const bl = corners.bottomStart ?? "0";
        return `${tl} ${tr} ${br} ${bl}`;
    }
    // Single-arg form: `RoundedCornerShape(16.dp)` or
    // `RoundedCornerShape(50)` (percent shorthand).
    const single = value.match(
        /RoundedCornerShape\(\s*([0-9.]+)(\.dp|dp|%)?\s*\)/i,
    );
    if (single) return formatCornerSize(single[1], single[2]);
    return null;
}

function formatCornerSize(num: string, unit: string | undefined): string {
    const parsed = parseFloat(num);
    if (!Number.isFinite(parsed)) return "0";
    if (unit === "%") return parsed + "%";
    // 1 dp → 1 px in the scaled preview. CSS clamps `border-radius` at
    // 50% of the side anyway, so a 28 dp radius on a 20 px swatch just
    // reads as "fully rounded" — the intended affordance.
    return parsed + "px";
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
