// Integration tests for the focus inspector's MRU persistence
// hand-off — `loadMru` is read on first render per scope, `saveMru`
// fires after every toggle. Uses happy-dom (registered globally via
// `setup-dom.ts`) so we can drive the controller through real DOM
// events instead of poking at private state.

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

interface Harness {
    inspector: FocusInspectorController;
    container: HTMLElement;
    card: HTMLElement;
    saved: { scope: string; mru: string[] }[];
    loaded: string[];
    loadMru: (scope: string) => readonly string[];
    setScope: (next: string) => void;
}

function makeHarness(opts?: {
    initialMru?: Record<string, readonly string[]>;
    scope?: string;
}): Harness {
    const initialMru = opts?.initialMru ?? {};
    let scope = opts?.scope ?? "/workspace/sample";
    const saved: { scope: string; mru: string[] }[] = [];
    const loaded: string[] = [];

    const container = document.createElement("div");
    document.body.appendChild(container);
    const card = document.createElement("div");
    card.dataset.previewId = samplePreview.id;
    document.body.appendChild(card);

    const inspector = new FocusInspectorController({
        el: container,
        earlyFeatures: () => true,
        autoEnableCheap: () => false,
        getPreview: (id) =>
            id === samplePreview.id ? samplePreview : undefined,
        getA11yFindings: () => [],
        getA11yNodes: () => [],
        getA11yOverlayId: () => null,
        isLive: () => false,
        onToggleA11yOverlay: () => {},
        onToggleInteractive: () => {},
        onToggleRecording: () => {},
        onRequestFocusedDiff: () => {},
        onRequestLaunchOnDevice: () => {},
        getScope: () => scope,
        loadMru: (s) => {
            loaded.push(s);
            return initialMru[s] ?? [];
        },
        saveMru: (s, mru) => {
            saved.push({ scope: s, mru: [...mru] });
        },
    });

    return {
        inspector,
        container,
        card,
        saved,
        loaded,
        loadMru: (s) => initialMru[s] ?? [],
        setScope: (next) => {
            scope = next;
        },
    };
}

describe("focus inspector MRU persistence", () => {
    afterEach(() => {
        // happy-dom is per-process; clean up DOM between tests to keep
        // assertions independent.
        document.body.innerHTML = "";
    });

    it("hydrates MRU from loadMru on first render of a scope", () => {
        const h = makeHarness({
            initialMru: {
                "/workspace/sample": ["compose/theme", "fonts/used"],
            },
        });
        h.inspector.render(h.card);
        assert.deepStrictEqual(h.loaded, ["/workspace/sample"]);
    });

    it("does not call loadMru a second time for the same scope", () => {
        const h = makeHarness({
            initialMru: {
                "/workspace/sample": ["compose/theme"],
            },
        });
        h.inspector.render(h.card);
        h.inspector.render(h.card);
        h.inspector.render(h.card);
        assert.deepStrictEqual(h.loaded, ["/workspace/sample"]);
    });

    it("calls saveMru with the kind moved to the front after a toggle", () => {
        const h = makeHarness({
            initialMru: {
                "/workspace/sample": ["fonts/used", "compose/theme"],
            },
        });
        h.inspector.render(h.card);
        // The accessibility row routes through `onToggleA11yOverlay`
        // (special case) and never touches MRU. Pick a theme-bucket
        // checkbox so we exercise the `toggleProduct` path.
        const themeCheckbox = h.container.querySelector<HTMLInputElement>(
            '.focus-product-option[data-bucket="theming"] > input',
        );
        assert.ok(themeCheckbox, "expected a theming-bucket checkbox");
        themeCheckbox.click();
        assert.strictEqual(h.saved.length, 1);
        assert.strictEqual(h.saved[0].scope, "/workspace/sample");
        assert.strictEqual(h.saved[0].mru[0], "compose/theme");
    });

    it("partitions MRU by scope (switching scope re-hydrates)", () => {
        const h = makeHarness({
            initialMru: {
                "/workspace/a": ["fonts/used"],
                "/workspace/b": ["compose/theme"],
            },
            scope: "/workspace/a",
        });
        h.inspector.render(h.card);
        h.setScope("/workspace/b");
        h.inspector.render(h.card);
        assert.deepStrictEqual(h.loaded, ["/workspace/a", "/workspace/b"]);
    });
});
