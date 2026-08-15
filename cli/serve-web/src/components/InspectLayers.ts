// `<cp-inspect-layers>` — the viewer's inspection overlays. Replaces `assets/inspect.js`.
//
// Draws what the render is MADE OF over the frame it produced: numbered boxes on the image plus a
// legend beside it. Three layers, each a checkbox in the Overrides panel's "Inspect" group —
// accessibility (what a screen reader sees), typography, and theme attributes.
//
// The box + numbered-badge + legend idiom is deliberately the compare page's, because a spec label
// is far wider than the box it describes: the box carries an index, the readable text lives in the
// legend, and hovering either one lights up the other.
//
// Geometry: every source reports bounds in the RENDER's own pixel space, which is exactly the
// snapshot `<img>`'s natural size — so one uniform scale places every layer, re-applied on resize
// and whenever new pixels land.
//
// Renders nothing of its own; the layer and legend containers are server-rendered and this fills
// them. `serve.css` hides the tag.
//
// The decisions live next door: `inspect/entries.ts` (which nodes and findings become boxes at all,
// and what each says) and `inspect/layers.ts` (which endpoint a layer reads, and how a deep link
// names it).

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import { whenParsed } from "../dom/whenParsed.js";
import {
    a11yEntries,
    annotationEntries,
    type Entry,
} from "../inspect/entries.js";
import {
    LAYERS,
    activeLayers,
    baseFrom,
    dataUrlFor,
    fallbackUrl,
    inspectParam,
    kindsFromParam,
    sourcesFor,
    type LayerSpec,
} from "../inspect/layers.js";

interface Box {
    id: string;
    node: HTMLElement;
    bounds: Entry["bounds"];
}

@customElement("cp-inspect-layers")
export class InspectLayers extends LitElement {
    private installed = false;
    private root: HTMLElement | null = null;
    private img: HTMLImageElement | null = null;
    private layer: HTMLElement | null = null;
    private legend: HTMLElement | null = null;
    private toggles: HTMLInputElement[] = [];
    private previewId = "";

    /** Per `(source × frame)`: re-ticking a layer must not re-run a render of the same frame. */
    private cache = new Map<string, Promise<unknown>>();
    private cacheKey = "";
    private entries: Entry[] = [];
    private boxes: Box[] = [];
    private activeId: string | null = null;
    /** Bumped per refresh, so a slow fetch cannot draw over a newer one. */
    private generation = 0;
    private cleanups: Array<() => void> = [];
    private observer: MutationObserver | null = null;
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
        this.observer?.disconnect();
        this.observer = null;
        this.resizes?.disconnect();
        this.resizes = null;
        this.installed = false;
        this.generation++;
        super.disconnectedCallback();
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        this.root = document.querySelector<HTMLElement>(".cp-viewer");
        this.img = document.getElementById("cp-img") as HTMLImageElement | null;
        this.layer = document.getElementById("cp-inspect-layer");
        this.legend = document.getElementById("cp-inspect-legend");
        this.toggles = Array.from(
            document.querySelectorAll<HTMLInputElement>(".cp-inspect"),
        );
        // Inert on a viewer whose host can produce none of the three products.
        if (
            !this.root ||
            !this.img ||
            !this.layer ||
            !this.legend ||
            !this.toggles.length
        )
            return false;
        this.installed = true;
        this.previewId = this.root.getAttribute("data-preview-id") ?? "";

        for (const toggle of this.toggles) {
            this.on(toggle, "change", () => void this.refresh());
        }
        this.on(window, "resize", () => this.place());
        this.on(this.img, "load", () => this.place());
        // `load` alone is a race this element must not depend on winning. It fires only if the
        // frame was still in flight when the tag upgraded — and when it has already decoded, the
        // sole `place()` is the one at the end of `draw()`, whose measurements are whatever the
        // stage happened to be mid-settle. The legend appearing beside the stage narrows it; so
        // does a web font landing. Boxes measured against that transient width are permanently
        // off, at a scale and origin that still LOOK deliberate — which is exactly how this
        // shipped misplaced under a script-order change with no test noticing.
        //
        // Observing the image instead makes placement a consequence of its geometry rather than of
        // arriving at the right moment: every reflow that moves or resizes the frame re-places, the
        // first layout included. The stage is observed too, because a stage that grows around an
        // already-capped frame changes `offsetLeft` without changing the image's own box.
        if (typeof ResizeObserver === "function") {
            this.resizes = new ResizeObserver(() => this.place());
            this.resizes.observe(this.img);
            if (this.img.parentElement)
                this.resizes.observe(this.img.parentElement);
        }
        // New pixels ⇒ new geometry and new facts. `viewer.js` stamps `data-cp-src` once the
        // replacement frame has DECODED, so that attribute is the one honest "the render changed"
        // signal available from here — cheaper and more accurate than re-deriving the override
        // query on every control.
        if (typeof MutationObserver === "function") {
            this.observer = new MutationObserver(() => {
                if (this.activeKinds().length) void this.refresh();
                else this.place();
            });
            this.observer.observe(this.img, {
                attributes: true,
                attributeFilter: ["data-cp-src"],
            });
        }

