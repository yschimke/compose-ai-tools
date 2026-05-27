import * as assert from "assert";
import { mergePermissionsChange } from "../daemon/permissionsMerge";
import type { PermissionsOverride } from "../daemon/daemonProtocol";

describe("mergePermissionsChange", () => {
    it("starts a single-grant bag from an undefined prior", () => {
        const next = mergePermissionsChange(undefined, {
            field: "setGrant",
            permission: "android.permission.CAMERA",
            grant: "granted",
        });
        assert.deepStrictEqual(next, {
            grants: { "android.permission.CAMERA": "granted" },
        });
    });

    it("treats null prior the same as undefined", () => {
        const next = mergePermissionsChange(null, {
            field: "setGrant",
            permission: "android.permission.RECORD_AUDIO",
            grant: "denied",
        });
        assert.deepStrictEqual(next, {
            grants: { "android.permission.RECORD_AUDIO": "denied" },
        });
    });

    it("merges a setGrant into a prior bag without aliasing", () => {
        const prior: PermissionsOverride = {
            grants: { "android.permission.CAMERA": "granted" },
        };
        const next = mergePermissionsChange(prior, {
            field: "setGrant",
            permission: "android.permission.RECORD_AUDIO",
            grant: "denied",
        });
        assert.deepStrictEqual(next.grants, {
            "android.permission.CAMERA": "granted",
            "android.permission.RECORD_AUDIO": "denied",
        });
        // Prior must be untouched — host code stashes the prior bag and would
        // otherwise see surprise mutations.
        assert.deepStrictEqual(prior.grants, {
            "android.permission.CAMERA": "granted",
        });
        assert.notStrictEqual(next.grants, prior.grants);
    });

    it("overwrites an existing grant on a setGrant for the same permission", () => {
        const prior: PermissionsOverride = {
            grants: {
                "android.permission.CAMERA": "denied",
                "android.permission.RECORD_AUDIO": "denied",
            },
        };
        const next = mergePermissionsChange(prior, {
            field: "setGrant",
            permission: "android.permission.CAMERA",
            grant: "granted",
        });
        assert.deepStrictEqual(next.grants, {
            "android.permission.CAMERA": "granted",
            "android.permission.RECORD_AUDIO": "denied",
        });
    });

    it("drops the named permission on clearGrant", () => {
        const prior: PermissionsOverride = {
            grants: {
                "android.permission.CAMERA": "granted",
                "android.permission.RECORD_AUDIO": "denied",
            },
        };
        const next = mergePermissionsChange(prior, {
            field: "clearGrant",
            permission: "android.permission.CAMERA",
        });
        assert.deepStrictEqual(next.grants, {
            "android.permission.RECORD_AUDIO": "denied",
        });
    });

    it("returns an empty bag (not undefined) on clearGrant against a missing prior", () => {
        const next = mergePermissionsChange(undefined, {
            field: "clearGrant",
            permission: "android.permission.CAMERA",
        });
        assert.deepStrictEqual(next, { grants: {} });
    });

    it("replaces the bag with an empty grants map on clearAll", () => {
        const prior: PermissionsOverride = {
            grants: {
                "android.permission.CAMERA": "granted",
                "android.permission.RECORD_AUDIO": "denied",
            },
        };
        const next = mergePermissionsChange(prior, { field: "clearAll" });
        assert.deepStrictEqual(next, { grants: {} });
    });
});
