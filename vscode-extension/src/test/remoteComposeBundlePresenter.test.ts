// Remote Compose bundle presenter — pins the payload → rows shape so
// the editable tab body's table doesn't silently drift when wire types
// change. Stateless; tests run the presenter directly without any DOM
// (the `<input>` cell renderers are exercised separately by the panel
// preview-harness snapshot — those would need a real Lit render to
// assert against).

import * as assert from "assert";
import {
    computeRemoteComposeBundleData,
    type RemoteComposePayload,
} from "../webview/preview/remoteComposeBundlePresenter";

describe("computeRemoteComposeBundleData", () => {
    it("returns empty rows + null profile for a null payload", () => {
        const data = computeRemoteComposeBundleData(null);
        assert.strictEqual(data.rows.length, 0);
        assert.strictEqual(data.namedCount, 0);
        assert.strictEqual(data.actionCount, 0);
        assert.strictEqual(data.profile, null);
    });

    it("emits a profile row even when no named values are bound", () => {
        const payload: RemoteComposePayload = { profile: "androidx" };
        const data = computeRemoteComposeBundleData(payload);
        // Single profile row, no value rows, no action rows.
        assert.strictEqual(data.rows.length, 1);
        assert.strictEqual(data.rows[0]?.section, "profile");
        assert.strictEqual(data.profile, "androidx");
        assert.strictEqual(data.namedCount, 0);
        assert.strictEqual(data.actionCount, 0);
    });

    it("preserves insertion order of named values", () => {
        const payload: RemoteComposePayload = {
            profile: "androidx9",
            namedValues: {
                seedColor: { kind: "color", argb: "#FF3366FF" },
                cornerRadius: { kind: "dp", value: 8 },
                enabled: { kind: "bool", value: true },
            },
        };
        const data = computeRemoteComposeBundleData(payload);
        const namedRows = data.rows.filter((r) => r.section === "named");
        assert.strictEqual(namedRows.length, 3);
        assert.strictEqual(data.namedCount, 3);
        // Object.entries walks insertion order for string keys per ES2020+;
        // the wire shape is LinkedHashMap-equivalent on the daemon side so
        // the panel's table matches the user-bound order.
        assert.deepStrictEqual(
            namedRows.map((r) => (r as { name: string }).name),
            ["seedColor", "cornerRadius", "enabled"],
        );
    });

    it("reverses host-action rows so newest fires render first", () => {
        const payload: RemoteComposePayload = {
            hostActions: [
                { payload: "tap", handlerId: 1, firedAtMillis: 1000 },
                { payload: "tap", handlerId: 1, firedAtMillis: 2000 },
                { payload: "long", handlerId: 2, firedAtMillis: 3000 },
            ],
        };
        const data = computeRemoteComposeBundleData(payload);
        const actionRows = data.rows.filter((r) => r.section === "actions");
        assert.strictEqual(actionRows.length, 3);
        assert.strictEqual(data.actionCount, 3);
        // Newest first: index 2 (the "long" press) appears first.
        assert.strictEqual(
            (actionRows[0] as { firedAtMillis: number | null }).firedAtMillis,
            3000,
        );
        assert.strictEqual(
            (actionRows[2] as { firedAtMillis: number | null }).firedAtMillis,
            1000,
        );
        // The original (oldest-first) order is preserved in the JSON payload
        // the table emits via Copy JSON.
        const jsonActions = (
            data.jsonPayload as {
                hostActions: readonly { firedAtMillis: number }[];
            }
        ).hostActions;
        assert.strictEqual(jsonActions[0]?.firedAtMillis, 1000);
        assert.strictEqual(jsonActions[2]?.firedAtMillis, 3000);
    });

    it("carries every typed named-value variant through unchanged", () => {
        const payload: RemoteComposePayload = {
            namedValues: {
                f: { kind: "float", value: 1.5 },
                d: { kind: "dp", value: 8.0 },
                i: { kind: "int", value: 42 },
                s: { kind: "string", value: "hello" },
                b: { kind: "bool", value: true },
                c: { kind: "color", argb: "#FFAABBCC" },
            },
        };
        const data = computeRemoteComposeBundleData(payload);
        const byName = new Map(
            data.rows
                .filter((r) => r.section === "named")
                .map((r) => [
                    (r as { name: string }).name,
                    (r as { value: unknown }).value,
                ]),
        );
        assert.deepStrictEqual(byName.get("f"), { kind: "float", value: 1.5 });
        assert.deepStrictEqual(byName.get("d"), { kind: "dp", value: 8.0 });
        assert.deepStrictEqual(byName.get("i"), { kind: "int", value: 42 });
        assert.deepStrictEqual(byName.get("s"), {
            kind: "string",
            value: "hello",
        });
        assert.deepStrictEqual(byName.get("b"), { kind: "bool", value: true });
        assert.deepStrictEqual(byName.get("c"), {
            kind: "color",
            argb: "#FFAABBCC",
        });
    });
});
