// Perfetto handoff: regression for the P1 review on PR #1079 — the
// "Open in Perfetto" button must ship the raw composeAiTrace JSON,
// not a wrapper object, or paste-into-ui.perfetto.dev fails. The
// click also opens the Perfetto UI in the user's browser so the
// flow is one-click: copy + open, paste in the just-opened tab.
//
// The button is built inside `renderPerformanceSections`, which is
// otherwise pure DOM glue. We mount it into happy-dom, locate the
// button, click it, and inspect the captured clipboard + openExternal
// payloads.

import * as assert from "assert";
import {
    computePerformanceBundleData,
    renderPerformanceSections,
} from "../webview/preview/performanceBundlePresenter";
import { DataTable } from "../webview/preview/components/DataTable";

interface StubTable {
    setRows(rows: readonly unknown[]): void;
    setOverlayId(fn: (row: unknown, index: number) => string): void;
    setJsonPayload(fn: () => unknown): void;
    summary: string;
}

function stubTable(): StubTable {
    return {
        setRows: () => undefined,
        setOverlayId: () => undefined,
        setJsonPayload: () => undefined,
        summary: "",
    };
}

describe("Perfetto handoff (PR #1079 P1)", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });

    it("copies the raw composeAiTrace JSON, not a wrapper object", () => {
        const traceEvents = [
            { name: "frame", ph: "B", ts: 0, pid: 1, tid: 1 },
            { name: "frame", ph: "E", ts: 16000, pid: 1, tid: 1 },
        ];
        const composeAiTrace = { traceEvents };
        const data = computePerformanceBundleData(null, null, composeAiTrace);

        const host = document.createElement("div");
        document.body.appendChild(host);
        let captured: string | null = null;
        renderPerformanceSections(
            host,
            data,
            "preview.id",
            stubTable() as unknown as DataTable<unknown>,
            (text) => {
                captured = text;
            },
            {
                recomposition: null,
                renderTrace: null,
                composeAiTrace,
            },
        );
        const button = host.querySelector<HTMLButtonElement>(
            "button.perf-perfetto-open",
        );
        assert.ok(button, "Perfetto button rendered");
        button!.click();
        assert.ok(captured, "clipboard write captured");
        const parsed = JSON.parse(captured!);
        assert.deepStrictEqual(
            parsed,
            composeAiTrace,
            "clipboard payload must be the raw composeAiTrace, not a wrapper",
        );
        assert.ok(
            !Object.prototype.hasOwnProperty.call(parsed, "previewId"),
            "previewId wrapper field must not appear at top level",
        );
        assert.ok(
            Object.prototype.hasOwnProperty.call(parsed, "traceEvents"),
            "Perfetto-importable trace must expose traceEvents at the document root",
        );
    });

    it("clipboard write is a clear no-op when no composeAiTrace payload arrived", () => {
        const data = computePerformanceBundleData(null, null, null);
        // No composeAiTrace data → section isn't even rendered, so
        // the button can't be clicked. Sanity-check that the absence
        // is visible to the host.
        assert.strictEqual(data.composeAiTrace, null);
    });

    it("click writes clipboard AND opens ui.perfetto.dev (in that order)", () => {
        const traceEvents = [{ name: "frame", ph: "B", ts: 0, pid: 1, tid: 1 }];
        const composeAiTrace = { traceEvents };
        const data = computePerformanceBundleData(null, null, composeAiTrace);

        const host = document.createElement("div");
        document.body.appendChild(host);
        const events: Array<
            { kind: "clip"; text: string } | { kind: "open"; url: string }
        > = [];
        renderPerformanceSections(
            host,
            data,
            "preview.id",
            stubTable() as unknown as DataTable<unknown>,
            (text) => events.push({ kind: "clip", text }),
            {
                recomposition: null,
                renderTrace: null,
                composeAiTrace,
            },
            (url) => events.push({ kind: "open", url }),
        );
        const button = host.querySelector<HTMLButtonElement>(
            "button.perf-perfetto-open",
        );
        assert.ok(button, "Perfetto button rendered");
        button!.click();
        assert.strictEqual(events.length, 2, "exactly two side effects fired");
        assert.strictEqual(
            events[0].kind,
            "clip",
            "clipboard write fires first",
        );
        assert.strictEqual(events[1].kind, "open", "openExternal fires second");
        assert.strictEqual(
            (events[1] as { kind: "open"; url: string }).url,
            "https://ui.perfetto.dev",
            "openExternal URL is the bare Perfetto UI origin (no trailing slash, no ?url=)",
        );
        const status = host.querySelector<HTMLElement>(".perf-perfetto-status");
        assert.ok(status, "status row rendered");
        assert.match(
            status!.textContent ?? "",
            /Paste with Cmd\/Ctrl-V/,
            "status hint tells the user where to paste",
        );
    });

    it("when composeAiTrace payload is missing, clipboard write is empty AND no openExternal fires", () => {
        // Synthesise a summary with no `traceEvents` so the section
        // still renders (it does when `phaseCount` or `totalMs` is
        // present), but `rawPayloads.composeAiTrace` is null — the
        // path the click handler guards against.
        const composeAiTrace = { traceEvents: [], totalMs: 5 };
        const data = computePerformanceBundleData(null, null, composeAiTrace);
        assert.ok(data.composeAiTrace, "summary rendered for totalMs-only");

        const host = document.createElement("div");
        document.body.appendChild(host);
        const events: Array<
            { kind: "clip"; text: string } | { kind: "open"; url: string }
        > = [];
        renderPerformanceSections(
            host,
            data,
            "preview.id",
            stubTable() as unknown as DataTable<unknown>,
            (text) => events.push({ kind: "clip", text }),
            {
                recomposition: null,
                renderTrace: null,
                composeAiTrace: null,
            },
            (url) => events.push({ kind: "open", url }),
        );
        const button = host.querySelector<HTMLButtonElement>(
            "button.perf-perfetto-open",
        );
        assert.ok(button, "Perfetto button rendered");
        button!.click();
        assert.strictEqual(events.length, 1, "only one side effect fires");
        assert.strictEqual(events[0].kind, "clip", "clipboard fired");
        assert.strictEqual(
            (events[0] as { kind: "clip"; text: string }).text,
            "",
            "clipboard write is the empty string",
        );
        assert.ok(
            !events.some((e) => e.kind === "open"),
            "no openExternal fires when there's nothing to paste",
        );
    });
});
