// `<cp-rc-lanes>` — the compare page's "Remote Compose players" view. Replaces `assets/rc-lanes.js`.
//
// Every player's published render of the same `ir/*.rc` document side by side, and — once a column
// is picked as the reference — a pixel diff of every other column against it. The renders and the
// baked-PNG diffs were computed offline by `scripts/design-artifacts/rc-compare.mjs` and published
// on the delivery branch, so nothing here renders a document: it places `<img>`s and, for the one
// question the build cannot answer (two players against each other), diffs two of them on a canvas.
//
// This one renders nothing of its own. The table is server-rendered — that is what makes the page
// readable before any of this runs, and with JavaScript off — so the element's job is to observe,
// measure and write into cells it does not own. `serve.css` hides the tag.
//
// The decisions live next door: `rc/rowPlan.ts` (which of a row's lanes get a number, and which of
// them need measuring at all), `rc/pixelDiff.ts` (the metric itself, previously nine untested magic
// constants), `rc/rowFilter.ts` (the shared search box, and what the status line admits about where
// a number came from).

import { ControllerElement, customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import { urlState } from "../urlState.js";
import {
    NO_REFERENCE,
    laneIdsOf,
    referenceFrom,
    shortLabelOf,
    type RcModel,
} from "../rc/model.js";
import {
    DEFAULT_THRESHOLD,
    diffPixels,
    sameSize,
    type Pixels,
} from "../rc/pixelDiff.js";
import {
    band,
    percentText,
    planRow,
    sizeMismatchText,
    type Band,
} from "../rc/rowPlan.js";
import { countLabel, filterRows, statusFor } from "../rc/rowFilter.js";

/** The global `format-compare.js` calls — it owns the format switch and the shared search box. */
interface RcLanesApi {
    filter(query: string): void;
    refresh(): void;
}

declare global {
    interface Window {
        cpRcLanes?: RcLanesApi;
    }
}

@customElement("cp-rc-lanes")
export class RcLanes extends ControllerElement {
    private model: RcModel | null = null;
    private laneIds: string[] = [];
    private threshold = DEFAULT_THRESHOLD;
    private section: HTMLElement | null = null;
    private rows: HTMLElement[] = [];
    private reference = NO_REFERENCE;
    /**
     * Bumped on every reference change. Every asynchronous step carries the token it started under
     * and abandons itself if it no longer matches, so switching reference mid-scroll drops the
     * previous pass instead of racing it into the same cells.
     */
    private pass = 0;
    private installed = false;
    private observer: IntersectionObserver | null = null;
    private scored = new WeakMap<HTMLElement, number>();
    private images = new Map<string, Promise<HTMLImageElement>>();
    private cleanups: Array<() => void> = [];

    /**
     * Set up NOW if the markup is already there, and only wait for the parse if it is not.
     *
     * The server emits this tag as the last thing in the compare section, immediately after the
     * inline model — so by the time the parser upgrades it, everything it reads exists, and
     * `install()` says so by finding `#cp-rc-model`. That matters because `format-compare.js` runs
     * its first pass as soon as it loads and calls `window.cpRcLanes.filter()` on the way through:
     * deferring unconditionally to `DOMContentLoaded` would publish the global after the only
     * caller had already looked for it, and the lanes view would open unfiltered with no count.
     *
     * The fallback is what keeps that from being an ordering accident — a page that emits the tag
     * somewhere else still works, one parse later.
     */
    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.observer?.disconnect();
        this.observer = null;
        if (window.cpRcLanes === this.api) delete window.cpRcLanes;
        // Everything this element owns lives OUTSIDE it — listeners on the picker, an observer on
        // the rows, a global — so a teardown has to be undoable. Clearing the flag lets `install()`
        // wire it all up again if the tag is reinserted; without that the picker and the shared
        // filter would come back inert after any DOM relocation. The pass is bumped so work still
        // in flight abandons itself rather than writing into a table nobody is observing.
        this.installed = false;
        this.pass++;
        super.disconnectedCallback();
    }

    private api: RcLanesApi = {
        filter: (query: string) => this.filter(query),
        refresh: () => this.apply(),
    };

    /** @return whether the page was ready to be wired up; false means "try again after the parse". */
    private install(): boolean {
        // Tracked separately from `model`, which an in-flight pass still reads after a disconnect.
        if (!this.isConnected || this.installed) return true;
        this.section = document.getElementById("cp-rc-lanes");
        const modelNode = document.getElementById("cp-rc-model");
        if (!this.section || !modelNode) return false;
        this.installed = true;
        try {
            this.model = JSON.parse(modelNode.textContent || "null") as RcModel;
        } catch {
            // A payload the server could not have produced. The table stays as served — every
            // render is already in it — rather than the page erroring on load. Nothing to retry.
            return true;
        }
        if (!this.model?.lanes) {
            this.model = null;
            return true;
        }
        this.laneIds = laneIdsOf(this.model);
        if (typeof this.model.threshold === "number")
            this.threshold = this.model.threshold;
        this.rows = Array.from(
            this.section.querySelectorAll<HTMLElement>(".cp-rc-row"),
        );
        this.reference = referenceFrom(location.search, this.laneIds);

        this.observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    if (!entry.isIntersecting) continue;
                    const row = entry.target as HTMLElement;
                    if (this.reference === NO_REFERENCE) continue;
                    if (this.scored.get(row) === this.pass) continue;
                    this.scored.set(row, this.pass);
                    void this.scoreRow(row, this.pass);
                }
            },
            // Start a row a little before it arrives, so scrolling meets numbers rather than
            // watching them appear.
            { rootMargin: "400px 0px" },
        );

        for (const button of this.buttons()) {
            const onClick = () => {
                this.reference =
                    button.getAttribute("data-rc-ref") ?? NO_REFERENCE;
                urlState()?.push({
                    ref: this.reference === NO_REFERENCE ? "" : this.reference,
                });
                this.apply();
            };
            button.addEventListener("click", onClick);
            this.cleanups.push(() =>
                button.removeEventListener("click", onClick),
            );
        }
        // Unsubscribed with the rest: `onPop` is a `popstate` listener on `window`, so without
        // this a detached-and-reinserted element would stack one callback per connection — every
        // Back would then clear the rows and restart the diff work once per prior life — and a
        // permanently detached one would keep writing into a table it no longer observes.
        const offPop = urlState()?.onPop(() => {
            this.reference = referenceFrom(location.search, this.laneIds);
            this.apply();
        });
        if (offPop) this.cleanups.push(offPop);

        window.cpRcLanes = this.api;
        this.apply();
        return true;
    }

    private buttons(): HTMLElement[] {
        return Array.from(
            this.section?.querySelectorAll<HTMLElement>("[data-rc-ref]") ?? [],
        );
    }

    private apply(): void {
        if (!this.section) return;
        this.pass++;
        for (const button of this.buttons()) {
            button.setAttribute(
                "aria-pressed",
                String(button.getAttribute("data-rc-ref") === this.reference),
            );
        }
        this.section.setAttribute("data-reference", this.reference);
        // Unconditionally, and BEFORE re-observing: `observe()` on an already-observed target is a
        // no-op, so switching straight from one reference to another would leave every on-screen
        // row blank until it scrolled out and back. Disconnecting queues a fresh initial callback.
        this.observer?.disconnect();
        for (const row of this.rows) {
            this.clearRow(row);
            this.scored.delete(row);
        }
        const status = document.getElementById("cp-rc-status");
        if (status) {
            status.textContent = statusFor(
                this.reference,
                shortLabelOf(this.model!, this.reference),
            );
        }
        if (this.reference === NO_REFERENCE) return;
        for (const row of this.rows)
            if (!row.hidden) this.observer?.observe(row);
    }

    private filter(query: string): void {
        const preview =
            new URLSearchParams(location.search).get("preview") ?? "";
        const { keep, visible, empty } = filterRows(
            this.rows.map((row) => ({
                hay: row.getAttribute("data-hay") ?? "",
                previewIds: row.getAttribute("data-preview-ids") ?? "",
            })),
            query,
            preview,
        );
        this.rows.forEach((row, i) => {
            row.hidden = !keep[i];
        });
        const count = document.getElementById("cp-compare-count");
        if (count) count.textContent = countLabel(visible);
        const emptyNote = document.getElementById("cp-rc-empty");
        if (emptyNote) emptyNote.hidden = !empty;
        if (this.reference === NO_REFERENCE) return;
        // A row filtered back into view has never been scored, so it has to start observing —
        // and one filtered out must stop, or it keeps a pass alive against a hidden row.
        for (const row of this.rows) {
            if (row.hidden) this.observer?.unobserve(row);
            else this.observer?.observe(row);
        }
    }

    private async scoreRow(row: HTMLElement, pass: number): Promise<void> {
        const model = this.model?.rows[Number(row.getAttribute("data-row"))];
        const scores = row.querySelector<HTMLElement>("[data-scores]");
        if (!model || !scores) return;
        this.cellFor(row, this.reference)?.classList.add("is-reference");
        // Say out loud that this row is mid-measurement, and when it stops being so. The whole
        // point of the reference picker is asynchronous, so without a signal there is no way — for
        // the preview-harness, or for anyone debugging — to tell "still working" from "finished,
        // and this is all there is". `clearRow` drops it again on the next pass.
        row.dataset.scored = "pending";

        // The reference frame is decoded ONCE for the row. Every lane is measured against the same
        // image, and `load()` caching the `HTMLImageElement` is not enough: each `pixels()` call
        // allocates a full-size canvas, redraws, and does another `getImageData` readback. On the
        // five-player wall that is four redundant full-frame readbacks per row, in the lazy scroll
        // path this observer exists to keep smooth.
        let referencePixels: Promise<Pixels> | null = null;

        // Sequential on purpose: each step decodes two full frames onto a canvas, and a row of
        // players started at once would stall the scroll this observer exists to keep smooth.
        for (const step of planRow(model, this.laneIds, this.reference)) {
            if (pass !== this.pass) return;
            const label = shortLabelOf(this.model!, step.laneId);
            if (step.kind === "chip") {
                scores.appendChild(
                    this.chip(label, step.text, band(step.pct), step.px),
                );
                if (step.diff) this.showDiff(row, step.laneId, step.diff);
                continue;
            }
            try {
                referencePixels ??= this.pixels(step.referenceSrc);
                const [reference, lane] = await Promise.all([
                    referencePixels,
                    this.pixels(step.laneSrc),
                ]);
                if (pass !== this.pass) return;
                if (!sameSize(lane, reference)) {
                    scores.appendChild(
                        this.chip(
                            label,
                            sizeMismatchText(lane, reference),
                            "na",
                            null,
                        ),
                    );
                    continue;
                }
                const diff = diffPixels(reference, lane, this.threshold);
                if (pass !== this.pass) return;
                scores.appendChild(
                    this.chip(
                        label,
                        percentText(diff.percent),
                        band(diff.percent),
                        diff.changed,
                    ),
                );
                this.showDiff(
                    row,
                    step.laneId,
                    this.toDataUrl(diff.data, reference),
                );
            } catch {
                if (pass !== this.pass) return;
                scores.appendChild(this.chip(label, "diff failed", "na", null));
            }
        }
        if (pass === this.pass) row.dataset.scored = "done";
    }

    private chip(
        label: string,
        text: string,
        tone: Band,
        px: number | null,
    ): HTMLElement {
        const line = document.createElement("div");
        line.className = "cp-rc-scoreline";
        const name = document.createElement("span");
        name.className = "cp-rc-scorelabel";
        name.textContent = label;
        const score = document.createElement("span");
        score.className = `cp-rc-score cp-rc-score--${tone}`;
        score.textContent = text;
        line.append(name, score);
        if (px !== null) {
            const pxEl = document.createElement("span");
            pxEl.className = "cp-rc-px";
            pxEl.textContent = `${px.toLocaleString("en-US")} px`;
            line.appendChild(pxEl);
        }
        return line;
    }

    private clearRow(row: HTMLElement): void {
        delete row.dataset.scored;
        const scores = row.querySelector("[data-scores]");
        if (scores) scores.textContent = "";
        for (const slot of row.querySelectorAll<HTMLElement>(
            ".cp-rc-diffslot",
        )) {
            slot.textContent = "";
            slot.hidden = true;
        }
        for (const cell of row.querySelectorAll(".cp-rc-cell")) {
            cell.classList.remove("is-reference");
        }
    }

    private cellFor(row: HTMLElement, laneId: string): HTMLElement | null {
        // `querySelector` with an interpolated attribute value would be a selector-injection sink
        // for a lane id — but every id reaching here came from `laneIds`, and `referenceFrom`
        // refuses anything the model does not name. Compared rather than interpolated anyway.
        return (
            Array.from(row.querySelectorAll<HTMLElement>(".cp-rc-cell")).find(
                (cell) => cell.dataset.lane === laneId,
            ) ?? null
        );
    }

    private showDiff(row: HTMLElement, laneId: string, src: string): void {
        const slot = this.cellFor(row, laneId)?.querySelector<HTMLElement>(
            ".cp-rc-diffslot",
        );
        if (!slot) return;
        const caption = document.createElement("div");
        caption.className = "cp-rc-difflabel";
        caption.textContent = `pixel diff vs ${shortLabelOf(this.model!, this.reference)}`;
        const img = document.createElement("img");
        img.loading = "lazy";
        img.src = src;
        img.alt = "pixel diff";
        slot.textContent = "";
        slot.append(caption, img);
        slot.hidden = false;
    }

    /** Decoded once per URL: the same render is the reference for every other lane in its row. */
    private load(src: string): Promise<HTMLImageElement> {
        const cached = this.images.get(src);
        if (cached) return cached;
        const pending = new Promise<HTMLImageElement>((resolve, reject) => {
            const img = new Image();
            img.onload = () => resolve(img);
            img.onerror = () => reject(new Error(`could not load ${src}`));
            img.src = src;
        });
        this.images.set(src, pending);
        return pending;
    }

    private async pixels(src: string): Promise<Pixels> {
        const img = await this.load(src);
        const canvas = document.createElement("canvas");
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        if (!context) throw new Error("no 2d context");
        context.drawImage(img, 0, 0);
        return context.getImageData(0, 0, canvas.width, canvas.height);
    }

    private toDataUrl(data: Uint8ClampedArray, size: Pixels): string {
        const canvas = document.createElement("canvas");
        canvas.width = size.width;
        canvas.height = size.height;
        // Copied into a fresh buffer: `ImageData` insists on a plain `ArrayBuffer`, and the
        // array `diffPixels` returns is typed loosely enough to have come from a shared one.
        const image = new ImageData(size.width, size.height);
        image.data.set(data);
        canvas.getContext("2d")!.putImageData(image, 0, 0);
        return canvas.toDataURL("image/png");
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-rc-lanes": RcLanes;
    }
}
