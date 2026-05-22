import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    ChunkLineSplitter,
    formatDaemonSpawnFailure,
    readLaunchDescriptor,
    spawnDaemon,
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

describe("formatDaemonSpawnFailure (issue #1326)", () => {
    it("returns just the cause when stderr and exit code are absent", () => {
        // Channel close before the JVM exit event fires and with no stderr
        // chunks buffered: degrade gracefully to the bare cause so the message
        // matches the legacy shape the rest of the gate-error wrapping
        // assumes.
        assert.strictEqual(
            formatDaemonSpawnFailure({
                cause: "Daemon channel closed",
                modulePath: ":samples:wear",
                exitCode: null,
                recentStderr: [],
            }),
            "Daemon channel closed",
        );
    });

    it("appends the exit code when the JVM exit event has fired", () => {
        assert.strictEqual(
            formatDaemonSpawnFailure({
                cause: "Daemon channel closed",
                modulePath: ":samples:wear",
                exitCode: 1,
                recentStderr: [],
            }),
            "Daemon channel closed (JVM exit code=1)",
        );
    });

    it("appends the stderr tail and a module-scoped header", () => {
        const out = formatDaemonSpawnFailure({
            cause: "Daemon channel closed",
            modulePath: ":samples:wear",
            exitCode: 1,
            recentStderr: [
                'Exception in thread "main" java.lang.NoClassDefFoundError: Foo',
                "\tat ee.schimke.composeai.daemon.DaemonMain.main(DaemonMain.kt:42)",
            ],
        });
        assert.deepStrictEqual(out.split("\n"), [
            "Daemon channel closed (JVM exit code=1)",
            "[daemon stderr tail for :samples:wear]",
            'Exception in thread "main" java.lang.NoClassDefFoundError: Foo',
            "\tat ee.schimke.composeai.daemon.DaemonMain.main(DaemonMain.kt:42)",
        ]);
    });

    it("surfaces stderr + exit code through spawnDaemon when the JVM dies before initialize", async () => {
        // End-to-end check that the diagnostic actually reaches the rejected
        // promise. Stand in a tiny Node script for the daemon JVM: it
        // simulates a JVM that prints a Robolectric / Compose-style boot
        // crash to stderr and exits before answering `initialize`. The fix
        // for issue #1326 must wrap the resulting "Daemon channel closed"
        // with the stderr tail and exit code; without the wrapping the test
        // runner would see only the bare phrase, hiding the actual cause.
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), "daemon-spawn-"));
        try {
            const scriptPath = path.join(dir, "fake-daemon.mjs");
            fs.writeFileSync(
                scriptPath,
                [
                    "process.stderr.write(",
                    "  'Exception in thread \"main\" java.lang.IllegalStateException: boom\\n'",
                    ");",
                    "process.stderr.write(",
                    "  '\\tat ee.schimke.composeai.daemon.DaemonMain.main(DaemonMain.kt:42)\\n'",
                    ");",
                    "process.exit(2);",
                ].join("\n"),
            );
            const descriptor: DaemonLaunchDescriptor = {
                schemaVersion: DAEMON_DESCRIPTOR_SCHEMA_VERSION,
                modulePath: ":samples:wear",
                variant: "debug",
                enabled: true,
                mainClass: "ignored",
                // Borrow the active Node binary as the fake JVM and pass the
                // script via systemProperties → -D entries, which `spawnDaemon`
                // forwards verbatim. Node ignores -D flags, so we instead use
                // jvmArgs to inject the script path before -cp, which Node
                // accepts as its own first positional arg.
                javaLauncher: process.execPath,
                classpath: ["ignored"],
                jvmArgs: [scriptPath],
                systemProperties: {},
                workingDirectory: dir,
                manifestPath: path.join(dir, "previews.json"),
            };
            let err: Error | null = null;
            try {
                await spawnDaemon({
                    workspaceRoot: dir,
                    descriptor,
                    clientVersion: "test",
                    events: {},
                });
            } catch (e) {
                err = e as Error;
            }
            assert.ok(err, "spawnDaemon must reject when the JVM dies early");
            const message = err!.message;
            assert.ok(
                message.includes("[daemon stderr tail for :samples:wear]"),
                `expected stderr-tail header in message, got: ${message}`,
            );
            assert.ok(
                message.includes("java.lang.IllegalStateException: boom"),
                `expected JVM exception text in message, got: ${message}`,
            );
            assert.ok(
                message.includes("JVM exit code=2"),
                `expected exit-code suffix in message, got: ${message}`,
            );
        } finally {
            fs.rmSync(dir, { recursive: true });
        }
    });

    it("caps the stderr tail at the documented limit", () => {
        // A real Robolectric bootstrap can dump hundreds of informational
        // lines before the crash; only the last ~50 carry signal. Pin the
        // cap so a regression that prints every line back to the test runner
        // is loud.
        const lines = Array.from({ length: 200 }, (_, i) => `line-${i}`);
        const out = formatDaemonSpawnFailure({
            cause: "boom",
            modulePath: ":samples:wear",
            exitCode: null,
            recentStderr: lines,
        });
        const split = out.split("\n");
        // 1 cause line + 1 header line + 50 tail lines = 52
        assert.strictEqual(split.length, 52);
        assert.strictEqual(split[0], "boom");
        assert.strictEqual(split[1], "[daemon stderr tail for :samples:wear]");
        assert.strictEqual(split[2], "line-150");
        assert.strictEqual(split[51], "line-199");
    });
});
