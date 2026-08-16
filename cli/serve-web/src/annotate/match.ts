// Pairing the annotations that describe the same element on each side.
//
// Figma commonly exposes a much deeper tree than Compose semantics — state layers, icon containers,
// dividers. Drawing both flattened trees independently gives unrelated ordinal numbers and, on a
// menu, puts ninety Figma boxes beside six Compose ones. So the design side is the inventory, each
// reference item is consumed at most once, and candidate bounds are mapped into the reference frame
// by the same width-derived uniform scale design-parity's structural layout diff uses. Aligning the
// two largest layout boxes also removes a preview scaffold/crop offset. Role and label similarity
// break geometric ties.

export interface Bounds {
    x: number;
    y: number;
    width: number;
    height: number;
}

export interface AnnotationItem {
    kind?: string;
    bounds?: Bounds;
    role?: string;
    label?: string;
    detail?: Record<string, unknown>;
    comparisonOrdinal?: number;
    marker?: string;
}

export interface MatchedSides {
    reference: AnnotationItem[];
    actual: AnnotationItem[];
}

/** A box has to be drawable before anything can be said about it. */
export function annotationHasBounds(item: AnnotationItem | null): boolean {
    const b = item?.bounds;
    return (
        !!b &&
        [b.x, b.y, b.width, b.height].every(Number.isFinite) &&
        b.width > 0 &&
        b.height > 0
    );
}

/**
 * The frame the two sides are aligned by: the largest LAYOUT box, or the largest box of any kind on
 * a payload with no layout annotations at all (an all-typography capture).
 */
export function largestAnnotationFrame(items: AnnotationItem[]): Bounds | null {
    const layouts = items.filter((item) => item.kind === "layout");
    const pool = layouts.length ? layouts : items;
    let largest: Bounds | null = null;
    for (const item of pool) {
        const b = item.bounds;
        if (!b) continue;
        if (!largest || b.width * b.height > largest.width * largest.height)
            largest = b;
    }
    return largest;
}

/** Candidate bounds in the reference frame's coordinates. */
export function mapAnnotationBounds(
    bounds: Bounds,
    referenceFrame: Bounds | null,
    actualFrame: Bounds | null,
    scale: number,
): Bounds {
    if (!referenceFrame || !actualFrame) return bounds;
    return {
        x: referenceFrame.x + (bounds.x - actualFrame.x) * scale,
        y: referenceFrame.y + (bounds.y - actualFrame.y) * scale,
        width: bounds.width * scale,
        height: bounds.height * scale,
    };
}

/** Normalised text for comparison. `px`, `dp` and `sp` all collapse to `u` — same length, one name. */
export function annotationText(value: unknown): string {
    return String(value ?? "")
        .trim()
        .toLowerCase()
        .replace(/(?:px|dp|sp)\b/g, "u");
}

/** "Button 1" and "Button 2" are the same family; so are "first item" and "last item". */
export function annotationRoleFamily(value: unknown): string {
    return annotationText(value)
        .replace(/\b(?:first|last)\b/g, "")
        .replace(/\b\d+\b/g, "")
        .replace(/[^a-z]+/g, " ")
        .trim();
}

/**
 * How badly two annotations fit each other.
 *
 * Position and size are measured as fractions of the FRAME, so the cost means the same thing on a
 * chip and on a full screen. Role and label agreement then nudge it — enough to break a geometric
 * tie between two boxes in the same place, never enough to overrule a real distance.
 */
