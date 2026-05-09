// Toggle UX for focus-mode data extensions:
//
//   1. Flipping a checkbox in the bucket list (or its suggestion chip)
//      fires `onToggleDataExtension(previewId, kind, enabled)` so the
//      caller can post `setDataExtensionEnabled` and trigger a daemon
//      subscribe — the "background fetch" half of the contract.
//
//   2. Re-render right after the toggle stamps a "Loading…" report
//      into the data section for any enabled kind whose presenter
//      hasn't produced a real contribution yet — the "immediate
//      placeholder" half. Avoids the silent-toggle UX where the
//      checkbox flips but nothing happens until a render attaches the
//      payload.
//
// `local/*` kinds (`local/a11y/overlay`, `local/render/error`) and the
// suggestion-chip path for `local/a11y/overlay` keep their dedicated
// wiring; they must NOT also fire the generic data-extension callback.

import * as assert from "assert";
import { FocusInspectorController } from "../webview/preview/focusInspector";
import type { PreviewInfo } from "../types";

const baseParams: PreviewInfo["params"] = {
    name: null,
    device: null,
    widthDp: 0,
    heightDp: 0,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: false,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: null,
};

const samplePreview: PreviewInfo = {
    id: "com.example.PreviewsKt.Sample",
    functionName: "Sample",
    className: "com.example.PreviewsKt",
    sourceFile: "Previews.kt",
    params: baseParams,
    captures: [
        {
            advanceTimeMillis: null,
            scroll: null,
            renderOutput: "renders/sample.png",
        },
    ],
};

interface ToggleEvent {
    previewId: string;
    kind: string;
    enabled: boolean;
}

interface Harness {
    inspector: FocusInspectorController;
    container: HTMLElement;
    card: HTMLElement;
    toggles: ToggleEvent[];
    a11yClicks: number;
}

function makeHarness(findingsCount = 0, autoEnableCheap = false): Harness {
    const toggles: ToggleEvent[] = [];
    let a11yClicks = 0;

    const container = document.createElement("div");
    document.body.appendChild(container);
    const card = document.createElement("div");
    card.dataset.previewId = samplePreview.id;
    document.body.appendChild(card);

    const inspector = new FocusInspectorController({
        el: container,
        earlyFeatures: () => true,
        autoEnableCheap: () => autoEnableCheap,
        // Echo any preview id back as a synthetic PreviewInfo so a test
        // can render against several cards without each one needing to
        // be threaded through the harness factory.
        getPreview: (id) => ({
            ...samplePreview,
            id,
            params: { ...samplePreview.params, uiMode: autoEnableCheap ? 32 : 0 },
        }),
        getA11yFindings: () =>
            Array.from({ length: findingsCount }, () => ({
                level: "ERROR",
                type: "stub",
                message: "stub",
            })),
        getA11yNodes: () => [],
        getA11yOverlayId: () => null,
        isLive: () => false,
        onToggleA11yOverlay: () => {
            a11yClicks += 1;
        },
        onToggleInteractive: () => {},
        onToggleRecording: () => {},
        onRequestFocusedDiff: () => {},
        onRequestLaunchOnDevice: () => {},
        onToggleDataExtension: (previewId, kind, enabled) => {
            toggles.push({ previewId, kind, enabled });
        },
        getScope: () => "/workspace/sample",
        loadMru: () => [],
        saveMru: () => {},
    });

    return {
        inspector,
        container,
        card,
        toggles,
        get a11yClicks() {
            return a11yClicks;
        },
    } as Harness;
}

