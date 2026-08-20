// What the inspection layers draw, decided before any DOM exists.
//
// Two sources feed one list of boxes: the accessibility hierarchy (what a screen reader sees), and
// three kinds of design annotation (typography, theme, layout). Turning them into entries is where
// all the judgement is, and almost none of it is visible in a screenshot — a rectangle drawn over a
// component looks equally right whether it is the correct node, a duplicate of its parent, or a
// finding that was silently dropped.
//
// The accessibility pass is the one with real rules in it, and each exists because of a specific
// wrong picture: stacked rectangles on the same pixels, a colour that says "fine" over a failure,
// and findings that vanish because their bounds did not line up with a node.

import { typographySpec } from "../annotate/typography.js";

/** A box in the RENDER's own pixel space — the snapshot image's natural size. */
export interface Bounds {
    x: number;
    y: number;
    width: number;
    height: number;
}

export type Level = "error" | "warning" | "info";

export interface Entry {
    kind: string;
    bounds: Bounds;
    title: string;
    detail: string;
    level: Level;
    /** A per-node hue, only for un-flagged stops. Null when the level is carrying the colour. */
    color: string | null;
    /** Optional expanded wording kept off the compact legend row and exposed as a tooltip. */
    tooltip?: string;
}

/**
 * Distinct hues for the accessibility layer's un-flagged stops — the same intent as the VS Code
 * panel's a11y palette. With one colour for everything, adjacent focus targets in a list merge into
 * a single block and the legend cannot be matched back to a box by eye.
 */
export const PALETTE = [
    "#f28b82",
    "#aecbfa",
    "#a8dab5",
    "#fdd663",
    "#d7aefb",
    "#fcad70",
    "#80cbc4",
    "#f6aea9",
];

/** `"x,y,right,bottom"` as the daemon reports it. Null for anything that is not a drawable box. */
export function parseBounds(wire: string | null | undefined): Bounds | null {
    const parts = String(wire ?? "").split(",");
    if (parts.length !== 4) return null;
    const n = parts.map((p) => parseInt(p, 10));
    if (n.some(Number.isNaN)) return null;
    const box = { x: n[0], y: n[1], width: n[2] - n[0], height: n[3] - n[1] };
    // A zero- or negative-area box is not a thing anyone can point at, and drawing it puts an
    // invisible entry in the legend that highlights nothing.
    return box.width > 0 && box.height > 0 ? box : null;
}

/** Normalise the wire's severity. Anything unrecognised is information, not a failure. */
export function levelOf(raw: string | null | undefined): Level {
    const lower = String(raw ?? "").toLowerCase();
    if (lower === "error") return "error";
    if (lower === "warning" || lower === "warn") return "warning";
    return "info";
}

/** The worse of two levels — a node carrying both a warning and an error reads as the error. */
export function worst(a: Level | null, b: Level): Level | null {
    if (a === "error" || b === "error") return "error";
    if (a === "warning" || b === "warning") return "warning";
    return a;
}

export interface A11yNode {
    boundsInScreen?: string;
    label?: string;
    role?: string;
    states?: string[];
    /** The daemon OMITS this when true — it is the Kotlin default. See `isFocusStop`. */
    merged?: boolean;
}

export interface A11yFinding {
    boundsInScreen?: string;
    level?: string;
    type?: string;
    message?: string;
    viewDescription?: string;
}

export interface TouchTarget {
    boundsInScreen?: string;
    widthDp?: number;
    heightDp?: number;
    findings?: string[];
}

export interface A11yPayload {
    nodes?: A11yNode[];
    findings?: A11yFinding[];
    touchTargets?: TouchTarget[];
}

/**
 * Whether a node is a screen-reader stop.
 *
 * Explicitly `merged === false`, NOT `!merged`: the daemon omits the field when it is true, so an
 * absent `merged` means merged. Read the other way, every unmerged inner Text would draw a second
 * rectangle on exactly its focusable ancestor's pixels — two boxes, two legend rows, one thing.
 */
export function isFocusStop(node: A11yNode): boolean {
    return node.merged !== false;
}

const boundsKey = (wire: string | null | undefined): string =>
    (wire ?? "").trim();

