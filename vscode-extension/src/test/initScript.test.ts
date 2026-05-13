import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    BUNDLED_PLUGIN_VERSION,
    INIT_SCRIPT_FILENAME,
    initScriptDigest,
    materializeInitScript,
    renderInitScript,
} from "../initScript";

function withTempDir(
    fn: (dir: string) => void | Promise<void>,
): () => Promise<void> {
    return async () => {
        const dir = fs.mkdtempSync(
            path.join(os.tmpdir(), "compose-preview-test-"),
        );
        try {
            await fn(dir);
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    };
}

describe("renderInitScript", () => {
    it("bakes the pinned plugin version into the script", () => {
        const script = renderInitScript("9.9.9-test");
        assert.match(
            script,
            /val pluginVersion = "9\.9\.9-test"/,
            "expected the plugin version to be interpolated",
        );
        assert.match(
            script,
            /ee\.schimke\.composeai\.preview:ee\.schimke\.composeai\.preview\.gradle\.plugin:\$pluginVersion/,
            "expected the buildscript classpath coordinate to reference the version variable",
        );
    });

    it("falls back to BUNDLED_PLUGIN_VERSION when no argument is given", () => {
        const script = renderInitScript();
        assert.ok(
            script.includes(`val pluginVersion = "${BUNDLED_PLUGIN_VERSION}"`),
        );
    });

    it("applies the plugin via withPlugin on each injectable host id", () => {
        const script = renderInitScript();
        for (const id of [
            "com.android.application",
            "com.android.library",
            "org.jetbrains.compose",
        ]) {
            // Substring assertion — avoids the regex-escape bookkeeping
            // CodeQL flagged for an incomplete `.replace(/\./g, ...)` that
            // missed `\`, `*`, and friends. Plugin ids never carry regex
            // metachars beyond `.`, but the static analyzer can't see that.
            assert.ok(
                script.includes(
                    `pluginManager.withPlugin("${id}") { applyComposeAiPreview() }`,
                ),
                `expected withPlugin hook for ${id}`,
            );
        }
    });

    it("guards against double-apply with hasPlugin check", () => {
        const script = renderInitScript();
        assert.match(
            script,
            /if \(plugins\.hasPlugin\("ee\.schimke\.composeai\.preview"\)\) return/,
        );
    });

    it("uses pluginManager.withPlugin, NOT afterEvaluate (as a code construct)", () => {
        // AGP's `finalizeDsl` callbacks have to register before the DSL lock —
        // afterEvaluate runs after that lock and would skip preview registration
        // entirely. This is the same constraint the CI script documents.
        //
        // The header comment legitimately mentions afterEvaluate to explain
        // *why* we don't use it, so check for the call forms rather than the
        // bare word.
        const script = renderInitScript();
        assert.ok(
            !script.includes("afterEvaluate("),
            "init script must not call afterEvaluate(...)",
        );
        assert.ok(
            !script.includes("afterEvaluate {"),
            "init script must not use the afterEvaluate { ... } block form",
        );
    });
});

describe("materializeInitScript", () => {
    it(
        "writes the rendered script to <storageDir>/<filename> and returns the path",
        withTempDir((dir) => {
            const target = materializeInitScript(dir, "1.2.3");
            assert.strictEqual(
                target,
                path.join(dir, INIT_SCRIPT_FILENAME),
                "should return the absolute path inside storageDir",
            );
            const onDisk = fs.readFileSync(target, "utf-8");
            assert.strictEqual(onDisk, renderInitScript("1.2.3"));
        }),
    );

    it(
        "creates the storage directory if missing (recursive)",
        withTempDir((dir) => {
            const nested = path.join(dir, "globalStorage", "compose-preview");
            materializeInitScript(nested, "1.0.0");
            assert.ok(fs.statSync(nested).isDirectory());
            assert.ok(fs.existsSync(path.join(nested, INIT_SCRIPT_FILENAME)));
        }),
    );

    it(
        "is idempotent — re-running with the same version leaves the file untouched",
        withTempDir((dir) => {
            const first = materializeInitScript(dir, "1.0.0");
            const stat1 = fs.statSync(first);
            // Tick forward so a write would produce a different mtime.
            const future = new Date(stat1.mtimeMs + 5000);
            fs.utimesSync(first, future, future);
            const stat2BeforeRerun = fs.statSync(first);
            const second = materializeInitScript(dir, "1.0.0");
            assert.strictEqual(second, first);
            const stat3 = fs.statSync(first);
            assert.strictEqual(
                stat3.mtimeMs,
                stat2BeforeRerun.mtimeMs,
                "expected no rewrite when contents are unchanged",
            );
        }),
    );

    it(
        "rewrites when the plugin version changes",
        withTempDir((dir) => {
            materializeInitScript(dir, "1.0.0");
            const target = materializeInitScript(dir, "2.0.0");
            const onDisk = fs.readFileSync(target, "utf-8");
            assert.match(onDisk, /val pluginVersion = "2\.0\.0"/);
            assert.ok(!onDisk.includes('val pluginVersion = "1.0.0"'));
        }),
    );
});

describe("initScriptDigest", () => {
    it("is stable for the same plugin version", () => {
        assert.strictEqual(
            initScriptDigest("1.0.0"),
            initScriptDigest("1.0.0"),
        );
    });

    it("differs across plugin versions", () => {
        assert.notStrictEqual(
            initScriptDigest("1.0.0"),
            initScriptDigest("1.0.1"),
        );
    });

    it("returns a 16-char hex prefix", () => {
        const digest = initScriptDigest("1.0.0");
        assert.strictEqual(digest.length, 16);
        assert.match(digest, /^[0-9a-f]{16}$/);
    });
});
