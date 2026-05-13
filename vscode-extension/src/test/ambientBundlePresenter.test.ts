// Ambient (Watch / Wear) bundle presenter (#1061 Cluster H). Pins
// the payload → rows + state badge level derivation. The presenter
// is stateless; tests run it directly without any DOM.

import * as assert from "assert";
import {
    computeAmbientBundleData,
    type AmbientPayload,
} from "../webview/preview/ambientBundlePresenter";

describe("computeAmbientBundleData", () => {
    it("returns no rows and an info-level state badge for a null payload", () => {
        const data = computeAmbientBundleData(null);
        assert.strictEqual(data.rows.length, 0);
        assert.strictEqual(data.state, null);
        assert.strictEqual(data.stateLevel, "info");
    });

    it("flattens an interactive payload to four key/value rows", () => {
        const payload: AmbientPayload = {
            state: "interactive",
            burnInProtectionRequired: false,
            deviceHasLowBitAmbient: false,
            updateTimeMillis: 1700000000000,
        };
        const data = computeAmbientBundleData(payload);
        assert.strictEqual(data.rows.length, 4);
        assert.strictEqual(data.state, "interactive");
        assert.strictEqual(data.stateLevel, "info");
        const byKey = new Map(data.rows.map((r) => [r.key, r.value]));
        assert.strictEqual(byKey.get("State"), "interactive");
        assert.strictEqual(byKey.get("Burn-in protection"), "no");
        assert.strictEqual(byKey.get("Low-bit ambient"), "no");
        assert.strictEqual(byKey.get("Update time (ms)"), "1700000000000");
    });

    it("escalates ambient state to a warning-level badge", () => {
        const data = computeAmbientBundleData({
            state: "ambient",
            burnInProtectionRequired: true,
            deviceHasLowBitAmbient: true,
            updateTimeMillis: 0,
        });
        assert.strictEqual(data.state, "ambient");
        assert.strictEqual(data.stateLevel, "warning");
        const byKey = new Map(data.rows.map((r) => [r.key, r.value]));
        assert.strictEqual(byKey.get("Burn-in protection"), "yes");
        assert.strictEqual(byKey.get("Low-bit ambient"), "yes");
    });

    it("escalates inactive state to an error-level badge", () => {
        const data = computeAmbientBundleData({
            state: "inactive",
            burnInProtectionRequired: false,
            deviceHasLowBitAmbient: false,
            updateTimeMillis: 1,
        });
        assert.strictEqual(data.stateLevel, "error");
    });

    it("renders dashes for missing booleans / timestamps without crashing", () => {
        const data = computeAmbientBundleData({});
        assert.strictEqual(data.rows.length, 4);
        const byKey = new Map(data.rows.map((r) => [r.key, r.value]));
        assert.strictEqual(byKey.get("State"), "—");
        assert.strictEqual(byKey.get("Burn-in protection"), "—");
        assert.strictEqual(byKey.get("Low-bit ambient"), "—");
        assert.strictEqual(byKey.get("Update time (ms)"), "—");
    });
});
