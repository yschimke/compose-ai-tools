import * as assert from "assert";
import { DaemonWarmScopeLedger } from "../daemonWarmScopes";

/**
 * The daemon view-open warm-up ledger.
 *
 * The behaviour under test is one property: warm-up is suppressed when nothing
 * about the file's previews changed, and **not** suppressed when they did. The
 * bug this replaces got that second half wrong — a scope warmed once stayed
 * warmed for the life of the daemon, so a `@Preview` added to the file on
 * screen was discovered, drawn as a card, and never rendered.
 */
describe("DaemonWarmScopeLedger", () => {
    const scope = DaemonWarmScopeLedger.scopeKey(":samples:cmp", "Previews.kt");

    it("warms a scope it has never seen", () => {
        const ledger = new DaemonWarmScopeLedger();
        assert.strictEqual(ledger.shouldWarm(scope, ["a", "b"]), true);
    });

    it("suppresses a focus bounce over the same previews", () => {
        // The reason the guard exists: moving focus away and back must not
        // re-render cards that are already on screen.
        const ledger = new DaemonWarmScopeLedger();
        ledger.markWarmed(scope, ["a", "b"]);
        assert.strictEqual(ledger.shouldWarm(scope, ["a", "b"]), false);
    });

    it("ignores ordering, which discovery does not promise", () => {
        const ledger = new DaemonWarmScopeLedger();
        ledger.markWarmed(scope, ["a", "b", "c"]);
        assert.strictEqual(ledger.shouldWarm(scope, ["c", "a", "b"]), false);
    });

    it("warms again when a preview is added to a warmed file", () => {
        // THE REGRESSION. Adding a @Preview to the file you are looking at
        // leaves the daemon alive, so nothing cleared the old guard and the new
        // card never got pixels. Discovery had already announced the id.
        const ledger = new DaemonWarmScopeLedger();
        ledger.markWarmed(scope, ["a", "b"]);
        assert.strictEqual(
            ledger.shouldWarm(scope, ["a", "b", "c"]),
            true,
            "a newly discovered preview must not be suppressed by an earlier warm-up",
        );
    });

    it("warms again when a preview is removed", () => {
        const ledger = new DaemonWarmScopeLedger();
        ledger.markWarmed(scope, ["a", "b"]);
        assert.strictEqual(ledger.shouldWarm(scope, ["a"]), true);
    });

    it("cannot be fooled by an id that contains the separator", () => {
        // A printable separator would make ["a", "b"] and ["a<sep>b"] the same
        // fingerprint, silently suppressing a real change.
        const ledger = new DaemonWarmScopeLedger();
        ledger.markWarmed(scope, ["a", "b"]);
        assert.strictEqual(ledger.shouldWarm(scope, ["a b"]), true);
        assert.strictEqual(ledger.shouldWarm(scope, ["a,b"]), true);
    });

    it("keeps scopes apart", () => {
        const other = DaemonWarmScopeLedger.scopeKey(
            ":samples:cmp",
            "Other.kt",
        );
        const ledger = new DaemonWarmScopeLedger();
        ledger.markWarmed(scope, ["a"]);
        assert.strictEqual(ledger.shouldWarm(other, ["a"]), true);
    });

    it("forgets a scope whose warm-up threw", () => {
        // Marked before the render is dispatched, so a throw must undo it or
        // the next attempt is suppressed by a render that never happened.
        const ledger = new DaemonWarmScopeLedger();
        ledger.markWarmed(scope, ["a"]);
        ledger.forget(scope);
        assert.strictEqual(ledger.shouldWarm(scope, ["a"]), true);
    });

    it("clears a dead daemon's module without touching its neighbours", () => {
        const ledger = new DaemonWarmScopeLedger();
        const mine = DaemonWarmScopeLedger.scopeKey(":samples:cmp", "A.kt");
        const alsoMine = DaemonWarmScopeLedger.scopeKey(":samples:cmp", "B.kt");
        const neighbour = DaemonWarmScopeLedger.scopeKey(
            ":samples:android",
            "A.kt",
        );
        ledger.markWarmed(mine, ["a"]);
        ledger.markWarmed(alsoMine, ["b"]);
        ledger.markWarmed(neighbour, ["c"]);

        ledger.clearModule(":samples:cmp");

        assert.strictEqual(ledger.shouldWarm(mine, ["a"]), true);
        assert.strictEqual(ledger.shouldWarm(alsoMine, ["b"]), true);
        assert.strictEqual(
            ledger.shouldWarm(neighbour, ["c"]),
            false,
            "another module's daemon is still alive and its renders still stand",
        );
    });

    it("does not clear a module whose id is a prefix of another", () => {
        // `:samples:cmp` must not clear `:samples:cmp-wasm`.
        const ledger = new DaemonWarmScopeLedger();
        const sibling = DaemonWarmScopeLedger.scopeKey(
            ":samples:cmp-wasm",
            "A.kt",
        );
        ledger.markWarmed(sibling, ["a"]);
        ledger.clearModule(":samples:cmp");
        assert.strictEqual(ledger.shouldWarm(sibling, ["a"]), false);
    });
});
