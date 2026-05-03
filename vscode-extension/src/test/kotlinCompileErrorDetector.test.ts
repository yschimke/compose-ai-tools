import * as assert from "assert";
import {
    KotlinCompileError,
    KotlinCompileErrorDetector,
} from "../kotlinCompileErrorDetector";

/**
 * Fixture mirroring `compileDebugKotlin` output on a typo —
 * representative of what Gradle pipes through `onOutput` when a Kotlin
 * compile fails. Mixes the error lines with surrounding Gradle noise
 * so the detector has to actually anchor on `e:`.
 */
const FIXTURE_SINGLE_ERROR = [
    "> Task :samples:android:compileDebugKotlin",
    "w: file:///home/user/proj/Other.kt:5:1 Variable 'x' is never used",
    "e: file:///home/user/proj/samples/android/src/main/kotlin/Previews.kt:42:5 Unresolved reference: Modfier",
    "",
    "> Task :samples:android:compileDebugKotlin FAILED",
    "",
    "FAILURE: Build failed with an exception.",
].join("\n");

/**
 * Two errors across two files — the cross-file case the LSP-gate misses
 * by design, since the gate only inspects diagnostics for the active
 * editor's file. Detector must produce both, each with its own path.
 */
const FIXTURE_CROSS_FILE = [
    "> Task :app:compileDebugKotlin",
    "e: file:///proj/app/src/main/kotlin/Theme.kt:18:12 Type mismatch: inferred type is String but Int was expected",
    "e: file:///proj/app/src/main/kotlin/Previews.kt:8:1 Expecting }",
    "> Task :app:compileDebugKotlin FAILED",
].join("\n");

/** Kotlin 2.0+ form with an `: error:` segment between the position and message. */
const FIXTURE_K2_FORMAT =
    "e: file:///proj/Foo.kt:42:5: error: Unresolved reference: Modfier\n";

/** Bare-path form (no file:// prefix) — seen on some Kotlin versions / shells. */
const FIXTURE_BARE_PATH =
    "e: /proj/Foo.kt:42:5 Unresolved reference: Modfier\n";

