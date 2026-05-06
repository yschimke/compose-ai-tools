import * as assert from "assert";
import {
    isFocusedInteractiveSupported,
    isFocusedModuleReady,
} from "../webview/preview/moduleReadiness";

describe("isFocusedModuleReady", () => {
    it("returns false for an empty Map", () => {
        assert.strictEqual(isFocusedModuleReady(new Map()), false);
    });

    it("returns true when at least one module is ready", () => {
        const m = new Map([
            [":app", true],
            [":lib", false],
        ]);
        assert.strictEqual(isFocusedModuleReady(m), true);
    });

    it("returns false when every module is not ready", () => {
        const m = new Map([
            [":app", false],
            [":lib", false],
        ]);
        assert.strictEqual(isFocusedModuleReady(m), false);
    });
});

describe("isFocusedInteractiveSupported", () => {
    it("returns false when the readiness map is empty", () => {
        assert.strictEqual(
            isFocusedInteractiveSupported(new Map(), new Map()),
            false,
        );
    });

    it("returns true when at least one ready module is interactive-supported", () => {
        const ready = new Map([
            [":app", true],
            [":lib", true],
        ]);
        const supported = new Map([
            [":app", false],
            [":lib", true],
        ]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            true,
        );
    });

    it("returns false when ready modules are all v1-fallback (interactive unsupported)", () => {
        const ready = new Map([[":app", true]]);
        const supported = new Map([[":app", false]]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });

    it("returns false when supported modules aren't ready", () => {
        const ready = new Map([[":app", false]]);
        const supported = new Map([[":app", true]]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });

    it("returns false when a module is ready but missing from the supported map", () => {
        const ready = new Map([[":app", true]]);
        const supported = new Map<string, boolean>();
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });
});
