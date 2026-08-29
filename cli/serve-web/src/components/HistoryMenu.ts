// `<cp-history-menu>` — the viewer's render-history menu. Replaces `assets/viewer-history.js`.
//
// A MENU, on the same `<details>` shape as Revision and Theme beside it, not a strip of chips. It
// was a horizontal row of dated chips, which is the exact pattern #3858 removed one row up: a wall
// of chips spends the width above the render on a list nobody reads most of the time, and answers
// "which one am I on?" only by making the reader find the highlighted one. Closed, this is one
// control in a row that already exists — so there is no phone-versus-desktop question about where
// it goes, and nothing between the title and the render at any width.
//
// The port dropped `place()` entirely. The old script built the menu at runtime and then had to
// find somewhere to put it — into `.cp-head-toggles` before the Overrides toggle, with a fallback
// above the stage for a page whose toggle row predated it. The server knows where the control
// belongs, so it emits the tag there and the placement question stops existing.
//
// The decisions live in `viewer/historyUrls.ts` (which links can be built at all, and safely) and
// `viewer/historyModel.ts` (which of them are worth showing). What is left here is fetching and
// rendering.

import { h, type VNode } from "../vue.js";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import { historySourceOf } from "../viewer/historyUrls.js";
import {
    historyMenuOf,
    type HistoryMenu as Menu,
    type HistoryRow,
    type ManifestEntry,
} from "../viewer/historyModel.js";

interface Manifest {
    previews?: Record<string, ManifestEntry>;
}

@customElement("cp-history-menu")
export class HistoryMenu extends VueElement {
    private menu: Menu | null = null;

    // Everything the menu reads lives on `.cp-viewer`, which sits BELOW the toggle row that
    // declares this tag — so connect time is too early to look for it. See `dom/whenParsed.ts`.
    connectedCallback(): void {
        super.connectedCallback();
        void whenParsed().then(() => this.load());
    }

    private async load(): Promise<void> {
        const root = document.querySelector<HTMLElement>(".cp-viewer");
        const previewId = root?.getAttribute("data-preview-id");
        if (!root || !previewId) return;
        const source = historySourceOf(
            root.getAttribute("data-history-repo"),
            root.getAttribute("data-history-blob-url"),
        );
        // Neither addressing mode is usable — a malformed repo or template. Draw nothing rather
        // than a menu of links that cannot be built.
        if (!source) return;

        const entry = await this.entryFor(root, previewId);
        this.menu = historyMenuOf(source, entry);
        this.requestUpdate();
    }

    /**
     * An INLINE payload lets a fixture (and any offline viewer) render the menu without reaching
     * raw.githubusercontent.com. Without it the preview-harness capture would be identical whether
     * the timeline works or is deleted — no coverage at all.
     */
    private async entryFor(
        root: HTMLElement,
        previewId: string,
    ): Promise<ManifestEntry | null> {
        const inline = document.getElementById("cp-history-data");
        if (inline) {
            try {
                const parsed = JSON.parse(
                    inline.textContent || "null",
                ) as Manifest | null;
                if (parsed?.previews) return parsed.previews[previewId] ?? null;
            } catch {
                // A malformed payload falls through to the fetch, which is the same answer the
                // page would have given without it.
            }
        }
        const manifestUrl = root.getAttribute("data-history-url");
        if (!manifestUrl) return null;
        try {
            const response = await fetch(manifestUrl);
            if (!response.ok) return null;
            const manifest = (await response.json()) as Manifest | null;
            return manifest?.previews?.[previewId] ?? null;
        } catch {
            // A missing or unreadable manifest is expected on a branch that has not published one
            // yet. The preview itself is unaffected, so fail silently rather than shouting.
            return null;
        }
    }

    protected renderVue(): VNode | null {
        const menu = this.menu;
        if (!menu) return null;
        // The instability warning rides on the TRIGGER, not inside the panel: it is a warning about
        // the whole list — the entries are a trimmed view and would otherwise not add up to the
        // publish count — and a closed menu must not hide it.
        const warning = menu.unstable
            ? h(
                  "span",
                  {
                      class: "cp-history-unstable",
                      title: menu.unstableTitle,
                  },
                  "unstable",
              )
            : null;
        return h("details", { class: "cp-history-menu" }, [
            h("summary", { class: "cp-history-btn" }, [
                h("span", { class: "cp-history-key" }, "History"),
                h("span", { class: "cp-history-value" }, menu.label),
                warning,
                h(
                    "span",
                    { class: "cp-history-caret", "aria-hidden": "true" },
                    "▾",
                ),
            ]),
            h("div", { class: "cp-history-panel" }, [
                h(
                    "nav",
                    {
                        class: "cp-history-list",
                        "aria-label": "Render history",
                    },
                    menu.rows.map((row) => this.item(row)),
                ),
                h("p", { class: "cp-history-note" }, menu.note),
            ]),
        ]);
    }

    /** One version, as a link out — the stage is never touched, so nothing can disagree with it. */
    private item(row: HistoryRow): VNode {
        const span = row.span
            ? h(
                  "span",
                  { class: "cp-history-span", title: row.spanTitle ?? "" },
                  row.span,
              )
            : null;
        return h(
            "a",
            {
                class: "cp-history-item",
                href: row.href,
                target: "_blank",
                rel: "noopener noreferrer",
                title: row.title,
                "data-current": row.current ? "1" : undefined,
            },
            [
                h("span", { class: "cp-history-date" }, row.date),
                h("span", { class: "cp-history-meta" }, row.meta),
                span,
            ],
        );
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-history-menu": HistoryMenu;
    }
}
