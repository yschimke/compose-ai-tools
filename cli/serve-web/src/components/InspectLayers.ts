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
// Mounted on two surfaces. The viewer's tag carries no attributes and gets the wiring this element
// was written for. The focused comparison mounts a second instance over its Actual panel, naming its
// own frame, layer, legend and toggles — which is how the DERIVED semantics layers reach the page
// where a parity report is filed. `inspect/host.ts` is the whole of that difference; nothing below
// this line knows which page it is on.
//
// The decisions live next door: `inspect/entries.ts` (which nodes and findings become boxes at all,
// and what each says), `inspect/layers.ts` (which endpoint a layer reads, and how a deep link names
// it) and `inspect/host.ts` (which DOM it draws into).

import { ControllerElement, customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import {
    a11yEntries,
    annotationEntries,
    type Entry,
} from "../inspect/entries.js";
import {
    LAYERS,
    activeLayers,
    dataUrlFor,
    fallbackUrl,
    inspectParam,
    kindsFromParam,
    sourcesFor,
    type LayerSpec,
} from "../inspect/layers.js";
import { resolveHost, type InspectHost } from "../inspect/host.js";

interface Box {
    id: string;
    node: HTMLElement;
    bounds: Entry["bounds"];
}

@customElement("cp-inspect-layers")
export class InspectLayers extends ControllerElement {
    private installed = false;
    private host: InspectHost | null = null;
    private img: HTMLImageElement | null = null;
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
        this.host = null;
        this.generation++;
        super.disconnectedCallback();
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        // Inert where the host can produce none of the products — a viewer without the inspect
        // group, or a page whose mount tag names parts it does not have.
        const host = resolveHost(this);
        if (!host) return false;
        this.host = host;
        this.syncTarget();
        this.installed = true;
        this.previewId = host.root.getAttribute("data-preview-id") ?? "";

        for (const toggle of host.toggles) {
            this.on(toggle, "change", () => void this.refresh());
        }
        this.on(window, "resize", () => this.place());
        this.on(host.frame, "load", () => this.place());
        if (host.specFrame) this.on(host.specFrame, "load", () => this.place());
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
            this.resizes.observe(host.frame);
            if (host.specFrame) this.resizes.observe(host.specFrame);
            if (host.frame.parentElement)
                this.resizes.observe(host.frame.parentElement);
        }
        // New pixels ⇒ new geometry and new facts. `viewer.js` stamps `data-cp-src` once the
        // replacement frame has DECODED, so that attribute is the one honest "the render changed"
        // signal available from here — cheaper and more accurate than re-deriving the override
        // query on every control.
        if (typeof MutationObserver === "function") {
            this.observer = new MutationObserver(() => {
                this.syncTarget();
                if (this.activeKinds().length) void this.refresh();
                else this.place();
            });
            this.observer.observe(host.frame, {
                attributes: true,
                attributeFilter: [host.frameSource],
            });
            this.observer.observe(host.root, {
                attributes: true,
                attributeFilter: ["data-mode", "data-spec-view"],
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
        return (this.host?.toggles ?? [])
            .filter((el) => el.checked && !el.disabled)
            .map((el) => el.getAttribute("data-cp-inspect") ?? "");
    }

    private isSpec(): boolean {
        if (!this.host?.hasSpecModes) return false;
        return (
            this.host.root.getAttribute("data-mode") === "spec" &&
            this.host.root.getAttribute("data-spec-view") === "spec"
        );
    }

    private isSpecComparison(): boolean {
        if (!this.host?.hasSpecModes) return false;
        const view = this.host.root.getAttribute("data-spec-view");
        return (
            this.host.root.getAttribute("data-mode") === "spec" &&
            (view === "diff" || view === "triptych" || view === "slider")
        );
    }

    /** Inspection follows the surface on the stage: Compose render or imported Figma raster. */
    private syncTarget(): void {
        const host = this.host;
        if (!host) return;
        this.img =
            this.isSpec() && host.specFrame ? host.specFrame : host.frame;
    }

    /** Restore from a deep link: `?inspect=a11y,typography`. */
    private hydrate(): void {
        const wanted = kindsFromParam(
            new URLSearchParams(location.search).get("inspect"),
        );
        if (!wanted.length) return;
        for (const toggle of this.host?.toggles ?? []) {
            if (wanted.includes(toggle.getAttribute("data-cp-inspect") ?? ""))
                toggle.checked = true;
        }
    }

    /** The URL of the frame ON SCREEN, which `viewer.js` records once its bytes have decoded. */
    private frameUrl(): string {
        if (this.isSpec())
            return (
                document
                    .getElementById("cp-spec-compare")
                    ?.getAttribute("data-reference") ?? "spec"
            );
        return this.img?.getAttribute(this.host?.frameSource ?? "") ?? "";
    }

    private referenceAnnotations(): unknown {
        const node = document.getElementById("cp-spec-annotations");
        if (!node) return null;
        try {
            const payload = JSON.parse(node.textContent ?? "") as {
                reference?: unknown;
            };
            return payload.reference ?? null;
        } catch {
            return null;
        }
    }

    private urlFor(source: string): string {
        const params = new URLSearchParams(location.search);
        return (
            dataUrlFor(this.frameUrl(), source) ??
            fallbackUrl(this.host?.base ?? "", this.previewId, source, {
                token: params.get("token") ?? "",
                session: params.get("session") ?? "",
            })
        );
    }

    private fetchSource(source: string): Promise<unknown> {
        if (this.isSpec())
            return Promise.resolve(
                source === "annotations"
                    ? { annotations: this.referenceAnnotations() }
                    : null,
            );
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
            //
            // But it must not be remembered as this frame's answer. The cache exists so re-ticking
            // a layer doesn't re-run a render of the SAME pixels — a failure ran no render worth
            // reusing, and caching it made one transient 500 blank the layer for as long as the
            // frame stayed on screen: every re-tick replayed the stored null, so the overlay looked
            // permanently broken on a server that had already recovered.
            .catch(() => {
                if (this.cache.get(source) === pending)
                    this.cache.delete(source);
                return null;
            });
        this.cache.set(source, pending);
        return pending;
    }

    private async refresh(): Promise<void> {
        const kinds = this.activeKinds();
        // Every refresh supersedes the previous one, including transitions into a comparison view
        // where this element deliberately paints nothing. Otherwise a request started on Compose
        // can resolve after Diff/Triptych/Slider takes the stage and repaint a stale render-only
        // legend over the comparison.
        const generation = ++this.generation;
        this.syncUrl(kinds);
        window.dispatchEvent(
            new CustomEvent("cp-inspect-change", { detail: { kinds } }),
        );
        if (this.isSpecComparison()) {
            this.entries = [];
            this.draw();
            return;
        }
        if (!kinds.length) {
            this.entries = [];
            this.draw();
            return;
        }
        this.host?.legend.setAttribute("aria-busy", "true");
        const names = sourcesFor(kinds);
        const results = await Promise.all(
            names.map((name) => this.fetchSource(name)),
        );
        if (generation !== this.generation) return;
        this.host?.legend.removeAttribute("aria-busy");
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
        const layer = this.host?.layer;
        if (!img || !layer || !img.naturalWidth || !this.boxes.length) return;
        const scale = img.clientWidth / img.naturalWidth;
        layer.style.width = `${img.clientWidth}px`;
        layer.style.height = `${img.clientHeight}px`;
        if (this.host?.anchor === "offset") {
            layer.style.left = `${img.offsetLeft}px`;
            layer.style.top = `${img.offsetTop}px`;
        }
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
        for (const row of this.host?.legend.querySelectorAll(
            "[data-cp-entry]",
        ) ?? []) {
            row.classList.toggle(
                "cp-inspect-entry-active",
                row.getAttribute("data-cp-entry") === id,
            );
        }
    }

    private draw(): void {
        const layer = this.host?.layer;
        const legend = this.host?.legend;
        if (!layer || !legend) return;
        layer.textContent = "";
        legend.textContent = "";
        this.boxes = [];
        if (!this.entries.length) {
            legend.hidden = true;
            this.host?.root.removeAttribute("data-inspect");
            return;
        }
        this.host?.root.setAttribute("data-inspect", "on");
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
            this.host?.layer.appendChild(
                this.box(entry, id, ordinal, spec.kind),
            );
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
        box.title =
            entry.tooltip ||
            (entry.detail ? `${entry.title} · ${entry.detail}` : entry.title);
        if (entry.color)
            box.style.setProperty("--cp-inspect-color", entry.color);
        const badge = document.createElement("span");
        badge.className = "cp-inspect-badge";
        badge.textContent = ordinal;
        box.appendChild(badge);
        box.addEventListener("mouseenter", () => this.highlight(id));
        box.addEventListener("mouseleave", () => this.highlight(null));
        // Only where the host says a click means something (see `InspectHost.selectable`). The
        // viewer's boxes stay inert, so its behaviour and its markup are both unchanged.
        //
        // The bounds travel as they are: every source reports them in the RENDER's own pixel space,
        // which is the plane `compose-parity-locator/v1` accepts, so a box click needs no conversion
        // and cannot acquire the display-plane error a drag has to be converted out of.
        if (this.host?.selectable) {
            box.classList.add("cp-inspect-box--selectable");
            box.addEventListener("click", (event) => {
                event.preventDefault();
                event.stopPropagation();
                this.announcePick(entry);
            });
        }
        this.boxes.push({ id, node: box, bounds: entry.bounds });
        return box;
    }

    /**
     * Tell the page which part of the render was picked.
     *
     * One method for the box and its legend row, because they name the same element and must record
     * the same thing — a keyboard reader and a pointer reader filing different reports for the same
     * click target is the kind of divergence nobody would notice until the two reports disagreed.
     *
     * The bounds travel unconverted: every annotation source reports in the RENDER's own pixel
     * space, which is the plane `compose-parity-locator/v1` accepts.
     */
    private announcePick(entry: Entry): void {
        window.dispatchEvent(
            new CustomEvent("cp-element-pick", {
                detail: { bounds: entry.bounds, label: entry.title },
            }),
        );
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
        if (entry.tooltip) row.title = entry.tooltip;
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
        // …and, where a pick means something, a keyboard path into the SELECTION as well.
        //
        // The box cannot be that path: it is an unfocusable `div` positioned over the frame, and
        // the layout layer's interior does not even take a pointer. The row is already focusable
        // and already names the thing the box outlines, so it is the affordance a keyboard reader
        // reaches anyway. Without this, a page whose tag picker is withheld — a catalog that
        // publishes annotations but no tag index — offers a keyboard user no way to select at all,
        // since the drag is pointer-only.
        if (this.host?.selectable) {
            row.classList.add("cp-inspect-entry--selectable");
            row.setAttribute("role", "button");
            row.addEventListener("click", () => this.announcePick(entry));
            row.addEventListener("keydown", (event: KeyboardEvent) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                // Space scrolls a focused element by default; a selection is not a scroll.
                event.preventDefault();
                this.announcePick(entry);
            });
        }
        return row;
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-inspect-layers": InspectLayers;
    }
}
