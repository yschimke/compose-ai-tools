import * as assert from 'assert';
import * as cp from 'child_process';
import { EventEmitter } from 'events';
import { PassThrough } from 'stream';
import {
    DaemonLaunchDescriptor,
    DaemonProcess,
    DaemonProcessError,
    EXPECTED_DESCRIPTOR_SCHEMA_VERSION,
} from '../../daemon/daemonProcess';

/**
 * Hand-rolled fake ChildProcess. Sinon isn't in the extension's deps —
 * `JsonRpcServerIntegrationTest`'s subprocess style with piped streams is
 * the existing in-tree pattern, so we mirror it here.
 *
 * The fake exposes `simulateExit(code)` so tests can drive the lifecycle
 * deterministically.
 */
class FakeChildProcess extends EventEmitter {
    public readonly stdin = new PassThrough();
    public readonly stdout = new PassThrough();
    public readonly stderr = new PassThrough();
    public exitCode: number | null = null;
    public signalCode: NodeJS.Signals | null = null;
    public killed = false;
    public lastSignal: NodeJS.Signals | undefined;

    kill(signal?: NodeJS.Signals): boolean {
        this.killed = true;
        this.lastSignal = signal;
        // Don't auto-emit exit; tests drive it explicitly so they can assert
        // on the SIGTERM-then-wait path.
        return true;
    }

    simulateExit(code: number, signal: NodeJS.Signals | null = null): void {
        this.exitCode = code;
        this.signalCode = signal;
        this.emit('exit', code, signal);
    }
}

interface CapturedSpawn {
    command: string;
    args: readonly string[];
    cwd: string | undefined;
}

function makeDescriptor(overrides: Partial<DaemonLaunchDescriptor> = {}): DaemonLaunchDescriptor {
    return {
        schemaVersion: EXPECTED_DESCRIPTOR_SCHEMA_VERSION,
        modulePath: ':samples:android',
        variant: 'debug',
        enabled: true,
        mainClass: 'ee.schimke.composeai.daemon.DaemonMainKt',
        javaLauncher: '/opt/jdk/bin/java',
        classpath: ['/cp/a.jar', '/cp/b.jar'],
        jvmArgs: ['-Xmx1024m', '--add-opens=java.base/java.lang=ALL-UNNAMED'],
        systemProperties: { 'composeai.daemon.idleTimeoutMs': '5000' },
        workingDirectory: '/ws/samples/android',
        manifestPath: '/ws/samples/android/build/compose-previews/previews.json',
        ...overrides,
    };
}

function setup(opts: {
    descriptor?: DaemonLaunchDescriptor | null;
    descriptorThrows?: Error;
    rebootstrap?: () => Promise<void>;
    idleTimeoutMs?: number;
} = {}) {
    const captured: CapturedSpawn[] = [];
    const fakes: FakeChildProcess[] = [];
    const spawn = (
        command: string,
        args: readonly string[],
        options: cp.SpawnOptions,
    ) => {
        const cwd = typeof options.cwd === 'string' ? options.cwd : undefined;
        captured.push({ command, args: [...args], cwd });
        const fake = new FakeChildProcess();
        fakes.push(fake);
        return fake as unknown as cp.ChildProcess;
    };
    const readFile = () => {
        if (opts.descriptorThrows) {
            throw opts.descriptorThrows;
        }
        if (opts.descriptor === null) {
            throw new Error('ENOENT');
        }
        return JSON.stringify(opts.descriptor ?? makeDescriptor());
    };
    const rebootstrapCalls: string[] = [];
    const rebootstrap = opts.rebootstrap ?? (async (modulePath: string) => {
        rebootstrapCalls.push(modulePath);
    });
    const proc = new DaemonProcess({
        workspaceRoot: '/ws',
        modulePath: ':samples:android',
        rebootstrap: async (m) => {
            rebootstrapCalls.push(m);
            return rebootstrap(m);
        },
        idleTimeoutMs: opts.idleTimeoutMs ?? 0, // 0 = no idle timer in tests
        spawn,
        readFile,
    });
    return { proc, captured, fakes, rebootstrapCalls };
}

