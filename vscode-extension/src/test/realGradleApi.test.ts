import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    CANCELLATION_REPAIR_ARGS,
    RealGradleApi,
    hasEmptyClassOutputs,
    moduleDirForTask,
    parseTaskOutcomes,
} from "./electron/realGradleApi";

/**
 * Unit coverage for the e2e suite's own Gradle driver (issue #4101).
 *
 * The interactive shards fail on roughly half their runs, always as a timeout waiting for a render
 * that never appeared, and never twice in the same scenario. Two things make that expensive: the
 * message cannot say whether the render task ran, came back `FROM-CACHE`, or was cancelled, and the
 * suite cancels real builds by design — a cancelled compile leaves an empty class output directory
 * that the *next* build compiles nothing into and Gradle then caches.
 *
 * These tests drive `RealGradleApi` against a stub `gradlew` so both behaviours are pinned without
 * a real build: the shards themselves take tens of minutes and only reproduce the condition by
 * luck.
 */
describe("RealGradleApi task ledger", () => {
    let tmp: string;

    beforeEach(() => {
        tmp = fs.mkdtempSync(path.join(os.tmpdir(), "real-gradle-api-"));
    });

    afterEach(() => {
        fs.rmSync(tmp, { recursive: true, force: true });
    });

    /** A `gradlew` that prints the task lines a real build prints, then exits with `code`. */
    function stubGradlew(lines: string[], code = 0): void {
        const script = [
            "#!/bin/sh",
            ...lines.map((line) => `echo ${JSON.stringify(line)}`),
            `exit ${code}`,
        ].join("\n");
        const file = path.join(tmp, "gradlew");
        fs.writeFileSync(file, `${script}\n`, { mode: 0o755 });
    }

    it("parses task outcomes, spelling a bare task line EXECUTED", () => {
        const outcomes = parseTaskOutcomes(
            [
                "> Task :samples:cmp:compileKotlinJvm FROM-CACHE",
                "> Task :samples:cmp:composePreviewDiscover",
                "> Task :samples:cmp:composePreviewRender UP-TO-DATE",
                "not a task line",
            ].join("\n"),
        );
        assert.deepStrictEqual(outcomes, [
            { task: ":samples:cmp:compileKotlinJvm", outcome: "FROM-CACHE" },
            {
                task: ":samples:cmp:composePreviewDiscover",
                outcome: "EXECUTED",
            },
            {
                task: ":samples:cmp:composePreviewRender",
                outcome: "UP-TO-DATE",
            },
        ]);
    });

    it("records what each invocation asked for and how it ended", async () => {
        stubGradlew([
            "> Task :samples:cmp:compileKotlinJvm FROM-CACHE",
            "> Task :samples:cmp:composePreviewRender",
            "BUILD SUCCESSFUL in 3s",
        ]);
        const api = new RealGradleApi(tmp);
        await api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
        });

        const [record] = api.gradleInvocations;
        assert.strictEqual(record.task, ":samples:cmp:composePreviewRenderAll");
        assert.strictEqual(record.status, "succeeded");
        assert.deepStrictEqual(record.taskOutcomes, [
            { task: ":samples:cmp:compileKotlinJvm", outcome: "FROM-CACHE" },
            { task: ":samples:cmp:composePreviewRender", outcome: "EXECUTED" },
        ]);

        // The diagnostic a timed-out wait embeds has to answer "did the render run?" on sight.
        const summary = api.describeGradleActivity();
        assert.ok(
            summary.includes(":samples:cmp:composePreviewRender EXECUTED"),
            `render outcome missing from: ${summary}`,
        );
        assert.ok(
            summary.includes("compileKotlinJvm FROM-CACHE"),
            `compile outcome missing from: ${summary}`,
        );
    });

    it("says so when a build reached no compile or render task at all", async () => {
        stubGradlew(["Configuring project :samples:cmp", "BUILD SUCCESSFUL"]);
        const api = new RealGradleApi(tmp);
        await api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
        });
        assert.ok(
            api
                .describeGradleActivity()
                .includes("no compile/render task reached"),
            api.describeGradleActivity(),
        );
    });

    it("reads a task line that arrived split across two chunks", async () => {
        // `printf` without a trailing newline on the first half, so the parser has to carry it.
        const file = path.join(tmp, "gradlew");
        fs.writeFileSync(
            file,
            [
                "#!/bin/sh",
                'printf "> Task :samples:cmp:composePreview"',
                "sleep 0.1",
                'printf "Render FROM-CACHE\\n"',
                "exit 0",
            ].join("\n") + "\n",
            { mode: 0o755 },
        );
        const api = new RealGradleApi(tmp);
        await api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
        });
        assert.deepStrictEqual(api.gradleInvocations[0].taskOutcomes, [
            {
                task: ":samples:cmp:composePreviewRender",
                outcome: "FROM-CACHE",
            },
        ]);
    });
});

