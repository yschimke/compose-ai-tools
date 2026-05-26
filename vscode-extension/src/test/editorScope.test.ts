import * as assert from "assert";
import { EditorScope, PreviewModuleIndex } from "../editorScope";
import { ModuleInfo } from "../gradleService";

function module(modulePath: string): ModuleInfo {
    return {
        modulePath,
        projectDir: modulePath.replace(/^:/, "").replace(/:/g, "/"),
    } as ModuleInfo;
}

describe("EditorScope", () => {
    it("starts empty", () => {
        const scope = new EditorScope();
        assert.strictEqual(scope.file, null);
        assert.strictEqual(scope.module, null);
    });

    it("set pins both fields together", () => {
        const scope = new EditorScope();
        const mod = module(":app");
        scope.set("/a.kt", mod);
        assert.strictEqual(scope.file, "/a.kt");
        assert.strictEqual(scope.module, mod);
    });

    it("ownsModule compares by modulePath, not reference identity", () => {
        const scope = new EditorScope();
        scope.set("/a.kt", module(":app"));
        // A fresh ModuleInfo instance with the same modulePath should still
        // be reported as the active scope's module — equality by id, not by
        // object identity, because manifests are re-decoded across refreshes.
        assert.strictEqual(scope.ownsModule(module(":app")), true);
        assert.strictEqual(scope.ownsModule(module(":other")), false);
    });

    it("ownsModule returns false when no scope is set", () => {
        const scope = new EditorScope();
        assert.strictEqual(scope.ownsModule(module(":app")), false);
    });

    it("isCurrentFile matches the pinned path", () => {
        const scope = new EditorScope();
        scope.set("/a.kt", module(":app"));
        assert.strictEqual(scope.isCurrentFile("/a.kt"), true);
        assert.strictEqual(scope.isCurrentFile("/b.kt"), false);
    });

    it("set(null, null) clears both fields", () => {
        const scope = new EditorScope();
        scope.set("/a.kt", module(":app"));
        scope.set(null, null);
        assert.strictEqual(scope.file, null);
        assert.strictEqual(scope.module, null);
    });
});

describe("PreviewModuleIndex", () => {
    it("get returns undefined for unknown previews", () => {
        const index = new PreviewModuleIndex();
        assert.strictEqual(index.get("nope"), undefined);
    });

    it("set + get round-trips the module", () => {
        const index = new PreviewModuleIndex();
        const mod = module(":app");
        index.set("p1", mod);
        assert.strictEqual(index.get("p1"), mod);
    });

    it("set overwrites the prior owner for a preview", () => {
        const index = new PreviewModuleIndex();
        index.set("p1", module(":app"));
        index.set("p1", module(":other"));
        assert.strictEqual(index.get("p1")?.modulePath, ":other");
    });

    it("clear drops every entry", () => {
        const index = new PreviewModuleIndex();
        index.set("p1", module(":app"));
        index.set("p2", module(":lib"));
        index.clear();
        assert.strictEqual(index.size(), 0);
        assert.strictEqual(index.get("p1"), undefined);
    });

    describe("replaceModule", () => {
        it("drops prior entries for the module and installs the fresh set", () => {
            const index = new PreviewModuleIndex();
            const app = module(":app");
            index.set("p1", app);
            index.set("p2", app);
            index.set("p3", app);
            index.replaceModule(app, ["p2", "p4"]);
            // p1 and p3 dropped (no longer in fresh), p2 retained, p4 added.
            assert.strictEqual(index.get("p1"), undefined);
            assert.strictEqual(index.get("p2")?.modulePath, ":app");
            assert.strictEqual(index.get("p3"), undefined);
            assert.strictEqual(index.get("p4")?.modulePath, ":app");
        });

        it("does not touch entries owned by other modules", () => {
            const index = new PreviewModuleIndex();
            index.set("p1", module(":app"));
            index.set("p2", module(":lib"));
            index.replaceModule(module(":app"), ["p3"]);
            // :lib's entry survives even though we replaced :app's entries.
            assert.strictEqual(index.get("p2")?.modulePath, ":lib");
            assert.strictEqual(index.get("p1"), undefined);
            assert.strictEqual(index.get("p3")?.modulePath, ":app");
        });

        it("with an empty fresh set removes every entry for the module", () => {
            const index = new PreviewModuleIndex();
            const app = module(":app");
            index.set("p1", app);
            index.set("p2", app);
            index.replaceModule(app, []);
            assert.strictEqual(index.get("p1"), undefined);
            assert.strictEqual(index.get("p2"), undefined);
            assert.strictEqual(index.size(), 0);
        });

        it("compares ownership by modulePath, not reference identity", () => {
            // Regression: prior code captured a `moduleKey` local and compared
            // `owner.modulePath === moduleKey`. Equivalent semantics — the
            // class method takes a ModuleInfo but must still match purely by
            // modulePath because the registered owner instance can be a stale
            // copy from a prior refresh.
            const index = new PreviewModuleIndex();
            index.set("p1", module(":app")); // one instance
            index.replaceModule(module(":app"), ["p2"]); // different instance, same modulePath
            assert.strictEqual(index.get("p1"), undefined);
            assert.strictEqual(index.get("p2")?.modulePath, ":app");
        });
    });

    describe("entries", () => {
        it("returns a snapshot the caller can iterate while mutating the index", () => {
            // Regression: the prior bare-Map call sites spread
            // `[...previewModuleMap.entries()]` defensively so concurrent
            // delete()s wouldn't trip a JS Map iterator. The class method
            // returns an array for the same reason.
            const index = new PreviewModuleIndex();
            index.set("p1", module(":app"));
            index.set("p2", module(":lib"));
            for (const [id] of index.entries()) {
                if (id === "p1") {
                    index.set("p3", module(":new"));
                }
            }
            assert.strictEqual(index.get("p3")?.modulePath, ":new");
        });
    });
});
