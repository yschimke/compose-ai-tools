// TypeScript port of the daemon's `SemanticsDiff` (Kotlin, issue #1785) so the history panel can
// diff two entries' captured `compose/semantics` trees client-side — no daemon round-trip, mirroring
// the client-side pixel diff in `pixelDiff.ts`. The algorithm must stay faithful to the Kotlin
// original (`data/layoutinspector/.../SemanticsDiff.kt` + `SemanticsRefs.kt`) so the panel, the
// daemon's `history/diff mode=semantics`, the CLI, and the MCP `diff_semantics` tool all agree.
//
// Nodes are matched by a stable, content-independent `ref` (a `/`-joined path anchored on
// testTag → role → "node", disambiguated by sibling occurrence). Text/label are NOT part of the ref,
// so a copy edit reports as a field change on the same ref rather than a remove + add. Positional
// bounds and the volatile per-render nodeId are ignored — bounds churn is the pixel diff's job.

export const SEMANTICS_DIFF_SCHEMA = "compose-semantics-diff/v1";
export const SEMANTICS_ROOT_REF = "r";

/** Subset of `compose/semantics` node fields the differ reads (parsed leniently from the sidecar). */
export interface SemanticsNode {
    ref?: string | null;
    role?: string | null;
    testTag?: string | null;
    label?: string | null;
    text?: string | null;
    mergeMode?: string | null;
    clickable?: boolean;
    editableText?: string | null;
    inputText?: string | null;
    /** Resolved line/overflow metrics, consolidated under one object in schema v6 (#1903). */
    textOverflow?: {
        truncated?: boolean | null;
        overflow?: string | null;
        lineCount?: number | null;
        maxLines?: number | null;
        didOverflowWidth?: boolean | null;
        didOverflowHeight?: boolean | null;
    } | null;
    children?: SemanticsNode[];
}

export interface SemanticsPayload {
    root: SemanticsNode;
}

export interface SemanticsFieldChange {
    field: string;
    from: string | null;
    to: string | null;
}

export interface SemanticsNodeChange {
    ref: string;
    /** testTag/role anchor of the node, for human-readable output. */
    anchor: string | null;
    changes: SemanticsFieldChange[];
}

export interface SemanticsNodeSummary {
    ref: string;
    role: string | null;
    testTag: string | null;
    text: string | null;
    label: string | null;
}

export interface SemanticsDelta {
    schema: string;
    added: SemanticsNodeSummary[];
    removed: SemanticsNodeSummary[];
    changed: SemanticsNodeChange[];
}

const GENERIC_ANCHOR = "node";
// Strip characters that would collide with the ref path grammar (`/`, `[`, `]`, whitespace).
const SANITIZE = new RegExp("[\\s/\\[\\]]+", "g");

function sanitize(value: string): string {
    return value.replace(SANITIZE, "_");
}

/** The anchor token for a node, before sibling disambiguation. */
export function semanticsAnchor(node: SemanticsNode): string {
    const tag = node.testTag?.trim();
    if (tag) {
        return `tag:${sanitize(tag)}`;
    }
    const role = node.role?.trim();
    if (role) {
        return `role:${sanitize(role)}`;
    }
    return GENERIC_ANCHOR;
}

function assignNode(node: SemanticsNode, ref: string): SemanticsNode {
    const children = node.children ?? [];
    const totals = new Map<string, number>();
    for (const child of children) {
        const a = semanticsAnchor(child);
        totals.set(a, (totals.get(a) ?? 0) + 1);
    }
    const seen = new Map<string, number>();
    const newChildren = children.map((child) => {
        const a = semanticsAnchor(child);
        const index = seen.get(a) ?? 0;
        seen.set(a, index + 1);
        const segment = (totals.get(a) ?? 0) > 1 ? `${a}[${index}]` : a;
        return assignNode(child, `${ref}/${segment}`);
    });
    return { ...node, ref, children: newChildren };
}

/** Assigns a stable [SemanticsNode.ref] to every node, rooted at [SEMANTICS_ROOT_REF]. */
export function assignSemanticsRefs(root: SemanticsNode): SemanticsNode {
    return assignNode(root, SEMANTICS_ROOT_REF);
}