describe("focus inspector data-extension toggle", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("fires onToggleDataExtension when a bucket checkbox is flipped", () => {
        const h = makeHarness();
        h.inspector.render(h.card);
        const themeCheckbox = h.container.querySelector<HTMLInputElement>(
            '.focus-product-option[data-bucket="theming"] > input',
        );
        assert.ok(themeCheckbox, "expected a theming-bucket checkbox");
        themeCheckbox.click();
        assert.deepStrictEqual(h.toggles, [
            {
                previewId: samplePreview.id,
                kind: "compose/theme",
                enabled: true,
            },
        ]);
        // Toggling the same row again disables it; both clicks reach the
        // callback so the extension can keep daemon state in sync.
        const themeCheckbox2 = h.container.querySelector<HTMLInputElement>(
            '.focus-product-option[data-bucket="theming"] > input',
        )!;
        themeCheckbox2.click();
        assert.strictEqual(h.toggles.length, 2);
        assert.strictEqual(h.toggles[1].enabled, false);
    });

    it("paints a Loading placeholder report immediately on toggle on", () => {
        const h = makeHarness();
        h.inspector.render(h.card);
        // Pick a kind with no registered presenter so we exercise the
        // generic fallback rather than the per-kind placeholder body
        // (compose/theme has its own "Awaiting data" report). `fonts/used`
        // ships in the built-in product list and has no presenter.
        const fontsRow = findRowByLabel(h.container, "Fonts");
        const fontsCheckbox = fontsRow.querySelector<HTMLInputElement>("input");
        assert.ok(fontsCheckbox, "expected the Fonts row to carry a checkbox");
        fontsCheckbox.click();
        const placeholderReport = h.container.querySelector(
            '.focus-report[data-kind="fonts/used"]',
        );
        assert.ok(
            placeholderReport,
            "expected a placeholder report keyed by the just-toggled kind",
        );
        const summary = placeholderReport!.querySelector(
            ".focus-report-summary-hint",
        );
        assert.ok(summary, "placeholder report should carry a Loading summary");
        assert.strictEqual(summary!.textContent, "Loading");
        const body = placeholderReport!.querySelector(".focus-report-body");
        assert.ok(body && body.textContent && body.textContent.length > 0);
    });

    it("does not fire onToggleDataExtension for the local a11y overlay chip", () => {
        const h = makeHarness();
        h.inspector.render(h.card);
        // The accessibility-overlay chip lives in the suggestion row.
        // suggestFor() always pushes `local/a11y/overlay` as the always-
        // helpful fallback, so it'll be present even with empty findings.
        const a11yChip = h.container.querySelector<HTMLButtonElement>(
            ".focus-suggestion-chip[data-state]",
        );
        assert.ok(a11yChip, "expected at least one suggestion chip");
        a11yChip.click();
        assert.strictEqual(h.toggles.length, 0);
        assert.strictEqual(h.a11yClicks, 1);
    });

    it("scopes enabled state per focused preview", () => {
        const h = makeHarness();
        // Render preview A and toggle compose/theme on. With per-preview
        // scope, A's checkbox flips while a sibling card never sees the
        // change.
        h.inspector.render(h.card);
        const themeCheckboxA = h.container.querySelector<HTMLInputElement>(
            '.focus-product-option[data-bucket="theming"] > input',
        )!;
        themeCheckboxA.click();
        assert.strictEqual(themeCheckboxA.checked, true);

        // Swap the focused card for a sibling preview. Re-render — the
        // theming row should be UNchecked because the per-preview enabled
        // set is fresh for B.
        const otherCard = document.createElement("div");
        otherCard.dataset.previewId = "com.example.PreviewsKt.Other";
        document.body.appendChild(otherCard);
        h.inspector.render(otherCard);
        const themeCheckboxB = h.container.querySelector<HTMLInputElement>(
            '.focus-product-option[data-bucket="theming"] > input',
        )!;
        assert.ok(
            themeCheckboxB,
            "expected the theming checkbox on the B render",
        );
        assert.strictEqual(
            themeCheckboxB.checked,
            false,
            "toggling on A must not bleed into B",
        );

        // Re-render A; its checkbox state is preserved.
        h.inspector.render(h.card);
        const themeCheckboxA2 = h.container.querySelector<HTMLInputElement>(
            '.focus-product-option[data-bucket="theming"] > input',
        )!;
        assert.strictEqual(themeCheckboxA2.checked, true);
    });

    it("surfaces suggested kinds first and badges audit findings count", () => {
        const h = makeHarness(3);
        h.inspector.render(h.card);
        const a11yBucket = h.container.querySelector(
            '.focus-bucket[data-bucket="accessibility"] .focus-bucket-body',
        );
        assert.ok(a11yBucket, "expected accessibility bucket body");
        const rows = [
            ...a11yBucket!.querySelectorAll<HTMLElement>(
                ".focus-product-option",
            ),
        ];
        assert.ok(rows.length > 0, "expected accessibility rows");
        const firstName = rows[0]
            .querySelector(".focus-product-name")
            ?.textContent?.trim();
        assert.strictEqual(firstName, "Accessibility overlay");
        const badge = rows[0].querySelector<HTMLElement>(
            ".focus-product-suggested-rank",
        );
        assert.ok(badge, "expected suggested rank badge on first row");
        assert.strictEqual(badge!.textContent, "3");
    });

    it("shows '?' while a suggested audit is queued, and removes for zero findings", () => {
        const h = makeHarness(0, true);
        h.inspector.render(h.card);
        const themingBucket = h.container.querySelector(
            '.focus-bucket[data-bucket="theming"] .focus-bucket-body',
        );
        assert.ok(themingBucket, "expected theming bucket body");
        const themeRow = findRowByLabel(themingBucket as HTMLElement, "Theme");
        const badge = themeRow.querySelector<HTMLElement>(
            ".focus-product-suggested-rank",
        );
        assert.ok(badge, "expected queued audit badge");
        assert.strictEqual(badge!.textContent, "?");
    });

    it("releasePreview unsubscribes every kind enabled for the previous focus", () => {
        const h = makeHarness();
        h.inspector.render(h.card);
        // Toggle two kinds on for the focused preview.
        const themeRow = findRowByLabel(h.container, "Theme");
        themeRow.querySelector<HTMLInputElement>("input")!.click();
        const fontsRow = findRowByLabel(h.container, "Fonts");
        fontsRow.querySelector<HTMLInputElement>("input")!.click();
        assert.strictEqual(h.toggles.length, 2);
        const enabledOn = h.toggles.filter((t) => t.enabled).map((t) => t.kind);
        assert.deepStrictEqual(enabledOn.sort(), [
            "compose/theme",
            "fonts/used",
        ]);

        // Simulate focus moving away. releasePreview should fire
        // matching unsubscribes for everything still enabled.
        h.inspector.releasePreview(samplePreview.id);
        const releaseCalls = h.toggles.slice(2);
        assert.strictEqual(releaseCalls.length, 2);
        for (const call of releaseCalls) {
            assert.strictEqual(call.previewId, samplePreview.id);
            assert.strictEqual(call.enabled, false);
        }
        const releasedKinds = releaseCalls.map((t) => t.kind).sort();
        assert.deepStrictEqual(releasedKinds, ["compose/theme", "fonts/used"]);

        // Re-render the same card — checkboxes are back to off because
        // the per-preview state was dropped.
        h.inspector.render(h.card);
        const themeRow2 = findRowByLabel(h.container, "Theme");
        assert.strictEqual(
            themeRow2.querySelector<HTMLInputElement>("input")!.checked,
            false,
        );
    });
});

function findRowByLabel(container: HTMLElement, label: string): HTMLElement {
    const rows = container.querySelectorAll<HTMLElement>(
        ".focus-product-option",
    );
    for (const row of rows) {
        const name = row.querySelector(".focus-product-name");
        if (name && name.textContent === label) return row;
    }
    throw new Error(`No focus-product-option row labelled "${label}"`);
}
