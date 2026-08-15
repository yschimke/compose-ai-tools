// What the inspection layers draw, decided before any DOM exists.
//
// Three sources feed one list of boxes: the accessibility hierarchy (what a screen reader sees),
// and two kinds of design annotation (typography, theme). Turning them into entries is where all
// the judgement is, and almost none of it is visible in a screenshot — a rectangle drawn over a
// component looks equally right whether it is the correct node, a duplicate of its parent, or a
// finding that was silently dropped.
//
// The accessibility pass is the one with real rules in it, and each exists because of a specific
// wrong picture: stacked rectangles on the same pixels, a colour that says "fine" over a failure,
// and findings that vanish because their bounds did not line up with a node.

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

        out.push({
            kind: "a11y",
            bounds,
            title: node.label || "(unlabelled)",
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
}

export interface AnnotationPayload {
    annotations?: Annotation[];
}

/** Typography / theme entries, straight off the shared design-annotation payload. */
export function annotationEntries(
    payload: AnnotationPayload | null,
    kind: string,
): Entry[] {
    return (payload?.annotations ?? [])
        .filter((a): a is Annotation & { bounds: Bounds } =>
            Boolean(a && a.kind === kind && a.bounds),
        )
        .map((a) => ({
            kind,
            bounds: a.bounds,
            title: a.role || a.label || "",
            detail: a.role ? (a.label ?? "") : "",
            level: "info" as const,
            color: null,
        }));
}