export function annotationMatchCost(
    ref: AnnotationItem,
    cand: AnnotationItem,
    rb: Bounds,
    cb: Bounds,
    frame: Bounds | null,
): number {
    const fw =
        frame && frame.width > 0
            ? frame.width
            : Math.max(rb.width, cb.width, 1);
    const fh =
        frame && frame.height > 0
            ? frame.height
            : Math.max(rb.height, cb.height, 1);
    const position = Math.hypot(
        (rb.x + rb.width / 2 - (cb.x + cb.width / 2)) / fw,
        (rb.y + rb.height / 2 - (cb.y + cb.height / 2)) / fh,
    );
    const size =
        Math.abs(rb.width - cb.width) / fw +
        Math.abs(rb.height - cb.height) / fh;
    let cost = position + size * 0.5;
    const rr = annotationText(ref.role);
    const cr = annotationText(cand.role);
    if (rr && cr) {
        if (rr === cr) cost -= 0.06;
        else if (annotationRoleFamily(rr) === annotationRoleFamily(cr))
            cost -= 0.03;
        else cost += 0.02;
    }
    if (annotationText(ref.label) === annotationText(cand.label)) cost -= 0.04;
    return cost;
}

export function withAnnotationOrdinal(
    item: AnnotationItem,
    ordinal: number,
): AnnotationItem {
    return { ...item, comparisonOrdinal: ordinal };
}

/**
 * Minimum-cost one-to-one assignment for a rectangular matrix with rows ≤ columns.
 *
 * The Hungarian algorithm (Jonker–Volgenant form). It is here rather than a greedy nearest-match
 * because greedy is order-dependent: the first row takes the column it likes best, and a later row
 * with only one good option is left with whatever is spare. On a menu that shows up as two adjacent
 * items swapping numbers between renders, which reads as a layout change that never happened.
 */
export function minimumCostAssignment(costs: number[][]): number[] {
    const rows = costs.length;
    if (!rows) return [];
    const columns = costs[0].length;
    const u = new Float64Array(rows + 1);
    const v = new Float64Array(columns + 1);
    const p = new Int32Array(columns + 1);
    const way = new Int32Array(columns + 1);
    for (let i = 1; i <= rows; i++) {
        p[0] = i;
        let j0 = 0;
        const minv = new Float64Array(columns + 1).fill(Infinity);
        const used = new Uint8Array(columns + 1);
        do {
            used[j0] = 1;
            const i0 = p[j0];
            let delta = Infinity;
            let j1 = 0;
            for (let j = 1; j <= columns; j++) {
                if (used[j]) continue;
                const current = costs[i0 - 1][j - 1] - u[i0] - v[j];
                if (current < minv[j]) {
                    minv[j] = current;
                    way[j] = j0;
                }
                if (minv[j] < delta) {
                    delta = minv[j];
                    j1 = j;
                }
            }
            for (let k = 0; k <= columns; k++) {
                if (used[k]) {
                    u[p[k]] += delta;
                    v[k] -= delta;
                } else {
                    minv[k] -= delta;
                }
            }
            j0 = j1;
        } while (p[j0] !== 0);
        do {
            const previous = way[j0];
            p[j0] = p[previous];
            j0 = previous;
        } while (j0 !== 0);
    }
    const assignment = new Array<number>(rows);
    for (let column = 1; column <= columns; column++) {
        if (p[column]) assignment[p[column] - 1] = column - 1;
    }
    return assignment;
}

interface Indexed {
    item: AnnotationItem;
    index: number;
}

/**
 * Pair the two sides, per annotation kind.
 *
 * Layout output carries `min(reference, actual)` items when both sides are present — an extra layout
 * node has no counterpart and is simply omitted. Typography keeps BOTH sides' overflow, because
 * style grouping needs it: a design usage with no render counterpart is a gap, and a render usage
 * with no design counterpart is a local override, and dropping either hides the thing the page is
 * for.
 */
