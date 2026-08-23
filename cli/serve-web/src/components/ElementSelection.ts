// `<cp-element-selection>` — choosing which element the focused comparison's report is about.
//
// Without it a report says "something in this picture is wrong" and a triager re-derives the rest by
// eye. With it the report names the element, and the locator block carries that name plus the
// region it covers — the two fields `compose-parity-locator/v1` reserved for exactly this.
//
// Two ways to choose, and the difference is not cosmetic:
//
//   A TAG is an identity that survives a re-render, so an acceptance can resolve it later. It is
//   offered only where the published index describes the frame on screen (the server decides that
//   and simply omits `data-cp-tags` otherwise), and a tag carried by more than one node is listed
//   but never selectable — `count > 1` is not an identity, and picking one of several silently is
//   the failure the field exists to catch.
//
//   A REGION is a rectangle read off the displayed pixels, so it describes what the reporter saw by
//   construction and needs nothing from the server. It is converted into the render's own pixel
//   plane before it is recorded, because `v1` accepts no other space — a rectangle in the display
//   plane makes an element that never moved report as moved.
//
// Selection is a REPORT affordance. Nothing here accepts a difference or scores anything; the
// epic's boundary, and the easiest one in it to erode by accident.
//
// The decisions live next door: `report/elementTargets.ts` (what is offerable and how a drag is
// converted), `report/locator.ts` (how the two fields are written) and `report/body.ts` (which
// composes them with everything else the report carries).

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import { whenParsed } from "../dom/whenParsed.js";
import { reportBody } from "../report/body.js";
import type { Selection } from "../report/locator.js";
import {
    tagTargets,
    toRenderPixels,
    type TagTarget,
} from "../report/elementTargets.js";

@customElement("cp-element-selection")
export class ElementSelection extends LitElement {
    private installed = false;
    private root: HTMLElement | null = null;
    private frame: HTMLImageElement | null = null;
    private layer: HTMLElement | null = null;
    private picker: HTMLSelectElement | null = null;
    private dragButton: HTMLButtonElement | null = null;
    private clearButton: HTMLButtonElement | null = null;
    private state: HTMLElement | null = null;
    private targets: TagTarget[] = [];
    private selection: Selection = {};
    private cleanups: Array<() => void> = [];

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
        const root = document.getElementById("cp-element-selection");
        const panel = document.getElementById("cp-compare-actual");
        if (!root || !panel) return false;
        this.installed = true;
        this.root = root;
        this.frame = panel.querySelector("img");
        this.layer = document.getElementById("cp-selection-layer");
        this.picker =
            root.querySelector<HTMLSelectElement>(".cp-selection-tag");
        this.dragButton =
            root.querySelector<HTMLButtonElement>(".cp-selection-drag");
        this.clearButton = root.querySelector<HTMLButtonElement>(
            ".cp-selection-clear",
        );
        this.state = root.querySelector<HTMLElement>(".cp-selection-state");

        if (this.picker) this.on(this.picker, "change", () => this.chooseTag());
        if (this.dragButton)
            this.on(this.dragButton, "click", () => this.startDrag());
        if (this.clearButton)
            this.on(this.clearButton, "click", () => this.clear());
        // A reflow moves the frame under an already-drawn marquee, so the box has to be re-placed
        // rather than left where the pointer put it.
        this.on(window, "resize", () => this.placeMarquee());

