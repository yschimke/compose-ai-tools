import * as assert from 'assert';
import { pickRefreshModeFor } from '../refreshMode';

/**
 * Pure predicate test — the production wrapper {@link pickRefreshMode}
 * reads `daemonGate` and `gradleService` from module scope, but the
 * decision logic is in {@link pickRefreshModeFor} so we can exercise
 * every branch without stubbing the VS Code API.
 */
describe('pickRefreshModeFor', () => {
    it('returns gradle when the daemon flag is off', () => {
        assert.strictEqual(
            pickRefreshModeFor('/x.kt', /* enabled */ false, 'mod'),
            'gradle',
        );
    });

    it('returns gradle when the file resolves to no module', () => {
        assert.strictEqual(
            pickRefreshModeFor('/outside.kt', true, null),
            'gradle',
        );
    });

    it('returns daemon when enabled and the file resolves to a module', () => {
        assert.strictEqual(
            pickRefreshModeFor('/x.kt', true, 'mod'),
            'daemon',
        );
    });
});
