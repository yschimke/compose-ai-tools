import * as assert from "assert";
import { EventEmitter, Readable, Writable } from "stream";
import { ChildProcess, SpawnOptions } from "child_process";
import { ContinuousCompileManager } from "../daemon/continuousCompileManager";
import { ContinuousCompileWorker } from "../daemon/continuousCompileWorker";
import { GradleService, ModuleInfo } from "../gradleService";

class FakeChild extends EventEmitter {
    stdout = new Readable({ read() {} });
    stderr = new Readable({ read() {} });
    stdin = new Writable({
        write(_c, _e, cb) {
            cb();
        },
    });
    pushStdout(s: string): void {
        this.stdout.push(s);
    }
    kill(): boolean {
        return true;
    }
}

function fakeSpawnRecorder(): {
    spawnFn: typeof import("child_process").spawn;
    calls: { command: string; args: readonly string[] }[];
    children: FakeChild[];
} {
    const calls: { command: string; args: readonly string[] }[] = [];
    const children: FakeChild[] = [];
    const spawnFn = ((
        command: string,
        args: readonly string[],
        _options?: SpawnOptions,
    ): ChildProcess => {
        calls.push({ command, args });
        const child = new FakeChild();
        children.push(child);
        return child as unknown as ChildProcess;
    }) as unknown as typeof import("child_process").spawn;
    return { spawnFn, calls, children };
}

function makeModule(modulePath: string): ModuleInfo {
    return {
        modulePath,
        projectDir: modulePath.replace(/^:/, "").replace(/:/g, "/"),
        plugins: ["com.android.application"],
        variant: "debug",
    } as ModuleInfo;
}

/**
 * Minimal GradleService double — captures `setContinuousCompileWorker` calls
 * so the manager test can assert the registration handshake without dragging
 * in the full GradleService dependency stack.
 */
class FakeGradleService {
    public readonly registered = new Map<
        string,
        ContinuousCompileWorker | null
    >();
    setContinuousCompileWorker(
        modulePath: string,
        worker: ContinuousCompileWorker | null,
    ): void {
        this.registered.set(modulePath, worker);
    }
}

function makeManager(): {
    manager: ContinuousCompileManager;
    gradleService: FakeGradleService;
    calls: { command: string; args: readonly string[] }[];
    children: FakeChild[];
} {
    const gradleService = new FakeGradleService();
    const { spawnFn, calls, children } = fakeSpawnRecorder();
    const manager = new ContinuousCompileManager(
        "/repo",
        gradleService as unknown as GradleService,
        { appendLine() {} },
        [],
        spawnFn,
    );
    return { manager, gradleService, calls, children };
}

describe("ContinuousCompileManager", () => {
    it("ensureWorker spawns gradlew once and registers the worker", () => {
        const { manager, gradleService, calls } = makeManager();
        manager.ensureWorker(makeModule(":samples:cmp"));
        assert.strictEqual(calls.length, 1);
        assert.deepStrictEqual(calls[0].args, [
            ":samples:cmp:composePreviewCompile",
            "--continuous",
            "--console=plain",
        ]);
        assert.deepStrictEqual(manager.activeModules(), [":samples:cmp"]);
        assert.notStrictEqual(
            gradleService.registered.get(":samples:cmp"),
            null,
        );
    });

    it("ensureWorker is idempotent", () => {
        const { manager, gradleService, calls } = makeManager();
        const module = makeModule(":samples:cmp");
        manager.ensureWorker(module);
        const first = gradleService.registered.get(":samples:cmp");
        manager.ensureWorker(module);
        const second = gradleService.registered.get(":samples:cmp");
        assert.strictEqual(first, second);
        assert.strictEqual(calls.length, 1);
        assert.deepStrictEqual(manager.activeModules(), [":samples:cmp"]);
    });

    it("disposeWorker unregisters and signals stdin EOF", async () => {
        const { manager, gradleService, children } = makeManager();
        manager.ensureWorker(makeModule(":samples:cmp"));
        // Fire-and-forget — `stop()` waits up to 5s for exit, so simulate one.
        const stopping = manager.disposeWorker(":samples:cmp");
        setTimeout(() => children[0].emit("exit", 0), 5);
        await stopping;
        assert.deepStrictEqual(manager.activeModules(), []);
        assert.strictEqual(gradleService.registered.get(":samples:cmp"), null);
    });

    it("disposeAll tears down every worker in parallel", async () => {
        const { manager, gradleService, children } = makeManager();
        manager.ensureWorker(makeModule(":samples:android"));
        manager.ensureWorker(makeModule(":samples:cmp"));
        assert.strictEqual(manager.activeModules().length, 2);
        const disposing = manager.disposeAll();
        setTimeout(() => children.forEach((c) => c.emit("exit", 0)), 5);
        await disposing;
        assert.strictEqual(manager.activeModules().length, 0);
        assert.strictEqual(
            gradleService.registered.get(":samples:android"),
            null,
        );
        assert.strictEqual(gradleService.registered.get(":samples:cmp"), null);
    });
});
