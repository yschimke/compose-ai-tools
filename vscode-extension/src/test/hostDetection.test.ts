import * as assert from "assert";
import { isAntigravityHost } from "../hostDetection";

describe("isAntigravityHost", () => {
    it("returns false for a stock VS Code env (no markers set)", () => {
        assert.strictEqual(isAntigravityHost({}), false);
    });

    it("returns true when __CFBundleIdentifier mentions antigravity", () => {
        assert.strictEqual(
            isAntigravityHost({
                __CFBundleIdentifier: "com.google.AntiGravity",
            }),
            true,
        );
    });

    it("matches case-insensitively on the bundle id", () => {
        assert.strictEqual(
            isAntigravityHost({ __CFBundleIdentifier: "antigravity-cli" }),
            true,
        );
    });

    it("returns true when ANTIGRAVITY_CLI_ALIAS is set, even to empty string", () => {
        // The probe checks for `!== undefined`, not truthy, so an empty
        // alias still flags the host.
        assert.strictEqual(
            isAntigravityHost({ ANTIGRAVITY_CLI_ALIAS: "" }),
            true,
        );
    });

    it("returns true when the alias is set even with a non-Antigravity bundle id", () => {
        assert.strictEqual(
            isAntigravityHost({
                __CFBundleIdentifier: "com.microsoft.VSCode",
                ANTIGRAVITY_CLI_ALIAS: "ag",
            }),
            true,
        );
    });

    it("returns false when only the bundle id is set, and it doesn't match", () => {
        assert.strictEqual(
            isAntigravityHost({
                __CFBundleIdentifier: "com.microsoft.VSCode",
            }),
            false,
        );
    });
});