/**
 * The announcement a focus stop gets from the nodes it merges.
 *
 * A stop whose copy lives on its descendants — a Wear `Button(label = { Text(…) })`, an icon button
 * whose `contentDescription` sits on the inner icon — reaches the wire with an empty `label` on
 * every hierarchy produced before the extractor started rolling those up. Reading that literally
 * prints `(unlabelled)` over a button with the word "Filled" plainly on it, which is worse than
 * useless: it reports a labelling bug that is not there and hides the one that would be.
 *
 * Reconstructing which nodes a stop folded in takes both signals the flat wire carries. Emission
 * order is pre-order, so its descendants follow it; bounds say where that subtree ends, and which
 * of the nodes inside it belong to a NESTED stop instead. A row folding in a title, then a button
 * of its own, then a subtitle announces "Title Subtitle" — stopping at the nested stop drops the
 * subtitle, ignoring it swallows the button's copy into the row.
 *
 * This is an APPROXIMATION, and deliberately the second line of defence: a freshly rendered
 * hierarchy arrives with the label already on the stop, because `AccessibilityLabels` reads the
 * real parent/child links during extraction, before the wire flattens them away. What is left here
 * cannot be made exact — two rectangles that nest tell you nothing certain about ancestry, so a
 * stop drawn over the whole of its parent will still swallow what follows it — and the payload has
 * no ancestry to consult instead. Re-rendering the bundle is the fix for those; this keeps the
 * common shapes readable until someone does.
 */
function mergedDescendantLabel(nodes: A11yNode[], index: number): string {
    const parts: string[] = [];
    const parent = parseBounds(nodes[index].boundsInScreen);
    // A stop whose bounds do not parse has no box to test its descendants against: fall back to the
    // plain following run of non-stops.
    if (!parent) {
        for (
            let i = index + 1;
            i < nodes.length && !isFocusStop(nodes[i]);
            i++
        ) {
            const label = (nodes[i].label ?? "").trim();
            if (label) parts.push(label);
        }
        return parts.join(" ");
    }
    // The nested focus stop currently being skipped over — what sits inside it is part of ITS
    // announcement, not this one's.
    let nested: Bounds | null = null;
    for (let i = index + 1; i < nodes.length; i++) {
        const bounds = parseBounds(nodes[i].boundsInScreen);
        // A node that describes no box cannot be placed; skipping it beats ending the scan there
        // and silently truncating the announcement.
        if (!bounds) continue;
        // Out of the parent's box: the walk has left its subtree.
        if (!contains(parent, bounds)) break;
        if (nested && contains(nested, bounds)) continue;
        nested = null;
        if (isFocusStop(nodes[i])) {
            nested = bounds;
            continue;
        }
        const label = (nodes[i].label ?? "").trim();
        if (label) parts.push(label);
    }
    return parts.join(" ");
}

/** Whether `inner` sits entirely within `outer` — the flat wire's only handle on ancestry. */
function contains(outer: Bounds, inner: Bounds): boolean {
    return (
        inner.x >= outer.x &&
        inner.y >= outer.y &&
        inner.x + inner.width <= outer.x + outer.width &&
        inner.y + inner.height <= outer.y + outer.height
    );
}

/**
 * One entry per screen-reader stop, carrying the findings and touch-target size whose bounds match
 * it — plus any finding the hierarchy has no node for.
 *
 * That last pass is not tidiness. A finding whose bounds do not line up with a node is still a real
 * accessibility problem, and dropping it would report the frame as clean on exactly the elements
 * the hierarchy could not describe.
 */
export function a11yEntries(payload: A11yPayload | null): Entry[] {
    if (!payload) return [];
    const nodes = payload.nodes ?? [];
    const findings = payload.findings ?? [];
    const targets = payload.touchTargets ?? [];

    const findingsByBounds = new Map<string, A11yFinding[]>();
    for (const finding of findings) {
        const key = boundsKey(finding.boundsInScreen);
        if (!key) continue;
        const list = findingsByBounds.get(key) ?? [];
        list.push(finding);
        findingsByBounds.set(key, list);
    }
    const targetByBounds = new Map<string, TouchTarget>();
    for (const target of targets) {
        const key = boundsKey(target.boundsInScreen);
        if (key) targetByBounds.set(key, target);
    }

    const out: Entry[] = [];
    nodes.forEach((node, index) => {
        if (!isFocusStop(node)) return;
        const bounds = parseBounds(node.boundsInScreen);
        if (!bounds) return;
        const key = boundsKey(node.boundsInScreen);
        const matched = findingsByBounds.get(key) ?? [];
        const target = targetByBounds.get(key);

        let level: Level | null = null;
        for (const finding of matched)
            level = worst(level, levelOf(finding.level));
        // A touch target with findings is a warning even when nothing else flagged the node: the
        // element is reachable and labelled, it is just too small to hit.
        if (!level && target?.findings?.length) level = "warning";

        const detail: string[] = [];
        if (node.role) detail.push(node.role);
        if (node.states?.length) detail.push(node.states.join(", "));
        if (target) {
            detail.push(
                `${Math.round(target.widthDp ?? 0)}×${Math.round(target.heightDp ?? 0)} dp`,
            );
        }
        for (const finding of matched)
            detail.push(`${finding.type}: ${finding.message}`);
        for (const note of target?.findings ?? []) detail.push(note);

        // A stop with no label of its own still announces its descendants' copy, so show that
        // rather than `(unlabelled)`. Only reached on hierarchies whose producer did not roll the
        // label up itself — a freshly rendered one arrives with it already on the node.
        const label =
            (node.label ?? "").trim() || mergedDescendantLabel(nodes, index);

        out.push({
            kind: "a11y",
            bounds,
            title: label || "(unlabelled)",
            detail: detail.join(" · "),
            level: level ?? "info",
            // A flagged stop takes its colour from its level; only the rest need telling apart.
            color: level ? null : PALETTE[index % PALETTE.length],
        });
    });

    const known = new Set(
        nodes.map((node) => boundsKey(node.boundsInScreen)).filter(Boolean),
    );
    for (const finding of findings) {
        const key = boundsKey(finding.boundsInScreen);
        if (key && known.has(key)) continue;
        const bounds = parseBounds(finding.boundsInScreen);
        if (!bounds) continue;
        out.push({
            kind: "a11y",
            bounds,
            title: finding.viewDescription || "(no element)",
            detail: `${finding.type}: ${finding.message}`,
            level: levelOf(finding.level),
            color: null,
        });
    }
    return out;
}

