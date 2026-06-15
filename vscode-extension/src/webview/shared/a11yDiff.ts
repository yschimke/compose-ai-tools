// Client-side a11y-hierarchy data-diff for the history panel (#1872), mirroring `semanticsDiff.ts`
// and `themeDiff.ts`. Diffs two entries' captured `a11y/hierarchy` node lists so the panel can show
// which accessibility nodes were added / removed / changed between renders — no daemon round-trip.
//
// Nodes are matched by their stable `ref` (#1784) when present, falling back to a content anchor
// (`role` + `label`, sibling-disambiguated) for older captures that predate refs. Positional
// `boundsInScreen` is deliberately NOT compared — bounds churn is the pixel diff's job; the a11y
// diff reports semantic changes (label / role / states / merged). There is no Kotlin a11y differ
// yet; this is the first implementation.

export const A11Y_DIFF_SCHEMA = "a11y-hierarchy-diff/v1";

/** Subset of an `a11y/hierarchy` node the differ reads (parsed leniently from the sidecar). */
export interface A11yNode {
    ref?: string | null;
    label?: string | null;
    role?: string | null;
    states?: string[] | null;
    merged?: boolean | null;
    boundsInScreen?: string | null;
}

export interface A11yPayload {
    nodes?: A11yNode[] | null;
}

export interface A11yFieldChange {
    field: string;
    from: string | null;
    to: string | null;
}

export interface A11yNodeSummary {
    /** Stable match key (ref or derived anchor). */
    key: string;
    label: string | null;
    role: string | null;
}

export interface A11yNodeChange {
    key: string;
    label: string | null;
    role: string | null;
    changes: A11yFieldChange[];
}

export interface A11yDelta {
    schema: string;
    added: A11yNodeSummary[];
    removed: A11yNodeSummary[];
    changed: A11yNodeChange[];
}

const asStr = (v: string | null | undefined): string | null =>
    v == null || v === "" ? null : v;

function statesStr(states: string[] | null | undefined): string | null {
    if (!states || states.length === 0) {
        return null;
    }
    return [...states].sort().join(", ");
}

// Fields compared between two nodes sharing a key, in stable report order. Bounds excluded by design.
const COMPARED_FIELDS: Array<[string, (n: A11yNode) => string | null]> = [
    ["label", (n) => asStr(n.label)],
    ["role", (n) => asStr(n.role)],
    ["states", (n) => statesStr(n.states)],
    // The wire model defaults a missing `merged` to true (AccessibilityNode.merged); mirror that
    // here so a legacy sidecar (written before the field existed) doesn't report a spurious
    // `merged: ∅ → true` against a newer capture.
    ["merged", (n) => String(n.merged ?? true)],
];

/** Content anchor for a node lacking a stable ref: role + label, before sibling disambiguation. */
function anchor(node: A11yNode): string {
    return `role=${asStr(node.role) ?? ""}|label=${asStr(node.label) ?? ""}`;
}

/** Keys each node by `ref:<ref>` when present, else a sibling-disambiguated content anchor. */
function keyed(nodes: A11yNode[]): Map<string, A11yNode> {
    const out = new Map<string, A11yNode>();
    const seen = new Map<string, number>();
    for (const node of nodes) {
        const ref = node.ref?.trim();
        let key: string;
        if (ref) {
            key = `ref:${ref}`;
        } else {
            const a = anchor(node);
            const index = seen.get(a) ?? 0;
            seen.set(a, index + 1);
            key = `a:${a}[${index}]`;
        }
        if (!out.has(key)) {
            out.set(key, node);
        }
    }
    return out;
}

function summary(key: string, node: A11yNode): A11yNodeSummary {
    return { key, label: asStr(node.label), role: asStr(node.role) };
}

function nodeChange(
    key: string,
    base: A11yNode,
    head: A11yNode,
): A11yNodeChange | null {
    const changes: A11yFieldChange[] = [];
    for (const [field, extract] of COMPARED_FIELDS) {
        const from = extract(base);
        const to = extract(head);
        if (from !== to) {
            changes.push({ field, from, to });
        }
    }
    if (changes.length === 0) {
        return null;
    }
    return { key, label: asStr(head.label), role: asStr(head.role), changes };
}

/**
 * Diffs two `a11y/hierarchy` payloads. `base` is the older entry, `head` the newer, so `added` reads
 * as nodes newly present and `changed` as `old → new` — matching the panel's "this entry vs the
 * previous" framing and the semantics/theme sections.
 */
export function diffA11y(base: A11yPayload, head: A11yPayload): A11yDelta {
    const baseByKey = keyed(base.nodes ?? []);
    const headByKey = keyed(head.nodes ?? []);

    const removed = [...baseByKey.keys()]
        .filter((k) => !headByKey.has(k))
        .sort()
        .map((k) => summary(k, baseByKey.get(k)!));
    const added = [...headByKey.keys()]
        .filter((k) => !baseByKey.has(k))
        .sort()
        .map((k) => summary(k, headByKey.get(k)!));
    const changed: A11yNodeChange[] = [];
    for (const k of [...baseByKey.keys()]
        .filter((k) => headByKey.has(k))
        .sort()) {
        const change = nodeChange(k, baseByKey.get(k)!, headByKey.get(k)!);
        if (change) {
            changed.push(change);
        }
    }
    return { schema: A11Y_DIFF_SCHEMA, added, removed, changed };
}

export function a11yDeltaIsEmpty(delta: A11yDelta): boolean {
    return (
        (delta.added?.length ?? 0) === 0 &&
        (delta.removed?.length ?? 0) === 0 &&
        (delta.changed?.length ?? 0) === 0
    );
}
