// DOM-feature tests for `<history-row>`'s reactive selection +
// expansion state (steps 2 and 3 of #858). The row owns its
// `selected` / `_expandedKind` reactive state and dispatches
// `history-row-selection-change` + `history-row-expand` (both
// bubbling) on user input; the host's `behavior.ts` listeners
// aggregate the (max-2) selection queue and route the matching
// vscode messages from those events. These tests pin the row-side
// contract:
//
//   - shift-click flips `.selected` and dispatches the event with
//     `selected: true` / `selected: false`,
//   - the `.selected` class on the host element tracks `this.selected`,
//   - non-shift (plain) clicks toggle the inline image expansion and
//     dispatch `history-row-expand` with `kind: "image"`,
//   - the diff action buttons set the row to diff mode and dispatch
//     `history-row-expand` with `kind: "diff"` + `against`,
//   - `setImage` / `setImageError` paint the matching content into
//     the `.expanded` block,
//   - `collapse()` removes the `.expanded` block,
//   - external `.selected = ...` writes do NOT re-dispatch (so the
//     host can clear an evicted oldest pick without re-entering its
//     own listener).
//
// Mirrors the style of `liveBadge.test.ts` from PR #867: pure
// happy-dom DOM, no Mocha-on-Mocha, narrow contract. The
// host's max-2 enforcement and `fillDiff` integration are out of
// scope here — both live in `behavior.ts` / `historyDiffView.ts`.

import * as assert from "assert";
// Side-effect import: `@customElement("history-row")` registers the
// element with `customElements.define`. The named-only `HistoryRow`
// import below is a type at the use sites (cast + `instanceof`), so
// without this bare import TypeScript elides the module entirely
// under glob runs and `document.createElement("history-row")` returns
// an unupgraded `HTMLElement`.
import "../webview/history/components/HistoryRow";
import {
    HistoryRow,
    type HistoryRowExpandEvent,
    type HistoryRowSelectionChangeEvent,
} from "../webview/history/components/HistoryRow";
import type { HistoryEntry } from "../webview/shared/types";

/** Build a minimal `HistoryEntry` with just enough fields for
 *  `HistoryRow.render()` to succeed and `dataset.id` to populate. */
function buildEntry(id: string): HistoryEntry {
    return {
        id,
        previewId: "com.example.Preview",
        timestamp: "2025-01-01T00:00:00Z",
        trigger: "manual",
        source: { kind: "fs" },
    } as HistoryEntry;
}

/** Mount a fresh `<history-row>` with [entry] into `document.body`,
 *  await its first render, return the element. */
async function mountRow(entry: HistoryEntry): Promise<HistoryRow> {
    const row = document.createElement("history-row") as HistoryRow;
    row.entry = entry;
    document.body.appendChild(row);
    await row.updateComplete;
    return row;
}

afterEach(() => {
    document.body.innerHTML = "";
});

describe("<history-row> selection state", () => {
    it("starts unselected with no `.selected` class", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        assert.strictEqual(row.selected, false);
        assert.strictEqual(row.classList.contains("selected"), false);
    });

    it("flips selected on shift-click and dispatches history-row-selection-change with selected: true", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        const events: HistoryRowSelectionChangeEvent[] = [];
        row.addEventListener("history-row-selection-change", (e) => {
            events.push(e);
        });

        row.dispatchEvent(
            new MouseEvent("click", { bubbles: true, shiftKey: true }),
        );
        await row.updateComplete;

        assert.strictEqual(row.selected, true);
        assert.strictEqual(row.classList.contains("selected"), true);
        assert.strictEqual(events.length, 1);
        assert.deepStrictEqual(events[0].detail, {
            id: "entry-1",
            selected: true,
        });
    });

    it("toggles back to unselected on a second shift-click and dispatches selected: false", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        const events: HistoryRowSelectionChangeEvent[] = [];
        row.addEventListener("history-row-selection-change", (e) => {
            events.push(e);
        });

        row.dispatchEvent(
            new MouseEvent("click", { bubbles: true, shiftKey: true }),
        );
        await row.updateComplete;
        row.dispatchEvent(
            new MouseEvent("click", { bubbles: true, shiftKey: true }),
        );
        await row.updateComplete;

        assert.strictEqual(row.selected, false);
        assert.strictEqual(row.classList.contains("selected"), false);
        assert.strictEqual(events.length, 2);
        assert.deepStrictEqual(events[0].detail, {
            id: "entry-1",
            selected: true,
        });
        assert.deepStrictEqual(events[1].detail, {
            id: "entry-1",
            selected: false,
        });
    });

    it("bubbles the selection-change event to ancestor listeners", async () => {
        // The host listens on `timelineEl` (the row's parent), so the
        // event must escape the row. `bubbles: true` + light DOM is
        // what wires that up; this test pins that behaviour.
        const row = await mountRow(buildEntry("entry-1"));
        let receivedOnBody: HistoryRowSelectionChangeEvent | null = null;
        document.body.addEventListener("history-row-selection-change", (e) => {
            receivedOnBody = e;
        });

        row.dispatchEvent(
            new MouseEvent("click", { bubbles: true, shiftKey: true }),
        );
        await row.updateComplete;

        assert.notStrictEqual(receivedOnBody, null);
        assert.deepStrictEqual(
            (receivedOnBody as unknown as HistoryRowSelectionChangeEvent)
                .detail,
            { id: "entry-1", selected: true },
        );
    });

    it("does not dispatch selection-change on a plain (non-shift) click", async () => {
        // Plain click flips `_expandedKind` and dispatches
        // `history-row-expand`, never `history-row-selection-change`.
        const row = await mountRow(buildEntry("entry-1"));
        row.callbacks = { onThumbConnected: () => {} };
        const events: HistoryRowSelectionChangeEvent[] = [];
        row.addEventListener("history-row-selection-change", (e) => {
            events.push(e);
        });

        row.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;

        assert.strictEqual(events.length, 0);
        assert.strictEqual(row.selected, false);
    });

    it("reflects external .selected writes in the `.selected` class without re-dispatching", async () => {
        // The host clears the oldest pick by writing `row.selected = false`
        // when a third row is shift-clicked. That must NOT re-enter the
        // selection-change listener (otherwise the host would loop on
        // its own eviction). Only user input (shift-click) dispatches.
        const row = await mountRow(buildEntry("entry-1"));
        const events: HistoryRowSelectionChangeEvent[] = [];
        row.addEventListener("history-row-selection-change", (e) => {
            events.push(e);
        });

        row.selected = true;
        await row.updateComplete;
        assert.strictEqual(row.classList.contains("selected"), true);

        row.selected = false;
        await row.updateComplete;
        assert.strictEqual(row.classList.contains("selected"), false);

        assert.strictEqual(events.length, 0);
    });
});

