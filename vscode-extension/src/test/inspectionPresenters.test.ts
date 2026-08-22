// Unit tests for the inspection-bundle compute path that feeds
// `paintBundleBoxes(card, "inspection", data.overlay)` in `main.ts`.

import * as assert from "assert";
import {
    computeInspectionBundleData,
    type InspectionKind,
} from "../webview/preview/inspectionPresenters";
import { flushMicrotasks, stubClipboard } from "./helpers/clipboard";

describe("computeInspectionBundleData (cardBundleOverlay path)", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("emits one OverlayBox per semantics node with parsed bounds", () => {
        const payload = {
            root: {
                nodeId: "1",
                boundsInRoot: "0,0,100,100",
                label: "Sample",
                role: "Button",
                testTag: "submit",
                children: [
                    {
                        nodeId: "2",
                        boundsInRoot: "10,10,40,40",
                        label: "Inner",
                    },
                ],
            },
        };
        const data = computeInspectionBundleData(
            (kind) => (kind === "compose/semantics" ? payload : undefined),
            new Set<InspectionKind>(["compose/semantics"]),
        );
        assert.strictEqual(data.overlay.length, 2);
        assert.strictEqual(data.overlay[0].id, "semantics-1");
        assert.deepStrictEqual(data.overlay[0].bounds, {
            left: 0,
            top: 0,
            right: 100,
            bottom: 100,
        });
        assert.strictEqual(data.overlay[0].level, "info");
        // Tooltip aggregates label · role · testTag.
        assert.strictEqual(data.overlay[0].tooltip, "Sample · Button · submit");
        // Section body is the tree-table; one section per enabled kind
        // with a payload.
        assert.strictEqual(data.sections.length, 1);
        assert.strictEqual(data.sections[0].kind, "compose/semantics");
    });

    it("skips overlay entries for malformed boundsInScreen but still emits the row", () => {
        const payload = {
            nodes: [
                {
                    text: "GoodNode",
                    testTag: "good",
                    boundsInScreen: "0,0,50,50",
                },
                {
                    text: "BadNode",
                    testTag: "bad",
                    boundsInScreen: "not,a,real,bounds",
                },
                {
                    text: "MissingNode",
                    testTag: "missing",
                    boundsInScreen: "",
                },
            ],
        };
        const data = computeInspectionBundleData(
            (kind) => (kind === "uia/hierarchy" ? payload : undefined),
            new Set<InspectionKind>(["uia/hierarchy"]),
        );
        // Only the well-formed node lands in the overlay.
        assert.strictEqual(data.overlay.length, 1);
        assert.strictEqual(data.overlay[0].id, "uia-0");
        // All three nodes still surface in the tree-table body so the
        // user can read them — overlay-skipping is silent at the row
        // level.
        const rows = data.sections[0].data.body.querySelectorAll(
            "tbody tr[data-legend-id]",
        );
        assert.strictEqual(rows.length, 3);
    });

    it("dedupes the merged overlay by id when two kinds share an id", () => {
        // Construct two kinds whose first node ids collide under the
        // kind-namespacing scheme — semantics + layout each have id
        // "shared", which becomes "semantics-shared" / "layout-shared"
        // respectively. To exercise the dedupe rule we feed the second
        // kind a payload whose first node carries the SAME final id by
        // matching the namespaced prefix.
        const semanticsPayload = {
            root: {
                nodeId: "shared",
                boundsInRoot: "0,0,10,10",
                label: "A",
            },
        };
        const layoutPayload = {
            // The layout node id will be namespaced to "layout-shared"
            // — to collide with "semantics-shared" we'd need a daemon
            // that emits matching cross-kind ids. Easier path: build
            // two kinds whose namespaced ids overlap naturally.
            root: {
                nodeId: "shared",
                component: "Column",
                bounds: { left: 0, top: 0, right: 20, bottom: 20 },
            },
        };
        // Sanity: distinct prefixes → no natural collision, total 2.
        const distinct = computeInspectionBundleData(
            (kind) => {
                if (kind === "compose/semantics") return semanticsPayload;
                if (kind === "layout/inspector") return layoutPayload;
                return undefined;
            },
            new Set<InspectionKind>(["compose/semantics", "layout/inspector"]),
        );
        assert.strictEqual(distinct.overlay.length, 2);
        const ids = distinct.overlay.map((b) => b.id);
        assert.deepStrictEqual(ids, ["semantics-shared", "layout-shared"]);

        // Force a collision: emit two semantics nodes with the same
        // `nodeId` — the daemon shouldn't, but the dedupe rule must
        // still hold so a buggy payload doesn't stack two boxes.
        const collidingPayload = {
            root: {
                nodeId: "dup",
                boundsInRoot: "0,0,10,10",
                label: "Outer",
                children: [
                    {
                        nodeId: "dup",
                        boundsInRoot: "1,1,9,9",
                        label: "Inner",
                    },
                ],
            },
        };
        const collided = computeInspectionBundleData(
            (kind) =>
                kind === "compose/semantics" ? collidingPayload : undefined,
            new Set<InspectionKind>(["compose/semantics"]),
        );
        assert.strictEqual(collided.overlay.length, 1);
        assert.strictEqual(collided.overlay[0].id, "semantics-dup");
        // First-seen wins.
        assert.deepStrictEqual(collided.overlay[0].bounds, {
            left: 0,
            top: 0,
            right: 10,
            bottom: 10,
        });
    });

    it("flags compose/semantics mergeDescendants nodes with the warning palette", () => {
        // mergeDescendants → child semantics get absorbed into the
        // parent, so the parent's overlay box represents merged
        // information. `clearAndSet` and the default (null) stay on
        // info — only the mergeDescendants case warrants the warning
        // accent.
        const payload = {
            root: {
                nodeId: "1",
                boundsInRoot: "0,0,100,100",
                mergeMode: "mergeDescendants",
                children: [
                    {
                        nodeId: "2",
                        boundsInRoot: "10,10,40,40",
                        mergeMode: "clearAndSet",
                    },
                    {
                        nodeId: "3",
                        boundsInRoot: "50,10,90,40",
                    },
                ],
            },
        };
        const data = computeInspectionBundleData(
            (kind) => (kind === "compose/semantics" ? payload : undefined),
            new Set<InspectionKind>(["compose/semantics"]),
        );
        const byId = new Map(data.overlay.map((b) => [b.id, b]));
        assert.strictEqual(byId.get("semantics-1")?.level, "warning");
        assert.strictEqual(byId.get("semantics-2")?.level, "info");
        assert.strictEqual(byId.get("semantics-3")?.level, "info");
    });

    it("draws no overlay box for an unplaced compose/semantics subtree", () => {
        // Measured, never placed: it has no position, so `boundsInRoot`
        // reads as the origin and a box for it lands in the frame's
        // top-left corner. The tree ROW stays — seeing the trial copy is
        // how the duplicate makes sense — but the box does not.
        const payload = {
            root: {
                nodeId: "1",
                boundsInRoot: "0,0,100,100",
                children: [
                    { nodeId: "2", boundsInRoot: "10,10,40,40" },
                    {
                        nodeId: "3",
                        boundsInRoot: "0,0,40,40",
                        placed: false,
                        children: [{ nodeId: "4", boundsInRoot: "0,0,20,20" }],
                    },
                ],
            },
        };
        const data = computeInspectionBundleData(
            (kind) => (kind === "compose/semantics" ? payload : undefined),
            new Set<InspectionKind>(["compose/semantics"]),
        );
        const ids = new Set(data.overlay.map((b) => b.id));
        assert.strictEqual(ids.has("semantics-2"), true);
        assert.strictEqual(ids.has("semantics-3"), false);
        assert.strictEqual(ids.has("semantics-4"), false, "subtree too");
    });

    it("ignores kinds the user has not enabled", () => {
        const payload = {
            root: {
                nodeId: "1",
                boundsInRoot: "0,0,10,10",
                label: "Hidden",
            },
        };
        const data = computeInspectionBundleData(
            () => payload,
            // Empty enabled-kinds set — every kind should be skipped.
            new Set<InspectionKind>(),
        );
        assert.strictEqual(data.overlay.length, 0);
        assert.strictEqual(data.sections.length, 0);
    });

    it("wires a 'Copy as selector' row action on uia/hierarchy rows", async () => {
        const payload = {
            nodes: [
                {
                    text: "Submit",
                    testTag: "submit",
                    boundsInScreen: "0,0,100,40",
                    actions: ["click"],
                },
                {
                    text: "Cancel",
                    testTag: "cancel",
                    boundsInScreen: "0,40,100,80",
                    actions: ["click"],
                },
            ],
        };
        const captured = stubClipboard();
        try {
            const data = computeInspectionBundleData(
                (kind) => (kind === "uia/hierarchy" ? payload : undefined),
                new Set<InspectionKind>(["uia/hierarchy"]),
            );
            const rows = data.sections[0].data.body.querySelectorAll(
                "tbody tr[data-legend-id]",
            );
            assert.strictEqual(rows.length, 2);
            const actBtn = rows[0].querySelector<HTMLButtonElement>(
                ".inspection-tree-action-btn",
            );
            assert.ok(actBtn, "row action button missing");
            actBtn!.click();
            await flushMicrotasks();
            assert.strictEqual(captured.text, 'By.testTag("submit")');
        } finally {
            captured.restore();
        }
    });
});

