// DOM-feature tests for `<history-row>`'s reactive selection state
// (step 2 of #858). The row owns its `selected` boolean and
// dispatches `history-row-selection-change` (bubbling) on shift-click;
// the host's `behavior.ts` listener aggregates the (max-2) queue from
// those events. These tests pin the row-side contract:
//
//   - shift-click flips `.selected` and dispatches the event with
//     `selected: true` / `selected: false`,
//   - the `.selected` class on the host element tracks `this.selected`,
//   - non-shift clicks do NOT dispatch a selection-change event (they
//     route through the `onExpand` callback instead),
//   - external `.selected = ...` writes do NOT re-dispatch (so the
//     host can clear an evicted oldest pick without re-entering its
//     own listener).
//
// Mirrors the style of `liveBadge.test.ts` from PR #867: pure
// happy-dom DOM, no Mocha-on-Mocha, narrow contract. The
// host's max-2 enforcement is integration code in `behavior.ts` and
// is intentionally out of scope here.

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
        // Plain click routes to `onExpand` via the row's callbacks.
        // No selection mutation, no event. The expansion path is
        // independently exercised by `historyTimeline.ts`'s integration.
        const row = await mountRow(buildEntry("entry-1"));
        row.callbacks = {
            onExpand: () => {},
            onDiffPrevious: () => {},
            onDiffCurrent: () => {},
            onThumbConnected: () => {},
        };
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
