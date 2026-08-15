// `<cp-viewer-drawers>` — the viewer's two drawers, the phone reflow, the theme toggle's value,
// and the component filter. Replaces `assets/viewer-drawers.js`.
//
// A page-level controller, not a control: everything it owns is server-rendered markup it wires
// behaviour onto (the same shape as `<cp-group-memory>`), so it renders nothing and `serve.css`
// hides the tag. The decisions live in `viewer/drawerState.ts` and `viewer/navFilter.ts` as pure
// functions, because that is where this file's real content is — three viewport bands crossed with
// a stored preference and a server default — and none of it needs a browser to be right or wrong.
//
// Dropped in the port: `bindFold("cp-axes-toggle", "cp-axes")`. #3893 deleted both that toggle and
// its target, so the binding had nothing to find and no other fold used the mechanism. Porting it
// would have carried dead code across, and the harness states that clicked it were failing on
// `main` for the same reason.

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import {
    drawerToClose,
    foldKey,
    resolveControlsOpen,
    resolveNavOpen,
    shouldPersistDrawer,
    toggleIdFor,
    type DrawerClass,
    type Viewport,
} from "../viewer/drawerState.js";
import { filterNav, type NavRow } from "../viewer/navFilter.js";

const PHONE_QUERY = "(max-width: 640px)";
const WIDE_QUERY = "(min-width: 1100px)";

function matches(query: string): boolean {
    return !!(window.matchMedia && window.matchMedia(query).matches);
}

/** Where a reflowed row came from, captured before anything moves. */
interface RowHome {
    el: HTMLElement;
    parent: Node;
    next: Node | null;
}

@customElement("cp-viewer-drawers")
export class ViewerDrawers extends LitElement {
    private viewer: HTMLElement | null = null;
    private scrim: HTMLElement | null = null;
    private foldScope = "default";
    private rowHomes: RowHome[] = [];
    private cleanups: Array<() => void> = [];
    private themeObserver?: MutationObserver;

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        this.viewer = document.querySelector<HTMLElement>(".cp-viewer");
        if (!this.viewer) return;
        this.scrim = document.getElementById("cp-scrim");
        this.foldScope =
            this.viewer.getAttribute("data-fold-scope") || "default";

