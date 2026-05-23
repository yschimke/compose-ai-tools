// `<focus-inspector>` — covers the render-error banner + history-panel
// surfaces and the `applyFocusInspectorState` adapter that maps a focused
// card's dataset onto the component's properties.

import * as assert from "assert";

// Importing the component registers the custom element.
import "../webview/preview/components/FocusInspector";
import {
    applyFocusInspectorState,
    type FocusInspector,
} from "../webview/preview/components/FocusInspector";

function build(): FocusInspector {
    const el = document.createElement("focus-inspector") as FocusInspector;
    document.body.appendChild(el);
    return el;
}

function buildCard(opts: {
    renderError?: string;
    renderErrorDetail?: string;
}): HTMLElement {
    const card = document.createElement("div");
    if (opts.renderError !== undefined) {
        card.dataset.renderError = opts.renderError;
    }
    if (opts.renderErrorDetail !== undefined) {
        card.dataset.renderErrorDetail = opts.renderErrorDetail;
    }
    return card;
}

describe("FocusInspector", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders nothing and stays hidden when card is null", async () => {
        const el = build();
        applyFocusInspectorState(el, null, {
            earlyFeatures: true,
            historyActive: true,
        });
        await el.updateComplete;
        assert.strictEqual(el.hidden, true);
        assert.strictEqual(el.children.length, 0);
    });

    it("stays hidden when earlyFeatures is off, even with an error stamped", async () => {
        const el = build();
        const card = buildCard({ renderError: "boom" });
        applyFocusInspectorState(el, card, {
            earlyFeatures: false,
            historyActive: false,
        });
        await el.updateComplete;
        assert.strictEqual(el.hidden, true);
        assert.strictEqual(el.querySelector(".focus-error-panel"), null);
    });

    it("paints the error banner when card.dataset.renderError is set", async () => {
        const el = build();
        const card = buildCard({
            renderError: "Render failed",
            renderErrorDetail: "java.lang.RuntimeException: oops",
        });
        applyFocusInspectorState(el, card, {
            earlyFeatures: true,
            historyActive: false,
        });
        await el.updateComplete;
        assert.strictEqual(el.hidden, false);
        const banner = el.querySelector(".focus-error-panel");
        assert.ok(banner);
        assert.strictEqual(
            banner!.querySelector(".focus-error-message")?.textContent?.trim(),
            "Render failed",
        );
        assert.strictEqual(
            banner!.querySelector(".focus-error-detail")?.textContent?.trim(),
            "java.lang.RuntimeException: oops",
        );
    });

    it("omits the detail row when no detail is stamped", async () => {
        const el = build();
        applyFocusInspectorState(el, buildCard({ renderError: "boom" }), {
            earlyFeatures: true,
            historyActive: false,
        });
        await el.updateComplete;
        assert.ok(el.querySelector(".focus-error-message"));
        assert.strictEqual(el.querySelector(".focus-error-detail"), null);
    });

    it("renders the history panel only when historyActive is true", async () => {
        const el = build();
        const card = buildCard({});
        applyFocusInspectorState(el, card, {
            earlyFeatures: true,
            historyActive: false,
        });
        await el.updateComplete;
        assert.strictEqual(el.querySelector(".focus-history-panel"), null);

        applyFocusInspectorState(el, card, {
            earlyFeatures: true,
            historyActive: true,
        });
        await el.updateComplete;
        const panel = el.querySelector(".focus-history-panel");
        assert.ok(panel);
        const buttons = panel!.querySelectorAll<HTMLButtonElement>(
            "button.focus-action",
        );
        assert.strictEqual(buttons.length, 2);
    });

    it("invokes onRequestDiff when a history button is clicked", async () => {
        const el = build();
        const calls: Array<"head" | "main"> = [];
        el.onRequestDiff = (against) => calls.push(against);
        applyFocusInspectorState(el, buildCard({}), {
            earlyFeatures: true,
            historyActive: true,
        });
        await el.updateComplete;
        const buttons = Array.from(
            el.querySelectorAll<HTMLButtonElement>(
                ".focus-history-panel button.focus-action",
            ),
        );
        buttons[0].click();
        buttons[1].click();
        assert.deepStrictEqual(calls, ["head", "main"]);
    });
});
