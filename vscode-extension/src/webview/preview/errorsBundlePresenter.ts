// Errors bundle presenter — fills the "Errors" tab body from the
// `test/failure` payload (see `TestFailureDataProduct.kt`):
// `{ status, phase, error: { type, message, topFrame, stackTrace[] },
// ... }`. Top of the body is a small key/value table (phase / type /
// message / top frame). Below it the stack trace lives in a
// `<details>` — the one place a `<details>` is acceptable inside a
// tab body since the postmortem stack is genuinely secondary to the
// summary row above it.
//
// The presenter is **stateless**. No overlay layer — render failures
// don't have bounds.

import { html } from "lit";
import type { DataTableColumn } from "./components/DataTable";

export interface TestFailureError {
    type?: string | null;
    message?: string | null;
    topFrame?: string | null;
    stackTrace?: readonly string[] | null;
}

export interface TestFailurePayload {
    status?: string | null;
    phase?: string | null;
    error?: TestFailureError | null;
}

export interface ErrorsRow {
    id: string;
    key: string;
    value: string;
}

export interface ErrorsBundleData {
    rows: readonly ErrorsRow[];
    stackFrames: readonly string[];
    /** True when at least one summary field or stack frame was present.
     *  Used by the host to swap in a "no failure recorded" placeholder
     *  instead of a table of dashes. */
    hasFailure: boolean;
}

export function computeErrorsBundleData(
    payload: TestFailurePayload | null | undefined,
): ErrorsBundleData {
    if (!payload) {
        return { rows: [], stackFrames: [], hasFailure: false };
    }
    const err = payload.error ?? {};
    const rows: ErrorsRow[] = [
        {
            id: "errors-status",
            key: "Status",
            value: stringOrDash(payload.status),
        },
        {
            id: "errors-phase",
            key: "Phase",
            value: stringOrDash(payload.phase),
        },
        {
            id: "errors-type",
            key: "Type",
            value: stringOrDash(err.type),
        },
        {
            id: "errors-message",
            key: "Message",
            value: stringOrDash(err.message),
        },
        {
            id: "errors-top-frame",
            key: "Top frame",
            value: stringOrDash(err.topFrame),
        },
    ];
    const stackFrames = Array.isArray(err.stackTrace)
        ? err.stackTrace.filter(
              (f): f is string => typeof f === "string" && f.length > 0,
          )
        : [];
    const hasFailure =
        !!payload.status ||
        !!payload.phase ||
        !!err.type ||
        !!err.message ||
        stackFrames.length > 0;
    return { rows, stackFrames, hasFailure };
}

export function errorsTableColumns(): readonly DataTableColumn<ErrorsRow>[] {
    return [
        {
            header: "Field",
            cellClass: "errors-key-cell",
            render: (row) => html`<strong>${row.key}</strong>`,
        },
        {
            header: "Value",
            cellClass: "errors-value-cell",
            render: (row) =>
                row.id === "errors-top-frame" && row.value !== "—"
                    ? html`<code>${row.value}</code>`
                    : row.value,
        },
    ];
}

/**
 * Build the stack-trace `<details>` block the host appends to the
 * Errors tab body below the data-table. Returns `null` when there
 * are no frames, so the host can skip appending the section
 * entirely.
 */
export function renderErrorsStackFrames(
    frames: readonly string[],
): HTMLElement | null {
    if (frames.length === 0) return null;
    const wrap = document.createElement("details");
    wrap.className = "errors-stack-frames";
    const summary = document.createElement("summary");
    summary.textContent =
        "Stack trace · " +
        frames.length +
        " frame" +
        (frames.length === 1 ? "" : "s");
    wrap.appendChild(summary);
    const pre = document.createElement("pre");
    pre.className = "errors-stack-frames-list";
    pre.textContent = frames.join("\n");
    wrap.appendChild(pre);
    return wrap;
}

function stringOrDash(s: string | null | undefined): string {
    if (typeof s !== "string" || s.length === 0) return "—";
    return s;
}