        this.captureRowHomes();
        this.restoreDrawers();
        this.reflowRows();
        this.bindToggles();
        this.bindBreakpoints();
        this.bindThemeValue();
        this.bindNavSearch();
        this.syncScrim();
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.themeObserver?.disconnect();
        this.themeObserver = undefined;
        this.viewer = null;
        super.disconnectedCallback();
    }

    private viewport(): Viewport {
        return { mobile: matches(PHONE_QUERY), wide: matches(WIDE_QUERY) };
    }

    // ── storage ──────────────────────────────────────────────────────────────────────────────
    // Best-effort throughout: a visitor with storage blocked gets the defaults and drawers that
    // still work, never a viewer that fails to wire up.

    private readFold(id: string): string | null {
        try {
            return localStorage.getItem(foldKey(this.foldScope, id));
        } catch {
            return null;
        }
    }

    private writeFold(id: string, open: boolean): void {
        try {
            localStorage.setItem(foldKey(this.foldScope, id), open ? "1" : "0");
        } catch {
            // Private mode, a full quota: the drawer still opens, it just is not remembered.
        }
    }

    // ── drawer state ─────────────────────────────────────────────────────────────────────────

    private setOpen(drawer: DrawerClass, open: boolean): void {
        const viewer = this.viewer;
        if (!viewer) return;
        const other = open ? drawerToClose(this.viewport(), drawer) : null;
        if (other && viewer.classList.contains(other)) {
            viewer.classList.remove(other);
            if (other === "cp-nav-open") viewer.classList.add("cp-nav-closed");
            document
                .getElementById(toggleIdFor(other))
                ?.setAttribute("aria-expanded", "false");
        }
        viewer.classList.toggle(drawer, open);
        // The nav's closed state has to be said out loud, not merely implied by the absence of
        // `cp-nav-open`: above 1100px the absence means OPEN, so without this class the toggle
        // would be inert at the width where the 240px column costs most.
        if (drawer === "cp-nav-open") {
            viewer.classList.toggle("cp-nav-closed", !open);
        }
        document
            .getElementById(toggleIdFor(drawer))
            ?.setAttribute("aria-expanded", open ? "true" : "false");
        this.syncScrim();
    }

    /**
     * On a phone the drawers open as bottom sheets over the preview, so a scrim goes behind
     * whichever is open. Off the phone they are inline columns and the scrim's CSS never applies.
     */
    private syncScrim(): void {
        const viewer = this.viewer;
        if (!this.scrim || !viewer) return;
        const anyOpen =
            viewer.classList.contains("cp-controls-open") ||
            viewer.classList.contains("cp-nav-open");
        this.scrim.classList.toggle("cp-scrim-on", anyOpen);
    }

    private restoreDrawers(): void {
        const viewer = this.viewer;
        if (!viewer) return;
        const viewport = this.viewport();
        // Read the server's own default BEFORE touching the class — it is the third input to the
        // rule, and #3893 changing it is what silently closed this drawer everywhere.
        const serverDefault = viewer.classList.contains("cp-controls-open");
        this.setOpen(
            "cp-controls-open",
            resolveControlsOpen(
                viewport,
                this.readFold("cp-controls-toggle"),
                serverDefault,
            ),
        );
        if (document.getElementById("cp-nav-toggle")) {
            this.setOpen(
                "cp-nav-open",
                resolveNavOpen(viewport, this.readFold("cp-nav-toggle")),
            );
        }
    }

    private bindToggles(): void {
        for (const drawer of [
            "cp-controls-open",
            "cp-nav-open",
        ] as DrawerClass[]) {
            const id = toggleIdFor(drawer);
            const btn = document.getElementById(id);
            if (!btn) continue;
            this.on(btn, "click", () => {
                const open = !this.viewer?.classList.contains(drawer);
                this.setOpen(drawer, open);
                if (shouldPersistDrawer(this.viewport())) {
                    this.writeFold(id, open);
                }
            });
        }

        const close = document.getElementById("cp-nav-close");
        if (close) {
            this.on(close, "click", () => {
                this.setOpen("cp-nav-open", false);
                // Same rule as the toggle: dismissing a phone's sheet is not a statement about the
                // desktop column, so it stores nothing there.
                if (shouldPersistDrawer(this.viewport())) {
                    this.writeFold("cp-nav-toggle", false);
                }
            });
        }

        if (this.scrim) {
            this.on(this.scrim, "click", () => {
                this.setOpen("cp-controls-open", false);
                this.setOpen("cp-nav-open", false);
            });
        }
    }

    /**
     * Re-resolve on a breakpoint crossing. Making the state explicit is what lost the CSS
     * default's own responsiveness: a page opened wide and then narrowed to a phone would
     * otherwise keep `cp-nav-open`, which below 640px is a fixed bottom sheet and a scrim dropped
     * over a viewer nobody asked to cover.
     */
    private bindBreakpoints(): void {
        for (const query of [PHONE_QUERY, WIDE_QUERY]) {
            const list = window.matchMedia?.(query);
            if (!list?.addEventListener) continue;
            const handler = () => {
                if (document.getElementById("cp-nav-toggle")) {
                    this.setOpen(
                        "cp-nav-open",
                        resolveNavOpen(
                            this.viewport(),
                            this.readFold("cp-nav-toggle"),
                        ),
                    );
                }
                this.reflowRows();
            };
            list.addEventListener("change", handler);
            this.cleanups.push(() =>
                list.removeEventListener("change", handler),
            );
        }
    }

    // ── the phone's row order ────────────────────────────────────────────────────────────────
    // On a phone the page order is bar, title, preview — so the two control rows that sat between
    // the title and the stage move BELOW it, and everything above the render is the one line
    // saying which component this is. Moved in the DOM rather than with `order`, so reading,
    // painting and tab order stay the same order at every width; a CSS re-order would leave a
    // keyboard walking to controls a screenful further down than they look.

    private captureRowHomes(): void {
        const rows = [
            document.querySelector<HTMLElement>(".cp-preview-primary"),
            document.querySelector<HTMLElement>(".cp-head-toggles"),
        ].filter((el): el is HTMLElement => !!el);
        // `nextSibling` rather than an index: the anchors are stable nodes that never move
        // themselves, so restoring is exact even though the two rows leave different parents.
        this.rowHomes = rows.map((el) => ({
            el,
            parent: el.parentNode as Node,
            next: el.nextSibling,
        }));
    }

    private reflowRows(): void {
        const viewer = this.viewer;
        if (!viewer?.parentNode) return;
        if (matches(PHONE_QUERY)) {
            let after: Node = viewer;
            for (const { el } of this.rowHomes) {
                moveIfNeeded(viewer.parentNode, el, after.nextSibling);
                after = el;
            }
        } else {
            for (const { el, parent, next } of this.rowHomes) {
                moveIfNeeded(parent, el, next);
            }
        }
    }

    // ── the theme toggle's value ─────────────────────────────────────────────────────────────

    /**
     * The Theme menu must still say which theme is showing, and the theme changes without a page
     * load — so the toggle's value half mirrors whichever chip `viewer.js` has marked pressed,
     * rather than the lane the server baked. Observing `aria-pressed` keeps this decoupled from
     * that file's own `syncThemeBar`, which has several callers and no hook of its own.
     */
    private bindThemeValue(): void {
        const bar = document.getElementById("cp-theme-bar");
        const value = document.getElementById("cp-theme-toggle-value");
        if (!bar || !value) return;
        const sync = () => {
            const on = bar.querySelector('.cp-theme-btn[aria-pressed="true"]');
            const name = (on?.textContent || "").trim();
            if (name) value.textContent = name;
        };
        sync();
        if (window.MutationObserver) {
            this.themeObserver = new MutationObserver(sync);
            this.themeObserver.observe(bar, {
                subtree: true,
                attributes: true,
                attributeFilter: ["aria-pressed"],
            });
        }
        this.on(bar, "click", (event) => {
            if (!(event.target as Element)?.closest?.(".cp-theme-btn")) return;
            const menu = bar.closest<HTMLDetailsElement>(".cp-theme-menu");
            if (menu) menu.open = false;
        });
    }

    // ── the component filter ─────────────────────────────────────────────────────────────────

    private bindNavSearch(): void {
        const search = document.getElementById(
            "cp-nav-search",
        ) as HTMLInputElement | null;
        if (!search) return;
        this.on(search, "input", () => {
            const items = [
                ...document.querySelectorAll<HTMLElement>(
                    "#cp-nav-list .cp-nav-item",
                ),
            ];
            const rows: NavRow[] = items.map((el) => ({
                haystack: el.getAttribute("data-search") || "",
                current: el.hasAttribute("aria-current"),
            }));
            const { keep, empty } = filterNav(
                rows,
                search.value,
                !!document.querySelector(".cp-nav-current"),
            );
            items.forEach((el, i) => {
                const row = el.parentNode as HTMLElement | null;
                if (row) row.hidden = !keep[i];
            });
            const none = document.getElementById("cp-nav-empty");
            if (none) none.hidden = !empty;
        });
    }

    /** addEventListener with teardown recorded, so the element leaves nothing behind. */
    private on(
        target: EventTarget,
        type: string,
        handler: (event: Event) => void,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }
}

/**
 * Move `el` before `before` under `parent` — but only when it is not already exactly there.
 *
 * The guard is the point. `insertBefore` of a node into the position it already occupies looks
 * like a no-op and is not: it detaches and re-attaches the element, and the browser rebuilds what
 * hangs off the attachment. The catalog toolbar shipped that bug — a desktop load "restored" rows
 * that had never moved, and an `<input type="search">` quietly lost its clear button and focus
 * ring, caught only by a capture of a state two steps later. Same hazard here, since this restores
 * on every breakpoint event at every width.
 */
function moveIfNeeded(
    parent: Node,
    el: HTMLElement,
    before: Node | null,
): void {
    if (el.parentNode === parent && el.nextSibling === before) return;
    parent.insertBefore(el, before);
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-viewer-drawers": ViewerDrawers;
    }
}
