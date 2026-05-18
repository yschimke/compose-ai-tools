import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    ChunkLineSplitter,
    readLaunchDescriptor,
} from "../daemon/daemonProcess";
import {
    DAEMON_DESCRIPTOR_SCHEMA_VERSION,
    DaemonLaunchDescriptor,
} from "../daemon/daemonProtocol";

function withTempWorkspace<T>(
    fn: (workspaceRoot: string) => T | Promise<T>,
): () => Promise<T> {
    return async () => {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), "daemon-launch-"));
        try {
            return await fn(dir);
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    };
}

function writeDescriptor(
    workspaceRoot: string,
    moduleId: string,
    descriptor: object,
): string {
    const dir = path.join(workspaceRoot, moduleId, "build", "compose-previews");
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, "daemon-launch.json");
    fs.writeFileSync(file, JSON.stringify(descriptor));
    return file;
}

function validDescriptor(): DaemonLaunchDescriptor {
    return {
        schemaVersion: DAEMON_DESCRIPTOR_SCHEMA_VERSION,
        modulePath: ":samples:android",
        variant: "debug",
        enabled: true,
        mainClass: "ee.schimke.composeai.daemon.DaemonMain",
        javaLauncher: "/opt/jdk/bin/java",
        classpath: ["/lib/a.jar"],
        jvmArgs: ["-Xmx1024m"],
        systemProperties: {},
        workingDirectory: "/work",
        manifestPath: "/work/build/compose-previews/previews.json",
    };
}

describe("readLaunchDescriptor", () => {
    it(
        "returns null when the descriptor file does not exist",
        withTempWorkspace((dir) => {
            const result = readLaunchDescriptor(dir, {
                projectDir: "samples/android",
                modulePath: ":samples:android",
            });
            assert.strictEqual(result, null);
        }),
    );

    it(
        "parses a valid descriptor",
        withTempWorkspace((dir) => {
            const descriptor = validDescriptor();
            writeDescriptor(dir, "samples/android", descriptor);
            const result = readLaunchDescriptor(dir, {
                projectDir: "samples/android",
                modulePath: ":samples:android",
            });
            assert.notStrictEqual(result, null);
            assert.strictEqual(result!.modulePath, ":samples:android");
            assert.strictEqual(result!.enabled, true);
            assert.deepStrictEqual(result!.classpath, ["/lib/a.jar"]);
        }),
    );

    it(
        "returns null on schema-version mismatch and logs the reason",
        withTempWorkspace((dir) => {
            const logs: string[] = [];
            const descriptor = { ...validDescriptor(), schemaVersion: 999 };
            writeDescriptor(dir, "samples/android", descriptor);
            const result = readLaunchDescriptor(
                dir,
                {
                    projectDir: "samples/android",
                    modulePath: ":samples:android",
                },
                { appendLine: (s) => logs.push(s) },
            );
            assert.strictEqual(result, null);
            assert.ok(
                logs.some((l) => l.includes("schema mismatch")),
                `expected schema mismatch log, got: ${logs.join(" / ")}`,
            );
        }),
    );

    it(
        "returns null on malformed JSON",
        withTempWorkspace((dir) => {
            const logs: string[] = [];
            const descriptorDir = path.join(
                dir,
                "samples/android",
                "build",
                "compose-previews",
            );
            fs.mkdirSync(descriptorDir, { recursive: true });
            fs.writeFileSync(
                path.join(descriptorDir, "daemon-launch.json"),
                "{ not json",
            );
            const result = readLaunchDescriptor(
                dir,
                {
                    projectDir: "samples/android",
                    modulePath: ":samples:android",
                },
                { appendLine: (s) => logs.push(s) },
            );
            assert.strictEqual(result, null);
            assert.ok(
                logs.some((l) => l.includes("failed to read")),
                `expected parse failure log, got: ${logs.join(" / ")}`,
            );
        }),
    );

    it(
        "preserves an explicit enabled=false flag without mutating it",
        withTempWorkspace((dir) => {
            // The build can opt out via composePreview { daemon { enabled = false } }.
            // Reader must return the descriptor honestly; the gate decides what to do with it.
            const descriptor = { ...validDescriptor(), enabled: false };
            writeDescriptor(dir, "samples/android", descriptor);
            const result = readLaunchDescriptor(dir, {
                projectDir: "samples/android",
                modulePath: ":samples:android",
            });
            assert.strictEqual(result?.enabled, false);
        }),
    );

    it(
        "preserves a null javaLauncher (toolchain absent)",
        withTempWorkspace((dir) => {
            const descriptor = { ...validDescriptor(), javaLauncher: null };
            writeDescriptor(dir, "samples/android", descriptor);
            const result = readLaunchDescriptor(dir, {
                projectDir: "samples/android",
                modulePath: ":samples:android",
            });
            assert.strictEqual(result?.javaLauncher, null);
        }),
    );
});

describe("ChunkLineSplitter", () => {
    it("emits whole lines from a single chunk", () => {
        const s = new ChunkLineSplitter();
        assert.deepStrictEqual(s.feed("alpha\nbeta\ngamma\n"), [
            "alpha",
            "beta",
            "gamma",
        ]);
        assert.strictEqual(s.flush(), null);
    });

    it("holds a trailing fragment until the next chunk completes it", () => {
        // Reproduces the JVM uncaught-exception bug: the JVM does
        // `System.err.print("Exception in thread \"main\" ")` and *then* calls
        // `printStackTrace`. When a pipe boundary lands between those two writes
        // the prefix arrived without `\n`; the previous naive splitter emitted
        // it as a complete line, and the quiet-level filter dropped the
        // `java.lang.IllegalArgumentException: ...` continuation that arrived
        // in the next chunk.
        const s = new ChunkLineSplitter();
        assert.deepStrictEqual(s.feed('Exception in thread "main" '), []);
        assert.deepStrictEqual(
            s.feed(
                "java.lang.IllegalArgumentException: PreviewManifestRouter: " +
                    "manifest '/x' does not exist\n\tat A.b(File.kt:1)\n",
            ),
            [
                'Exception in thread "main" java.lang.IllegalArgumentException: ' +
                    "PreviewManifestRouter: manifest '/x' does not exist",
                "\tat A.b(File.kt:1)",
            ],
        );
        assert.strictEqual(s.flush(), null);
    });

    it("handles CRLF and bare LF line endings", () => {
        const s = new ChunkLineSplitter();
        assert.deepStrictEqual(s.feed("a\r\nb\nc\r\n"), ["a", "b", "c"]);
        assert.strictEqual(s.flush(), null);
    });

    it("splits across arbitrary mid-line chunk boundaries", () => {
        const s = new ChunkLineSplitter();
        const full = "alpha\nbeta\ngamma";
        const all: string[] = [];
        for (const char of full) {
            for (const line of s.feed(char)) {
                all.push(line);
            }
        }
        const tail = s.flush();
        if (tail !== null) {
            all.push(tail);
        }
        assert.deepStrictEqual(all, ["alpha", "beta", "gamma"]);
    });

    it("emits empty lines between blank-line separated paragraphs", () => {
        const s = new ChunkLineSplitter();
        assert.deepStrictEqual(s.feed("a\n\nb\n"), ["a", "", "b"]);
    });

    it("flushes a final fragment with no trailing newline", () => {
        const s = new ChunkLineSplitter();
        assert.deepStrictEqual(s.feed("partial"), []);
        assert.strictEqual(s.flush(), "partial");
        assert.strictEqual(s.flush(), null);
    });
});
