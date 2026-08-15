// Behavioural contract for `<cp-group-memory>`.
//
// `assets/viewer-groups.js` had no test of any kind — nothing in Kotlin could
// assert more than "the page loads a script called viewer-groups.js", which is
// true of a file containing a syntax error. These run the real element against a
// real `<details>` column.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import "../src/components/GroupMemory.js";

/** A viewer control column: two remembered drawers plus one that opts out. */
const COLUMN = `
  <div class="cp-controls">
    <details class="cp-group" data-cp-group="overlays"><summary>Overlays</summary></details>
    <details class="cp-group" data-cp-group="size" open><summary>Size</summary></details>
    <details class="cp-group"><summary>Unkeyed</summary></details>
  </div>`;

async function mount(markup = COLUMN): Promise<void> {
    document.body.innerHTML = `${markup}<cp-group-memory></cp-group-memory>`;
    await flush();
}

function group(name: string): HTMLDetailsElement {
    return document.querySelector(
        `details[data-cp-group="${name}"]`,
    ) as HTMLDetailsElement;
}

/** happy-dom does not fire `toggle` off an `open` assignment; the browser does. */
function setOpen(details: HTMLDetailsElement, open: boolean): void {
    details.open = open;
    details.dispatchEvent(new Event("toggle"));
}

describe("<cp-group-memory>", () => {
    afterEach(() => resetDom());

    it("remembers a drawer the visitor opened", async () => {
        const store = stubStorage();
        await mount();
        setOpen(group("overlays"), true);
        assert.equal(store.get("cp-grp.overlays"), "1");
    });

    it("remembers a drawer the visitor closed, not just opened ones", async () => {
        const store = stubStorage();
        await mount();
        setOpen(group("size"), false);
        assert.equal(store.get("cp-grp.size"), "0");
    });

    it("restores both choices on the next page", async () => {
        const store = stubStorage();
        await mount();
        setOpen(group("overlays"), true);
        setOpen(group("size"), false);

        // Navigate: same storage, a fresh copy of the same column.
        await mount();
        assert.equal(group("overlays").open, true);
        assert.equal(group("size").open, false);
        assert.equal(store.get("cp-grp.overlays"), "1");
    });

    it("leaves the server's default standing when nothing is stored", async () => {
        stubStorage();
        await mount();
        // The server opened Size and closed Overlays; an unvisited drawer keeps that.
        assert.equal(group("overlays").open, false);
        assert.equal(group("size").open, true);
    });

    it("ignores a stored value that is neither 1 nor 0", async () => {
        stubStorage();
        await mount();
        localStorage.setItem("cp-grp.size", "yes");
        await mount();
        assert.equal(group("size").open, true);
    });

    it("keys each drawer separately", async () => {
        const store = stubStorage();
        await mount();
        setOpen(group("overlays"), true);
        assert.equal(store.get("cp-grp.size"), null);
    });

    it("leaves a drawer with no group id alone", async () => {
        const store = stubStorage();
        await mount();
        const unkeyed = document.querySelector(
            "details.cp-group:not([data-cp-group])",
        ) as HTMLDetailsElement;
        setOpen(unkeyed, true);
        assert.equal(store.get("cp-grp.null"), null);
        assert.equal(store.get("cp-grp.undefined"), null);
    });

    it("still opens drawers when storage is blocked", async () => {
        stubStorage(true);
        await mount();
        // Neither the restore read nor the toggle write may throw out of the element.
        setOpen(group("overlays"), true);
        assert.equal(group("overlays").open, true);
    });

    it("stops remembering once removed", async () => {
        const store = stubStorage();
        await mount();
        document.querySelector("cp-group-memory")?.remove();
        setOpen(group("overlays"), true);
        assert.equal(store.get("cp-grp.overlays"), null);
    });
});
