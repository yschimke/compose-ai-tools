// Unit tests for the inspection-bundle tree-table primitive. Verifies
// row expand/collapse, hover-correlation hooks (data-legend-id /
// data-has-overlay), the Copy JSON button, and the per-row action.

import * as assert from "assert";
import {
    buildInspectionTreeTable,
    stringify,
    type TreeColumn,
    type TreeTableNode,
} from "../webview/preview/inspectionTreeTable";
import { flushMicrotasks, stubClipboard } from "./helpers/clipboard";

interface Row {
    name: string;
    bounds?: string;
    children?: Row[];
}

const COLS: TreeColumn<Row>[] = [
    { id: "name", label: "Name", render: (r) => r.name },
    { id: "bounds", label: "Bounds", render: (r) => r.bounds ?? "—" },
];

describe("inspectionTreeTable", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renders a flat tree with one row per node", () => {
        const rows: TreeTableNode<Row>[] = [
            { id: "a", data: { name: "A" } },
            { id: "b", data: { name: "B" } },
        ];
        const el = buildInspectionTreeTable({
            title: "Test",
            columns: COLS,
            rows,
        });
        document.body.appendChild(el);
        const trs = el.querySelectorAll("tbody tr");
        assert.strictEqual(trs.length, 2);
        assert.strictEqual(trs[0].getAttribute("data-legend-id"), "a");
        assert.strictEqual(trs[1].getAttribute("data-legend-id"), "b");
    });

    it("recurses children and indents by depth", () => {
        const rows: TreeTableNode<Row>[] = [
            {
                id: "a",
                data: { name: "A" },
                children: [{ id: "a-1", data: { name: "A1" } }],
            },
        ];
        const el = buildInspectionTreeTable({
            title: "Test",
            columns: COLS,
            rows,
        });
        const trs = el.querySelectorAll("tbody tr");
        assert.strictEqual(trs.length, 2);
        assert.strictEqual(trs[0].getAttribute("data-depth"), "0");
        assert.strictEqual(trs[1].getAttribute("data-depth"), "1");
    });

    it("collapses children when the expander is clicked", () => {
        const rows: TreeTableNode<Row>[] = [
            {
                id: "a",
                data: { name: "A" },
                children: [{ id: "a-1", data: { name: "A1" } }],
            },
        ];
        const el = buildInspectionTreeTable({
            title: "Test",
            columns: COLS,
            rows,
        });
        document.body.appendChild(el);
        const childRow = el.querySelector<HTMLElement>(
            "tbody tr[data-legend-id='a-1']",
        )!;
        assert.strictEqual(childRow.hidden, false);
        const expander = el.querySelector<HTMLButtonElement>(
            ".inspection-tree-expand",
        )!;
        expander.click();
        assert.strictEqual(childRow.hidden, true);
        assert.strictEqual(expander.getAttribute("aria-expanded"), "false");
        expander.click();
        assert.strictEqual(childRow.hidden, false);
        assert.strictEqual(expander.getAttribute("aria-expanded"), "true");
    });

    it("respects defaultExpandDepth=0 (root rows visible, children hidden)", () => {
        const rows: TreeTableNode<Row>[] = [
            {
                id: "a",
                data: { name: "A" },
                children: [{ id: "a-1", data: { name: "A1" } }],
            },
        ];
        const el = buildInspectionTreeTable({
            title: "Test",
            columns: COLS,
            rows,
            defaultExpandDepth: 0,
        });
        const childRow = el.querySelector<HTMLElement>(
            "tbody tr[data-legend-id='a-1']",
        )!;
        assert.strictEqual(childRow.hidden, true);
    });

    it("stamps data-has-overlay when hasOverlayFor returns true", () => {
        const rows: TreeTableNode<Row>[] = [
            { id: "a", data: { name: "A", bounds: "0,0,10,10" } },
            { id: "b", data: { name: "B" } },
        ];
        const el = buildInspectionTreeTable({
            title: "Test",
            columns: COLS,
            rows,
            hasOverlayFor: (r) => !!r.bounds,
        });
        const a = el.querySelector("tbody tr[data-legend-id='a']")!;
        const b = el.querySelector("tbody tr[data-legend-id='b']")!;
        assert.strictEqual(a.getAttribute("data-has-overlay"), "true");
        assert.strictEqual(b.getAttribute("data-has-overlay"), null);
    });

    it("renders Copy JSON button that copies stringify(jsonForCopy())", async () => {
        const payload = { foo: { bar: [1, 2, 3] } };
        const captured = stubClipboard();
        try {
            const el = buildInspectionTreeTable({
                title: "Test",
                columns: COLS,
                rows: [{ id: "a", data: { name: "A" } }],
                jsonForCopy: () => payload,
            });
            document.body.appendChild(el);
            const copyBtn = el.querySelector<HTMLButtonElement>(
                ".inspection-tree-copy-json",
            )!;
            copyBtn.click();
            await flushMicrotasks();
            assert.strictEqual(captured.text, stringify(payload));
        } finally {
            captured.restore();
        }
    });

    it("fires the row action with the row's data when clicked", () => {
        let actionRow: Row | null = null;
        const rows: TreeTableNode<Row>[] = [{ id: "a", data: { name: "A" } }];
        const el = buildInspectionTreeTable({
            title: "Test",
            columns: COLS,
            rows,
            rowAction: {
                icon: "copy",
                label: "Copy",
                onClick: (r) => {
                    actionRow = r;
                },
            },
        });
        const btn = el.querySelector<HTMLButtonElement>(
            ".inspection-tree-action-btn",
        )!;
        btn.click();
        assert.deepStrictEqual(actionRow, { name: "A" });
    });
});
