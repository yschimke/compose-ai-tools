import * as assert from "assert";
import { mergeRemoteComposeChange } from "../daemon/remoteComposeMerge";
import type { RemoteComposeOverride } from "../daemon/daemonProtocol";

describe("mergeRemoteComposeChange", () => {
    it("starts a profile-only bag from a missing prior", () => {
        const next = mergeRemoteComposeChange(undefined, {
            field: "profile",
            value: "androidx9",
        });
        assert.strictEqual(next.profile, "androidx9");
        assert.deepStrictEqual(next.namedValues, {});
        assert.strictEqual(next.acceptedHostActions, undefined);
    });

    it("starts a single-named-value bag from a null prior", () => {
        const next = mergeRemoteComposeChange(null, {
            field: "namedValue",
            name: "seedColor",
            value: { kind: "color", argb: "#FF3366FF" },
        });
        assert.strictEqual(next.profile, undefined);
        assert.deepStrictEqual(next.namedValues, {
            seedColor: { kind: "color", argb: "#FF3366FF" },
        });
    });

    it("preserves named values when the change is a profile flip", () => {
        const prior: RemoteComposeOverride = {
            profile: "androidx",
            namedValues: {
                seedColor: { kind: "color", argb: "#FF3366FF" },
                cornerRadius: { kind: "dp", value: 8 },
            },
            acceptedHostActions: ["tap"],
        };
        const next = mergeRemoteComposeChange(prior, {
            field: "profile",
            value: "wearWidgets",
        });
        assert.strictEqual(next.profile, "wearWidgets");
        assert.deepStrictEqual(next.namedValues, prior.namedValues);
        assert.deepStrictEqual(next.acceptedHostActions, ["tap"]);
    });

    it("preserves the profile + other named values when the change is a single value", () => {
        const prior: RemoteComposeOverride = {
            profile: "androidx9",
            namedValues: {
                seedColor: { kind: "color", argb: "#FF3366FF" },
                cornerRadius: { kind: "dp", value: 8 },
            },
        };
        const next = mergeRemoteComposeChange(prior, {
            field: "namedValue",
            name: "opacity",
            value: { kind: "float", value: 0.5 },
        });
        assert.strictEqual(next.profile, "androidx9");
        assert.deepStrictEqual(next.namedValues, {
            seedColor: { kind: "color", argb: "#FF3366FF" },
            cornerRadius: { kind: "dp", value: 8 },
            opacity: { kind: "float", value: 0.5 },
        });
    });

    it("overwrites an existing named value with the same name", () => {
        const prior: RemoteComposeOverride = {
            namedValues: { score: { kind: "float", value: 0.25 } },
        };
        const next = mergeRemoteComposeChange(prior, {
            field: "namedValue",
            name: "score",
            value: { kind: "float", value: 0.75 },
        });
        assert.deepStrictEqual(next.namedValues, {
            score: { kind: "float", value: 0.75 },
        });
    });

    it("accepts a null profile clear without dropping named values", () => {
        const prior: RemoteComposeOverride = {
            profile: "androidx",
            namedValues: { enabled: { kind: "bool", value: true } },
        };
        const next = mergeRemoteComposeChange(prior, {
            field: "profile",
            value: null,
        });
        assert.strictEqual(next.profile, null);
        assert.deepStrictEqual(next.namedValues, {
            enabled: { kind: "bool", value: true },
        });
    });

    it("does not mutate the prior bag", () => {
        const prior: RemoteComposeOverride = {
            profile: "androidx",
            namedValues: { a: { kind: "int", value: 1 } },
        };
        const priorSnapshot = JSON.parse(JSON.stringify(prior));
        mergeRemoteComposeChange(prior, {
            field: "namedValue",
            name: "b",
            value: { kind: "int", value: 2 },
        });
        assert.deepStrictEqual(prior, priorSnapshot);
    });
});
