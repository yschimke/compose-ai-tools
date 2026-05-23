// Remote Compose bundle presenter — fills the "Remote Compose" tab body
// from the `compose/remotecompose` payload (see RemoteComposeModels.kt):
// `{ namedValues: Map<String, RemoteNamedValue>, hostActions:
// List<RemoteHostAction>, profile: RemoteComposeProfile? }`.
//
// One mixed-section table:
//
//   * a Profile row (the daemon-requested `RcPlatformProfiles` mirror) so
//     the user can see which platform target the document is compiled
//     against and switch it from a `<select>` cell;
//   * a Named values group — one row per (name, type, value), with the
//     value column editable in-place via a type-shaped `<input>`. The
//     cell's `change` event bubbles a `remote-compose-value-changed`
//     CustomEvent that the host (`main.ts`) catches and turns into a
//     `setRemoteComposeNamedValue` post-message back to the extension.
//   * a Host actions group — read-only audit log of `HostAction` events
//     the remote runtime fired during the captured frame (capped at the
//     `RemoteComposePayload.HOST_ACTION_BUFFER_SIZE` ring on the daemon
//     side; this side just renders what arrived).

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";

/** Wire-shape mirror of `RemoteNamedValue` (see Messages.kt). */
export type RemoteNamedValue =
    | { kind: "float"; value: number }
    | { kind: "dp"; value: number }
    | { kind: "int"; value: number }
    | { kind: "string"; value: string }
    | { kind: "bool"; value: boolean }
    | { kind: "color"; argb: string };

/** Wire-shape mirror of `RemoteHostAction`. */
export interface RemoteHostAction {
    payload: string;
    handlerId: number;
    firedAtMillis?: number;
}

/** Wire-shape mirror of `RemoteComposeProfile` (see Messages.kt). */
export type RemoteComposeProfile =
    | "androidx"
    | "androidx7"
    | "androidx8"
    | "androidx9"
    | "widgetsV6"
    | "widgetsV7"
    | "wearWidgets";

export interface RemoteComposePayload {
    namedValues?: Record<string, RemoteNamedValue> | null;
    hostActions?: readonly RemoteHostAction[] | null;
    profile?: RemoteComposeProfile | null;
}

type Section = "profile" | "named" | "actions";

interface BaseRow {
    id: string;
    section: Section;
}

export interface ProfileRow extends BaseRow {
    section: "profile";
    profile: RemoteComposeProfile | null;
}

export interface NamedValueRow extends BaseRow {
    section: "named";
    name: string;
    value: RemoteNamedValue;
}

export interface HostActionRow extends BaseRow {
    section: "actions";
    payload: string;
    handlerId: number;
    firedAtMillis: number | null;
    index: number;
}

export type RemoteComposeRow = ProfileRow | NamedValueRow | HostActionRow;

export interface RemoteComposeBundleData {
    rows: readonly RemoteComposeRow[];
    /** Counts for the table summary line. */
    namedCount: number;
    actionCount: number;
    /** Active profile so callers can drive a header chip / badge. */
    profile: RemoteComposeProfile | null;
    /** Payload echoed back into the row data for the table's `Copy JSON`. */
    jsonPayload: unknown;
}

/**
 * Stable spelling order for the profile `<select>` cell. Matches the
 * `RcPlatformProfiles` constants the daemon's connector binds to —
 * keeping the order consistent with the source enum so the dropdown
 * looks the same across releases.
 */
const PROFILE_OPTIONS: readonly {
    value: RemoteComposeProfile;
    label: string;
}[] = [
    { value: "androidx", label: "ANDROIDX (latest)" },
    { value: "androidx7", label: "ANDROIDX7" },
    { value: "androidx8", label: "ANDROIDX8" },
    { value: "androidx9", label: "ANDROIDX9" },
    { value: "widgetsV6", label: "Widgets v6" },
    { value: "widgetsV7", label: "Widgets v7" },
    { value: "wearWidgets", label: "Wear widgets" },
];