describe("<history-row> inline expansion", () => {
    it("plain-clicks open an image expansion and dispatch history-row-expand with kind: image", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        const events: HistoryRowExpandEvent[] = [];
        row.addEventListener("history-row-expand", (e) => {
            events.push(e);
        });

        row.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;

        assert.strictEqual(events.length, 1);
        assert.deepStrictEqual(events[0].detail, {
            id: "entry-1",
            kind: "image",
        });
        const expanded = row.querySelector(".expanded");
        assert.notStrictEqual(expanded, null);
        assert.strictEqual((expanded as HTMLElement).dataset.id, "entry-1");
        assert.strictEqual(
            expanded?.classList.contains("diff-expanded"),
            false,
        );
    });

    it("a second plain-click collapses the row and emits no further history-row-expand event", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        const events: HistoryRowExpandEvent[] = [];
        row.addEventListener("history-row-expand", (e) => {
            events.push(e);
        });

        row.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;
        assert.notStrictEqual(row.querySelector(".expanded"), null);

        row.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;

        assert.strictEqual(row.querySelector(".expanded"), null);
        // Only the open emitted an event — the close is a pure local
        // state flip with no associated vscode request.
        assert.strictEqual(events.length, 1);
    });

    it("the diff-vs-previous button opens a diff-expanded block and dispatches history-row-expand with against: previous", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        const events: HistoryRowExpandEvent[] = [];
        row.addEventListener("history-row-expand", (e) => {
            events.push(e);
        });

        const btn = row.querySelector<HTMLButtonElement>(
            'button[aria-label="Diff vs previous"]',
        );
        assert.notStrictEqual(btn, null);
        btn?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;

        assert.strictEqual(events.length, 1);
        assert.deepStrictEqual(events[0].detail, {
            id: "entry-1",
            kind: "diff",
            against: "previous",
        });
        const shell = row.querySelector(".expanded.diff-expanded");
        assert.notStrictEqual(shell, null);
        assert.strictEqual((shell as HTMLElement).dataset.id, "entry-1");
        assert.strictEqual((shell as HTMLElement).dataset.against, "previous");
    });

    it("the diff-vs-current button dispatches history-row-expand with against: current", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        const events: HistoryRowExpandEvent[] = [];
        row.addEventListener("history-row-expand", (e) => {
            events.push(e);
        });

        const btn = row.querySelector<HTMLButtonElement>(
            'button[aria-label="Diff vs current"]',
        );
        assert.notStrictEqual(btn, null);
        btn?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;

        assert.strictEqual(events.length, 1);
        assert.deepStrictEqual(events[0].detail, {
            id: "entry-1",
            kind: "diff",
            against: "current",
        });
    });

    it("setImage paints the streamed bytes inside .expanded", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        // Open the image expansion first so `.expanded` is in the DOM.
        row.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;

        // Single-pixel transparent PNG, base64-encoded.
        const bytes =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
        row.setImage(bytes);
        await row.updateComplete;

        const img = row.querySelector<HTMLImageElement>(".expanded img");
        assert.notStrictEqual(img, null);
        assert.strictEqual(img?.src, "data:image/png;base64," + bytes);
    });

    it("setImageError surfaces the failure text in .expanded", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        row.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;

        row.setImageError("daemon unreachable");
        await row.updateComplete;

        const expanded = row.querySelector(".expanded");
        assert.notStrictEqual(expanded, null);
        assert.match(
            expanded?.textContent ?? "",
            /Failed to load image: daemon unreachable/,
        );
        assert.strictEqual(row.querySelector(".expanded img"), null);
    });

    it("collapse() removes the .expanded block and clears reactive state", async () => {
        const row = await mountRow(buildEntry("entry-1"));
        row.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await row.updateComplete;
        row.setImage("AAAA");
        await row.updateComplete;
        assert.notStrictEqual(row.querySelector(".expanded"), null);

        row.collapse();
        await row.updateComplete;

        assert.strictEqual(row.querySelector(".expanded"), null);
    });
});