export interface Annotation {
    kind?: string;
    bounds?: Bounds;
    role?: string;
    label?: string;
    detail?: Record<string, unknown>;
}

export interface AnnotationPayload {
    annotations?: Annotation[];
}

/**
 * The compact style line established by the typography comparison UI (#3677):
 * `token · family weight · size/line-height`. This is the glanceable line; tracking, style,
 * and variable axes remain in the row tooltip rather than turning the narrow component legend back
 * into the multi-line dump #3677 replaced on the comparison page.
 */
export function typographyDetail(annotation: Annotation): string {
    const spec = typographySpec(annotation);
    if (spec.labelOnly) return annotation.label ?? "";

    const size =
        spec.size === undefined
            ? undefined
            : `${spec.size}${
                  spec.lineHeight === undefined
                      ? (spec.unit ?? "")
                      : `/${spec.lineHeight}`
              }`;
    const face = [
        spec.family,
        spec.weight === undefined ? undefined : spec.weight,
    ]
        .filter((value) => value !== undefined)
        .join(" ");

    return [spec.token, face || undefined, size].filter(Boolean).join(" · ");
}

/**
 * The annotation's whole structured payload, as `key value` pairs — the theme and layout layers'
 * tooltip (#4328).
 *
 * The compact row can only carry the handful of tokens that fit on one line, and before this the
 * rest of `detail` was parsed and then thrown away: a render whose capture resolved a shadow, an
 * effective alpha, a gradient's stops or a per-edge padding showed none of them anywhere in the
 * viewer. Values are already formatted server-side (`"16.0dp"`, `"#FF6750A4"`), so this only has to
 * order and join them.
 *
 * Nothing is filtered out, `box` included — the compact label lists fill / radius / border and never
 * says which rectangle they were measured on, so on a padded paint chain (where the theme box is the
 * node's paint box and the Layout layer's box is its placement box) the tooltip is the only place
 * that distinction exists. The server omits the key entirely for the ordinary case, so it appears
 * exactly where it means something.
 */
export function annotationTooltip(annotation: Annotation): string {
    const detail = annotation.detail ?? {};
    return Object.entries(detail)
        .filter(([, value]) => value !== undefined && value !== null)
        .map(([key, value]) => `${key} ${String(value)}`)
        .join(" · ");
}

/** Typography / theme / layout entries, straight off the shared design-annotation payload. */
export function annotationEntries(
    payload: AnnotationPayload | null,
    kind: string,
): Entry[] {
    return (payload?.annotations ?? [])
        .filter((a): a is Annotation & { bounds: Bounds } =>
            Boolean(a && a.kind === kind && a.bounds),
        )
        .map((a) => {
            const typography = kind === "typography" ? typographySpec(a) : null;
            const summary =
                kind === "typography"
                    ? typographyDetail(a)
                    : a.role
                      ? (a.label ?? "")
                      : "";
            const tooltip =
                kind === "typography"
                    ? [
                          a.label,
                          typography?.axes ? `axes ${typography.axes}` : "",
                      ]
                          .filter(Boolean)
                          .join(" · ")
                    : annotationTooltip(a);
            return {
                kind,
                bounds: a.bounds,
                title: a.role || a.label || "",
                detail: summary,
                level: "info" as const,
                color: null,
                tooltip: tooltip || undefined,
            };
        });
}
