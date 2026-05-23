// Permissions bundle presenter — fills the Inspection tab body from
// the `compose/permissions` payload (see `PermissionsModels.kt`):
// `{ grants: { permission -> "granted" | "denied" }, queried: [permission] }`.
//
// Two table sections so the panel surfaces both halves of the data
// product:
//
//   - Effective grants — what the around-composable's controller
//     pushed into Robolectric's `ShadowApplication` for this render.
//     `granted` lands as an info row; `denied` as a warning row so a
//     screen rendering the "request permission" branch is visually
//     distinct from one rendering the granted UI.
//   - Queried — what the shadow on `ContextWrapper.checkPermission`
//     recorded the screen asking about during composition. Permissions
//     queried but with no override applied land as `unknown` so the
//     reader can see "screen asked, no grant pinned, defaulted to the
//     manifest baseline" without an off-screen mental step.
//
// The presenter is **stateless**. The panel's bundle controller calls
// `computePermissionsBundleData(payload)` whenever a fresh payload
// lands and re-renders the rows.

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";

export type PermissionGrantWire = "granted" | "denied";

export interface PermissionsPayload {
    grants?: Record<string, PermissionGrantWire> | null;
    queried?: readonly string[] | null;
}

export type PermissionRowLevel = "info" | "warning" | "unknown";

export interface PermissionRow {
    id: string;
    permission: string;
    /** Short label trimmed of the `android.permission.` prefix where present. */
    shortLabel: string;
    /** `granted` / `denied` for grants; `null` for queried-only rows. */
    grant: PermissionGrantWire | null;
    /** `true` when the screen asked about this permission during composition. */
    queried: boolean;
    level: PermissionRowLevel;
}

export interface PermissionsBundleData {
    /** Effective grants the around-composable applied for this render. */
    grantRows: readonly PermissionRow[];
    /** Permissions the screen queried during composition. */
    queriedRows: readonly PermissionRow[];
    /** Convenience union for callers that want one flat list (e.g. the badge). */
    allPermissions: readonly string[];
    /** Worst-case level across both lists — drives the focus-card chip palette. */
    worstLevel: PermissionRowLevel;
}

export function computePermissionsBundleData(
    payload: PermissionsPayload | null | undefined,
): PermissionsBundleData {
    if (!payload) {
        return {
            grantRows: [],
            queriedRows: [],
            allPermissions: [],
            worstLevel: "info",
        };
    }
    const grants = normaliseGrants(payload.grants);
    const queried = normaliseQueried(payload.queried);
    const queriedSet = new Set(queried);

    const grantRows: PermissionRow[] = Object.entries(grants).map(
        ([perm, g]) => ({
            id: `permissions-grant-${perm}`,
            permission: perm,
            shortLabel: shortLabelFor(perm),
            grant: g,
            queried: queriedSet.has(perm),
            level: g === "granted" ? "info" : "warning",
        }),
    );
    // Keep insertion order stable across renders so the panel doesn't jitter on a re-fetch
    // that returns the same map in a different order. Object.entries on a record preserves
    // insertion order; sort only when grants is itself unordered (Map iteration semantics).
    grantRows.sort((a, b) => a.permission.localeCompare(b.permission));

    const queriedRows: PermissionRow[] = queried.map((perm) => ({
        id: `permissions-queried-${perm}`,
        permission: perm,
        shortLabel: shortLabelFor(perm),
        grant: grants[perm] ?? null,
        queried: true,
        level: grants[perm]
            ? grants[perm] === "granted"
                ? "info"
                : "warning"
            : "unknown",
    }));

    const allPermissions = Array.from(
        new Set([
            ...grantRows.map((r) => r.permission),
            ...queriedRows.map((r) => r.permission),
        ]),
    );

    const worstLevel: PermissionRowLevel = pickWorstLevel(
        grantRows,
        queriedRows,
    );
    return { grantRows, queriedRows, allPermissions, worstLevel };
}

export function permissionsGrantTableColumns(): readonly DataTableColumn<PermissionRow>[] {
    return [
        {
            header: "Permission",
            cellClass: "permissions-name-cell",
            render: (row) => html`<code>${row.shortLabel}</code>`,
        },
        {
            header: "Grant",
            cellClass: "permissions-grant-cell",
            render: (row) => row.grant ?? "—",
        },
        {
            header: "Queried?",
            cellClass: "permissions-queried-cell",
            render: (row) => (row.queried ? "yes" : "no"),
        },
    ];
}

export function permissionsQueriedTableColumns(): readonly DataTableColumn<PermissionRow>[] {
    return [
        {
            header: "Permission",
            cellClass: "permissions-name-cell",
            render: (row) => html`<code>${row.shortLabel}</code>`,
        },
        {
            header: "Effective grant",
            cellClass: "permissions-grant-cell",
            render: (row) => row.grant ?? "unknown",
        },
    ];
}

function shortLabelFor(permission: string): string {
    const prefix = "android.permission.";
    return permission.startsWith(prefix)
        ? permission.substring(prefix.length)
        : permission;
}

function normaliseGrants(
    grants: Record<string, PermissionGrantWire> | null | undefined,
): Record<string, PermissionGrantWire> {
    if (!grants || typeof grants !== "object") return {};
    const out: Record<string, PermissionGrantWire> = {};
    for (const [k, v] of Object.entries(grants)) {
        if (v === "granted" || v === "denied") out[k] = v;
    }
    return out;
}

function normaliseQueried(
    queried: readonly string[] | null | undefined,
): string[] {
    if (!Array.isArray(queried)) return [];
    return queried.filter(
        (q): q is string => typeof q === "string" && q.length > 0,
    );
}

function pickWorstLevel(
    grantRows: readonly PermissionRow[],
    queriedRows: readonly PermissionRow[],
): PermissionRowLevel {
    let worst: PermissionRowLevel = "info";
    for (const r of [...grantRows, ...queriedRows]) {
        if (r.level === "unknown") return "unknown";
        if (r.level === "warning") worst = "warning";
    }
    return worst;
}