// Override-toggle UI in the `compose/permissions` section. The presenter renders
// per-row Grant / Deny / Clear buttons, an "Add permission" form, and a "Clear
// overrides" action; each dispatches a bubbled `permissions-override-change`
// CustomEvent ({@link PermissionsChangeDetail}). `main.ts` catches the event at
// the inspection bundle wrapper and forwards it as the host's
// `setPermissionsOverride` message; here we just pin the DOM-level contract.
describe("computeInspectionBundleData compose/permissions override controls", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    type CapturedChange =
        | { field: "setGrant"; permission: string; grant: "granted" | "denied" }
        | { field: "clearGrant"; permission: string }
        | { field: "clearAll" };

    function mountSection(
        payload: {
            grants?: Record<string, "granted" | "denied">;
            queried?: string[];
        } | null,
    ): { body: HTMLElement; events: CapturedChange[] } {
        const data = computeInspectionBundleData(
            (kind) => (kind === "compose/permissions" ? payload : undefined),
            new Set<InspectionKind>(["compose/permissions"]),
        );
        assert.strictEqual(data.sections.length, 1);
        assert.strictEqual(data.sections[0].kind, "compose/permissions");
        // Mount the section into a wrapper that captures the bubbled events,
        // mirroring the listener `main.ts` installs on the inspection bundle
        // body wrapper.
        const wrapper = document.createElement("div");
        wrapper.appendChild(data.sections[0].data.body);
        document.body.appendChild(wrapper);
        const events: CapturedChange[] = [];
        wrapper.addEventListener("permissions-override-change", (evt) => {
            events.push((evt as CustomEvent<CapturedChange>).detail);
        });
        return { body: data.sections[0].data.body, events };
    }

    it("emits a setGrant=granted change when a queried-row Grant button is clicked", () => {
        const { body, events } = mountSection({
            grants: {},
            queried: ["android.permission.CAMERA"],
        });
        const queriedTable = body.querySelector<HTMLElement>(
            '[data-permission-table="queried"]',
        );
        assert.ok(queriedTable, "queried table missing");
        const grantBtn = queriedTable!.querySelector<HTMLButtonElement>(
            'button[data-permission-action="grant"][data-permission-name="android.permission.CAMERA"]',
        );
        assert.ok(grantBtn, "queried-row Grant button missing");
        grantBtn!.click();
        assert.deepStrictEqual(events, [
            {
                field: "setGrant",
                permission: "android.permission.CAMERA",
                grant: "granted",
            },
        ]);
    });

    it("emits a clearGrant change when a grant-table Clear button is clicked", () => {
        const { body, events } = mountSection({
            grants: { "android.permission.CAMERA": "granted" },
            queried: [],
        });
        const grantsTable = body.querySelector<HTMLElement>(
            '[data-permission-table="grants"]',
        );
        assert.ok(grantsTable, "grants table missing");
        const clearBtn = grantsTable!.querySelector<HTMLButtonElement>(
            'button[data-permission-action="clear"][data-permission-name="android.permission.CAMERA"]',
        );
        assert.ok(clearBtn, "grant-row Clear button missing");
        clearBtn!.click();
        assert.deepStrictEqual(events, [
            {
                field: "clearGrant",
                permission: "android.permission.CAMERA",
            },
        ]);
    });

    it("marks the currently-applied grant button as aria-pressed", () => {
        const { body } = mountSection({
            grants: { "android.permission.CAMERA": "denied" },
            queried: [],
        });
        const grantsTable = body.querySelector<HTMLElement>(
            '[data-permission-table="grants"]',
        );
        const grantBtn = grantsTable!.querySelector<HTMLButtonElement>(
            'button[data-permission-action="grant"][data-permission-name="android.permission.CAMERA"]',
        );
        const denyBtn = grantsTable!.querySelector<HTMLButtonElement>(
            'button[data-permission-action="deny"][data-permission-name="android.permission.CAMERA"]',
        );
        assert.strictEqual(grantBtn?.getAttribute("aria-pressed"), null);
        assert.strictEqual(denyBtn?.getAttribute("aria-pressed"), "true");
    });

    it("emits a clearAll change when the section's Clear overrides button is clicked", () => {
        const { body, events } = mountSection({
            grants: { "android.permission.CAMERA": "granted" },
            queried: [],
        });
        const clearAllBtn = body.querySelector<HTMLButtonElement>(
            'button[data-permission-action="clear-all"]',
        );
        assert.ok(clearAllBtn, "Clear overrides button missing");
        clearAllBtn!.click();
        assert.deepStrictEqual(events, [{ field: "clearAll" }]);
    });

    it("normalises bare short names from the add form and emits a fully-qualified setGrant", () => {
        const { body, events } = mountSection({ grants: {}, queried: [] });
        const form = body.querySelector<HTMLFormElement>(
            '[data-permission-form="add"]',
        );
        assert.ok(form, "Add permission form missing");
        const input = form!.querySelector<HTMLInputElement>(
            ".inspection-permissions-add-input",
        );
        assert.ok(input, "Add permission input missing");
        input!.value = "camera";
        const grantBtn = form!.querySelector<HTMLButtonElement>(
            'button[data-permission-action="add-grant"]',
        );
        grantBtn!.click();
        form!.dispatchEvent(
            new Event("submit", { bubbles: true, cancelable: true }),
        );
        assert.deepStrictEqual(events, [
            {
                field: "setGrant",
                permission: "android.permission.CAMERA",
                grant: "granted",
            },
        ]);
        assert.strictEqual(
            input!.value,
            "",
            "input should be cleared after submission",
        );
    });

    it("passes through an already-qualified permission name from the add form unchanged", () => {
        const { body, events } = mountSection({ grants: {}, queried: [] });
        const form = body.querySelector<HTMLFormElement>(
            '[data-permission-form="add"]',
        );
        const input = form!.querySelector<HTMLInputElement>(
            ".inspection-permissions-add-input",
        );
        input!.value = "android.permission.FOREGROUND_SERVICE_LOCATION";
        const denyBtn = form!.querySelector<HTMLButtonElement>(
            'button[data-permission-action="add-deny"]',
        );
        denyBtn!.click();
        form!.dispatchEvent(
            new Event("submit", { bubbles: true, cancelable: true }),
        );
        assert.deepStrictEqual(events, [
            {
                field: "setGrant",
                permission: "android.permission.FOREGROUND_SERVICE_LOCATION",
                grant: "denied",
            },
        ]);
    });

    it("renders the add form and clear-all action even when the payload has no rows", () => {
        const { body } = mountSection({ grants: {}, queried: [] });
        assert.ok(
            body.querySelector('[data-permission-form="add"]'),
            "Add form should render with an empty payload so the user can pin a permission before the screen queries one",
        );
        assert.ok(
            body.querySelector('button[data-permission-action="clear-all"]'),
            "Clear overrides should render with an empty payload",
        );
        assert.strictEqual(
            body.querySelector('[data-permission-table="grants"]'),
            null,
            "Grants table should be omitted when there are no rows",
        );
        assert.strictEqual(
            body.querySelector('[data-permission-table="queried"]'),
            null,
            "Queried table should be omitted when there are no rows",
        );
    });
});