        this.hydrate();
        if (this.activeKinds().length) void this.refresh();
        return true;
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }

    private activeKinds(): string[] {
        return this.toggles
            .filter((el) => el.checked && !el.disabled)
            .map((el) => el.getAttribute("data-cp-inspect") ?? "");
    }

    /** Restore from a deep link: `?inspect=a11y,typography`. */
    private hydrate(): void {
        const wanted = kindsFromParam(
            new URLSearchParams(location.search).get("inspect"),
        );
        if (!wanted.length) return;
        for (const toggle of this.toggles) {
            if (wanted.includes(toggle.getAttribute("data-cp-inspect") ?? ""))
                toggle.checked = true;
        }
    }

    /** The URL of the frame ON SCREEN, which `viewer.js` records once its bytes have decoded. */
    private frameUrl(): string {
        return this.img?.getAttribute("data-cp-src") ?? "";
    }

    private urlFor(source: string): string {
        const params = new URLSearchParams(location.search);
        return (
            dataUrlFor(this.frameUrl(), source) ??
            fallbackUrl(baseFrom(location.pathname), this.previewId, source, {
                token: params.get("token") ?? "",
                session: params.get("session") ?? "",
            })
        );
    }

    private fetchSource(source: string): Promise<unknown> {
        if (this.cacheKey !== this.frameUrl()) {
            this.cache = new Map();
            this.cacheKey = this.frameUrl();
        }
        const cached = this.cache.get(source);
        if (cached) return cached;
        const pending = fetch(this.urlFor(source), {
            credentials: "same-origin",
        })
            .then((response) => {
                if (!response.ok)
                    throw new Error(`${source} ${response.status}`);
                return response.json() as unknown;
            })
            // A host that cannot produce this product is not an error worth shouting about; the
            // layer simply draws nothing.
            .catch(() => null);
        this.cache.set(source, pending);
        return pending;
    }

    private async refresh(): Promise<void> {
        const kinds = this.activeKinds();
        this.syncUrl(kinds);
        if (!kinds.length) {
            this.entries = [];
            this.draw();
            return;
        }
        const generation = ++this.generation;
        this.legend?.setAttribute("aria-busy", "true");
        const names = sourcesFor(kinds);
        const results = await Promise.all(
            names.map((name) => this.fetchSource(name)),
        );
        if (generation !== this.generation) return;
        this.legend?.removeAttribute("aria-busy");
        const byName = new Map(names.map((name, i) => [name, results[i]]));
        this.entries = activeLayers(kinds).flatMap((spec) => {
            const payload = byName.get(spec.source) ?? null;
            return spec.kind === "a11y"
                ? a11yEntries(payload as never)
                : annotationEntries(payload as never, spec.kind);
        });
        this.draw();
    }

    /**
     * Deep-link state, written with `replaceState`.
     *
     * Ticking a layer must not stack a history entry the way a knob edit does: it is a reading aid
     * over the same frame, not a different render.
     */
    private syncUrl(kinds: string[]): void {
        try {
            const url = new URL(location.href);
            const value = inspectParam(kinds);
            if (value) url.searchParams.set("inspect", value);
            else url.searchParams.delete("inspect");
            history.replaceState(history.state, "", url.toString());
        } catch {
            // A browser that refuses the rewrite still gets the overlay.
        }
    }

    /**
     * Place every box against the image's CURRENT size.
     *
     * The stage centres the image, so the layer has to sit where the image sits rather than at the
     * stage's own origin — otherwise every box drifts left by half the slack.
     */
    private place(): void {
        const img = this.img;
        const layer = this.layer;
        if (!img || !layer || !img.naturalWidth || !this.boxes.length) return;
        const scale = img.clientWidth / img.naturalWidth;
        layer.style.width = `${img.clientWidth}px`;
        layer.style.height = `${img.clientHeight}px`;
        layer.style.left = `${img.offsetLeft}px`;
        layer.style.top = `${img.offsetTop}px`;
        for (const box of this.boxes) {
            box.node.style.left = `${box.bounds.x * scale}px`;
            box.node.style.top = `${box.bounds.y * scale}px`;
            box.node.style.width = `${box.bounds.width * scale}px`;
            box.node.style.height = `${box.bounds.height * scale}px`;
        }
    }

    /** Hovering either a box or its legend row lights up the other. */
    private highlight(id: string | null): void {
        this.activeId = id;
        for (const box of this.boxes) {
            box.node.classList.toggle("cp-inspect-box-active", box.id === id);
        }
        for (const row of this.legend?.querySelectorAll("[data-cp-entry]") ??
            []) {
            row.classList.toggle(
                "cp-inspect-entry-active",
                row.getAttribute("data-cp-entry") === id,
            );
        }
    }

    private draw(): void {
        const layer = this.layer;
        const legend = this.legend;
        if (!layer || !legend) return;
        layer.textContent = "";
        legend.textContent = "";
        this.boxes = [];
        if (!this.entries.length) {
            legend.hidden = true;
            this.root?.removeAttribute("data-inspect");
            return;
        }
        this.root?.setAttribute("data-inspect", "on");
        legend.hidden = false;
        legend.appendChild(this.legendHead());

        // In DECLARED order, not the order the entries happen to arrive in, so the legend's
        // sections read the same way every time.
        for (const spec of LAYERS) {
            const mine = this.entries.filter(
                (entry) => entry.kind === spec.kind,
            );
            if (!mine.length) continue;
            legend.appendChild(this.section(spec, mine));
        }
        this.place();
        this.highlight(this.activeId);
    }

    private legendHead(): HTMLElement {
        const head = document.createElement("div");
        head.className = "cp-inspect-legend-head";
        head.textContent = "Inspect";
        const count = document.createElement("span");
        count.className = "cp-inspect-legend-count";
        count.textContent = String(this.entries.length);
        head.appendChild(count);
        return head;
    }

    private section(spec: LayerSpec, entries: Entry[]): HTMLElement {
        const section = document.createElement("div");
        section.className = "cp-inspect-section";
        const title = document.createElement("div");
        title.className = "cp-inspect-section-head";
        title.textContent = `${spec.label} (${entries.length})`;
        section.appendChild(title);
        const list = document.createElement("ol");
        list.className = "cp-inspect-list";
        entries.forEach((entry, index) => {
            const id = `${spec.kind}-${index}`;
            const ordinal = String(index + 1);
            this.layer?.appendChild(this.box(entry, id, ordinal, spec.kind));
            list.appendChild(this.row(entry, id, ordinal, spec.kind));
        });
        section.appendChild(list);
        return section;
    }

    private box(
        entry: Entry,
        id: string,
        ordinal: string,
        kind: string,
    ): HTMLElement {
        const box = document.createElement("div");
        box.className = "cp-inspect-box";
        box.setAttribute("data-cp-kind", kind);
        box.setAttribute("data-level", entry.level);
        box.title = entry.detail
            ? `${entry.title} · ${entry.detail}`
            : entry.title;
        if (entry.color)
            box.style.setProperty("--cp-inspect-color", entry.color);
        const badge = document.createElement("span");
        badge.className = "cp-inspect-badge";
        badge.textContent = ordinal;
        box.appendChild(badge);
        box.addEventListener("mouseenter", () => this.highlight(id));
        box.addEventListener("mouseleave", () => this.highlight(null));
        this.boxes.push({ id, node: box, bounds: entry.bounds });
        return box;
    }

    private row(
        entry: Entry,
        id: string,
        ordinal: string,
        kind: string,
    ): HTMLElement {
        const row = document.createElement("li");
        row.className = "cp-inspect-entry";
        row.setAttribute("data-cp-entry", id);
        row.setAttribute("data-cp-kind", kind);
        row.setAttribute("data-level", entry.level);
        row.tabIndex = 0;
        if (entry.color)
            row.style.setProperty("--cp-inspect-color", entry.color);
        const marker = document.createElement("span");
        marker.className = "cp-inspect-badge";
        marker.textContent = ordinal;
        row.appendChild(marker);
        const text = document.createElement("span");
        text.className = "cp-inspect-text";
        const strong = document.createElement("strong");
        strong.textContent = entry.title;
        text.appendChild(strong);
        if (entry.detail) {
            const sub = document.createElement("span");
            sub.className = "cp-inspect-detail";
            sub.textContent = entry.detail;
            text.appendChild(sub);
        }
        row.appendChild(text);
        // Focus as well as hover: the legend is a keyboard path into the same highlight.
        row.addEventListener("mouseenter", () => this.highlight(id));
        row.addEventListener("mouseleave", () => this.highlight(null));
        row.addEventListener("focus", () => this.highlight(id));
        row.addEventListener("blur", () => this.highlight(null));
        return row;
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-inspect-layers": InspectLayers;
    }
}
