// `<cp-reference-compare>` — the design-reference detail page: the design's own drawing, our render,
// and the difference between them, with the annotation redline over both.
// Replaces the `#cp-reference-compare` half of `assets/format-compare.js`.
//
// Two surfaces in one page. The comparison itself is three panels and a result line, driven by the
// scorer. The redline is the interesting half: layout annotations are instance-level (one numbered
// box per element, sharing an ordinal across the two panels), typography is style-level (usages
// grouped by resolved metrics, one letter per style, nearby usages under one cluster box, and the
// readable settings once in a table below).
//
// Renders nothing of its own; `serve.css` hides the tag. The decisions live next door:
// `annotate/match.ts` (which annotation on the left is which on the right), `annotate/typography.ts`
// (what counts as the same style), `annotate/clusters.ts` (what counts as nearby) and
// `annotate/fieldState.ts` (whether a field is a fidelity finding or a local override).

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import {
    fieldState,
    showsOptional,
    type FieldState,
} from "../annotate/fieldState.js";
import {
    matchAnnotationItems,
    type AnnotationItem,
    type Bounds,
} from "../annotate/match.js";
import { clusterTypography } from "../annotate/clusters.js";
import { rawScores, reportRenderUrl, resultLine } from "../annotate/report.js";
import { reportBody } from "../report/body.js";
import {
    groupTypography,
    pairTypography,
    typographyDefaults,
    typographyValue,
    type Field,
    type TypographyGroup,
    type TypographyPair,
} from "../annotate/typography.js";
import { compareApi } from "../compare/api.js";
import { compareImageUrls, type ComparisonResult } from "../compare/detail.js";
import { whenParsed } from "../dom/whenParsed.js";

interface Panel {
    shot: HTMLElement;
    image: HTMLImageElement;
    side: string;
    items: AnnotationItem[];
    layer: HTMLElement;
    boxes: Array<{ node: HTMLElement; bounds: Bounds }>;
}

