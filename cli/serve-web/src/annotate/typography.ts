// Typography as STYLES rather than instances.
//
// The layout redline is instance-level: one box per element. Typography is not — a screen using
// `bodyLarge` in nine places has one style, and nine numbered boxes saying so is noise. So usages
// are grouped by their resolved metrics, each group gets one letter, and the readable settings live
// once in the table under the panels.
//
// Everything here decides what counts as "the same style", which is the whole judgement of the
// feature. Getting it wrong does not error — it reports two styles where there is one, or one where
// there are two.

import type { AnnotationItem } from "./match.js";

/** A number with an optional unit suffix. `0` is a value; `""` and absent are not. */
export function annotationNumber(value: unknown): number | undefined {
    if (value === undefined || value === null || value === "") return undefined;
    const match = String(value)
        .trim()
        .match(/^(-?(?:\d+(?:\.\d+)?|\.\d+))(?:\s*[a-z%]+)?$/i);
    const number = match ? Number(match[1]) : NaN;
    return Number.isFinite(number) ? number : undefined;
}

export function annotationUnit(value: unknown): string | undefined {
    if (value === undefined || value === null) return undefined;
    const match = String(value)
        .trim()
        .match(/[a-z%]+$/i);
    return match ? match[0].toLowerCase() : undefined;
}

/**
 * The Material token a style names, in camelCase.
 *
 * `"text"` is dropped deliberately: it is the default a tool emits when it knows nothing, so
 * treating it as a token would group every unmapped usage under one name and report them as
 * matching.
 */
export function typographyToken(
    detail: Record<string, unknown> | undefined,
): string | undefined {
    return typographyTokens(detail).join(",") || undefined;
}

/** Every honest candidate token carried by a resolved usage. */
export function typographyTokens(
    detail: Record<string, unknown> | undefined,
): string[] {
    return String(detail?.token ?? "")
        .split(",")
        .map((token) => normaliseTypographyToken(token))
        .filter((token): token is string => Boolean(token));
}

function normaliseTypographyToken(raw: string): string | undefined {
    const token = raw.trim();
    if (!token || token.toLowerCase() === "text") return undefined;
    const m3 = token.match(
        /^m3[/-](display|headline|title|body|label)[/-](large|medium|small)$/i,
    );
    if (m3)
        return (
            m3[1].toLowerCase() +
            m3[2].charAt(0).toUpperCase() +
            m3[2].slice(1).toLowerCase()
        );
    return token;
}

/**
 * A font file or family name reduced to its family: no path, no extension, no weight suffix.
 *
 * ONE weight suffix, and it must only ever be applied once. `typographySpec` used to call it again
 * on its own result, which is not idempotent: `Roboto-Medium-Bold.ttf` reduces to `Roboto-Medium`
 * and then to `Roboto`. The page displayed the first and compared on the second, so a table showing
 * `Roboto-Medium` beside `Roboto-Black` reported them as unchanged.
 *
 * Stripping one suffix IS right: weight is carried by `spec.weight` and compared there, so
 * `Roboto-Medium` and `Roboto-Bold` are one family with two weights, not two families.
 */
export function typographyFamily(value: unknown): string | undefined {
    const raw = String(value ?? "").trim();
    if (!raw) return undefined;
    const filename = raw
        .split(/[\\/]/)
        .pop()!
        .replace(/\.(?:ttf|otf|woff2?|ttc)$/i, "");
    return filename
        .replace(
            /[-_](thin|extralight|light|regular|medium|semibold|bold|extrabold|black)$/i,
            "",
        )
        .trim();
}