        void this.loadTags();
        return true;
    }

    // ---- tags ----------------------------------------------------------------

    /**
     * Fetch the published index and fill the picker.
     *
     * The absence of `data-cp-tags` is the server saying a tag selection would not describe this
     * frame — an override or a pin has re-rendered it, or the catalog publishes no index — so the
     * picker stays hidden and the drag stays available. That is deliberately not the same as "no
     * tags": see `ServeWeb.referenceComparisonPage`'s `tagIndexUrl`.
     */
    private async loadTags(): Promise<void> {
        const url = this.root?.getAttribute("data-cp-tags");
        const picker = this.picker;
        if (!url || !picker) return;
        let payload: unknown = null;
        try {
            const response = await fetch(url, { credentials: "same-origin" });
            if (!response.ok) return;
            payload = await response.json();
        } catch {
            // A host that cannot answer leaves the page exactly as it was: the drag is still there,
            // and a picker that appeared empty would read as "this render has no tagged elements",
            // which is a different and false claim.
            return;
        }
        this.targets = tagTargets(payload);
        if (!this.targets.length) return;
        for (const target of this.targets) {
            const option = document.createElement("option");
            option.value = target.tag;
            // The tag verbatim, and the count only when it is the thing that disqualifies it.
            option.textContent = target.ambiguous
                ? `${target.tag} — ${target.count} nodes, not unique`
                : target.tag;
            // Listed but not choosable. Hiding an ambiguous tag entirely would leave someone
            // hunting for a tag they can see in the code; saying why is what lets them fix it.
            option.disabled = target.ambiguous;
            picker.appendChild(option);
        }
        picker.hidden = false;
    }

    private chooseTag(): void {
        const tag = this.picker?.value ?? "";
        const target = this.targets.find((entry) => entry.tag === tag);
        if (!tag || !target) return this.clear();
        // Belt and braces with the disabled option above: an ambiguous tag reaching here (a
        // keyboard path, a page script, a browser that ignores `disabled`) must not become an
        // element selector.
        if (target.ambiguous) return this.clear();
        // `bounds` may be absent — a tag whose every carrying node had a zero-area box still counts
        // — and an element with no region is a perfectly good record. It is `count` that makes a
        // tag an identity, not its geometry.
        this.apply({ element: target.tag, bounds: target.bounds });
    }

    // ---- region --------------------------------------------------------------

    /**
     * Drag a rectangle over the Actual frame.
     *
     * Over the *frame*, not the viewport: the rectangle has to be expressible in the render's own
     * pixels, and only the frame's box has a scale to convert by. The overlay takes the pointer
     * itself so a drag that wanders over the panel's own controls does not end in a click on them.
     */
    private startDrag(): void {
        const layer = this.layer;
        const frame = this.frame;
        if (!layer || !frame) return;
        layer.hidden = false;
        layer.textContent = "";
        this.sizeLayer();
        const box = document.createElement("div");
        box.className = "cp-selection-marquee";
        box.hidden = true;
        layer.appendChild(box);
        this.root?.setAttribute("data-dragging", "on");
        this.say("Drag a box over the render · Esc to cancel");

        let start: { x: number; y: number } | null = null;
        const offs: Array<() => void> = [];
        const stop = () => {
            for (const off of offs) off();
            layer.hidden = true;
            layer.textContent = "";
            this.root?.removeAttribute("data-dragging");
        };
        const local = (event: PointerEvent) => {
            const rect = frame.getBoundingClientRect();
            return {
                x: clamp(event.clientX - rect.left, 0, rect.width),
                y: clamp(event.clientY - rect.top, 0, rect.height),
            };
        };
        const draw = (
            a: { x: number; y: number },
            b: { x: number; y: number },
        ) => {
            box.hidden = false;
            box.style.left = `${Math.min(a.x, b.x)}px`;
            box.style.top = `${Math.min(a.y, b.y)}px`;
            box.style.width = `${Math.abs(a.x - b.x)}px`;
            box.style.height = `${Math.abs(a.y - b.y)}px`;
        };
        const listen = (
            target: EventTarget,
            type: string,
            handler: EventListener,
        ) => {
            target.addEventListener(type, handler, true);
            offs.push(() => target.removeEventListener(type, handler, true));
        };
        listen(layer, "pointerdown", ((event: PointerEvent) => {
            start = local(event);
            draw(start, start);
        }) as EventListener);
        listen(layer, "pointermove", ((event: PointerEvent) => {
            if (start) draw(start, local(event));
        }) as EventListener);
        listen(layer, "pointerup", ((event: PointerEvent) => {
            if (!start) return;
            const end = local(event);
            const rect = {
                x: Math.min(start.x, end.x),
                y: Math.min(start.y, end.y),
                width: Math.abs(start.x - end.x),
                height: Math.abs(start.y - end.y),
            };
            stop();
            const bounds = toRenderPixels(rect, frame);
            // A click with no drag is a cancel, not an error — that is what a stray click on a
            // full-frame overlay IS.
            if (!bounds) return this.describe();
            // A region names no element: keep any tag already chosen, so "this tag, in this corner"
            // is expressible, and drop nothing the reporter did not drop themselves.
            this.apply({ element: this.selection.element, bounds });
        }) as EventListener);
        listen(window, "keydown", ((event: KeyboardEvent) => {
            if (event.key !== "Escape") return;
            event.preventDefault();
            stop();
            this.describe();
        }) as EventListener);
    }

    private sizeLayer(): void {
        const layer = this.layer;
        const frame = this.frame;
        if (!layer || !frame) return;
        layer.style.width = `${frame.clientWidth}px`;
        layer.style.height = `${frame.clientHeight}px`;
    }

    private placeMarquee(): void {
        if (this.root?.getAttribute("data-dragging") === "on") this.sizeLayer();
    }

    // ---- what the report carries ---------------------------------------------

    private clear(): void {
        if (this.picker) this.picker.value = "";
        this.apply({});
    }

    private apply(selection: Selection): void {
        this.selection = selection;
        reportBody.set({ selection });
        this.describe();
        if (this.clearButton)
            this.clearButton.hidden = !selection.element && !selection.bounds;
    }

    /** The status line — the only place the page says what the report will actually name. */
    private describe(): void {
        const { element, bounds } = this.selection;
        const region = bounds
            ? `${bounds.width}×${bounds.height} at ${bounds.x},${bounds.y} in render pixels`
            : "";
        if (element && region)
            return this.say(`Reporting “${element}” · ${region}.`);
        if (element) return this.say(`Reporting “${element}”.`);
        if (region) return this.say(`Reporting a region · ${region}.`);
        this.say("Reporting the whole render.");
    }

    private say(text: string): void {
        if (this.state) this.state.textContent = text;
    }
}

function clamp(value: number, low: number, high: number): number {
    return Math.min(Math.max(value, low), high);
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-element-selection": ElementSelection;
    }
}
