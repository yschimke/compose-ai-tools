// LiveStateController — launcher-widget override coverage.
//
// Pins the picker → wire-payload contract: writing a `LauncherWidgetSize`
// via `setLauncherWidgetCellsForCard` surfaces as
// `overrides.launcherWidget = { cells }` on the next `overridesForPreview`,
// and clearing the override (passing `null`) removes that branch. Also
// asserts the override is stored per-preview, not globally — a write
// against one card leaves another card's payload undefined.

import * as assert from "assert";
import type { LauncherWidgetSize } from "../daemon/daemonProtocol";
import { LiveStateController } from "../webview/preview/liveState";

function makeCard(previewId: string): HTMLElement {
    const el = document.createElement("div");
    el.className = "preview-card";
    el.dataset.previewId = previewId;
    return el;
}

interface ChangeRecord {
    previewId: string;
    cells: LauncherWidgetSize | null;
}

function makeController(
    onLauncherWidgetCellsChanged?: (
        previewId: string,
        cells: LauncherWidgetSize | null,
    ) => void,
): LiveStateController {
    const recordingFormat = document.createElement("select");
    return new LiveStateController({
        vscode: {
            postMessage: () => {},
            getState: () => undefined,
            setState: () => {},
        },
        recordingFormat,
        interactiveInputConfig: {
            vscode: {
                postMessage: () => {},
                getState: () => undefined,
                setState: () => {},
            },
            isLive: () => false,
        },
        earlyFeatures: () => true,
        inFocus: () => false,
        focusedCard: () => null,
        applyInteractiveButtonState: () => {},
        applyRecordingButtonState: () => {},
        renderInspector: () => {},
        onLauncherWidgetCellsChanged,
    });
}

describe("LiveStateController — launcher-widget override", () => {
    it("returns undefined overrides when no toggles have been written", () => {
        const live = makeController();
        assert.strictEqual(live.overridesForPreview("preview:A"), undefined);
        assert.strictEqual(
            live.launcherWidgetCellsForPreview("preview:A"),
            null,
        );
    });

    it("setLauncherWidgetCellsForCard surfaces in overridesForPreview", () => {
        const live = makeController();
        const card = makeCard("preview:A");
        const cells: LauncherWidgetSize = { width: 4, height: 2 };
        live.setLauncherWidgetCellsForCard(card, cells);
        assert.deepStrictEqual(
            live.launcherWidgetCellsForPreview("preview:A"),
            cells,
        );
        assert.deepStrictEqual(live.overridesForPreview("preview:A"), {
            launcherWidget: { cells: { width: 4, height: 2 } },
        });
    });

    it("clears the override when called with null", () => {
        const live = makeController();
        const card = makeCard("preview:A");
        live.setLauncherWidgetCellsForCard(card, { width: 3, height: 3 });
        live.setLauncherWidgetCellsForCard(card, null);
        assert.strictEqual(
            live.launcherWidgetCellsForPreview("preview:A"),
            null,
        );
        assert.strictEqual(live.overridesForPreview("preview:A"), undefined);
    });

    it("scopes the override per-preview", () => {
        const live = makeController();
        live.setLauncherWidgetCellsForCard(makeCard("preview:A"), {
            width: 5,
            height: 1,
        });
        assert.deepStrictEqual(live.overridesForPreview("preview:A"), {
            launcherWidget: { cells: { width: 5, height: 1 } },
        });
        assert.strictEqual(live.overridesForPreview("preview:B"), undefined);
    });

    it("is a no-op when card has no previewId", () => {
        const live = makeController();
        const card = document.createElement("div");
        live.setLauncherWidgetCellsForCard(card, { width: 2, height: 2 });
        assert.strictEqual(live.overridesForPreview(""), undefined);
    });

    it("fires onLauncherWidgetCellsChanged on set and clear", () => {
        const changes: ChangeRecord[] = [];
        const live = makeController((previewId, cells) =>
            changes.push({ previewId, cells }),
        );
        const card = makeCard("preview:A");
        live.setLauncherWidgetCellsForCard(card, { width: 3, height: 2 });
        live.setLauncherWidgetCellsForCard(card, null);
        assert.deepStrictEqual(changes, [
            { previewId: "preview:A", cells: { width: 3, height: 2 } },
            { previewId: "preview:A", cells: null },
        ]);
    });

    it("does not fire onLauncherWidgetCellsChanged for redundant writes", () => {
        const changes: ChangeRecord[] = [];
        const live = makeController((previewId, cells) =>
            changes.push({ previewId, cells }),
        );
        const card = makeCard("preview:A");
        live.setLauncherWidgetCellsForCard(card, { width: 3, height: 3 });
        // Same value again — `sameCells` short-circuits, no callback fires.
        live.setLauncherWidgetCellsForCard(card, { width: 3, height: 3 });
        assert.strictEqual(changes.length, 1);
    });

    it("clears the Controls flag when the live target is toggled off", () => {
        // Regression (#1709): once the focus-toolbar live button became the
        // sole stop path, toggling a card OFF must clear its #1203 Controls
        // flag. `planLiveToggle` only lists the *other* priors in
        // `deactivate`, so the target's own flag is cleared by the explicit
        // `!turnOnTarget` branch in `setInteractiveForCard` — without it a
        // later live re-entry silently re-attaches keyboard interception.
        const live = makeController();
        const card = makeCard("preview:A");
        // Enabling Controls drives the card live and sets the flag.
        live.toggleControlsForCard(card, true);
        assert.strictEqual(live.isLive("preview:A"), true);
        assert.strictEqual(live.isControlsEnabled("preview:A"), true);
        // Plain toggle off (the focus-toolbar live/stop button) stops live...
        live.setInteractiveForCard(card, false);
        assert.strictEqual(live.isLive("preview:A"), false);
        // ...and must also drop the Controls flag.
        assert.strictEqual(live.isControlsEnabled("preview:A"), false);
    });

    it("hydrateLauncherWidgetOverride seeds the map without firing the callback", () => {
        const changes: ChangeRecord[] = [];
        const live = makeController((previewId, cells) =>
            changes.push({ previewId, cells }),
        );
        live.hydrateLauncherWidgetOverride("preview:A", {
            width: 4,
            height: 3,
        });
        // The seeded value surfaces through the read accessor + override
        // payload, but no `onLauncherWidgetCellsChanged` event fired — boot
        // hydration must not re-persist what it just loaded.
        assert.deepStrictEqual(
            live.launcherWidgetCellsForPreview("preview:A"),
            { width: 4, height: 3 },
        );
        assert.deepStrictEqual(live.overridesForPreview("preview:A"), {
            launcherWidget: { cells: { width: 4, height: 3 } },
        });
        assert.strictEqual(changes.length, 0);
    });
});
