// Coverage for the `isFocusedModuleReady` / `isFocusedInteractiveSupported`
// predicates as re-exported from `focusToolbar.ts`. The implementation
// itself lives in `./moduleReadiness.ts` (and is unit-tested there in
// `moduleReadiness.test.ts`); this file pins the re-export surface so
// callers that import the predicates from `focusToolbar` (main.ts,
// focusController.ts) keep getting the same functions and behaviour.

import * as assert from "assert";
import {
    isFocusedInteractiveSupported,
    isFocusedModuleReady,
} from "../webview/preview/focusToolbar";
import * as moduleReadiness from "../webview/preview/moduleReadiness";

describe("focusToolbar re-exports", () => {
    it("re-exports the same isFocusedModuleReady reference as moduleReadiness", () => {
        assert.strictEqual(
            isFocusedModuleReady,
            moduleReadiness.isFocusedModuleReady,
        );
    });

    it("re-exports the same isFocusedInteractiveSupported reference as moduleReadiness", () => {
        assert.strictEqual(
            isFocusedInteractiveSupported,
            moduleReadiness.isFocusedInteractiveSupported,
        );
    });
});

describe("isFocusedModuleReady (via focusToolbar)", () => {
    it("returns false for an empty Map", () => {
        assert.strictEqual(isFocusedModuleReady(new Map()), false);
    });

    it("returns true when the only module is ready", () => {
        const m = new Map([[":app", true]]);
        assert.strictEqual(isFocusedModuleReady(m), true);
    });

    it("returns false when the only module is not ready", () => {
        const m = new Map([[":app", false]]);
        assert.strictEqual(isFocusedModuleReady(m), false);
    });

    it("returns true when at least one of several modules is ready", () => {
        const m = new Map([
            [":app", false],
            [":lib", false],
            [":feature", true],
        ]);
        assert.strictEqual(isFocusedModuleReady(m), true);
    });

    it("returns false when every module in a multi-entry map is not ready", () => {
        const m = new Map([
            [":app", false],
            [":lib", false],
            [":feature", false],
        ]);
        assert.strictEqual(isFocusedModuleReady(m), false);
    });
});

describe("isFocusedInteractiveSupported (via focusToolbar)", () => {
    it("returns false when both maps are empty", () => {
        assert.strictEqual(
            isFocusedInteractiveSupported(new Map(), new Map()),
            false,
        );
    });

    it("returns true when a module is both ready and interactive-supported", () => {
        const ready = new Map([[":app", true]]);
        const supported = new Map([[":app", true]]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            true,
        );
    });

    it("returns false when the module is ready but interactive-unsupported (v1 fallback)", () => {
        const ready = new Map([[":app", true]]);
        const supported = new Map([[":app", false]]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });

    it("returns false when the module is interactive-supported but not ready", () => {
        const ready = new Map([[":app", false]]);
        const supported = new Map([[":app", true]]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });

    it("returns false when a ready module is missing from the supported map", () => {
        const ready = new Map([[":app", true]]);
        const supported = new Map<string, boolean>();
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });

    it("returns true when at least one of several ready modules is supported", () => {
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

    it("ignores supported modules whose readiness entry is missing", () => {
        // Only modules present in `moduleDaemonReady` are walked; an
        // entry that lives only in the supported map can't tip the
        // predicate true.
        const ready = new Map<string, boolean>();
        const supported = new Map([[":lib", true]]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });

    it("ignores ready modules whose supported entry is explicitly absent (=== true gate)", () => {
        // The implementation requires `supported.get(moduleId) === true`
        // — a missing entry yields `undefined`, not `true`, and so does
        // not flip the predicate.
        const ready = new Map([
            [":app", true],
            [":lib", true],
        ]);
        const supported = new Map<string, boolean>([[":app", false]]);
        assert.strictEqual(
            isFocusedInteractiveSupported(ready, supported),
            false,
        );
    });
});