export function matchAnnotationItems(
    referenceIn: unknown,
    actualIn: unknown,
): MatchedSides {
    const reference = (Array.isArray(referenceIn) ? referenceIn : []).filter(
        annotationHasBounds,
    ) as AnnotationItem[];
    const actual = (Array.isArray(actualIn) ? actualIn : []).filter(
        annotationHasBounds,
    ) as AnnotationItem[];
    if (!reference.length || !actual.length) return { reference, actual };

    const referenceFrame = largestAnnotationFrame(reference);
    const actualFrame = largestAnnotationFrame(actual);
    const scale =
        referenceFrame && actualFrame && actualFrame.width > 0
            ? referenceFrame.width / actualFrame.width
            : 1;

    const kinds: string[] = [];
    for (const item of [...reference, ...actual]) {
        const kind = item.kind ?? "";
        if (!kinds.includes(kind)) kinds.push(kind);
    }

    const pairs: Array<{ reference: Indexed; actual: Indexed }> = [];
    let referenceOnly: Indexed[] = [];
    let actualOnly: Indexed[] = [];

    for (const kind of kinds) {
        const refs: Indexed[] = [];
        const cands: Indexed[] = [];
        reference.forEach((item, index) => {
            if ((item.kind ?? "") === kind) refs.push({ item, index });
        });
        actual.forEach((item, index) => {
            if ((item.kind ?? "") === kind) cands.push({ item, index });
        });
        if (!refs.length) {
            actualOnly = actualOnly.concat(cands);
            continue;
        }
        if (!cands.length) {
            referenceOnly = referenceOnly.concat(refs);
            continue;
        }

        const mapped = cands.map((cand) =>
            mapAnnotationBounds(
                cand.item.bounds!,
                referenceFrame,
                actualFrame,
                scale,
            ),
        );
        const usedRefs = new Set<number>();
        const usedCands = new Set<number>();
        // The matrix has to be rows ≤ columns, so whichever side is smaller supplies the rows and
        // the assignment is read back in that orientation.
        if (cands.length <= refs.length) {
            const candCosts = cands.map((cand, candIndex) =>
                refs.map((ref) =>
                    annotationMatchCost(
                        ref.item,
                        cand.item,
                        ref.item.bounds!,
                        mapped[candIndex],
                        referenceFrame,
                    ),
                ),
            );
            minimumCostAssignment(candCosts).forEach((refIndex, candIndex) => {
                usedRefs.add(refIndex);
                usedCands.add(candIndex);
                pairs.push({
                    reference: refs[refIndex],
                    actual: cands[candIndex],
                });
            });
        } else {
            const refCosts = refs.map((ref) =>
                cands.map((cand, candIndex) =>
                    annotationMatchCost(
                        ref.item,
                        cand.item,
                        ref.item.bounds!,
                        mapped[candIndex],
                        referenceFrame,
                    ),
                ),
            );
            minimumCostAssignment(refCosts).forEach((candIndex, refIndex) => {
                usedRefs.add(refIndex);
                usedCands.add(candIndex);
                pairs.push({
                    reference: refs[refIndex],
                    actual: cands[candIndex],
                });
            });
        }
        if (kind === "typography") {
            refs.forEach((ref, index) => {
                if (!usedRefs.has(index)) referenceOnly.push(ref);
            });
            cands.forEach((cand, index) => {
                if (!usedCands.has(index)) actualOnly.push(cand);
            });
        }
    }

    // Both legends follow DESIGN order, so a shared ordinal identifies the same element on the two
    // panels even when the Compose tree arrived in a different traversal order.
    pairs.sort((a, b) => a.reference.index - b.reference.index);
    const referenceOut: AnnotationItem[] = [];
    const actualOut: AnnotationItem[] = [];
    pairs.forEach((pair, index) => {
        referenceOut.push(
            withAnnotationOrdinal(pair.reference.item, index + 1),
        );
        actualOut.push(withAnnotationOrdinal(pair.actual.item, index + 1));
    });
    // The two overflow lists number INDEPENDENTLY from here, so ordinal 8 on the left and ordinal 8
    // on the right are different elements once the pairs run out. Worth knowing before "fixing" it
    // into a shared counter: the pairs are what the shared numbering is for, and an unpaired item
    // has nothing on the other side to agree with.
    for (const entry of referenceOnly) {
        referenceOut.push(
            withAnnotationOrdinal(entry.item, referenceOut.length + 1),
        );
    }
    for (const entry of actualOnly) {
        actualOut.push(withAnnotationOrdinal(entry.item, actualOut.length + 1));
    }
    return { reference: referenceOut, actual: actualOut };
}
