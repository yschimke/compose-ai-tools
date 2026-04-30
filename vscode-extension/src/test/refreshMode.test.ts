import * as assert from 'assert';
import { pickRefreshModeFor } from '../refreshMode';

/**
 * Pure predicate test — the production wrapper {@link pickRefreshMode}
 * reads `daemonGate` and `gradleService` from module scope, but the
 * decision logic is in {@link pickRefreshModeFor} so we can exercise
 * every branch without stubbing the VS Code API.
 */
describe('pickRefreshModeFor', () => {
    const ready = (id: string) => id === 'mod';
    const notReady = () => false;

    it('returns gradle when the daemon flag is off', () => {
        assert.strictEqual(
            pickRefreshModeFor('/x.kt', /* enabled */ false, 'mod', ready),
            'gradle',
        );
    });

    it('returns gradle when the file resolves to no module', () => {
        assert.strictEqual(
            pickRefreshModeFor('/outside.kt', true, null, ready),
            'gradle',
        );
    });

    it('returns gradle when the daemon for the module is not ready', () => {
        assert.strictEqual(
            pickRefreshModeFor('/x.kt', true, 'mod', notReady),
            'gradle',
        );
    });

    it('returns daemon only when all three preconditions hold', () => {
        assert.strictEqual(
            pickRefreshModeFor('/x.kt', true, 'mod', ready),
            'daemon',
        );
    });

    it('isDaemonReady is consulted per-module — a different module that\'s not warm stays on gradle', () => {
        // Two modules in the same workspace; one daemon is up, the other
        // isn't. The save picks the path keyed off the saved file's
        // module, not the union of all daemon health.
        assert.strictEqual(
            pickRefreshModeFor('/x.kt', true, 'cold-module', ready),
            'gradle',
        );
        assert.strictEqual(
            pickRefreshModeFor('/x.kt', true, 'mod', ready),
            'daemon',
        );
    });
});