describe('DaemonProcess', () => {

    describe('descriptor loading', () => {
        it('rejects a missing descriptor with descriptor-missing', () => {
            const { proc } = setup({ descriptor: null });
            try {
                proc.start();
                assert.fail('expected start() to throw');
            } catch (e) {
                assert.ok(e instanceof DaemonProcessError);
                assert.strictEqual(e.kind, 'descriptor-missing');
            }
        });

        it('rejects a malformed descriptor', () => {
            const { proc } = setup({});
            // Re-construct with a readFile that returns invalid JSON.
            const proc2 = new DaemonProcess({
                workspaceRoot: '/ws',
                modulePath: ':samples:android',
                rebootstrap: async () => { /* */ },
                idleTimeoutMs: 0,
                spawn: () => { throw new Error('should not spawn'); },
                readFile: () => 'not json {',
            });
            try {
                proc2.start();
                assert.fail('expected start() to throw');
            } catch (e) {
                assert.ok(e instanceof DaemonProcessError);
                assert.strictEqual(e.kind, 'descriptor-malformed');
            }
            // Suppress unused var warning.
            void proc;
        });

        it('rejects a schema-mismatched descriptor', () => {
            const { proc } = setup({
                descriptor: makeDescriptor({ schemaVersion: 99 }),
            });
            try {
                proc.start();
                assert.fail('expected start() to throw');
            } catch (e) {
                assert.ok(e instanceof DaemonProcessError);
                assert.strictEqual(e.kind, 'descriptor-schema-mismatch');
            }
        });

        it('rejects a disabled descriptor', () => {
            const { proc } = setup({
                descriptor: makeDescriptor({ enabled: false }),
            });
            try {
                proc.start();
                assert.fail('expected start() to throw');
            } catch (e) {
                assert.ok(e instanceof DaemonProcessError);
                assert.strictEqual(e.kind, 'descriptor-disabled');
            }
        });
    });

    describe('spawn arguments', () => {
        it('passes javaLauncher, JVM args, system properties, classpath, mainClass', () => {
            const desc = makeDescriptor({
                jvmArgs: ['-Xmx2048m'],
                systemProperties: { 'foo.bar': 'baz', 'composeai.x': 'y' },
                classpath: ['/a.jar', '/b.jar', '/c.jar'],
                mainClass: 'com.example.Main',
            });
            const { proc, captured } = setup({ descriptor: desc });
            proc.start();
            assert.strictEqual(captured.length, 1);
            assert.strictEqual(captured[0].command, '/opt/jdk/bin/java');
            assert.strictEqual(captured[0].cwd, '/ws/samples/android');
            const args = captured[0].args;
            // JVM args precede -D system properties precede -cp + main class.
            assert.deepStrictEqual(args.slice(0, 1), ['-Xmx2048m']);
            assert.ok(args.includes('-Dfoo.bar=baz'));
            assert.ok(args.includes('-Dcomposeai.x=y'));
            const cpIdx = args.indexOf('-cp');
            assert.ok(cpIdx > 0, '-cp must be present');
            // Path separator joins classpath entries.
            const sep = process.platform === 'win32' ? ';' : ':';
            assert.strictEqual(args[cpIdx + 1], `/a.jar${sep}/b.jar${sep}/c.jar`);
            assert.strictEqual(args[args.length - 1], 'com.example.Main');
        });

        it('falls back to PATH java when descriptor.javaLauncher is null', () => {
            const desc = makeDescriptor({ javaLauncher: null });
            const { proc, captured } = setup({ descriptor: desc });
            proc.start();
            assert.strictEqual(captured[0].command, 'java');
        });
    });

    describe('lifecycle', () => {
        it('start moves to running, exit moves to crashed', () => {
            const { proc, fakes } = setup({});
            proc.start();
            assert.strictEqual(proc.currentState, 'running');
            assert.strictEqual(proc.isHealthy(), true);
            fakes[0].simulateExit(137);
            assert.strictEqual(proc.currentState, 'crashed');
            assert.strictEqual(proc.isHealthy(), false);
            assert.strictEqual(proc.lastExitCode, 137);
        });

        it('dispose closes stdin and waits for natural exit', async () => {
            const { proc, fakes } = setup({});
            proc.start();
            const fake = fakes[0];
            // Fake exits cleanly when stdin closes (mirrors PROTOCOL.md § 3
            // idle-timeout exit path).
            fake.stdin.on('finish', () => { fake.simulateExit(0); });
            await proc.dispose(2_000);
            assert.strictEqual(proc.currentState, 'disposed');
            assert.strictEqual(fake.killed, false, 'should not have SIGTERM-ed');
        });

        it('dispose escalates to SIGTERM then SIGKILL on stubborn child', async () => {
            const { proc, fakes } = setup({});
            proc.start();
            const fake = fakes[0];
            // Don't auto-exit on stdin close; force the timeout path.
            const dispose = proc.dispose(50);
            // Wait briefly for SIGTERM, then simulate exit.
            setTimeout(() => fake.simulateExit(143, 'SIGTERM'), 75);
            await dispose;
            assert.strictEqual(proc.currentState, 'disposed');
            assert.strictEqual(fake.killed, true);
            assert.strictEqual(fake.lastSignal, 'SIGTERM');
        });

        it('disposed instance refuses subsequent start()', async () => {
            const { proc, fakes } = setup({});
            proc.start();
            fakes[0].stdin.on('finish', () => fakes[0].simulateExit(0));
            await proc.dispose(2_000);
            try {
                proc.start();
                assert.fail('expected start() to throw');
            } catch (e) {
                assert.ok(e instanceof DaemonProcessError);
                assert.strictEqual(e.kind, 'spawn-failed');
            }
        });
    });

    describe('restart', () => {
        it('restarts on simulated classpathDirty notification', async () => {
            const rebootstrapCalls: string[] = [];
            const { proc, fakes, captured } = setup({
                rebootstrap: async () => {
                    rebootstrapCalls.push('called');
                },
            });
            proc.start();
            assert.strictEqual(captured.length, 1);

            // Simulate classpathDirty by routing the notification through the
            // helper, then exiting (matches PROTOCOL.md § 6 — daemon emits
            // classpathDirty then exits within classpathDirtyGraceMs).
            proc.onNotification({
                jsonrpc: '2.0',
                method: 'classpathDirty',
                params: { reason: 'fingerprintMismatch', detail: 'libs.versions.toml' },
            });
            // The first child needs to exit to allow the restart to proceed.
            fakes[0].simulateExit(0);
            // Wait for the restart pipeline to finish — the onNotification
            // handler dispatches asynchronously.
            await new Promise((r) => setTimeout(r, 50));
            assert.strictEqual(captured.length, 2, 'should have spawned twice');
            assert.strictEqual(proc.currentState, 'running');
        });

        it('rebootstrap failure surfaces as restart-failed', async () => {
            const { proc, fakes } = setup({
                rebootstrap: async () => {
                    throw new Error('gradle failed');
                },
            });
            proc.start();
            fakes[0].simulateExit(0);
            try {
                await proc.restart();
                assert.fail('expected restart to throw');
            } catch (e) {
                assert.ok(e instanceof DaemonProcessError);
                assert.strictEqual(e.kind, 'restart-failed');
            }
        });
    });

    describe('idle timer', () => {
        it('disposes after the idle timeout when bumpActivity is not called', async () => {
            const { proc, fakes } = setup({ idleTimeoutMs: 30 });
            proc.start();
            fakes[0].stdin.on('finish', () => fakes[0].simulateExit(0));
            await new Promise((r) => setTimeout(r, 100));
            assert.strictEqual(proc.currentState, 'disposed');
        });

        it('bumpActivity defers the idle timeout', async () => {
            const { proc, fakes } = setup({ idleTimeoutMs: 30 });
            proc.start();
            fakes[0].stdin.on('finish', () => fakes[0].simulateExit(0));
            // Bump every 10ms for 80ms — the idle timer should never fire.
            for (let i = 0; i < 8; i++) {
                await new Promise((r) => setTimeout(r, 10));
                proc.bumpActivity();
            }
            assert.strictEqual(proc.currentState, 'running');
            await proc.dispose(200);
        });
    });
});
