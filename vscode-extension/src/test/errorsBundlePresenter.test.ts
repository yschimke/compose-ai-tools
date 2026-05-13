// Errors bundle presenter (#1061 Cluster H). Pins the
// payload → rows / stackFrames derivation, including the
// hasFailure flag the host uses to decide whether to swap in a
// placeholder.

import * as assert from "assert";
import {
    computeErrorsBundleData,
    type TestFailurePayload,
} from "../webview/preview/errorsBundlePresenter";

describe("computeErrorsBundleData", () => {
    it("returns no rows and hasFailure=false for a null payload", () => {
        const data = computeErrorsBundleData(null);
        assert.strictEqual(data.rows.length, 0);
        assert.strictEqual(data.stackFrames.length, 0);
        assert.strictEqual(data.hasFailure, false);
    });

    it("flattens a populated payload into status / phase / type / message / topFrame rows", () => {
        const payload: TestFailurePayload = {
            status: "failed",
            phase: "render",
            error: {
                type: "IllegalStateException",
                message: "missing CompositionLocal",
                topFrame: "MyScreen.kt:42",
                stackTrace: ["MyScreen.kt:42", "Composer.kt:101"],
            },
        };
        const data = computeErrorsBundleData(payload);
        assert.strictEqual(data.rows.length, 5);
        assert.strictEqual(data.hasFailure, true);
        assert.strictEqual(data.stackFrames.length, 2);
        const byKey = new Map(data.rows.map((r) => [r.key, r.value]));
        assert.strictEqual(byKey.get("Status"), "failed");
        assert.strictEqual(byKey.get("Phase"), "render");
        assert.strictEqual(byKey.get("Type"), "IllegalStateException");
        assert.strictEqual(byKey.get("Message"), "missing CompositionLocal");
        assert.strictEqual(byKey.get("Top frame"), "MyScreen.kt:42");
    });

    it("filters out non-string / empty stack frames defensively", () => {
        const payload = {
            status: "failed",
            phase: "render",
            error: {
                type: "X",
                message: "y",
                topFrame: "Z",
                stackTrace: [
                    "good",
                    "",
                    // these aren't legal under our type but the wire is
                    // JSON — the presenter must not crash on them.
                    null as unknown as string,
                    42 as unknown as string,
                    "also-good",
                ],
            },
        } satisfies TestFailurePayload;
        const data = computeErrorsBundleData(payload);
        assert.deepStrictEqual([...data.stackFrames], ["good", "also-good"]);
    });

    it("emits dashes for missing fields without crashing", () => {
        const data = computeErrorsBundleData({});
        // 5 fields × dash — `hasFailure` stays false so the host can
        // swap in a friendlier placeholder.
        assert.strictEqual(data.rows.length, 5);
        for (const row of data.rows) {
            assert.strictEqual(row.value, "—");
        }
        assert.strictEqual(data.hasFailure, false);
    });

    it("treats a payload with only a stack trace as a real failure", () => {
        const data = computeErrorsBundleData({
            error: { stackTrace: ["only-frame"] },
        });
        assert.strictEqual(data.hasFailure, true);
        assert.strictEqual(data.stackFrames.length, 1);
    });
});
