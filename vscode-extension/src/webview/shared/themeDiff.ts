// Client-side theme data-diff for the history panel (#1872), mirroring the semantics differ in
// `semanticsDiff.ts`. Diffs two entries' captured `compose/theme` resolved-token maps so the panel
// can show which Material 3 design tokens drifted between renders — no daemon round-trip.
//
// Tokens are matched by key within their category (Colors / Typography / Shapes). Colors and shapes
// are flat `key → string` maps; typography is `key → object`, formatted into a compact display
// string so a changed token reads as `old → new` like every other category. There is no Kotlin
// `ThemeDiff` yet — this is the first implementation; a daemon/CLI port can follow for parity.

export const THEME_DIFF_SCHEMA = "compose-theme-diff/v1";

export type ThemeCategory = "color" | "typography" | "shape";

/** A token present on only one side (added/removed). */
export interface ThemeTokenSummary {
    category: ThemeCategory;
    key: string;
    value: string;
}

/** A token present on both sides with a different resolved value. */
export interface ThemeTokenChange {
    category: ThemeCategory;
    key: string;
    from: string;
    to: string;
}

export interface ThemeDelta {
    schema: string;
    added: ThemeTokenSummary[];
    removed: ThemeTokenSummary[];
    changed: ThemeTokenChange[];
}

/** Lenient view of `compose/theme`'s `resolvedTokens` — parsed straight from the sidecar JSON. */
interface ResolvedTokensLike {
    colorScheme?: unknown;
    typography?: unknown;
    shapes?: unknown;
}

/** Lenient view of the `compose/theme` payload as stored in `HistoryEntry.theme`. */
export interface ThemePayloadLike {
    resolvedTokens?: ResolvedTokensLike | null;
}

/** Narrows an untyped wire value to a `{ resolvedTokens }` theme payload. */
export function isThemePayload(value: unknown): value is ThemePayloadLike {
    return (
        typeof value === "object" &&
        value !== null &&
        "resolvedTokens" in value &&
        typeof (value as { resolvedTokens: unknown }).resolvedTokens ===
            "object" &&
        (value as { resolvedTokens: unknown }).resolvedTokens !== null
    );
}

function asRecord(value: unknown): Record<string, unknown> {
    return typeof value === "object" && value !== null
        ? (value as Record<string, unknown>)
        : {};
}

/** Colors / shapes are already strings; null-safe stringify keeps the diff total. */
function formatScalar(value: unknown): string {
    return value == null ? "" : String(value);
}

/**
 * Formats one typography token (`{ fontFamily, fontSize, fontWeight, … }`) into a compact, stable
 * one-liner so a changed token diffs as `old → new`. Mirrors the fields `TypographyToken` carries;
 * omitted fields drop out so the string only reflects what was captured.
 */
function formatTypography(value: unknown): string {
    const t = asRecord(value);
    const parts: string[] = [];
    const push = (v: unknown, suffix: unknown = ""): void => {
        if (v != null && v !== "") {
            parts.push(`${String(v)}${suffix == null ? "" : String(suffix)}`);
        }
    };
    push(t.fontFamily);
    if (t.fontSize != null) {
        push(t.fontSize, t.fontSizeUnit);
    }
    if (t.fontWeight != null && t.fontWeight !== "") {
        parts.push(`w${String(t.fontWeight)}`);
    }
    if (
        t.fontStyle != null &&
        String(t.fontStyle) !== "" &&
        String(t.fontStyle).toLowerCase() !== "normal"
    ) {
        parts.push(String(t.fontStyle));
    }
    if (t.lineHeight != null) {
        parts.push(
            `lh=${String(t.lineHeight)}${formatScalar(t.lineHeightUnit)}`,
        );
    }
    if (t.letterSpacing != null) {
        parts.push(
            `ls=${String(t.letterSpacing)}${formatScalar(t.letterSpacingUnit)}`,
        );
    }
    return parts.join(" · ");
}

function normalize(
    map: unknown,
    format: (v: unknown) => string,
): Map<string, string> {
    const out = new Map<string, string>();
    const record = asRecord(map);
    for (const key of Object.keys(record)) {
        out.set(key, format(record[key]));
    }
    return out;
}

function diffCategory(
    category: ThemeCategory,
    base: Map<string, string>,
    head: Map<string, string>,
    delta: Pick<ThemeDelta, "added" | "removed" | "changed">,
): void {
    const keys = [...new Set([...base.keys(), ...head.keys()])].sort();
    for (const key of keys) {
        const from = base.get(key);
        const to = head.get(key);
        if (from === undefined && to !== undefined) {
            delta.added.push({ category, key, value: to });
        } else if (from !== undefined && to === undefined) {
            delta.removed.push({ category, key, value: from });
        } else if (from !== undefined && to !== undefined && from !== to) {
            delta.changed.push({ category, key, from, to });
        }
    }
}

/**
 * Diffs two `compose/theme` payloads. `base` is the older entry, `head` the newer, so `added` reads
 * as tokens newly present and `changed` as `old → new` — matching how the panel labels "this entry
 * vs the previous". Categories are diffed in Colors → Typography → Shapes order.
 */
export function diffTheme(
    base: ThemePayloadLike,
    head: ThemePayloadLike,
): ThemeDelta {
    const b = asRecord(base.resolvedTokens);
    const h = asRecord(head.resolvedTokens);
    const delta: Pick<ThemeDelta, "added" | "removed" | "changed"> = {
        added: [],
        removed: [],
        changed: [],
    };
    diffCategory(
        "color",
        normalize(b.colorScheme, formatScalar),
        normalize(h.colorScheme, formatScalar),
        delta,
    );
    diffCategory(
        "typography",
        normalize(b.typography, formatTypography),
        normalize(h.typography, formatTypography),
        delta,
    );
    diffCategory(
        "shape",
        normalize(b.shapes, formatScalar),
        normalize(h.shapes, formatScalar),
        delta,
    );
    return { schema: THEME_DIFF_SCHEMA, ...delta };
}

export function themeDeltaIsEmpty(delta: ThemeDelta): boolean {
    return (
        (delta.added?.length ?? 0) === 0 &&
        (delta.removed?.length ?? 0) === 0 &&
        (delta.changed?.length ?? 0) === 0
    );
}
