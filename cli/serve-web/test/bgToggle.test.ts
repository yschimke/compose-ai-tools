// Behavioural contract for `<cp-bg-toggle>` and the choice module behind it.
//
// These assertions used to live in `ServeUrlStateTest.kt` as substring matches
// against the *source text* of `assets/bg-toggle.js` ("must contain
// `urlState.push({ bg: choice });`"). That could only ever prove the source said
// something, never that clicking the button did it, and it cannot survive a
// minified bundle. They run against the real element in a real DOM here instead;
// the Kotlin side keeps only what it can genuinely check — that the page emits
// the tag and loads the bundle.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import { resetForTest } from "../src/backgroundChoice.js";
import type { UrlState } from "../src/urlState.js";
import "../src/components/BgToggle.js";

const TRANSPARENT = "cp-bg-transparent";

interface FakeUrlState extends UrlState {
    pushed: Array<Record<string, string | null | undefined>>;
    pop(): void;
}

function fakeUrlState(params: Record<string, string> = {}): FakeUrlState {
    const values = new Map(Object.entries(params));
    const popHandlers: Array<() => void> = [];
    return {
        pushed: [],
        get: (name) => values.get(name) ?? "",
        push(next) {
            this.pushed.push(next);
            for (const [name, value] of Object.entries(next)) {
                if (value === null || value === undefined || value === "")
                    values.delete(name);
                else values.set(name, String(value));
            }
        },
        replace() {},
        sync() {},
        onPop: (callback) => void popHandlers.push(callback),
        pop() {
            for (const handler of popHandlers) handler();
        },
    };
}

/** Mount `count` toggles and return their rendered buttons. */
async function mount(count = 1): Promise<HTMLButtonElement[]> {
    document.body.innerHTML = Array.from(
        { length: count },
        () => `<cp-bg-toggle label="Show the checkerboard"></cp-bg-toggle>`,
    ).join("");
    await flush();
    return [
        ...document.querySelectorAll("cp-bg-toggle button"),
    ] as HTMLButtonElement[];
}

describe("<cp-bg-toggle>", () => {
    beforeEach(() => {
        resetDom();
        resetForTest();
        stubStorage();
        window.cpUrlState = undefined;
    });

    it("renders the toolbar button into light DOM so serve.css applies", async () => {
        const [button] = await mount();
        assert.equal(button.className, "cp-bg-btn");
        assert.equal(button.getAttribute("type"), "button");
        assert.equal(button.textContent?.trim(), "Transparent");
        assert.equal(button.title, "Show the checkerboard");
        // Light DOM, not shadow: the element must have no shadow root at all, or
        // every `.cp-bg-btn` rule in serve.css would stop reaching the button.
        assert.equal(document.querySelector("cp-bg-toggle")!.shadowRoot, null);
    });

    it("reflects the pre-paint class as its resting pressed state", async () => {
        document.documentElement.classList.add(TRANSPARENT);
        const [button] = await mount();
        assert.equal(button.getAttribute("aria-pressed"), "true");
    });

    it("a click flips the page class and the pressed state", async () => {
        const [button] = await mount();
        assert.equal(button.getAttribute("aria-pressed"), "false");

        button.click();
        await flush();
        assert.ok(document.documentElement.classList.contains(TRANSPARENT));
        assert.equal(button.getAttribute("aria-pressed"), "true");

        button.click();
        await flush();
        assert.ok(!document.documentElement.classList.contains(TRANSPARENT));
        assert.equal(button.getAttribute("aria-pressed"), "false");
    });

    it("persists the choice per visitor", async () => {
        const storage = stubStorage();
        const [button] = await mount();
        button.click();
        assert.equal(storage.get("cp-bg"), "off");
        button.click();
        assert.equal(storage.get("cp-bg"), "on");
    });

    it("survives blocked storage", async () => {
        stubStorage(true);
        const [button] = await mount();
        assert.doesNotThrow(() => button.click());
        await flush();
        // The choice still applies to this page; it just doesn't outlive it.
        assert.ok(document.documentElement.classList.contains(TRANSPARENT));
    });

    it("pushes a history entry so the checkerboard view is shareable", async () => {
        const state = fakeUrlState();
        window.cpUrlState = state;
        const [button] = await mount();
        button.click();
        assert.deepEqual(state.pushed, [{ bg: "off" }]);
        button.click();
        assert.deepEqual(state.pushed, [{ bg: "off" }, { bg: "on" }]);
    });

    it("works on a page that never loaded url-state.js", async () => {
        const [button] = await mount();
        assert.doesNotThrow(() => button.click());
        await flush();
        assert.ok(document.documentElement.classList.contains(TRANSPARENT));
    });

    it("Back restores the background this load opened with, not the stored one", async () => {
        // The regression this pins: re-reading localStorage on popstate returns
        // the value written by the very click being backed OUT of, so Transparent
        // would survive its own Back.
        const state = fakeUrlState();
        window.cpUrlState = state;
        const [button] = await mount();

        button.click(); // -> transparent, and localStorage now says "off"
        await flush();
        assert.ok(document.documentElement.classList.contains(TRANSPARENT));

        // Back to the entry before the click, which names no `bg`.
        state.push({ bg: null });
        state.pop();
        await flush();
        assert.ok(!document.documentElement.classList.contains(TRANSPARENT));
        assert.equal(button.getAttribute("aria-pressed"), "false");
    });

    it("Back into an explicit ?bg= honours it over the load's resting choice", async () => {
        const state = fakeUrlState({ bg: "off" });
        window.cpUrlState = state;
        const [button] = await mount();
        state.pop();
        await flush();
        assert.ok(document.documentElement.classList.contains(TRANSPARENT));
        assert.equal(button.getAttribute("aria-pressed"), "true");
    });

    it("keeps every toggle on the page in step", async () => {
        // The viewer and the grid each carry one, and a page can show both bars.
        const buttons = await mount(2);
        assert.equal(buttons.length, 2);
        buttons[0].click();
        await flush();
        for (const button of buttons) {
            assert.equal(button.getAttribute("aria-pressed"), "true");
        }
    });

    it("stops tracking the page once removed", async () => {
        await mount();
        const element = document.querySelector("cp-bg-toggle")!;
        element.remove();
        await flush();
        // No listener leak: toggling now notifies nobody and must not throw on a
        // detached element's render root.
        assert.doesNotThrow(() => {
            document.documentElement.classList.toggle(TRANSPARENT);
        });
    });
});
