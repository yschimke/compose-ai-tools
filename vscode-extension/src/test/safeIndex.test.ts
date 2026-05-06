import * as assert from "assert";
import { safeArrayIndex } from "../webview/shared/safeIndex";

describe("safeArrayIndex", () => {
    it("returns valid non-negative integers unchanged", () => {
        assert.strictEqual(safeArrayIndex(0), 0);
        assert.strictEqual(safeArrayIndex(1), 1);
        assert.strictEqual(safeArrayIndex(42), 42);
        assert.strictEqual(safeArrayIndex(1_000_000), 1_000_000);
    });

    it("collapses negative numbers to 0", () => {
        assert.strictEqual(safeArrayIndex(-1), 0);
        assert.strictEqual(safeArrayIndex(-42), 0);
        assert.strictEqual(safeArrayIndex(-Infinity), 0);
    });

    it("collapses non-integer numbers to 0", () => {
        assert.strictEqual(safeArrayIndex(1.5), 0);
        assert.strictEqual(safeArrayIndex(0.1), 0);
        assert.strictEqual(safeArrayIndex(NaN), 0);
        assert.strictEqual(safeArrayIndex(Infinity), 0);
    });

    it("collapses non-numeric inputs to 0", () => {
        assert.strictEqual(safeArrayIndex(undefined), 0);
        assert.strictEqual(safeArrayIndex(null), 0);
        assert.strictEqual(safeArrayIndex(""), 0);
        assert.strictEqual(safeArrayIndex("0"), 0);
        assert.strictEqual(safeArrayIndex("42"), 0);
        assert.strictEqual(safeArrayIndex(true), 0);
        assert.strictEqual(safeArrayIndex({}), 0);
        assert.strictEqual(safeArrayIndex([]), 0);
    });

    it("collapses the prototype-pollution attack string to 0", () => {
        // The CodeQL-flagged threat: an attacker passes "__proto__" as
        // captureIndex, expecting `caps["__proto__"] = ...` to mutate
        // Array.prototype. After coercion it's 0 — a normal in-range write.
        assert.strictEqual(safeArrayIndex("__proto__"), 0);
        assert.strictEqual(safeArrayIndex("constructor"), 0);
    });
});
