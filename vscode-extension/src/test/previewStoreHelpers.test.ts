import * as assert from "assert";
import {
    bumpPreviewMapsRevision,
    clearCardA11yFindings,
    clearCardA11yNodes,
    deleteCardA11yFindings,
    deleteCardA11yNodes,
    deleteCardCaptures,
    previewStore,
    setCardA11yFindings,
    setCardA11yNodes,
    setCardCaptures,
} from "../webview/preview/previewStore";
import type { CapturePresentation } from "../webview/preview/frameCarousel";
import type {
    AccessibilityFinding,
    AccessibilityNode,
} from "../webview/shared/types";

function resetStore(): void {
    previewStore.setState({
        cardCaptures: new Map(),
        cardA11yFindings: new Map(),
        cardA11yNodes: new Map(),
        mapsRevision: 0,
    });
}

function makeCapture(label: string): CapturePresentation {
    return {
        label,
        renderOutput: `${label}.png`,
        imageData: null,
        errorMessage: null,
        renderError: null,
    };
}

const finding: AccessibilityFinding = {
    level: "WARNING",
    type: "low-contrast",
    message: "contrast too low",
};

const node: AccessibilityNode = {
    label: "Button",
    role: "button",
    states: [],
    merged: false,
    boundsInScreen: "0,0,100,40",
};

describe("previewStore per-preview Map helpers", () => {
    beforeEach(() => {
        resetStore();
    });

    describe("setCardCaptures", () => {
        it("writes to cardCaptures and bumps mapsRevision", () => {
            setCardCaptures("p1", [makeCapture("a")]);
            const state = previewStore.getState();
            assert.deepStrictEqual(state.cardCaptures.get("p1"), [
                makeCapture("a"),
            ]);
            assert.strictEqual(state.mapsRevision, 1);
        });

        it("bumps revision on every call even with the same value (no dedupe)", () => {
            const caps = [makeCapture("a")];
            setCardCaptures("p1", caps);
            setCardCaptures("p1", caps);
            assert.strictEqual(previewStore.getState().mapsRevision, 2);
        });
    });

    describe("deleteCardCaptures", () => {
        it("removes the entry and bumps revision when entry existed", () => {
            setCardCaptures("p1", [makeCapture("a")]);
            const before = previewStore.getState().mapsRevision;
            deleteCardCaptures("p1");
            const state = previewStore.getState();
            assert.strictEqual(state.cardCaptures.has("p1"), false);
            assert.strictEqual(state.mapsRevision, before + 1);
        });

        it("does not bump revision when no entry existed", () => {
            const before = previewStore.getState().mapsRevision;
            deleteCardCaptures("missing");
            assert.strictEqual(previewStore.getState().mapsRevision, before);
        });
    });

    describe("setCardA11yFindings", () => {
        it("writes to cardA11yFindings and bumps mapsRevision", () => {
            setCardA11yFindings("p1", [finding]);
            const state = previewStore.getState();
            assert.deepStrictEqual(state.cardA11yFindings.get("p1"), [finding]);
            assert.strictEqual(state.mapsRevision, 1);
        });

        it("bumps revision on every call even with the same value", () => {
            setCardA11yFindings("p1", [finding]);
            setCardA11yFindings("p1", [finding]);
            assert.strictEqual(previewStore.getState().mapsRevision, 2);
        });
    });

    describe("deleteCardA11yFindings", () => {
        it("removes the entry and bumps revision when entry existed", () => {
            setCardA11yFindings("p1", [finding]);
            const before = previewStore.getState().mapsRevision;
            deleteCardA11yFindings("p1");
            const state = previewStore.getState();
            assert.strictEqual(state.cardA11yFindings.has("p1"), false);
            assert.strictEqual(state.mapsRevision, before + 1);
        });

        it("does not bump revision when no entry existed", () => {
            const before = previewStore.getState().mapsRevision;
            deleteCardA11yFindings("missing");
            assert.strictEqual(previewStore.getState().mapsRevision, before);
        });
    });

    describe("setCardA11yNodes", () => {
        it("writes to cardA11yNodes and bumps mapsRevision", () => {
            setCardA11yNodes("p1", [node]);
            const state = previewStore.getState();
            assert.deepStrictEqual(state.cardA11yNodes.get("p1"), [node]);
            assert.strictEqual(state.mapsRevision, 1);
        });

        it("bumps revision on every call even with the same value", () => {
            setCardA11yNodes("p1", [node]);
            setCardA11yNodes("p1", [node]);
            assert.strictEqual(previewStore.getState().mapsRevision, 2);
        });
    });

    describe("deleteCardA11yNodes", () => {
        it("removes the entry and bumps revision when entry existed", () => {
            setCardA11yNodes("p1", [node]);
            const before = previewStore.getState().mapsRevision;
            deleteCardA11yNodes("p1");
            const state = previewStore.getState();
            assert.strictEqual(state.cardA11yNodes.has("p1"), false);
            assert.strictEqual(state.mapsRevision, before + 1);
        });

        it("does not bump revision when no entry existed", () => {
            const before = previewStore.getState().mapsRevision;
            deleteCardA11yNodes("missing");
            assert.strictEqual(previewStore.getState().mapsRevision, before);
        });
    });

    describe("clearCardA11yFindings", () => {
        it("no-ops and does not bump revision when map is already empty", () => {
            const before = previewStore.getState().mapsRevision;
            clearCardA11yFindings();
            assert.strictEqual(previewStore.getState().mapsRevision, before);
        });

        it("clears the map and bumps revision when entries existed", () => {
            setCardA11yFindings("p1", [finding]);
            setCardA11yFindings("p2", [finding]);
            const before = previewStore.getState().mapsRevision;
            clearCardA11yFindings();
            const state = previewStore.getState();
            assert.strictEqual(state.cardA11yFindings.size, 0);
            assert.strictEqual(state.mapsRevision, before + 1);
        });
    });

    describe("clearCardA11yNodes", () => {
        it("no-ops and does not bump revision when map is already empty", () => {
            const before = previewStore.getState().mapsRevision;
            clearCardA11yNodes();
            assert.strictEqual(previewStore.getState().mapsRevision, before);
        });

        it("clears the map and bumps revision when entries existed", () => {
            setCardA11yNodes("p1", [node]);
            setCardA11yNodes("p2", [node]);
            const before = previewStore.getState().mapsRevision;
            clearCardA11yNodes();
            const state = previewStore.getState();
            assert.strictEqual(state.cardA11yNodes.size, 0);
            assert.strictEqual(state.mapsRevision, before + 1);
        });
    });

    describe("bumpPreviewMapsRevision", () => {
        it("increments mapsRevision by 1", () => {
            const before = previewStore.getState().mapsRevision;
            bumpPreviewMapsRevision();
            assert.strictEqual(
                previewStore.getState().mapsRevision,
                before + 1,
            );
        });

        it("is monotonic across many bumps", () => {
            const before = previewStore.getState().mapsRevision;
            for (let i = 0; i < 25; i++) bumpPreviewMapsRevision();
            assert.strictEqual(
                previewStore.getState().mapsRevision,
                before + 25,
            );
        });
    });
});