@customElement("cp-reference-compare")
export class ReferenceCompare extends LitElement {
    private installed = false;
    private root!: HTMLElement;
    private panels: Panel[] = [];
    private toggles: HTMLInputElement[] = [];
    private cleanups: Array<() => void> = [];
    private generated: HTMLElement[] = [];
    private resizes: ResizeObserver | null = null;

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.resizes?.disconnect();
        this.resizes = null;
        for (const node of this.generated) node.remove();
        this.generated = [];
        this.panels = [];
        this.toggles = [];
        this.installed = false;
        super.disconnectedCallback();
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const root = document.getElementById("cp-reference-compare");
        if (!root) return false;
        this.installed = true;
        this.root = root;
        this.claimReport();
        void this.compare();
        this.wireOverlay();
        this.setUpAnnotations();
        return true;
    }

    // ---- the comparison ------------------------------------------------------

    private async compare(): Promise<void> {
        const resultText = this.root.querySelector<HTMLElement>(
            ".cp-reference-result",
        );
        if (!resultText) return;
        const referenceUrl = this.root.getAttribute("data-reference") ?? "";
        const actualUrl = this.root.getAttribute("data-actual") ?? "";
        const canvas =
            this.root.querySelector<HTMLCanvasElement>(".cp-reference-diff");
        // `format-compare.js` publishes the scorer from its own script tag, and a light-DOM element
        // is upgraded the moment the parser reaches ITS tag. The served page happens to put the two
        // in an order that works, which is correct-by-accident that any reordering breaks silently
        // — the page would simply say "unavailable" with the scorer one tag away. So a missing
        // handle waits for the document to finish parsing and asks once more before giving up.
        let compare = compareApi();
        if (!compare) {
            await whenParsed();
            compare = compareApi();
        }
        if (!compare || !canvas) {
            resultText.textContent = "Comparison unavailable";
            return;
        }
        try {
            const result = await compareImageUrls(
                compare,
                referenceUrl,
                actualUrl,
                canvas,
            );
            resultText.textContent = resultLine(result);
            this.fillReportBody(result);
        } catch {
            // A reference the host cannot produce is not an error worth a stack trace; the page
            // still shows both panels and the redline.
            resultText.textContent = "Comparison unavailable";
        }
    }

    /**
     * Hand the report field its render URL, before any scoring has happened.
     *
     * Early, deliberately. The field has one writer ([reportBody]) and three producers, and the
     * element selector is one of them: a reporter who picks an element while the scorer is still
     * running — or on a comparison the browser could not score at all — must still get their
     * selection into the filed issue. Waiting for a score to compose the body is what used to make
     * that impossible, silently.
     */
    private claimReport(): void {
        const body = document.getElementById(
            "cp-report-body",
        ) as HTMLInputElement | null;
        if (!reportBody.attach(body)) return;
        const actualUrl = this.root.getAttribute("data-actual") ?? "";
        if (actualUrl)
            reportBody.set({
                render: reportRenderUrl(actualUrl, location.href),
            });
    }

    private fillReportBody(result: ComparisonResult): void {
        // The report stays a GET form: page-derived values are written to its hidden INPUT and
        // nowhere else — never to an href or any other navigation sink.
        reportBody.set({ scores: rawScores(result) });
    }

    private wireOverlay(): void {
        const range =
            this.root.querySelector<HTMLInputElement>(".cp-overlay-range");
        const actual = this.root.querySelector<HTMLElement>(
            ".cp-reference-overlay img:last-child",
        );
        const value = this.root.querySelector<HTMLElement>(
            ".cp-overlay-control span",
        );
        if (!range || !actual || !value) return;
        const apply = () => {
            actual.style.opacity = String(parseInt(range.value, 10) / 100);
            value.textContent = `${range.value}%`;
        };
        this.on(range, "input", apply);
        apply();
    }

    // ---- the redline ---------------------------------------------------------

    private setUpAnnotations(): void {
        const payloadNode = document.getElementById("cp-annotations");
        if (!payloadNode) return;
        let raw: { reference?: unknown; actual?: unknown };
        try {
            raw = JSON.parse(payloadNode.textContent ?? "");
        } catch {
            return;
        }
        const payload = matchAnnotationItems(raw.reference, raw.actual);
        this.toggles = Array.from(
            this.root.querySelectorAll<HTMLInputElement>(
                "[data-cp-annotation-kind]",
            ),
        );
        if (!this.toggles.length) return;

        for (const shot of this.root.querySelectorAll<HTMLElement>(
            "[data-cp-annotated]",
        )) {
            const side = shot.getAttribute("data-cp-annotated") ?? "";
            if (side !== "reference" && side !== "actual") continue;
            const items = payload[side].filter((item) => item?.bounds);
            if (!items.length) continue;
            const image = shot.querySelector("img");
            if (!image) continue;
            const layer = document.createElement("div");
            layer.className = "cp-annotation-layer";
            shot.appendChild(layer);
            this.generated.push(layer);
            this.panels.push({ shot, image, side, items, layer, boxes: [] });
        }
        if (!this.panels.length) return;

        const referenceGroups = groupTypography(payload.reference);
        const actualGroups = groupTypography(payload.actual);
        const pairs = pairTypography(referenceGroups, actualGroups);
        const grid = this.root.querySelector(".cp-reference-grid");
        if (grid) {
            this.appendTypographySummary(
                grid,
                pairs,
                typographyDefaults(referenceGroups),
                typographyDefaults(actualGroups),
            );
        }

        for (const panel of this.panels) {
            this.drawLayout(panel);
            this.drawTypography(
                panel,
                panel.side === "reference" ? referenceGroups : actualGroups,
            );
        }
        this.wireTypographyHighlight();

        for (const toggle of this.toggles) {
            this.on(toggle, "change", () => this.syncKinds());
        }
        // Observed rather than only listening for `resize`: the panels are inside a responsive grid
        // that can reflow without the window changing size at all.
        if (typeof ResizeObserver === "function") {
            this.resizes = new ResizeObserver(() => this.place());
            for (const panel of this.panels) this.resizes.observe(panel.image);
        } else {
            this.on(window, "resize", () => this.place());
        }
        for (const panel of this.panels) {
            if (!panel.image.complete)
                this.on(panel.image, "load", () => this.place());
        }
        this.syncKinds();
    }

    /** Layout stays instance-level: one numbered box and one legend row per element. */
    private drawLayout(panel: Panel): void {
        const legend = document.createElement("ol");
        legend.className = "cp-annotation-legend";
        const layoutItems = panel.items.filter(
            (item) => item.kind !== "typography",
        );
        layoutItems.forEach((item, index) => {
            const ordinal = String(item.comparisonOrdinal ?? index + 1);
            const box = document.createElement("div");
            box.className = `cp-annotation cp-annotation--${item.kind}`;
            box.setAttribute("data-cp-kind", item.kind ?? "");
            box.title = item.role
                ? `${item.role} · ${item.label}`
                : (item.label ?? "");
            box.appendChild(this.badge(ordinal));
            panel.layer.appendChild(box);
            panel.boxes.push({ node: box, bounds: item.bounds! });

            const row = document.createElement("li");
            row.className = `cp-annotation-entry cp-annotation-entry--${item.kind}`;
            row.setAttribute("data-cp-kind", item.kind ?? "");
            row.appendChild(this.badge(ordinal));
            if (item.role) {
                const role = document.createElement("span");
                role.className = "cp-annotation-role";
                role.textContent = item.role;
                row.appendChild(role);
            }
            const text = document.createElement("span");
            text.className = "cp-annotation-spec";
            text.textContent = item.label ?? "";
            row.appendChild(text);
            legend.appendChild(row);
        });
        if (layoutItems.length) {
            panel.shot.parentNode?.appendChild(legend);
            this.generated.push(legend);
        }
    }

    /**
     * Typography is style-level: one lettered cluster box per run of nearby usages, plus an
     * invisible hit box per usage so hovering any individual word still lights the style.
     */
    private drawTypography(panel: Panel, groups: TypographyGroup[]): void {
        for (const group of groups) {
            for (const bounds of clusterTypography(group)) {
                const box = document.createElement("div");
                box.className =
                    "cp-annotation cp-annotation--typography cp-annotation--typography-cluster";
                box.setAttribute("data-cp-kind", "typography");
                box.setAttribute(
                    "data-cp-typography-marker",
                    group.marker ?? "",
                );
                box.title = `${group.spec.token || "Resolved style"} · ${group.spec.label}`;
                box.appendChild(this.badge(group.marker ?? ""));
                panel.layer.appendChild(box);
                panel.boxes.push({ node: box, bounds });
            }
            for (const item of group.items) {
                const hit = document.createElement("div");
                hit.className =
                    "cp-annotation cp-annotation--typography cp-annotation--typography-hit";
                hit.setAttribute("data-cp-kind", "typography");
                hit.setAttribute(
                    "data-cp-typography-marker",
                    group.marker ?? "",
                );
                panel.layer.appendChild(hit);
                panel.boxes.push({ node: hit, bounds: item.bounds! });
            }
        }
    }

    private badge(text: string): HTMLElement {
        const badge = document.createElement("span");
        badge.className = "cp-annotation-badge";
        badge.textContent = text;
        return badge;
    }

    // ---- the typography table ------------------------------------------------

    private appendTypographySummary(
        grid: Element,
        pairs: TypographyPair[],
        referenceDefaults: Map<string, TypographyGroup>,
        actualDefaults: Map<string, TypographyGroup>,
    ): void {
        if (!pairs.length) return;
        const summary = document.createElement("section");
        summary.className = "cp-typography-summary";
        summary.setAttribute("aria-label", "Typography style comparison");
        const heading = document.createElement("h2");
        heading.textContent = "Typography styles";
        summary.appendChild(heading);
        const list = document.createElement("div");
        list.className = "cp-typography-groups";
        for (const pair of pairs) {
            const row = document.createElement("article");
            row.className = "cp-typography-group";
            row.setAttribute("data-cp-typography-marker", pair.marker);
            row.tabIndex = 0;
            const marker = this.badge(pair.marker);
            marker.classList.add("cp-typography-marker");
            row.appendChild(marker);
            row.appendChild(
                this.inlineSide(
                    "Reference",
                    pair.reference,
                    pair.actual,
                    pair.reference &&
                        referenceDefaults.get(pair.reference.spec.token ?? ""),
                ),
            );
            const arrow = document.createElement("span");
            arrow.className = "cp-typography-arrow";
            arrow.setAttribute("aria-hidden", "true");
            arrow.textContent = "→";
            row.appendChild(arrow);
            row.appendChild(
                this.inlineSide(
                    "Actual",
                    pair.actual,
                    pair.reference,
                    pair.actual &&
                        actualDefaults.get(pair.actual.spec.token ?? ""),
                ),
            );
            list.appendChild(row);
        }
        summary.appendChild(list);
        // AFTER the grid, so the table reads under the three panels it describes.
        grid.parentNode?.insertBefore(summary, grid.nextSibling);
        this.generated.push(summary);
    }

    private inlineSide(
        label: string,
        group: TypographyGroup | undefined,
        other: TypographyGroup | undefined,
        baseline: TypographyGroup | undefined,
    ): HTMLElement {
        const side = document.createElement("span");
        side.className = "cp-typography-inline";
        const sideLabel = document.createElement("span");
        sideLabel.className = "cp-typography-side";
        sideLabel.textContent = label;
        side.appendChild(sideLabel);
        if (!group) {
            side.appendChild(document.createTextNode(" No matching usage"));
            return side;
        }
        if (group.spec.labelOnly) {
            side.appendChild(
                document.createTextNode(
                    ` ${group.spec.label || "Unspecified typography"}`,
                ),
            );
        }

        const mark = (node: HTMLElement, state: FieldState) => {
            if (state.changed || state.override)
                node.className = "cp-typography-changed";
            if (state.override) {
                node.classList.add("cp-typography-override");
                node.title = state.title;
            }
        };

        if (!group.spec.labelOnly) {
            for (const field of [
                "token",
                "family",
                "weight",
                "size",
            ] as Field[]) {
                side.appendChild(document.createTextNode(" · "));
                const value = document.createElement("span");
                let text = typographyValue(group.spec, field);
                if (field === "weight" && text !== "—") text = `wght ${text}`;
                if (field === "size" && group.spec.lineHeight !== undefined)
                    text += `/${typographyValue(group.spec, "lineHeight")}`;
                value.textContent = text;
                mark(value, fieldState(group, other, baseline, field));
                side.appendChild(value);
            }
        }

        // The three optional fields, each shown when it is non-default OR has changed — a value that
        // reverted to its default is exactly the case worth seeing.
        const optional: Array<
            ["tracking" | "style" | "axes", string, (v: string) => string]
        > = [
            ["tracking", "default", (v) => `tracking ${v}`],
            ["style", "normal", (v) => v],
            ["axes", "", (v) => `axes ${v}`],
        ];
        for (const [field, defaultValue, render] of optional) {
            const raw = group.spec[field];
            const state = fieldState(group, other, baseline, field);
            if (!showsOptional(raw, defaultValue, state)) continue;
            side.appendChild(document.createTextNode(" · "));
            const node = document.createElement("span");
            node.textContent = render(raw!);
            mark(node, state);
            side.appendChild(node);
        }

        const count = document.createElement("span");
        count.className = "cp-typography-count";
        count.textContent = `${group.items.length} ${group.items.length === 1 ? "usage" : "usages"}`;
        side.appendChild(document.createTextNode(" · "));
        side.appendChild(count);
        return side;
    }

    /**
     * Hovering or focusing a table row lights every box of that style, on BOTH panels.
     *
     * Hover and focus are tracked separately and OR'd: a pointer leaving a row that still has focus
     * must not clear the highlight the keyboard reader is relying on.
     */
    private wireTypographyHighlight(): void {
        for (const row of this.root.querySelectorAll<HTMLElement>(
            ".cp-typography-group",
        )) {
            const marker = row.getAttribute("data-cp-typography-marker");
            let hovered = false;
            let focused = false;
            const sync = () => {
                for (const node of this.root.querySelectorAll(
                    ".cp-annotation[data-cp-typography-marker]",
                )) {
                    if (
                        node.getAttribute("data-cp-typography-marker") ===
                        marker
                    )
                        node.classList.toggle(
                            "cp-annotation-active",
                            hovered || focused,
                        );
                }
            };
            this.on(row, "mouseenter", () => {
                hovered = true;
                sync();
            });
            this.on(row, "mouseleave", () => {
                hovered = false;
                sync();
            });
            this.on(row, "focus", () => {
                focused = true;
                sync();
            });
            this.on(row, "blur", () => {
                focused = false;
                sync();
            });
        }
    }

    /**
     * Place every box against its OWN panel.
     *
     * Annotation bounds are in each image's own pixel space and the two frames are routinely
     * different sizes, so a shared coordinate space would put one panel's redline at the wrong
     * scale. Each layer is scaled off its own image's rendered width.
     */
    private place(): void {
        for (const panel of this.panels) {
            const natural = panel.image.naturalWidth;
            if (!natural) continue;
            const scale = panel.image.clientWidth / natural;
            panel.layer.style.width = `${panel.image.clientWidth}px`;
            panel.layer.style.height = `${panel.image.clientHeight}px`;
            for (const box of panel.boxes) {
                box.node.style.left = `${box.bounds.x * scale}px`;
                box.node.style.top = `${box.bounds.y * scale}px`;
                box.node.style.width = `${box.bounds.width * scale}px`;
                box.node.style.height = `${box.bounds.height * scale}px`;
            }
        }
    }

    private syncKinds(): void {
        for (const toggle of this.toggles) {
            const kind = toggle.getAttribute("data-cp-annotation-kind");
            this.root.setAttribute(
                `data-annotate-${kind}`,
                toggle.checked ? "on" : "off",
            );
        }
        this.place();
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-reference-compare": ReferenceCompare;
    }
}
