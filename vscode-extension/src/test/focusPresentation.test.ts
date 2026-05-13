// Unit tests for the presentation framework — registry behaviour
// (built-in presenters dispatched correctly), and the arrow
// correlator (attach/detach is idempotent and matches legend rows
// against overlay elements by id).

import * as assert from "assert";
import {
    _resetPresentersForTest,
    _seedBuiltInPresentersForTest,
    getPresenter,
    registerPresenter,
} from "../webview/preview/focusPresentation";
import { LegendArrowController } from "../webview/preview/legendArrow";
import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../types";

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

function ctx(overrides?: {
    nodes?: readonly AccessibilityNode[];
    findings?: readonly AccessibilityFinding[];
    cardDataset?: Record<string, string>;
    data?: (kind: string) => unknown;
}) {
    const card = document.createElement("div");
    if (overrides?.cardDataset) {
        Object.assign(card.dataset, overrides.cardDataset);
    }
    return {
        card,
        preview: samplePreview,
        findings: overrides?.findings ?? [],
        nodes: overrides?.nodes ?? [],
        data: overrides?.data,
    };
}

describe("focusPresentation registry", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("dispatches the registered presenter for a kind", () => {
        // The built-in presenters register at module import.
        const a11y = getPresenter("a11y/hierarchy");
        assert.ok(a11y, "a11y/hierarchy presenter should be registered");
    });

    it("returns a placeholder report for a11y/hierarchy with no nodes", () => {
        const a11y = getPresenter("a11y/hierarchy")!;
        const result = a11y(ctx());
        assert.ok(result);
        assert.ok(result!.report);
        assert.strictEqual(result!.overlay, undefined);
        assert.strictEqual(result!.legend, undefined);
    });

    it("paints overlay boxes and a legend when nodes are present", () => {
        const a11y = getPresenter("a11y/hierarchy")!;
        const nodes: AccessibilityNode[] = [
            {
                label: "Submit",
                role: "Button",
                states: ["enabled"],
                merged: false,
                boundsInScreen: "10,20,110,80",
            },
            {
                label: "Description",
                role: "Text",
                states: [],
                merged: true,
                boundsInScreen: "0,90,200,140",
            },
        ];
        const result = a11y(ctx({ nodes }));
        assert.ok(result);
        assert.ok(result!.overlay instanceof HTMLElement);
        const boxes = result!.overlay!.querySelectorAll("[data-overlay-id]");
        assert.strictEqual(boxes.length, 2);
        assert.strictEqual(boxes[0].getAttribute("data-overlay-id"), "node-0");
        assert.ok(result!.legend);
        assert.strictEqual(result!.legend!.entries.length, 2);
        assert.strictEqual(result!.legend!.entries[0].id, "node-0");
        assert.strictEqual(result!.legend!.entries[1].level, "warning");
    });

    it("emits an error contribution when every bound is malformed", () => {
        const a11y = getPresenter("a11y/hierarchy")!;
        const nodes: AccessibilityNode[] = [
            {
                label: "Bad",
                role: "Button",
                states: [],
                merged: false,
                boundsInScreen: "garbage",
            },
        ];
        const result = a11y(ctx({ nodes }));
        assert.ok(result);
        assert.ok(result!.error);
        assert.strictEqual(result!.overlay, null);
    });

    it("a11y/overlay presenter returns null when no payload is cached", () => {
        const overlay = getPresenter("a11y/overlay")!;
        assert.strictEqual(overlay(ctx()), null);
        // Wrong-shape payloads also fall through to null so the
        // inspector renders the pending fallback instead of a broken
        // <img>.
        const malformed = overlay(ctx({ data: () => ({ imageBase64: 42 }) }));
        assert.strictEqual(malformed, null);
        const empty = overlay(ctx({ data: () => ({ imageBase64: "" }) }));
        assert.strictEqual(empty, null);
    });

    it("a11y/overlay presenter emits a Report with the PNG when payload is present", () => {
        const overlay = getPresenter("a11y/overlay")!;
        const result = overlay(
            ctx({
                data: (kind) =>
                    kind === "a11y/overlay"
                        ? {
                              imageBase64: "AAAA",
                              mediaType: "image/png",
                              sizeBytes: 4096,
                          }
                        : undefined,
            }),
        );
        assert.ok(result);
        assert.ok(result!.report);
        assert.strictEqual(
            result!.report!.title,
            "Accessibility overlay (PNG)",
        );
        assert.strictEqual(result!.report!.summary, "4 kB");
        const img = result!.report!.body.querySelector("img");
        assert.ok(img);
        assert.strictEqual(img!.src, "data:image/png;base64,AAAA");
    });

    it("compose/recomposition returns null when no payload is cached", () => {
        const recomp = getPresenter("compose/recomposition")!;
        assert.strictEqual(recomp(ctx()), null);
    });

    it("compose/recomposition returns null when nodes is empty", () => {
        const recomp = getPresenter("compose/recomposition")!;
        const result = recomp(
            ctx({
                data: (kind) =>
                    kind === "compose/recomposition"
                        ? { mode: "snapshot", nodes: [] }
                        : undefined,
            }),
        );
        assert.strictEqual(result, null);
    });

    it("compose/recomposition sorts nodes by count desc and surfaces mode badge", () => {
        const recomp = getPresenter("compose/recomposition")!;
        const result = recomp(
            ctx({
                data: (kind) =>
                    kind === "compose/recomposition"
                        ? {
                              mode: "delta",
                              inputSeq: 42,
                              nodes: [
                                  { nodeId: "0xaaa", count: 3 },
                                  { nodeId: "0xbbb", count: 17 },
                                  { nodeId: "0xccc", count: 9 },
                              ],
                          }
                        : undefined,
            }),
        );
        assert.ok(result);
        assert.ok(result!.legend);
        assert.strictEqual(result!.legend!.entries.length, 3);
        assert.strictEqual(result!.legend!.entries[0].label, "0xbbb");
        assert.strictEqual(result!.legend!.entries[1].label, "0xccc");
        assert.strictEqual(result!.legend!.entries[2].label, "0xaaa");
        assert.ok(result!.legend!.summary!.includes("delta"));
        assert.ok(result!.legend!.summary!.includes("input 42"));
        assert.ok(result!.report);
        const badge = result!.report!.body.querySelector(
            ".focus-report-recomposition-mode",
        );
        assert.ok(badge);
        assert.strictEqual(badge!.textContent, "delta");
        const code = result!.report!.body.querySelector(
            ".focus-report-recomposition-nodeid",
        );
        assert.ok(code);
        assert.strictEqual(code!.textContent, "0xbbb");
    });

    it("compose/recomposition caps the visible list at top-N with a +N more affordance", () => {
        const recomp = getPresenter("compose/recomposition")!;
        const nodes = Array.from({ length: 15 }, (_, i) => ({
            nodeId: "0x" + i,
            count: i,
        }));
        const result = recomp(
            ctx({
                data: (kind) =>
                    kind === "compose/recomposition"
                        ? { mode: "snapshot", nodes }
                        : undefined,
            }),
        );
        assert.ok(result);
        assert.strictEqual(result!.legend!.entries.length, 10);
        const more = result!.report!.body.querySelector(
            ".focus-report-recomposition-more",
        );
        assert.ok(more);
        assert.strictEqual(more!.textContent, "+5 more");
    });

    it("compose/recomposition skips inputSeq when not in delta mode", () => {
        const recomp = getPresenter("compose/recomposition")!;
        const result = recomp(
            ctx({
                data: (kind) =>
                    kind === "compose/recomposition"
                        ? {
                              mode: "snapshot",
                              inputSeq: 99,
                              nodes: [{ nodeId: "0xaaa", count: 1 }],
                          }
                        : undefined,
            }),
        );
        assert.ok(result);
        assert.ok(!result!.legend!.summary!.includes("input"));
        const seq = result!.report!.body.querySelector(
            ".focus-report-recomposition-inputseq",
        );
        assert.strictEqual(seq, null);
    });

    it("render/trace returns null when both phases and metrics are empty", () => {
        const trace = getPresenter("render/trace")!;
        assert.strictEqual(trace(ctx()), null);
        const result = trace(
            ctx({
                data: (kind) =>
                    kind === "render/trace"
                        ? { totalMs: 0, phases: [], metrics: {} }
                        : undefined,
            }),
        );
        assert.strictEqual(result, null);
    });

    it("render/trace renders a phase bar and a totalMs summary", () => {
        const trace = getPresenter("render/trace")!;
        const result = trace(
            ctx({
                data: (kind) =>
                    kind === "render/trace"
                        ? {
                              totalMs: 412,
                              phases: [
                                  {
                                      name: "render",
                                      startMs: 0,
                                      durationMs: 412,
                                  },
                              ],
                              metrics: { tookMs: 412 },
                          }
                        : undefined,
            }),
        );
        assert.ok(result);
        assert.ok(result!.report);
        assert.strictEqual(result!.report!.summary, "412 ms");
        const phases = result!.report!.body.querySelectorAll(
            ".focus-report-render-trace-phase",
        );
        assert.strictEqual(phases.length, 1);
        assert.strictEqual((phases[0] as HTMLElement).style.width, "100%");
        // tookMs is suppressed because it duplicates totalMs.
        const metrics = result!.report!.body.querySelector(
            ".focus-report-render-trace-metrics",
        );
        assert.strictEqual(metrics, null);
    });

    it("render/trace surfaces non-tookMs metrics under the phases bar", () => {
        const trace = getPresenter("render/trace")!;
        const result = trace(
            ctx({
                data: (kind) =>
                    kind === "render/trace"
                        ? {
                              totalMs: 30,
                              phases: [
                                  {
                                      name: "compose",
                                      startMs: 0,
                                      durationMs: 10,
                                  },
                                  {
                                      name: "measure",
                                      startMs: 10,
                                      durationMs: 20,
                                  },
                              ],
                              metrics: {
                                  tookMs: 30,
                                  allocBytes: 1234,
                                  gcMs: 2,
                              },
                          }
                        : undefined,
            }),
        );
        assert.ok(result);
        const phases = result!.report!.body.querySelectorAll(
            ".focus-report-render-trace-phase",
        );
        assert.strictEqual(phases.length, 2);
        const dts = result!.report!.body.querySelectorAll(
            ".focus-report-render-trace-metrics dt",
        );
        const keys = Array.from(dts).map((n) => n.textContent);
        assert.deepStrictEqual(keys, ["allocBytes", "gcMs"]);
    });

    it("renderErrorPresenter activates only when the card carries a render error", () => {
        const renderErr = getPresenter("local/render/error")!;
        assert.strictEqual(renderErr(ctx()), null);
        const result = renderErr(
            ctx({
                cardDataset: { renderError: "NPE", renderErrorDetail: "boom" },
            }),
        );
        assert.ok(result);
        assert.ok(result!.error);
        assert.strictEqual(result!.error!.message, "NPE");
        assert.strictEqual(result!.error!.detail, "boom");
    });

    it("registerPresenter is last-write-wins (visible to tests)", () => {
        // Snapshot the existing presenter so we can restore module
        // behaviour for sibling tests.
        const original = getPresenter("a11y/hierarchy")!;
        registerPresenter("a11y/hierarchy", () => null);
        assert.strictEqual(getPresenter("a11y/hierarchy")!(ctx()), null);
        // Restore by re-registering the original directly. (Using
        // `_resetPresentersForTest` would also drop the other built-ins.)
        registerPresenter("a11y/hierarchy", original);
        assert.notStrictEqual(getPresenter("a11y/hierarchy")!(ctx()), null);
    });

    it("_resetPresentersForTest empties the registry", () => {
        _resetPresentersForTest();
        assert.strictEqual(getPresenter("a11y/hierarchy"), undefined);
        // Reseed so sibling test files that depend on the built-ins
        // (the "trusts a presenter that returns null" assertion in
        // focusInspectorToggle.test is the canonical case) don't see
        // an empty registry. A bare re-`require` here is a no-op
        // (Node's module cache), and busting the cache creates a
        // *second* copy of the registry the first import's
        // `getPresenter` can't see — the only reliable restore is
        // calling the seed helper directly.
        _seedBuiltInPresentersForTest();
        assert.ok(
            getPresenter("a11y/hierarchy"),
            "registry must be reseeded so the built-in a11y/hierarchy presenter is callable again",
        );
    });
});