describe("RealGradleApi cancellation repair", () => {
    let tmp: string;

    beforeEach(() => {
        tmp = fs.mkdtempSync(path.join(os.tmpdir(), "real-gradle-repair-"));
        fs.writeFileSync(
            path.join(tmp, "gradlew"),
            '#!/bin/sh\necho "> Task :samples:cmp:composePreviewRender"\nexit 0\n',
            { mode: 0o755 },
        );
    });

    afterEach(() => {
        fs.rmSync(tmp, { recursive: true, force: true });
    });

    function classOutputDir(...leaf: string[]): string {
        const dir = path.join(
            tmp,
            "samples",
            "cmp",
            "build",
            "classes",
            ...leaf,
        );
        fs.mkdirSync(dir, { recursive: true });
        return dir;
    }

    it("maps a task path to its module directory", () => {
        assert.strictEqual(
            moduleDirForTask(":samples:cmp:composePreviewRenderAll"),
            path.join("samples", "cmp"),
        );
        assert.strictEqual(
            moduleDirForTask("composePreviewApplied"),
            undefined,
        );
        assert.strictEqual(moduleDirForTask(":help"), undefined);
    });

    it("calls a module with output roots but no class files empty", () => {
        const moduleDir = path.join("samples", "cmp");
        // Never built: no output roots, nothing to repair.
        assert.strictEqual(hasEmptyClassOutputs(tmp, moduleDir), false);

        const dir = classOutputDir("kotlin", "jvm", "main");
        assert.strictEqual(hasEmptyClassOutputs(tmp, moduleDir), true);

        fs.writeFileSync(path.join(dir, "PreviewsKt.class"), "");
        assert.strictEqual(hasEmptyClassOutputs(tmp, moduleDir), false);
    });

    it("reruns the next build for a module a cancellation left with no classes", async () => {
        classOutputDir("kotlin", "jvm", "main");
        const api = new RealGradleApi(tmp);

        const running = api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
            cancellationKey: "key-1",
        });
        await api.cancelRunTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            cancellationKey: "key-1",
        });
        await running.catch(() => {
            /* cancellation rejects by contract */
        });

        await api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
            cancellationKey: "key-2",
        });

        const second = api.gradleInvocations[1];
        assert.strictEqual(second.repaired, true);
        for (const arg of CANCELLATION_REPAIR_ARGS) {
            assert.ok(
                second.args.includes(arg),
                `expected ${arg} in ${second.args.join(" ")}`,
            );
        }
        assert.ok(api.describeGradleActivity().includes("repaired:"));

        // One repair per cancellation: the build that repaired it also proved the module healthy.
        await api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
            cancellationKey: "key-3",
        });
        assert.strictEqual(api.gradleInvocations[2].repaired, undefined);
    });

    it("leaves a healthy module's next build alone after a cancellation", async () => {
        const dir = classOutputDir("kotlin", "jvm", "main");
        fs.writeFileSync(path.join(dir, "PreviewsKt.class"), "");
        const api = new RealGradleApi(tmp);

        const running = api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
            cancellationKey: "key-1",
        });
        await api.cancelRunTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            cancellationKey: "key-1",
        });
        await running.catch(() => {
            /* expected */
        });

        await api.runTask({
            projectFolder: tmp,
            taskName: ":samples:cmp:composePreviewRenderAll",
            showOutputColors: false,
            cancellationKey: "key-2",
        });
        assert.strictEqual(api.gradleInvocations[1].repaired, undefined);
        assert.ok(
            !api.gradleInvocations[1].args.includes("--rerun-tasks"),
            api.gradleInvocations[1].args.join(" "),
        );
    });
});
