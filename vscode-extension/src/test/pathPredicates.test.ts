import * as assert from "assert";
import {
    isGeneratedOutputPath,
    isPreviewSourceFile,
    isSourceFile,
    sameScope,
} from "../pathPredicates";

describe("sameScope", () => {
    it("returns true for two empty arrays", () => {
        assert.strictEqual(sameScope([], []), true);
    });

    it("returns true regardless of element order", () => {
        assert.strictEqual(
            sameScope([":a", ":b", ":c"], [":c", ":a", ":b"]),
            true,
        );
    });

    it("returns false when lengths differ", () => {
        assert.strictEqual(sameScope([":a", ":b"], [":a", ":b", ":c"]), false);
    });

    it("returns false when an element is missing on either side", () => {
        assert.strictEqual(sameScope([":a", ":b"], [":a", ":c"]), false);
    });

    it("treats duplicates the same as a single entry — set semantics", () => {
        // Length check rejects this even though set-of-elements matches.
        assert.strictEqual(sameScope([":a", ":a"], [":a"]), false);
    });
});

describe("isGeneratedOutputPath", () => {
    it("flags a file under a top-level build/", () => {
        assert.strictEqual(isGeneratedOutputPath("/repo/build/foo.png"), true);
    });

    it("flags a file under a nested build/", () => {
        assert.strictEqual(
            isGeneratedOutputPath("/repo/samples/cmp/build/renders/foo.png"),
            true,
        );
    });

    it("flags a file under bin/ (IntelliJ default)", () => {
        assert.strictEqual(
            isGeneratedOutputPath("/repo/module/bin/Foo.class"),
            true,
        );
    });

    it("does not match a file whose name contains 'build' but not as a segment", () => {
        assert.strictEqual(
            isGeneratedOutputPath("/repo/src/main/buildHelper.kt"),
            false,
        );
    });

    it("handles Windows-style separators", () => {
        assert.strictEqual(
            isGeneratedOutputPath("C:\\repo\\module\\build\\foo.png"),
            true,
        );
    });

    it("returns false for a clean source path", () => {
        assert.strictEqual(
            isGeneratedOutputPath("/repo/src/main/kotlin/Foo.kt"),
            false,
        );
    });
});

describe("isSourceFile", () => {
    it("accepts .kt outside build/", () => {
        assert.strictEqual(isSourceFile("/repo/src/main/kotlin/Foo.kt"), true);
    });

    it("accepts .xml outside build/", () => {
        assert.strictEqual(
            isSourceFile("/repo/src/main/res/layout/main.xml"),
            true,
        );
    });

    it("accepts .json and .properties (case-insensitive)", () => {
        assert.strictEqual(isSourceFile("/repo/local.PROPERTIES"), true);
        assert.strictEqual(isSourceFile("/repo/data/x.JSON"), true);
    });

    it("rejects an unrelated extension", () => {
        assert.strictEqual(isSourceFile("/repo/src/main/Foo.txt"), false);
    });

    it("rejects a generated-output file even if its extension matches", () => {
        assert.strictEqual(
            isSourceFile("/repo/module/build/generated/source/Foo.kt"),
            false,
        );
    });
});

describe("isPreviewSourceFile", () => {
    it("accepts a plain .kt file", () => {
        assert.strictEqual(
            isPreviewSourceFile("/repo/src/main/kotlin/Foo.kt"),
            true,
        );
    });

    it("rejects a Gradle build script (.gradle.kts)", () => {
        assert.strictEqual(
            isPreviewSourceFile("/repo/build.gradle.kts"),
            false,
        );
    });

    it("rejects a non-Kotlin file", () => {
        assert.strictEqual(
            isPreviewSourceFile("/repo/src/main/AndroidManifest.xml"),
            false,
        );
    });

    it("rejects a generated .kt under build/", () => {
        assert.strictEqual(
            isPreviewSourceFile("/repo/module/build/generated/Foo.kt"),
            false,
        );
    });
});
