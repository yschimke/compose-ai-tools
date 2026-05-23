// `<data-table>` Copy JSON contract — pins the wire shape every
// per-bundle Copy JSON callsite depends on. The component dispatches
// a `copy-json` CustomEvent carrying `{ payload }`; the host listens
// once on `<data-tabs>` and ships the payload over the wire. If the
// event shape or default payload drifts, every bundle's Copy JSON
// regresses silently — this test is the regression lock.

import * as assert from "assert";
import "../webview/preview/components/DataTable";
import type {
    CopyJsonDetail,
    DataTable,
    DataTableColumn,
} from "../webview/preview/components/DataTable";

interface SampleRow {
    id: string;
    label: string;
}

const COLUMNS: readonly DataTableColumn<SampleRow>[] = [
    { header: "Label", render: (row) => row.label },
];

function build(): DataTable<SampleRow> {
    const el = document.createElement("data-table") as DataTable<SampleRow>;
    el.setColumns(COLUMNS);
    document.body.appendChild(el);
    return el;
}

function clickCopy(el: DataTable<SampleRow>): CopyJsonDetail | null {
    let captured: CopyJsonDetail | null = null;
    el.addEventListener("copy-json", (evt) => {
        captured = (evt as CustomEvent<CopyJsonDetail>).detail;
    });
    const btn = el.querySelector<HTMLButtonElement>(".data-table-copy");
    assert.ok(btn, "data-table copy button missing");
    btn!.click();
    return captured;
}

describe("DataTable Copy JSON contract", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("defaults the copy payload to the rows array when no override is set", async () => {
        const el = build();
        el.setRows([
            { id: "a", label: "Alpha" },
            { id: "b", label: "Bravo" },
        ]);
        await el.updateComplete;
        const captured = clickCopy(el);
        assert.ok(captured, "copy-json event should have fired");
        assert.deepStrictEqual(captured!.payload, [
            { id: "a", label: "Alpha" },
            { id: "b", label: "Bravo" },
        ]);
    });

    it("dispatches the setJsonPayload-supplied shape over the rows default", async () => {
        const el = build();
        el.setRows([{ id: "a", label: "Alpha" }]);
        el.setJsonPayload(() => ({
            previewId: "preview-1",
            kind: "compose/theme",
            rows: [{ id: "a", label: "Alpha" }],
        }));
        await el.updateComplete;
        const captured = clickCopy(el);
        assert.deepStrictEqual(captured!.payload, {
            previewId: "preview-1",
            kind: "compose/theme",
            rows: [{ id: "a", label: "Alpha" }],
        });
    });

    it("re-reads the payload closure on each click so live refreshes are observed", async () => {
        const el = build();
        let snapshot = { revision: 1 };
        el.setJsonPayload(() => snapshot);
        await el.updateComplete;
        const first = clickCopy(el);
        assert.deepStrictEqual(first!.payload, { revision: 1 });
        snapshot = { revision: 2 };
        const second = clickCopy(el);
        assert.deepStrictEqual(second!.payload, { revision: 2 });
    });
});
