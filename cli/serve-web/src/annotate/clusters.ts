// Surrounding the nearby usages of one style with a single box.
//
// A style used nine times down a list should read as one thing, so its usages are clustered by
// proximity and each cluster gets one box. The gap that counts as "nearby" is derived from the
// style's own line height rather than fixed, because 12px between two captions and 12px between two
// headlines are not the same distance.

import type { AnnotationItem, Bounds } from "./match.js";
import type { TypographyGroup } from "./typography.js";

/** Whether two boxes touch once each is grown by the gap. */
export function expandedBoxesTouch(
    left: Bounds,
    right: Bounds,
    xGap: number,
    yGap: number,
): boolean {
    return (
        left.x <= right.x + right.width + xGap &&
        right.x <= left.x + left.width + xGap &&
        left.y <= right.y + right.height + yGap &&
        right.y <= left.y + left.height + yGap
    );
}

export function unionBounds(items: AnnotationItem[]): Bounds {
    const boxes = items.map((item) => item.bounds!);
    const left = Math.min(...boxes.map((b) => b.x));
    const top = Math.min(...boxes.map((b) => b.y));
    const right = Math.max(...boxes.map((b) => b.x + b.width));
    const bottom = Math.max(...boxes.map((b) => b.y + b.height));
    return { x: left, y: top, width: right - left, height: bottom - top };
}

/**
 * The scale between the style's declared line height and its drawn pixels.
 *
 * The MEDIAN of the per-usage ratios, not the mean: one usage inside a scaled container would drag a
 * mean far enough to cluster the whole screen into one box. Clamped, because a line height the
 * capture got badly wrong would otherwise produce a gap larger than the frame.
 */
export function pixelScaleOf(group: TypographyGroup): number {
    const lineHeight = group.spec.lineHeight || 16;
    const ratios = group.items
        .map((item) => item.bounds!.height / Math.max(lineHeight, 1))
        .filter((ratio) => Number.isFinite(ratio) && ratio > 0)
        .sort((a, b) => a - b);
    const median = ratios.length ? ratios[Math.floor(ratios.length / 2)] : 1;
    return Math.max(0.5, Math.min(8, median));
}

/**
 * One box per run of nearby usages.
 *
 * The horizontal gap is far larger than the vertical one — four line heights against one and a
 * quarter — because text runs along a line. Two words side by side are one phrase; two lines apart
 * vertically are usually two separate pieces of content.
 *
 * A new item touching several existing clusters MERGES them, spliced in reverse index order so the
 * removals do not shift the indices still to be read. That loop is where items go missing silently
 * if it is written forwards.
 */
export function clusterTypography(group: TypographyGroup): Bounds[] {
    const lineHeight = group.spec.lineHeight || 16;
    const pixelScale = pixelScaleOf(group);
    const xGap = Math.max(12, lineHeight * pixelScale * 4);
    const yGap = Math.max(8, lineHeight * pixelScale * 1.25);
    const clusters: Array<{ items: AnnotationItem[] }> = [];
    for (const item of group.items) {
        const touching: number[] = [];
        clusters.forEach((cluster, index) => {
            if (
                cluster.items.some((other) =>
                    expandedBoxesTouch(item.bounds!, other.bounds!, xGap, yGap),
                )
            )
                touching.push(index);
        });
        if (!touching.length) {
            clusters.push({ items: [item] });
            continue;
        }
        const target = clusters[touching[0]];
        target.items.push(item);
        for (let i = touching.length - 1; i > 0; i -= 1) {
            target.items = target.items.concat(clusters[touching[i]].items);
            clusters.splice(touching[i], 1);
        }
    }
    return clusters.map((cluster) => unionBounds(cluster.items));
}
