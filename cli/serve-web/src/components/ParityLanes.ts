// `<cp-parity-lanes>` — the design-parity feed's lane filter. Half of `assets/parity.js`.
//
// Renders nothing. The buttons and the feed are both server-rendered — the page is readable with
// JavaScript off, which is the whole reason this filters in place instead of fetching — so all the
// element does is answer "which entries stay visible" and write that answer onto rows it does not
// own. `serve.css` hides the tag.
//
// The rule itself lives in `parity/laneFilter.ts`, where its one exception (`all` means "no
// filter", not "entries whose lane is `all`") is a test rather than a screenshot of a feed.

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import { whenParsed } from "../dom/whenParsed.js";
import { filterLanes } from "../parity/laneFilter.js";

@customElement("cp-parity-lanes")
export class ParityLanes extends LitElement {
    private cleanups: Array<() => void> = [];

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    // The buttons and the feed are both siblings further down the page, so connect time is too
    // early to find either. See `dom/whenParsed.ts`.
    connectedCallback(): void {
        super.connectedCallback();
        void whenParsed().then(() => this.bind());
    }

    private bind(): void {
        if (!this.isConnected) return;
        for (const button of this.buttons()) {
            const onClick = () =>
                this.apply(button.dataset.parityLane ?? "all");
            button.addEventListener("click", onClick);
            this.cleanups.push(() =>
                button.removeEventListener("click", onClick),
            );
        }
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        super.disconnectedCallback();
    }

    private buttons(): HTMLElement[] {
        return Array.from(
            document.querySelectorAll<HTMLElement>("[data-parity-lane]"),
        );
    }

    private apply(lane: string): void {
        const feed = document.getElementById("cp-parity-feed");
        if (!feed) return;
        const entries = Array.from(
            feed.querySelectorAll<HTMLElement>(".cp-parity-entry"),
        );
        const { keep, empty } = filterLanes(
            entries.map((entry) => entry.dataset.lane ?? ""),
            lane,
        );
        entries.forEach((entry, i) => {
            entry.hidden = !keep[i];
        });
        const emptyNote = document.getElementById("cp-parity-feed-empty");
        if (emptyNote) emptyNote.hidden = !empty;
        // `aria-current="page"` rather than a pressed state, matching the server's own resting
        // markup for the `all` button — the filter reads as navigation within the feed.
        for (const button of this.buttons()) {
            if (button.dataset.parityLane === lane)
                button.setAttribute("aria-current", "page");
            else button.removeAttribute("aria-current");
        }
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-parity-lanes": ParityLanes;
    }
}
