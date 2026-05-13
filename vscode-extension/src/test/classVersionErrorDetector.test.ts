import * as assert from "assert";
import { ClassVersionErrorDetector } from "../classVersionErrorDetector";

// Real failure copy-pasted from the Output > Compose Preview channel — the
// Gradle daemon on Java 21 tried to load build-logic compiled with Java 25.
const FIXTURE_LINE =
    "java.lang.UnsupportedClassVersionError: ee/schimke/composeai/buildlogic/ComposeAiJvmConventionsPlugin " +
    "has been compiled by a more recent version of the Java Runtime (class file version 69.0), " +
    "this version of the Java Runtime only recognizes class file versions up to 65.0";

describe("ClassVersionErrorDetector", () => {
    it("extracts class name + class file versions + Java versions", () => {
        const d = new ClassVersionErrorDetector();
        d.consume(FIXTURE_LINE + "\n");
        const f = d.getFinding();
        assert.notStrictEqual(f, null);
        assert.strictEqual(
            f!.className,
            "ee/schimke/composeai/buildlogic/ComposeAiJvmConventionsPlugin",
        );
        assert.strictEqual(f!.compiledClassVersion, 69);
        assert.strictEqual(f!.runtimeMaxClassVersion, 65);
        // 69 - 44 = 25 (Java 25), 65 - 44 = 21 (Java 21).
        assert.strictEqual(f!.compiledJavaVersion, 25);
        assert.strictEqual(f!.runtimeJavaVersion, 21);
    });

    it("survives chunks that split mid-line", () => {
        const d = new ClassVersionErrorDetector();
        const mid = Math.floor(FIXTURE_LINE.length / 2);
        d.consume(FIXTURE_LINE.slice(0, mid));
        assert.strictEqual(d.getFinding(), null);
        d.consume(FIXTURE_LINE.slice(mid) + "\n");
        assert.notStrictEqual(d.getFinding(), null);
    });

    it("catches the line at end-of-stream without a trailing newline", () => {
        const d = new ClassVersionErrorDetector();
        d.consume(FIXTURE_LINE);
        assert.strictEqual(d.getFinding(), null);
        d.end();
        assert.notStrictEqual(d.getFinding(), null);
    });

    it("returns null when the output is unrelated", () => {
        const d = new ClassVersionErrorDetector();
        d.consume(
            "FAILURE: Build failed with an exception.\n> Could not resolve all files.\n",
        );
        d.end();
        assert.strictEqual(d.getFinding(), null);
    });

    it("is idempotent after the first match", () => {
        const d = new ClassVersionErrorDetector();
        d.consume(FIXTURE_LINE + "\n");
        const first = d.getFinding();
        d.consume(
            "java.lang.UnsupportedClassVersionError: pkg/Other has been compiled by a more recent " +
                "version of the Java Runtime (class file version 70.0), this version of the Java " +
                "Runtime only recognizes class file versions up to 65.0\n",
        );
        const second = d.getFinding();
        assert.strictEqual(second!.className, first!.className);
        assert.strictEqual(second!.compiledClassVersion, 69);
    });
});
