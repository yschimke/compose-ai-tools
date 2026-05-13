// Ambient (Watch / Wear) bundle presenter — fills the "Watch" tab
// body from the `compose/ambient` payload (see `AmbientModels.kt`):
// `{ state, burnInProtectionRequired, deviceHasLowBitAmbient,
// updateTimeMillis }`. The table is a single-row-per-field key/value
// flip of the payload so the user can read the wear ambient knobs
// without reaching for the full inspector.
//
// The presenter is **stateless**. There is no `<box-overlay>` here —
// ambient is a card-wide modality, not a region-level one. A small
// state badge gets stamped onto the focused card's `.image-container`
// by `main.ts` (see `refreshAmbientBundle`), keyed on the same level
// palette `<box-overlay>` uses (`info` / `warning` / `error`) but
// without bounds.

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";

export type AmbientStateLevel = "info" | "warning" | "error";

export interface AmbientPayload {
    state?: string | null;
    burnInProtectionRequired?: boolean;
    deviceHasLowBitAmbient?: boolean;
    updateTimeMillis?: number;
}

export interface AmbientRow {
    id: string;
    key: string;
    value: string;
}

export interface AmbientBundleData {
    rows: readonly AmbientRow[];
    /** Normalised state label — `"interactive"` / `"ambient"` /
     *  `"inactive"` — or `null` when the payload had no `state` field. */
    state: string | null;
    /** Level palette for the focus-card state badge. */
    stateLevel: AmbientStateLevel;
}

export function computeAmbientBundleData(
    payload: AmbientPayload | null | undefined,
): AmbientBundleData {
    if (!payload) {
        return { rows: [], state: null, stateLevel: "info" };
    }
    const state =
        typeof payload.state === "string" && payload.state.length > 0
            ? payload.state
            : null;
    const rows: AmbientRow[] = [
        { id: "ambient-state", key: "State", value: state ?? "—" },
        {
            id: "ambient-burn-in",
            key: "Burn-in protection",
            value: formatBool(payload.burnInProtectionRequired),
        },
        {
            id: "ambient-low-bit",
            key: "Low-bit ambient",
            value: formatBool(payload.deviceHasLowBitAmbient),
        },
        {
            id: "ambient-update-time",
            key: "Update time (ms)",
            value: formatMillis(payload.updateTimeMillis),
        },
    ];
    return { rows, state, stateLevel: levelFor(state) };
}

export function ambientTableColumns(): readonly DataTableColumn<AmbientRow>[] {
    return [
        {
            header: "Field",
            cellClass: "ambient-key-cell",
            render: (row) => html`<strong>${row.key}</strong>`,
        },
        {
            header: "Value",
            cellClass: "ambient-value-cell",
            render: (row) => row.value,
        },
    ];
}

function formatBool(b: boolean | null | undefined): string {
    if (b === true) return "yes";
    if (b === false) return "no";
    return "—";
}

function formatMillis(n: number | null | undefined): string {
    if (typeof n !== "number" || !Number.isFinite(n)) return "—";
    return String(n);
}

/**
 * Map the wire `state` string to a level palette. `"ambient"` is the
 * power-saving / readability mode — warn the user so they know the
 * preview pixels reflect the dim path, not interactive. `"inactive"`
 * usually means screen off — error tier so it stands out from a
 * normal interactive render. Everything else (including unknown
 * states the daemon adds later) lands as `"info"`.
 */
function levelFor(state: string | null): AmbientStateLevel {
    switch ((state ?? "").toLowerCase()) {
        case "ambient":
            return "warning";
        case "inactive":
            return "error";
        default:
            return "info";
    }
}
