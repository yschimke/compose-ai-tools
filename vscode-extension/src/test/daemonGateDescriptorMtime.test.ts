import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { descriptorMtimeMs } from "../daemon/daemonGate";

/**
 * Pinned-down behaviour of the mtime-watch helper that drives `LiveDaemonGate.getOrSpawn`'s
 * descriptor-change respawn (the "fresh module first warm" companion to
 * `daemon-launch.json`'s `@Optional @InputFile previewsManifest` invalidation — see issue #1629's
 * follow-up). Keeps the actual `LiveDaemonGate` instantiation out of this test (it would pull in
 * `spawnDaemon`, a real `java` child process) by exercising the pure function directly.
 */
describe("descriptorMtimeMs (LiveDaemonGate respawn signal)", () => {
    let tmpDir: string;

    beforeEach(() => {
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "daemon-gate-mtime-"));
    });

    afterEach(() => {
        fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    function writeDescriptor(projectDir: string, body = "{}"): string {
        const dir = path.join(tmpDir, projectDir, "build", "compose-previews");
        fs.mkdirSync(dir, { recursive: true });
        const file = path.join(dir, "daemon-launch.json");
        fs.writeFileSync(file, body);
        return file;
    }

    it("returns null when daemon-launch.json is missing", () => {
        assert.strictEqual(
            descriptorMtimeMs(tmpDir, {
                projectDir: "missing",
                modulePath: ":missing",
            }),
            null,
        );
    });

    it("returns a numeric mtime once the file exists", () => {
        writeDescriptor("app");
        const mtime = descriptorMtimeMs(tmpDir, {
            projectDir: "app",
            modulePath: ":app",
        });
        assert.ok(
            mtime !== null && Number.isFinite(mtime) && mtime > 0,
            `expected a finite positive mtime, got ${mtime}`,
        );
    });

    it("advances after a Gradle-style rewrite (simulates composePreviewDaemonStart re-running because previewsManifest changed)", async () => {
        writeDescriptor("app", "v1");
        const before = descriptorMtimeMs(tmpDir, {
            projectDir: "app",
            modulePath: ":app",
        });
        assert.ok(before !== null);
        // Bump mtime by a known delta — file timestamps on some FSes only have second precision,
        // so a millisecond-scale `sleep + write` doesn't reliably advance them. `utimesSync` is
        // the deterministic substitute for the test; in production `composePreviewDaemonStart`
        // updates mtime via Gradle's own file rewrite.
        const file = path.join(
            tmpDir,
            "app",
            "build",
            "compose-previews",
            "daemon-launch.json",
        );
        fs.writeFileSync(file, "v2");
        fs.utimesSync(file, new Date(), new Date(before + 5_000));
        const after = descriptorMtimeMs(tmpDir, {
            projectDir: "app",
            modulePath: ":app",
        });
        assert.ok(after !== null);
        assert.ok(
            after > before,
            `expected post-rewrite mtime (${after}) > pre-rewrite mtime (${before})`,
        );
    });
});
