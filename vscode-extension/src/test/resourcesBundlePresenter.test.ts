// Resources bundle presenter — pins the wire-payload → row mapping
// for the `<data-table>`-based Resources tab. Defensive parse so a
// malformed daemon payload doesn't blank the panel.

import * as assert from "assert";
import { computeResourcesBundleData } from "../webview/preview/resourcesBundlePresenter";

describe("computeResourcesBundleData", () => {
    it("emits no rows for a null payload", () => {
        const data = computeResourcesBundleData(null);
        assert.strictEqual(data.rows.length, 0);
    });

    it("emits no rows for a payload missing references", () => {
        const data = computeResourcesBundleData({ something: "else" });
        assert.strictEqual(data.rows.length, 0);
    });

    it("emits one row per well-formed reference", () => {
        const data = computeResourcesBundleData({
            references: [
                {
                    resourceType: "drawable",
                    resourceName: "ic_launcher",
                    packageName: "com.example",
                    resolvedFile: "/abs/path/ic_launcher.xml",
                    consumers: [{ nodeId: "n1" }, { nodeId: "n2" }],
                },
                {
                    resourceType: "string",
                    resourceName: "app_name",
                    packageName: "com.example",
                    resolvedValue: "Demo",
                    consumers: [{ nodeId: "n3" }],
                },
            ],
        });
        assert.strictEqual(data.rows.length, 2);
        assert.strictEqual(data.rows[0].resourceType, "drawable");
        assert.strictEqual(data.rows[0].resourceName, "ic_launcher");
        assert.strictEqual(
            data.rows[0].resolvedFile,
            "/abs/path/ic_launcher.xml",
        );
        assert.strictEqual(data.rows[0].resolvedValue, null);
        assert.strictEqual(data.rows[0].consumerCount, 2);
        assert.strictEqual(data.rows[1].resolvedValue, "Demo");
        assert.strictEqual(data.rows[1].resolvedFile, null);
        assert.strictEqual(data.rows[1].consumerCount, 1);
    });

    it("skips entries missing type or name", () => {
        const data = computeResourcesBundleData({
            references: [
                { resourceType: "drawable" },
                { resourceName: "missing_type" },
                {
                    resourceType: "color",
                    resourceName: "primary",
                    resolvedValue: "#FF0000",
                },
            ],
        });
        assert.strictEqual(data.rows.length, 1);
        assert.strictEqual(data.rows[0].resourceName, "primary");
    });

    it("defaults packageName / consumerCount when fields are absent", () => {
        const data = computeResourcesBundleData({
            references: [
                {
                    resourceType: "string",
                    resourceName: "no_meta",
                    resolvedValue: "x",
                },
            ],
        });
        assert.strictEqual(data.rows[0].packageName, "");
        assert.strictEqual(data.rows[0].consumerCount, 0);
        assert.deepStrictEqual([...data.rows[0].consumerNodeIds], []);
    });

    it("extracts unique consumer node ids in first-seen order", () => {
        const data = computeResourcesBundleData({
            references: [
                {
                    resourceType: "drawable",
                    resourceName: "ic_launcher",
                    consumers: [
                        { nodeId: "n3" },
                        { nodeId: "n1" },
                        { nodeId: "n3" },
                    ],
                },
            ],
        });
        // Deduped: n3 listed twice but emitted once.
        assert.deepStrictEqual([...data.rows[0].consumerNodeIds], ["n3", "n1"]);
        // consumerCount tracks unique ids (matches the hover overlay's
        // per-row box count).
        assert.strictEqual(data.rows[0].consumerCount, 2);
    });

    it("drops malformed consumer entries instead of crashing the panel", () => {
        const data = computeResourcesBundleData({
            references: [
                {
                    resourceType: "string",
                    resourceName: "app_name",
                    consumers: [
                        { nodeId: "valid" },
                        null,
                        { nodeId: "" },
                        { nodeId: 42 },
                        "string-not-object",
                        {},
                    ],
                },
            ],
        });
        assert.deepStrictEqual([...data.rows[0].consumerNodeIds], ["valid"]);
    });

    it("emits stable ids derived from index + type + name", () => {
        const data = computeResourcesBundleData({
            references: [
                {
                    resourceType: "string",
                    resourceName: "title",
                    resolvedValue: "Hi",
                },
                {
                    resourceType: "string",
                    resourceName: "title",
                    resolvedValue: "Bye",
                },
            ],
        });
        // Index disambiguates duplicate (type, name) pairs.
        assert.notStrictEqual(data.rows[0].id, data.rows[1].id);
        assert.ok(data.rows[0].id.startsWith("resource-0-string-title"));
        assert.ok(data.rows[1].id.startsWith("resource-1-string-title"));
    });
});
