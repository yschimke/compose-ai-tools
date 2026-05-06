import * as assert from "assert";
import { pickRefreshModeFor } from "../refreshMode";

/**
 * Pure predicate test — the production wrapper {@link pickRefreshMode}
 * reads `daemonGate` and `gradleService` from module scope, but the
 * decision logic is in {@link pickRefreshModeFor} so we can exercise
 * every branch without stubbing the VS Code API.
 */
describe("pickRefreshModeFor", () => {
    it("returns gradle when the file resolves to no module", () => {
        assert.strictEqual(pickRefreshModeFor("/outside.kt", null), "gradle");
    });

    it("returns daemon when the file resolves to a module", () => {
        assert.strictEqual(pickRefreshModeFor("/x.kt", "mod"), "daemon");
    });
});