describe("KotlinCompileErrorDetector", () => {
    it("returns no errors for an empty stream", () => {
        const d = new KotlinCompileErrorDetector();
        d.end();
        assert.deepStrictEqual(d.getErrors(), []);
    });

    it("extracts a single error from realistic compile output", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume(FIXTURE_SINGLE_ERROR);
        d.end();
        const errors = d.getErrors();
        assert.strictEqual(errors.length, 1, "should capture one e: line");
        assert.strictEqual(errors[0].file, "Previews.kt");
        assert.strictEqual(
            errors[0].path,
            "/home/user/proj/samples/android/src/main/kotlin/Previews.kt",
        );
        assert.strictEqual(errors[0].line, 42);
        assert.strictEqual(errors[0].column, 5);
        assert.strictEqual(errors[0].message, "Unresolved reference: Modfier");
    });

    it("ignores w: warning lines", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume(FIXTURE_SINGLE_ERROR);
        d.end();
        // The fixture has one warning + one error. Detector should
        // only surface the error — warnings flow through the log
        // channel but don't gate the build banner.
        const messages = d.getErrors().map((e) => e.message);
        assert.ok(
            !messages.some((m) => m.includes("never used")),
            "warnings must not appear in error list",
        );
    });

    it("captures cross-file errors with distinct paths", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume(FIXTURE_CROSS_FILE);
        d.end();
        const errors = d.getErrors();
        assert.strictEqual(errors.length, 2);
        const files = errors.map((e) => e.file).sort();
        assert.deepStrictEqual(files, ["Previews.kt", "Theme.kt"]);
        // Each error must carry its own path so the click handler
        // routes to the right file.
        const themePath = errors.find((e) => e.file === "Theme.kt")!.path;
        const previewsPath = errors.find((e) => e.file === "Previews.kt")!.path;
        assert.notStrictEqual(themePath, previewsPath);
        assert.ok(themePath.endsWith("Theme.kt"));
        assert.ok(previewsPath.endsWith("Previews.kt"));
    });

    it('parses the Kotlin 2.0+ "error:" segment between position and message', () => {
        const d = new KotlinCompileErrorDetector();
        d.consume(FIXTURE_K2_FORMAT);
        d.end();
        const errors = d.getErrors();
        assert.strictEqual(errors.length, 1);
        assert.strictEqual(errors[0].message, "Unresolved reference: Modfier");
        // The "error:" prefix should NOT leak into the message.
        assert.ok(!errors[0].message.startsWith("error:"));
    });

    it("parses bare-path form (no file:// prefix)", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume(FIXTURE_BARE_PATH);
        d.end();
        const errors = d.getErrors();
        assert.strictEqual(errors.length, 1);
        assert.strictEqual(errors[0].path, "/proj/Foo.kt");
        assert.strictEqual(errors[0].line, 42);
        assert.strictEqual(errors[0].column, 5);
    });

    it("handles partial chunks split mid-line", () => {
        const d = new KotlinCompileErrorDetector();
        // Split a single error across four reads to exercise buffering.
        d.consume("e: file:///proj/Foo.kt:42:5 ");
        d.consume("Unresolved");
        d.consume(" reference: ");
        d.consume("Modfier\n");
        d.end();
        const errors = d.getErrors();
        assert.strictEqual(errors.length, 1);
        assert.strictEqual(errors[0].message, "Unresolved reference: Modfier");
    });

    it("catches a final line that lacks a trailing newline", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume("e: file:///proj/Foo.kt:42:5 Unresolved reference: Modfier");
        // No newline before end() — flush must scan the residual buffer.
        d.end();
        const errors = d.getErrors();
        assert.strictEqual(errors.length, 1);
    });

    it("strips CR from CRLF-terminated lines", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume(
            "e: file:///proj/Foo.kt:42:5 Unresolved reference: Modfier\r\n",
        );
        d.end();
        const errors = d.getErrors();
        assert.strictEqual(errors.length, 1);
        // Trailing CR must not leak into the message — would print as a
        // mojibake glyph in the banner.
        assert.ok(!errors[0].message.endsWith("\r"));
    });

    it("skips lines that have e: somewhere but not at the start", () => {
        // A literal "see e: line" elsewhere in stdout shouldn't be
        // mistaken for a kotlin error. The ^ anchor enforces this.
        const d = new KotlinCompileErrorDetector();
        d.consume("see e: file:///proj/Foo.kt:42:5 nope\n");
        d.end();
        assert.deepStrictEqual(d.getErrors(), []);
    });

    it("caps captures at MAX_ERRORS so a runaway compile cannot grow the buffer", () => {
        const d = new KotlinCompileErrorDetector();
        const lines = [];
        for (let i = 0; i < 200; i++) {
            lines.push(`e: file:///proj/Foo.kt:${i}:1 oops`);
        }
        d.consume(lines.join("\n") + "\n");
        d.end();
        const errors = d.getErrors();
        assert.ok(
            errors.length <= 50,
            `should cap at MAX_ERRORS, got ${errors.length}`,
        );
    });

    it("extracts the basename from absolute Linux-style paths", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume("e: file:///long/abs/path/to/Previews.kt:1:1 oops\n");
        d.end();
        assert.strictEqual(d.getErrors()[0].file, "Previews.kt");
    });

    it("extracts the basename from Windows-style paths in a file URL", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume("e: file:///C:/work/Previews.kt:1:1 oops\n");
        d.end();
        assert.strictEqual(d.getErrors()[0].file, "Previews.kt");
    });

    it("extracts the basename from bare Windows paths", () => {
        const d = new KotlinCompileErrorDetector();
        d.consume("e: C:\\work\\Previews.kt:1:1 oops\n");
        d.end();
        assert.strictEqual(d.getErrors()[0].file, "Previews.kt");
    });
});

describe("KotlinCompileError", () => {
    it("carries the parsed errors plus the failing task", () => {
        const e = new KotlinCompileError(
            [
                {
                    file: "Foo.kt",
                    path: "/p/Foo.kt",
                    line: 1,
                    column: 1,
                    message: "x",
                },
            ],
            ":app:compileDebugKotlin",
        );
        assert.strictEqual(e.errors.length, 1);
        assert.strictEqual(e.task, ":app:compileDebugKotlin");
        assert.ok(e instanceof Error);
        assert.strictEqual(e.name, "KotlinCompileError");
    });
});
