import * as assert from "assert";
import { DaemonGate, GradleOnlyDaemonGate } from "../daemon/daemonGate";
import {
    DaemonScheduler,
    GradleOnlyDaemonScheduler,
} from "../daemon/daemonScheduler";

// Both no-op classes are structurally typed to the interfaces; this assignment
// fails at compile-time if either drifts. Kept as live `it` blocks too so a
// runtime regression surfaces in mocha output.
const _gate: DaemonGate = new GradleOnlyDaemonGate();
const _scheduler: DaemonScheduler = new GradleOnlyDaemonScheduler();
// Suppress "declared but not used" without changing emit semantics.
void _gate;
void _scheduler;

const FAKE_MODULE = { projectDir: "app", modulePath: ":app" };
const FAKE_EVENTS = {};

describe("GradleOnlyDaemonGate", () => {
    it("never claims daemons are spawning", () => {
        const gate = new GradleOnlyDaemonGate();
        assert.strictEqual(gate.spawnsDaemons, false);
    });

    it("never returns a daemon client from getOrSpawn", async () => {
        const gate = new GradleOnlyDaemonGate();
        const client = await gate.getOrSpawn(FAKE_MODULE, FAKE_EVENTS);
        assert.strictEqual(client, null);
    });

    it("reports every module as build-disabled so callers fall back silently", () => {
        const gate = new GradleOnlyDaemonGate();
        // The disabled vs. failed distinction in [notifyDaemonOfSave] hinges
        // on isBuildDisabled — disabled triggers silent Gradle fallback, failed
        // surfaces an error popup. Minimal mode must look like a clean opt-out.
        assert.strictEqual(gate.isBuildDisabled(FAKE_MODULE), true);
    });

    it("reports no daemon as ready, regardless of module", () => {
        const gate = new GradleOnlyDaemonGate();
        assert.strictEqual(gate.isDaemonReady(":app"), false);
        assert.strictEqual(gate.isDaemonReady(":anything"), false);
    });

    it("reports interactive mode as unsupported", () => {
        const gate = new GradleOnlyDaemonGate();
        assert.strictEqual(gate.isInteractiveSupported(":app"), false);
    });

    it("returns null for getCapabilitiesSnapshot", () => {
        // The panel's focus-mode inspector falls back to its placeholder set
        // when this is null — exactly what we want for minimal mode.
        const gate = new GradleOnlyDaemonGate();
        assert.strictEqual(gate.getCapabilitiesSnapshot(":app"), null);
    });

    it("restartAll resolves with an empty list (nothing to restart)", async () => {
        const gate = new GradleOnlyDaemonGate();
        assert.deepStrictEqual(await gate.restartAll(), []);
    });

    it("dispose resolves cleanly", async () => {
        const gate = new GradleOnlyDaemonGate();
        await gate.dispose();
    });

    it("bootstrap resolves cleanly without invoking the gradle service", async () => {
        const gate = new GradleOnlyDaemonGate();
        let touched = false;
        const fakeGradle = {
            // Should never be called.
            runDaemonBootstrap: () => {
                touched = true;
                return Promise.resolve();
            },
        } as never;
        await gate.bootstrap(fakeGradle, FAKE_MODULE);
        assert.strictEqual(
            touched,
            false,
            "minimal-mode bootstrap must not invoke Gradle",
        );
    });
});

describe("GradleOnlyDaemonScheduler", () => {
    it("ensureModule resolves false (no daemon)", async () => {
        const sched = new GradleOnlyDaemonScheduler();
        assert.strictEqual(await sched.ensureModule(FAKE_MODULE), false);
    });

    it("warmModule reports 'fallback' progress and resolves false", async () => {
        const sched = new GradleOnlyDaemonScheduler();
        const states: string[] = [];
        const result = await sched.warmModule(
            // GradleService is unused in the GradleOnly impl; cast is safe
            // because the no-op never reaches into it.
            {} as never,
            FAKE_MODULE,
            (s) => states.push(s),
        );
        assert.strictEqual(result, false);
        assert.deepStrictEqual(states, ["fallback"]);
    });

    it("write paths are no-ops (fileChanged / setFocus / setVisible)", async () => {
        const sched = new GradleOnlyDaemonScheduler();
        await sched.fileChanged(FAKE_MODULE, "/abs/foo.kt", "modified");
        await sched.setFocus(FAKE_MODULE, ["a", "b"]);
        await sched.setVisible(FAKE_MODULE, ["x"], ["y"]);
        // Just exercising that none of these throw.
    });

    it("setDataProductSubscription is a no-op (data extensions disabled)", async () => {
        const sched = new GradleOnlyDaemonScheduler();
        await sched.setDataProductSubscription(
            FAKE_MODULE,
            "preview-id",
            ["a11y/atf", "a11y/hierarchy"],
            true,
        );
        // Disabling is also a no-op.
        await sched.setDataProductSubscription(
            FAKE_MODULE,
            "preview-id",
            ["a11y/atf"],
            false,
        );
    });

    it("renderNow resolves false (no daemon to ask)", async () => {
        const sched = new GradleOnlyDaemonScheduler();
        assert.strictEqual(
            await sched.renderNow(FAKE_MODULE, ["id"], "fast", "test"),
            false,
        );
    });

    it("daemonEvents returns an inert bag with no handlers wired", () => {
        const sched = new GradleOnlyDaemonScheduler();
        const events = sched.daemonEvents(":app");
        // The events bag is `{}` — all DaemonClientEvents fields are optional,
        // so the gate's getOrSpawn (which never fires anyway) sees an empty
        // surface. This keeps the call-site uniform across backends.
        assert.deepStrictEqual(Object.keys(events), []);
    });
});
