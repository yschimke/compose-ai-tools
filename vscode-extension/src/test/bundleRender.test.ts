// Coverage for `parseRenderOutput` — the CLI summary parser the
// `BundleViewerPanel` relies on to surface per-preview render outcomes.
// We don't drive the real CLI here (that's a JVM spawn); locating /
// spawning the binary is covered by integration in the cloud env.

import * as assert from "assert";
import * as path from "path";
import { parseRenderOutput } from "../bundleRenderOutput";

describe("parseRenderOutput", () => {
    it("parses a successful render summary with relative filenames", () => {
        const stdout =
            "rendered 2 / 2 preview(s) → /tmp/out\n" +
            "  ok    com.example.Foo.Bar  →  com.example.Foo.Bar.png\n" +
            "  ok    com.example.Foo.Baz  →  com.example.Foo.Baz.png\n";
        const result = parseRenderOutput(stdout, "/tmp/out");
        assert.ok(result);
        assert.strictEqual(result!.previewCount, 2);
        assert.deepStrictEqual(
            result!.succeeded.map((s) => s.id),
            ["com.example.Foo.Bar", "com.example.Foo.Baz"],
        );
        assert.strictEqual(
            result!.succeeded[0].outputFile,
            path.join("/tmp/out", "com.example.Foo.Bar.png"),
        );
        assert.strictEqual(result!.failed.length, 0);
    });

    it("records failed previews with their exit code", () => {
        const stdout =
            "rendered 1 / 2 preview(s) → /tmp/out\n" +
            "  ok    com.example.Foo.Bar  →  com.example.Foo.Bar.png\n" +
            "  FAIL  com.example.Foo.Baz  (exit=137)\n";
        const result = parseRenderOutput(stdout, "/tmp/out");
        assert.ok(result);
        assert.strictEqual(result!.previewCount, 2);
        assert.strictEqual(result!.succeeded.length, 1);
        assert.deepStrictEqual(result!.failed, [
            { id: "com.example.Foo.Baz", exitCode: 137 },
        ]);
    });

    it("returns null when no summary line is present (launcher crashed before reporting)", () => {
        const stdout = "compose-preview: failed to read bundle\n";
        assert.strictEqual(parseRenderOutput(stdout, "/tmp/out"), null);
    });

    it("treats summary as 0/0 valid when previewCount is zero", () => {
        const stdout = "rendered 0 / 0 preview(s) → /tmp/out\n";
        const result = parseRenderOutput(stdout, "/tmp/out");
        assert.ok(result);
        assert.strictEqual(result!.previewCount, 0);
        assert.strictEqual(result!.succeeded.length, 0);
        assert.strictEqual(result!.failed.length, 0);
    });

    it("keeps absolute paths verbatim when the CLI emits them", () => {
        // The CLI today emits basenames, but `BundleRenderer.kt` could
        // grow to emit absolute paths; the parser shouldn't double-root
        // those by re-joining with `outputDir`.
        const stdout =
            "rendered 1 / 1 preview(s) → /tmp/out\n" +
            "  ok    com.example.Foo.Bar  →  /custom/path/foo.png\n";
        const result = parseRenderOutput(stdout, "/tmp/out");
        assert.strictEqual(
            result!.succeeded[0].outputFile,
            "/custom/path/foo.png",
        );
    });

    it("ignores extra prose between summary and the listing", () => {
        const stdout =
            "compose-preview bundle render — desktop backend\n" +
            "rendered 1 / 1 preview(s) → /tmp/out\n" +
            "\n" +
            "  ok    com.example.Foo.Bar  →  com.example.Foo.Bar.png\n" +
            "Done in 4.2s\n";
        const result = parseRenderOutput(stdout, "/tmp/out");
        assert.strictEqual(result!.succeeded.length, 1);
    });
});
