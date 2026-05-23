// Permissions bundle presenter (#1370 follow-up). Pins the payload →
// rows + worst-level derivation. The presenter is stateless; tests
// run it directly without any DOM.

import * as assert from "assert";
import {
    computePermissionsBundleData,
    type PermissionsPayload,
} from "../webview/preview/permissionsBundlePresenter";

describe("computePermissionsBundleData", () => {
    it("returns empty lists and an info-level worst for a null payload", () => {
        const data = computePermissionsBundleData(null);
        assert.strictEqual(data.grantRows.length, 0);
        assert.strictEqual(data.queriedRows.length, 0);
        assert.deepStrictEqual(data.allPermissions, []);
        assert.strictEqual(data.worstLevel, "info");
    });

    it("flattens grants into rows with short labels and an info worst when all granted", () => {
        const payload: PermissionsPayload = {
            grants: {
                "android.permission.CAMERA": "granted",
                "android.permission.RECORD_AUDIO": "granted",
            },
            queried: [],
        };
        const data = computePermissionsBundleData(payload);
        assert.strictEqual(data.grantRows.length, 2);
        const byPerm = new Map(data.grantRows.map((r) => [r.permission, r]));
        assert.strictEqual(
            byPerm.get("android.permission.CAMERA")?.shortLabel,
            "CAMERA",
        );
        assert.strictEqual(
            byPerm.get("android.permission.CAMERA")?.grant,
            "granted",
        );
        assert.strictEqual(
            byPerm.get("android.permission.CAMERA")?.level,
            "info",
        );
        assert.strictEqual(data.worstLevel, "info");
    });

    it("escalates worstLevel to warning when any grant is denied", () => {
        const data = computePermissionsBundleData({
            grants: {
                "android.permission.CAMERA": "granted",
                "android.permission.RECORD_AUDIO": "denied",
            },
            queried: [],
        });
        assert.strictEqual(data.worstLevel, "warning");
        const audio = data.grantRows.find(
            (r) => r.permission === "android.permission.RECORD_AUDIO",
        );
        assert.strictEqual(audio?.level, "warning");
    });

    it("marks queried rows without a matching grant as unknown and escalates worstLevel", () => {
        const data = computePermissionsBundleData({
            grants: { "android.permission.CAMERA": "granted" },
            queried: [
                "android.permission.CAMERA",
                "android.permission.ACCESS_FINE_LOCATION",
            ],
        });
        assert.strictEqual(data.queriedRows.length, 2);
        const location = data.queriedRows.find(
            (r) => r.permission === "android.permission.ACCESS_FINE_LOCATION",
        );
        assert.strictEqual(location?.grant, null);
        assert.strictEqual(location?.level, "unknown");
        assert.strictEqual(data.worstLevel, "unknown");
    });

    it("marks grant rows whose permission was queried during composition", () => {
        const data = computePermissionsBundleData({
            grants: { "android.permission.CAMERA": "granted" },
            queried: ["android.permission.CAMERA"],
        });
        const cam = data.grantRows.find(
            (r) => r.permission === "android.permission.CAMERA",
        );
        assert.strictEqual(cam?.queried, true);
    });

    it("returns insertion-stable sorted grantRows so re-fetches don't jitter", () => {
        const a = computePermissionsBundleData({
            grants: {
                "android.permission.RECORD_AUDIO": "granted",
                "android.permission.CAMERA": "denied",
            },
        });
        const b = computePermissionsBundleData({
            grants: {
                "android.permission.CAMERA": "denied",
                "android.permission.RECORD_AUDIO": "granted",
            },
        });
        assert.deepStrictEqual(
            a.grantRows.map((r) => r.permission),
            b.grantRows.map((r) => r.permission),
        );
    });

    it("ignores unknown grant strings without crashing", () => {
        const data = computePermissionsBundleData({
            grants: {
                "android.permission.CAMERA": "weird" as unknown as "granted",
                "android.permission.RECORD_AUDIO": "denied",
            },
            queried: [
                123 as unknown as string,
                "android.permission.CAMERA",
                "",
            ],
        });
        // CAMERA dropped from grants because the wire value wasn't `granted` / `denied`.
        assert.strictEqual(
            data.grantRows.find(
                (r) => r.permission === "android.permission.CAMERA",
            ),
            undefined,
        );
        // Only one valid queried row survived the filter.
        assert.deepStrictEqual(
            data.queriedRows.map((r) => r.permission),
            ["android.permission.CAMERA"],
        );
    });

    it("leaves non-prefixed permission names untouched", () => {
        const data = computePermissionsBundleData({
            grants: { "com.example.CUSTOM": "granted" },
        });
        assert.strictEqual(data.grantRows[0]?.shortLabel, "com.example.CUSTOM");
    });
});
