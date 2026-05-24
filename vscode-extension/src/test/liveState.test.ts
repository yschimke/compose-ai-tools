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

function makeController(): LiveStateController {
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
});