type Extractor = (n: SemanticsNode) => string | null;

const asStr = (v: string | null | undefined): string | null =>
    v == null ? null : v;
const asBool = (v: boolean | null | undefined): string | null =>
    v == null ? null : String(v);
const asNum = (v: number | null | undefined): string | null =>
    v == null ? null : String(v);

// Semantic fields compared between two nodes sharing a ref, in stable report order. Mirrors the
// Kotlin COMPARED_FIELDS exactly.
const COMPARED_FIELDS: Array<[string, Extractor]> = [
    ["role", (n) => asStr(n.role)],
    ["testTag", (n) => asStr(n.testTag)],
    ["label", (n) => asStr(n.label)],
    ["text", (n) => asStr(n.text)],
    ["mergeMode", (n) => asStr(n.mergeMode)],
    ["clickable", (n) => String(n.clickable ?? false)],
    ["editableText", (n) => asStr(n.editableText)],
    ["inputText", (n) => asStr(n.inputText)],
    ["layoutTruncated", (n) => asBool(n.textOverflow?.truncated)],
    ["layoutOverflow", (n) => asStr(n.textOverflow?.overflow)],
    ["layoutLineCount", (n) => asNum(n.textOverflow?.lineCount)],
    ["layoutMaxLines", (n) => asNum(n.textOverflow?.maxLines)],
    ["layoutDidOverflowWidth", (n) => asBool(n.textOverflow?.didOverflowWidth)],
    ["layoutDidOverflowHeight", (n) => asBool(n.textOverflow?.didOverflowHeight)],
];

function byRef(root: SemanticsNode): Map<string, SemanticsNode> {
    const out = new Map<string, SemanticsNode>();
    const walk = (node: SemanticsNode): void => {
        if (node.ref) {
            out.set(node.ref, node);
        }
        (node.children ?? []).forEach(walk);
    };
    walk(root);
    return out;
}

function summary(node: SemanticsNode): SemanticsNodeSummary {
    return {
        ref: node.ref ?? SEMANTICS_ROOT_REF,
        role: asStr(node.role),
        testTag: asStr(node.testTag),
        text: asStr(node.text),
        label: asStr(node.label),
    };
}

function nodeChange(
    ref: string,
    base: SemanticsNode,
    head: SemanticsNode,
): SemanticsNodeChange | null {
    const changes: SemanticsFieldChange[] = [];
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
    return { ref, anchor: semanticsAnchor(head), changes };
}

/** Diffs two `compose/semantics` payloads. See module header for the matching contract. */
export function diffSemantics(
    base: SemanticsPayload,
    head: SemanticsPayload,
): SemanticsDelta {
    return diffSemanticsNodes(base.root, head.root);
}

export function diffSemanticsNodes(
    base: SemanticsNode,
    head: SemanticsNode,
): SemanticsDelta {
    const baseByRef = byRef(assignSemanticsRefs(base));
    const headByRef = byRef(assignSemanticsRefs(head));

    const removed = [...baseByRef.keys()]
        .filter((ref) => !headByRef.has(ref))
        .sort()
        .map((ref) => summary(baseByRef.get(ref)!));
    const added = [...headByRef.keys()]
        .filter((ref) => !baseByRef.has(ref))
        .sort()
        .map((ref) => summary(headByRef.get(ref)!));
    const changed: SemanticsNodeChange[] = [];
    for (const ref of [...baseByRef.keys()]
        .filter((ref) => headByRef.has(ref))
        .sort()) {
        const change = nodeChange(
            ref,
            baseByRef.get(ref)!,
            headByRef.get(ref)!,
        );
        if (change) {
            changed.push(change);
        }
    }
    return { schema: SEMANTICS_DIFF_SCHEMA, added, removed, changed };
}

export function semanticsDeltaIsEmpty(delta: SemanticsDelta): boolean {
    // Tolerate a lean-encoded delta (e.g. from the daemon's `history/diff mode=semantics`, whose
    // encoder omits empty arrays) — absent means empty, not a throw.
    return (
        (delta.added?.length ?? 0) === 0 &&
        (delta.removed?.length ?? 0) === 0 &&
        (delta.changed?.length ?? 0) === 0
    );
}