export function computeRemoteComposeBundleData(
    payload: RemoteComposePayload | null | undefined,
): RemoteComposeBundleData {
    if (!payload) {
        return {
            rows: [],
            namedCount: 0,
            actionCount: 0,
            profile: null,
            jsonPayload: { rows: [] },
        };
    }

    const profile = payload.profile ?? null;
    const rows: RemoteComposeRow[] = [
        { id: "profile", section: "profile", profile },
    ];

    // Named values — preserved in insertion order so `data/fetch` →
    // panel keeps the natural ordering the user code bound (LinkedHashMap
    // semantics on the wire). `Object.entries` walks insertion order for
    // string keys per ES2020+.
    const named = payload.namedValues ?? {};
    const namedEntries = Object.entries(named);
    for (const [name, value] of namedEntries) {
        rows.push({
            id: "named:" + name,
            section: "named",
            name,
            value,
        });
    }

    // Host actions — newest first so the most-recent fire is visible
    // without scrolling. The wire shape is insertion-ordered (oldest
    // first); we reverse for display only — `data/fetch` consumers
    // still see the canonical order in the payload.
    const actions = payload.hostActions ?? [];
    for (let i = actions.length - 1; i >= 0; i--) {
        const a = actions[i]!;
        rows.push({
            id: "action:" + i,
            section: "actions",
            payload: a.payload,
            handlerId: a.handlerId,
            firedAtMillis:
                typeof a.firedAtMillis === "number" ? a.firedAtMillis : null,
            index: i,
        });
    }

    return {
        rows,
        namedCount: namedEntries.length,
        actionCount: actions.length,
        profile,
        jsonPayload: {
            profile,
            namedValues: named,
            hostActions: actions,
        },
    };
}

export function remoteComposeTableColumns(): readonly DataTableColumn<RemoteComposeRow>[] {
    return [
        {
            header: "Name",
            cellClass: "rc-name-cell",
            render: (row) => renderNameCell(row),
        },
        {
            header: "Type",
            cellClass: "rc-type-cell",
            render: (row) => renderTypeCell(row),
        },
        {
            header: "Value",
            cellClass: "rc-value-cell",
            render: (row) => renderValueCell(row),
        },
    ];
}

function renderNameCell(row: RemoteComposeRow): TemplateResult {
    switch (row.section) {
        case "profile":
            return html`<strong>Profile</strong>
                <div class="rc-row-sub">RcPlatformProfiles target</div>`;
        case "named":
            return html`<code class="rc-name">${row.name}</code>`;
        case "actions":
            return html`<span class="rc-action-label"
                >HostAction
                <span class="rc-action-index">#${row.index}</span></span
            >`;
    }
}

function renderTypeCell(row: RemoteComposeRow): TemplateResult | string {
    switch (row.section) {
        case "profile":
            return html`<span class="rc-type-badge rc-type-profile"
                >profile</span
            >`;
        case "named":
            return html`<span class="rc-type-badge rc-type-${row.value.kind}"
                >${row.value.kind}</span
            >`;
        case "actions":
            return html`<span class="rc-type-badge rc-type-action"
                >action</span
            >`;
    }
}

function renderValueCell(row: RemoteComposeRow): TemplateResult | string {
    switch (row.section) {
        case "profile":
            return html`
                <select
                    class="rc-value-input rc-profile-select"
                    data-rc-field="profile"
                    @change=${(e: Event) => {
                        const raw = (e.currentTarget as HTMLSelectElement)
                            .value;
                        emitChange(e, {
                            field: "profile",
                            value: (raw || null) as RemoteComposeProfile | null,
                        });
                    }}
                >
                    ${PROFILE_OPTIONS.map(
                        (opt) =>
                            html`<option
                                value=${opt.value}
                                ?selected=${row.profile === opt.value}
                            >
                                ${opt.label}
                            </option>`,
                    )}
                </select>
            `;
        case "named":
            return renderNamedValueInput(row);
        case "actions":
            return html`<div class="rc-action-cell">
                <code class="rc-action-payload">${row.payload}</code>
                <span class="rc-action-meta"
                    >handler=${row.handlerId.toFixed(0)}${row.firedAtMillis !==
                    null
                        ? html` · @${formatMillis(row.firedAtMillis)}`
                        : ""}</span
                >
            </div>`;
    }
}