describe("LegendArrowController", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    function setup(): {
        legends: HTMLElement;
        overlay: HTMLElement;
        controller: LegendArrowController;
    } {
        const legends = document.createElement("div");
        const overlay = document.createElement("div");
        const row = document.createElement("li");
        row.dataset.legendId = "node-0";
        legends.appendChild(row);
        const box = document.createElement("div");
        box.dataset.overlayId = "node-0";
        overlay.appendChild(box);
        document.body.appendChild(legends);
        document.body.appendChild(overlay);
        const controller = new LegendArrowController();
        return { legends, overlay, controller };
    }

    it("adds legend-active to the matching overlay on hover", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        const row = legends.querySelector<HTMLElement>("[data-legend-id]")!;
        row.dispatchEvent(new Event("mouseenter"));
        const box = overlay.querySelector<HTMLElement>("[data-overlay-id]")!;
        assert.ok(box.classList.contains("legend-active"));
        row.dispatchEvent(new Event("mouseleave"));
        assert.ok(!box.classList.contains("legend-active"));
    });

    it("detach removes listeners (idempotent)", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        controller.detach();
        controller.detach(); // no throw
        const row = legends.querySelector<HTMLElement>("[data-legend-id]")!;
        row.dispatchEvent(new Event("mouseenter"));
        const box = overlay.querySelector<HTMLElement>("[data-overlay-id]")!;
        assert.ok(!box.classList.contains("legend-active"));
    });

    it("re-attach replaces previous listeners", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        controller.attach(legends, overlay);
        const row = legends.querySelector<HTMLElement>("[data-legend-id]")!;
        // Without dedupe, two listeners would fire and a removeEvent
        // pair would still leave one dangling. We assert the toggle
        // behaviour instead — enter then leave should clear the class.
        row.dispatchEvent(new Event("mouseenter"));
        row.dispatchEvent(new Event("mouseleave"));
        const box = overlay.querySelector<HTMLElement>("[data-overlay-id]")!;
        assert.ok(!box.classList.contains("legend-active"));
    });

    it("creates a singleton SVG arrow on document.body", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        const svgs = document.querySelectorAll("#focus-legend-arrow");
        assert.strictEqual(svgs.length, 1);
    });
});
