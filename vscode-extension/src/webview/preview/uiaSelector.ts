// Build the `By.testTag(...)` / `By.text(...)` / chained `hasParent(...)`
// snippet the `uia/hierarchy` row action copies to the clipboard.
//
// Logic mirror of the Kotlin
// `HierarchySelectorBuilder.buildSelectorSnippet`
// (`data/uiautomator/core/.../HierarchySelectorBuilder.kt`) so the same
// rules apply whether the user clicks "Copy selector" in VS Code or the
// daemon emits a snippet through MCP. Keep the two in sync —
// `HierarchySelectorBuilderTest.kt` and `uiaSelector.test.ts` are the
// regression locks.

export interface UiaHierarchyNodeLite {
    text?: string | null;
    contentDescription?: string | null;
    testTag?: string | null;
    testTagAncestors?: readonly string[];
    role?: string | null;
}

/**
 * Choose the smallest selector that uniquely identifies [node] in the
 * full [nodes] list.
 *
 * Priority — same order the Kotlin helper applies:
 *   1. `testTag` when present and unique.
 *   2. `text` when present and unique.
 *   3. `contentDescription` when present and unique.
 *   4. Best non-unique anchor (`testTag` ▶ `text` ▶ `contentDescription`)
 *      plus `hasParent(...)` chained from the nearest non-blank
 *      `testTagAncestors` entry that actually disambiguates.
 *
 * Returns `null` when none of the above apply — the node has no
 * distinguishing axis. Callers should fall back to a UI hint ("nothing
 * unique to copy") rather than emitting a useless `By` chain.
 */
export function buildSelectorSnippet(
    node: UiaHierarchyNodeLite,
    nodes: readonly UiaHierarchyNodeLite[],
): string | null {
    const tag = nonBlank(node.testTag);
    const text = nonBlank(node.text);
    const desc = nonBlank(node.contentDescription);

    if (tag && uniqueBy(nodes, (n) => nonBlank(n.testTag) === tag)) {
        return `By.testTag(${quote(tag)})`;
    }
    if (text && uniqueBy(nodes, (n) => nonBlank(n.text) === text)) {
        return `By.text(${quote(text)})`;
    }
    if (
        desc &&
        uniqueBy(nodes, (n) => nonBlank(n.contentDescription) === desc)
    ) {
        return `By.desc(${quote(desc)})`;
    }

    // Non-unique anchor — chain through ancestors. Walk from the
    // nearest ancestor outward; the first one that uniquely identifies
    // (this node + parentTag pair) wins.
    const anchor = pickAnchor(node);
    if (!anchor) return null;
    const ancestors = node.testTagAncestors ?? [];
    // testTagAncestors is root-most → nearest; iterate reverse so the
    // nearest, narrowest scope is tried first.
    for (let i = ancestors.length - 1; i >= 0; i--) {
        const parentTag = nonBlank(ancestors[i]);
        if (!parentTag) continue;
        const matchCount = nodes.filter(
            (n) =>
                anchorMatches(n, anchor) &&
                (n.testTagAncestors ?? []).some(
                    (a) => nonBlank(a) === parentTag,
                ),
        ).length;
        if (matchCount === 1) {
            return `${renderAnchor(anchor)}.hasParent(By.testTag(${quote(parentTag)}))`;
        }
    }
    // Even with an ancestor chain we can't disambiguate — return the
    // anchor on its own so the user has something to start from; they
    // can refine by adding more chains by hand.
    return renderAnchor(anchor);
}

interface Anchor {
    kind: "tag" | "text" | "desc";
    value: string;
}

function pickAnchor(node: UiaHierarchyNodeLite): Anchor | null {
    const tag = nonBlank(node.testTag);
    if (tag) return { kind: "tag", value: tag };
    const text = nonBlank(node.text);
    if (text) return { kind: "text", value: text };
    const desc = nonBlank(node.contentDescription);
    if (desc) return { kind: "desc", value: desc };
    return null;
}

function renderAnchor(a: Anchor): string {
    switch (a.kind) {
        case "tag":
            return `By.testTag(${quote(a.value)})`;
        case "text":
            return `By.text(${quote(a.value)})`;
        case "desc":
            return `By.desc(${quote(a.value)})`;
    }
}

function anchorMatches(n: UiaHierarchyNodeLite, a: Anchor): boolean {
    switch (a.kind) {
        case "tag":
            return nonBlank(n.testTag) === a.value;
        case "text":
            return nonBlank(n.text) === a.value;
        case "desc":
            return nonBlank(n.contentDescription) === a.value;
    }
}

function nonBlank(s: string | null | undefined): string | null {
    if (!s) return null;
    return s.length === 0 ? null : s;
}

function uniqueBy<T>(items: readonly T[], pred: (t: T) => boolean): boolean {
    let n = 0;
    for (const i of items) {
        if (pred(i)) n += 1;
        if (n > 1) return false;
    }
    return n === 1;
}

/** Kotlin-source-friendly string quoting — `"` plus standard escapes. */
function quote(s: string): string {
    const escaped = s
        .replace(/\\/g, "\\\\")
        .replace(/"/g, '\\"')
        .replace(/\n/g, "\\n")
        .replace(/\r/g, "\\r")
        .replace(/\t/g, "\\t");
    return `"${escaped}"`;
}
