// Behavioural contract for `<cp-parity-lanes>`.
//
// The rule itself is pinned in `laneFilter.test.ts`; this covers what only the element can answer —
// that it finds buttons and rows the server rendered further down the page, moves the
// `aria-current` marker with the choice, and reveals the empty note only when a lane really is.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/ParityLanes.js";

/** Mount the feed as the server renders it: the tag first, its subjects below. */
async function mount(lanes = ["code", "figma", "comment"]): Promise<void> {
    document.body.innerHTML = `
      <cp-parity-lanes></cp-parity-lanes>
      <div class="cp-states">
        <button type="button" data-parity-lane="all" aria-current="page">All</button>
        <button type="button" data-parity-lane="code">Code</button>
        <button type="button" data-parity-lane="figma">Figma</button>
      </div>
      <ul class="cp-parity-feed" id="cp-parity-feed">
        ${lanes.map((l) => `<li class="cp-parity-entry" data-lane="${l}"></li>`).join("")}
      </ul>
      <p id="cp-parity-feed-empty" hidden></p>`;
    await flush();
}

const entries = () =>
    Array.from(document.querySelectorAll<HTMLElement>(".cp-parity-entry"));
const click = (lane: string) =>
    document
        .querySelector<HTMLElement>(`[data-parity-lane="${lane}"]`)
        ?.click();
const emptyNote = () =>
    document.getElementById("cp-parity-feed-empty") as HTMLElement;

describe("<cp-parity-lanes>", () => {
    afterEach(() => resetDom());

    it("leaves the server's rendering alone until asked", async () => {
        // The page is readable with JavaScript off, so the resting state must be the served one.
        await mount();
        assert.equal(
            entries().every((e) => !e.hidden),
            true,
        );
        assert.equal(emptyNote().hidden, true);
    });

    it("filters to the chosen lane", async () => {
        await mount();
        click("code");
        assert.deepEqual(
            entries().map((e) => e.hidden),
            [false, true, true],
        );
    });

    it("moves the current marker to the chosen button", async () => {
        await mount();
        click("figma");
        assert.equal(
            document
                .querySelector("[data-parity-lane='all']")
                ?.hasAttribute("aria-current"),
            false,
        );
        assert.equal(
            document
                .querySelector("[data-parity-lane='figma']")
                ?.getAttribute("aria-current"),
            "page",
        );
    });

    it("comes back to everything", async () => {
        await mount();
        click("code");
        click("all");
        assert.equal(
            entries().every((e) => !e.hidden),
            true,
        );
        assert.equal(emptyNote().hidden, true);
    });

    it("explains an empty lane instead of showing a blank feed", async () => {
        await mount(["comment", "comment"]);
        click("code");
        assert.equal(emptyNote().hidden, false);
        click("all");
        assert.equal(emptyNote().hidden, true, "the note goes away again");
    });

    it("stays silent on a page with no feed", async () => {
        document.body.innerHTML = `<cp-parity-lanes></cp-parity-lanes>
          <button data-parity-lane="code">Code</button>`;
        await flush();
        click("code");
        // Nothing to filter and nothing to throw; the button simply does nothing.
        assert.equal(
            document
                .querySelector("[data-parity-lane='code']")
                ?.hasAttribute("aria-current"),
            false,
        );
    });
});