function renderNamedValueInput(row: NamedValueRow): TemplateResult {
    const v = row.value;
    switch (v.kind) {
        case "bool":
            return html`<label class="rc-bool-cell">
                <input
                    type="checkbox"
                    class="rc-value-input rc-bool-input"
                    data-rc-field="namedValue"
                    data-rc-name=${row.name}
                    data-rc-kind="bool"
                    ?checked=${v.value}
                    @change=${(e: Event) =>
                        emitChange(e, {
                            field: "namedValue",
                            name: row.name,
                            value: {
                                kind: "bool",
                                value: (e.currentTarget as HTMLInputElement)
                                    .checked,
                            },
                        })}
                />
                <span>${v.value ? "true" : "false"}</span>
            </label>`;
        case "int":
            return html`<input
                type="number"
                step="1"
                class="rc-value-input rc-number-input"
                data-rc-field="namedValue"
                data-rc-name=${row.name}
                data-rc-kind="int"
                .value=${String(v.value)}
                @change=${(e: Event) => {
                    const n = parseInt(
                        (e.currentTarget as HTMLInputElement).value,
                        10,
                    );
                    if (!Number.isFinite(n)) return;
                    emitChange(e, {
                        field: "namedValue",
                        name: row.name,
                        value: { kind: "int", value: n },
                    });
                }}
            />`;
        case "float":
        case "dp": {
            const suffix = v.kind === "dp" ? "dp" : "";
            return html`<span class="rc-number-cell">
                <input
                    type="number"
                    step="0.01"
                    class="rc-value-input rc-number-input"
                    data-rc-field="namedValue"
                    data-rc-name=${row.name}
                    data-rc-kind=${v.kind}
                    .value=${String(v.value)}
                    @change=${(e: Event) => {
                        const n = parseFloat(
                            (e.currentTarget as HTMLInputElement).value,
                        );
                        if (!Number.isFinite(n)) return;
                        emitChange(e, {
                            field: "namedValue",
                            name: row.name,
                            value: { kind: v.kind, value: n },
                        });
                    }}
                />${suffix
                    ? html`<span class="rc-unit">${suffix}</span>`
                    : ""}</span
            >`;
        }
        case "string":
            return html`<input
                type="text"
                class="rc-value-input rc-text-input"
                data-rc-field="namedValue"
                data-rc-name=${row.name}
                data-rc-kind="string"
                .value=${v.value}
                @change=${(e: Event) =>
                    emitChange(e, {
                        field: "namedValue",
                        name: row.name,
                        value: {
                            kind: "string",
                            value: (e.currentTarget as HTMLInputElement).value,
                        },
                    })}
            />`;
        case "color": {
            // `<input type="color">` requires `#RRGGBB`; the wire shape
            // carries `#AARRGGBB`. Strip the alpha byte for the picker
            // and re-attach it on emit so we round-trip cleanly.
            const argb = v.argb.startsWith("#") ? v.argb : "#" + v.argb;
            const alpha = argb.length === 9 ? argb.slice(1, 3) : "FF";
            const rgb = argb.length === 9 ? "#" + argb.slice(3) : argb;
            return html`<span class="rc-color-cell">
                <input
                    type="color"
                    class="rc-value-input rc-color-input"
                    data-rc-field="namedValue"
                    data-rc-name=${row.name}
                    data-rc-kind="color"
                    .value=${rgb}
                    @change=${(e: Event) => {
                        const picked = (e.currentTarget as HTMLInputElement)
                            .value;
                        const merged = (
                            "#" +
                            alpha +
                            picked.slice(1)
                        ).toUpperCase();
                        emitChange(e, {
                            field: "namedValue",
                            name: row.name,
                            value: { kind: "color", argb: merged },
                        });
                    }}
                />
                <code class="rc-color-hex">${argb.toUpperCase()}</code>
            </span>`;
        }
    }
}

/** Detail payload of the `remote-compose-value-changed` CustomEvent. */
export type RemoteComposeChangeDetail =
    | { field: "profile"; value: RemoteComposeProfile | null }
    | { field: "namedValue"; name: string; value: RemoteNamedValue };

function emitChange(e: Event, detail: RemoteComposeChangeDetail): void {
    const evt = new CustomEvent<RemoteComposeChangeDetail>(
        "remote-compose-value-changed",
        { detail, bubbles: true, composed: true },
    );
    (e.currentTarget as HTMLElement).dispatchEvent(evt);
}

function formatMillis(ms: number): string {
    // Compact wall-clock label so the audit log stays readable in the
    // narrow Value column. Full timestamp is in the Copy JSON dump.
    const d = new Date(ms);
    const hh = String(d.getHours()).padStart(2, "0");
    const mm = String(d.getMinutes()).padStart(2, "0");
    const ss = String(d.getSeconds()).padStart(2, "0");
    return `${hh}:${mm}:${ss}`;
}
