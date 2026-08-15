// `<cp-group-memory>` — remembers which viewer control groups a visitor left
// open. Replaces `assets/viewer-groups.js`.
//
// The viewer's controls are a column of `<details class="cp-group">` drawers:
// Overlays, Features, Size, Locale, Overrides, Remote Compose. Someone tuning a
// preview opens the one or two they care about and then walks the catalog — so
// re-collapsing them on every navigation means re-opening the same drawer at
// every stop. The state is per-visitor taste, not per-preview data, which is why
// it lives in `localStorage` under `cp-grp.<data-cp-group>` and not in the URL:
// putting it in the URL would bake one reader's drawer habits into every link
// they shared.
//
// A CONTROLLER element, not one component per drawer. `<details>` cannot be a
// custom element, so the alternative is a marker child inside all eight of them;
// this is the same one-element-per-page shape the script it replaces already had,
// and `details.cp-group[data-cp-group]` is the declarative marker it keys off.
//
// It renders nothing (`serve.css` gives the tag `display: none`) and holds no
// state of its own — each `<details>` is its own source of truth, read on connect
// and written back on `toggle`. Storage is best-effort throughout: a visitor
// with storage blocked gets the server's default open/closed state and drawers
// that still work, never a page that fails to wire up.

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";

const PREFIX = "cp-grp.";

function storageKey(group: HTMLDetailsElement): string {
    return PREFIX + group.getAttribute("data-cp-group");
}

@customElement("cp-group-memory")
export class GroupMemory extends LitElement {
    private wired: Array<{ group: HTMLDetailsElement; onToggle: () => void }> =
        [];

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        for (const group of document.querySelectorAll<HTMLDetailsElement>(
            "details.cp-group[data-cp-group]",
        )) {
            this.restore(group);
            const onToggle = () => this.remember(group);
            group.addEventListener("toggle", onToggle);
            this.wired.push({ group, onToggle });
        }
    }

    disconnectedCallback(): void {
        for (const { group, onToggle } of this.wired) {
            group.removeEventListener("toggle", onToggle);
        }
        this.wired = [];
        super.disconnectedCallback();
    }

    /**
     * Apply the stored choice. Only an explicit `"1"` / `"0"` moves the drawer —
     * anything else (never visited, storage cleared, a value from some other
     * origin's key) leaves the server's own default standing.
     */
    private restore(group: HTMLDetailsElement): void {
        let stored: string | null = null;
        try {
            stored = localStorage.getItem(storageKey(group));
        } catch {
            return;
        }
        if (stored === "1") group.open = true;
        else if (stored === "0") group.open = false;
    }

    private remember(group: HTMLDetailsElement): void {
        try {
            localStorage.setItem(storageKey(group), group.open ? "1" : "0");
        } catch {
            // Private mode, a full quota, a blocked third-party context: the drawer
            // still opens, it just won't be remembered.
        }
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-group-memory": GroupMemory;
    }
}