/** Variable-font axes as one sorted string, from any of the three shapes the wire uses. */
export function typographyAxes(value: unknown): string {
    const axes: Array<[string, number | undefined]> = [];
    if (Array.isArray(value)) {
        for (const entry of value) {
            if (entry && entry.tag !== undefined && entry.value !== undefined) {
                axes.push([
                    String(entry.tag).toLowerCase(),
                    annotationNumber(entry.value),
                ]);
            }
        }
    } else if (value && typeof value === "object") {
        for (const tag of Object.keys(value as Record<string, unknown>)) {
            axes.push([
                tag.toLowerCase(),
                annotationNumber((value as Record<string, unknown>)[tag]),
            ]);
        }
    } else {
        const pattern =
            /["']?([A-Za-z0-9]{4})["']?\s*(?:=|\s)\s*(-?(?:\d+(?:\.\d+)?|\.\d+))/g;
        const text = String(value ?? "");
        let match: RegExpExecArray | null;
        while ((match = pattern.exec(text)))
            axes.push([match[1].toLowerCase(), Number(match[2])]);
    }
    // Sorted, so two usages that list the same axes in a different order are one style.
    return axes
        .filter((axis) => axis[0] && axis[1] !== undefined)
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map((axis) => `${axis[0]}=${axis[1]}`)
        .join(",");
}

export interface TypographySpec {
    token?: string;
    tokens: string[];
    /**
     * The family, reduced once: no path, no extension, no weight suffix.
     *
     * What is DISPLAYED and what is COMPARED, deliberately the same field. They used to be two —
     * `family` and a `familyKey` reduced a second time — and the second reduction meant the table
     * could show two different families and report them as unchanged. See `typographyFamily`.
     */
    family?: string;
    size?: number;
    lineHeight?: number;
    weight?: number;
    unit?: string;
    lineHeightUnit?: string;
    tracking?: string;
    style?: string;
    axes?: string;
    label?: string;
    /** Nothing resolved at all — the annotation is a label and nothing else. */
    labelOnly: boolean;
}

function firstNonBlank(...values: unknown[]): unknown {
    return values.find(
        (value) =>
            value !== null &&
            value !== undefined &&
            (typeof value !== "string" || value.trim() !== ""),
    );
}

export function typographySpec(item: AnnotationItem): TypographySpec {
    const detail = (item.detail ?? {}) as Record<string, unknown>;
    const sizeValue =
        detail.fontSize !== undefined ? detail.fontSize : detail.size;
    const size = annotationNumber(sizeValue);
    const lineHeight = annotationNumber(detail.lineHeight);
    const weight = annotationNumber(detail.fontWeight);
    const family = typographyFamily(detail.fontFamily);
    const unit =
        annotationUnit(sizeValue) ||
        String(detail.unit ?? "").trim() ||
        undefined;
    const lineHeightUnit =
        annotationUnit(detail.lineHeight) ||
        String(detail.lineHeightUnit ?? "").trim() ||
        unit;
    const tracking =
        String(
            firstNonBlank(detail.letterSpacing, detail.tracking) ?? "",
        ).trim() || undefined;
    const style = String(detail.fontStyle ?? "").trim() || undefined;
    const axes = typographyAxes(
        firstNonBlank(
            detail.fontVariationSettings,
            detail.variationSettings,
            detail.axes,
        ),
    );
    const tokens = typographyTokens(detail);
    const token = tokens.join(",") || undefined;
    const labelOnly =
        !token &&
        !family &&
        size === undefined &&
        lineHeight === undefined &&
        weight === undefined &&
        !tracking &&
        !style &&
        !axes;
    return {
        token,
        tokens,
        family,
        size,
        lineHeight,
        weight,
        unit,
        lineHeightUnit,
        tracking,
        style,
        axes: axes || undefined,
        label: item.label,
        labelOnly,
    };
}

/**
 * What makes two usages the same style.
 *
 * `label` participates ONLY for a label-only spec — that is the whole "style-level, not
 * instance-level" claim. Two usages with different words but identical metrics are one style;
 * including the label unconditionally would make every distinct string its own group and turn the
 * table back into the instance list this replaced.
 */
export function typographyGroupKey(spec: TypographySpec): string {
    return [
        spec.token ?? "",
        spec.family ?? "",
        spec.size,
        spec.unit ?? "",
        spec.lineHeight,
        spec.lineHeightUnit ?? "",
        spec.weight,
        spec.tracking ?? "",
        spec.style ?? "",
        spec.axes ?? "",
        spec.labelOnly ? (spec.label ?? "") : "",
    ].join("|");
}

export interface TypographyGroup {
    key: string;
    spec: TypographySpec;
    items: AnnotationItem[];
    roles: Set<string>;
    marker?: string;
}

export function groupTypography(items: AnnotationItem[]): TypographyGroup[] {
    const byKey = new Map<string, TypographyGroup>();
    for (const item of items.filter((item) => item.kind === "typography")) {
        const spec = typographySpec(item);
        const key = typographyGroupKey(spec);
        let group = byKey.get(key);
        if (!group) {
            group = { key, spec, items: [], roles: new Set() };
            byKey.set(key, group);
        }
        group.items.push(item);
        if (item.role) group.roles.add(String(item.role).trim().toLowerCase());
    }
    return Array.from(byKey.values());
}

/**
 * The most-used group per token — the "default" a local override is measured against.
 *
 * Most-used rather than first-seen: an override applied once should read as the exception, and
 * whichever usage happened to be captured first is not evidence of anything.
 */
export function typographyDefaults(
    groups: TypographyGroup[],
): Map<string, TypographyGroup> {
    const defaults = new Map<string, TypographyGroup>();
    for (const group of groups) {
        for (const token of group.spec.tokens) {
            const current = defaults.get(token);
            if (!current || group.items.length > current.items.length)
                defaults.set(token, group);
        }
    }
    return defaults;
}

/**
 * How far apart two style groups are.
 *
 * The three negative biases are shortcuts, not measurements: an identical key, the same token, or
 * shared roles mean these are the same style however far their numbers have drifted — which is
 * exactly the case the page exists to show.
 *
 * One oddity worth knowing: an UNSPECIFIED size costs a flat 8, while a 3sp difference costs 9. So
 * "no size at all" is treated as closer than a small real difference. That is deliberate — a missing
 * value is missing information, not evidence of a different style.
 */
export function typographyDistance(
    left: TypographyGroup,
    right: TypographyGroup,
): number {
    if (left.key === right.key) return -200;
    const tokenBias = typographyTokensOverlap(left.spec, right.spec) ? -100 : 0;
    let commonRoles = 0;
    for (const role of left.roles) if (right.roles.has(role)) commonRoles += 1;
    const roleBias = commonRoles ? -50 - commonRoles : 0;

    const a = left.spec;
    const b = right.spec;
    let distance = 0;
    if (a.size !== undefined && b.size !== undefined)
        distance += Math.abs(a.size - b.size) * 3;
    else if (a.size !== b.size) distance += 8;
    if (a.lineHeight !== undefined && b.lineHeight !== undefined)
        distance += Math.abs(a.lineHeight - b.lineHeight) * 2;
    else if (a.lineHeight !== b.lineHeight) distance += 5;
    if (a.weight !== undefined && b.weight !== undefined)
        distance += Math.abs(a.weight - b.weight) / 100;
    else if (a.weight !== b.weight) distance += 2;
    if ((a.family ?? "").toLowerCase() !== (b.family ?? "").toLowerCase())
        distance += 2;
    if ((a.style ?? "").toLowerCase() !== (b.style ?? "").toLowerCase())
        distance += 1;
    if ((a.tracking ?? "") !== (b.tracking ?? "")) distance += 1;
    if ((a.unit ?? "") !== (b.unit ?? "")) distance += 4;
    if ((a.lineHeightUnit ?? "") !== (b.lineHeightUnit ?? "")) distance += 3;
    if ((a.axes ?? "") !== (b.axes ?? "")) distance += 3;
    if (a.labelOnly || b.labelOnly) distance += a.label === b.label ? 0 : 8;
    return tokenBias + roleBias + distance;
}

/** Beyond this the two groups are different styles, not a changed one. */
export const TYPOGRAPHY_MATCH_CUTOFF = 15;

export interface TypographyPair {
    reference?: TypographyGroup;
    actual?: TypographyGroup;
    /** Same-token default used only to compare an otherwise unmatched local override. */
    comparisonReference?: TypographyGroup;
    comparisonActual?: TypographyGroup;
    marker: string;
}

export function typographyTokensOverlap(
    left: TypographySpec | undefined,
    right: TypographySpec | undefined,
): boolean {
    if (!left || !right) return false;
    return left.tokens.some((token) => right.tokens.includes(token));
}

/**
 * Pair the two sides' style groups, and letter them.
 *
 * MUTATES its inputs: each paired group gets `.marker` written onto it, because the cluster boxes
 * drawn over the panels read it back off the group they came from. Returning a map instead would
 * mean threading it through the drawing code for no gain.
 *
 * The 27th pair is `"27"`, not `"AA"` — past twenty-six the letters have stopped being mnemonic and
 * a number is at least unambiguous.
 */
export function pairTypography(
    reference: TypographyGroup[],
    actual: TypographyGroup[],
): TypographyPair[] {
    const remaining = actual.slice();
    const pairs: TypographyPair[] = reference.map((ref) => {
        let bestIndex = -1;
        let bestDistance = Infinity;
        remaining.forEach((candidate, index) => {
            const distance = typographyDistance(ref, candidate);
            if (distance < bestDistance) {
                bestIndex = index;
                bestDistance = distance;
            }
        });
        const matched =
            bestIndex >= 0 && bestDistance <= TYPOGRAPHY_MATCH_CUTOFF
                ? remaining.splice(bestIndex, 1)[0]
                : undefined;
        return { reference: ref, actual: matched, marker: "" };
    });
    for (const actualOnly of remaining)
        pairs.push({ actual: actualOnly, marker: "" });
    const referenceDefaults = typographyDefaults(reference);
    const actualDefaults = typographyDefaults(actual);
    for (const pair of pairs) {
        if (!pair.reference && pair.actual)
            pair.comparisonReference = pair.actual.spec.tokens
                .map((token) => referenceDefaults.get(token))
                .find(Boolean);
        if (!pair.actual && pair.reference)
            pair.comparisonActual = pair.reference.spec.tokens
                .map((token) => actualDefaults.get(token))
                .find(Boolean);
    }
    pairs.forEach((pair, index) => {
        const marker =
            index < 26 ? String.fromCharCode(65 + index) : String(index + 1);
        pair.marker = marker;
        if (pair.reference) pair.reference.marker = marker;
        if (pair.actual) pair.actual.marker = marker;
    });
    return pairs;
}

export type Field =
    | "token"
    | "family"
    | "size"
    | "lineHeight"
    | "weight"
    | "tracking"
    | "style"
    | "axes";

/**
 * What a field READS as.
 *
 * Four different words for four absent fields, and each is load-bearing: an unmapped token is a
 * finding, an unspecified family is a gap, default tracking is normal, and a missing size is simply
 * unknown. Collapsing them to one dash would report all four as the same thing.
 */
export function typographyValue(
    spec: TypographySpec | undefined,
    field: Field,
): string {
    if (!spec) return "—";
    if (field === "token") return spec.token || "unmapped";
    if (field === "family") return spec.family || "unspecified";
    if (field === "size")
        return spec.size === undefined ? "—" : `${spec.size}${spec.unit ?? ""}`;
    if (field === "lineHeight")
        return spec.lineHeight === undefined
            ? "—"
            : `${spec.lineHeight}${spec.lineHeightUnit ?? ""}`;
    if (field === "weight")
        return spec.weight === undefined ? "—" : String(spec.weight);
    if (field === "tracking") return spec.tracking || "default";
    if (field === "style") return spec.style || "normal";
    if (field === "axes") return spec.axes || "default";
    return "—";
}

/** What a field COMPARES as — the same text, normalised, and the family by its key. */
export function typographyComparableValue(
    spec: TypographySpec | undefined,
    field: Field,
): string {
    if (!spec) return "—";
    if (field === "family") return (spec.family || "unspecified").toLowerCase();
    if (field === "size")
        return spec.size === undefined ? "—" : `${spec.size}${spec.unit ?? ""}`;
    return typographyValue(spec, field).toLowerCase();
}
