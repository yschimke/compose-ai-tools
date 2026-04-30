import * as assert from 'assert';
import { DaemonScheduler } from '../daemon/daemonScheduler';

interface RecordedCall {
    method: 'fileChanged' | 'setFocus' | 'setVisible' | 'renderNow';
    args: unknown;
}

class FakeClient {
    public calls: RecordedCall[] = [];
    public closed = false;
    public renderNowResult = { queued: ['x'], rejected: [] };

    fileChanged(args: unknown): void { this.calls.push({ method: 'fileChanged', args }); }
    setFocus(args: unknown): void { this.calls.push({ method: 'setFocus', args }); }
    setVisible(args: unknown): void { this.calls.push({ method: 'setVisible', args }); }
    renderNow(args: unknown): Promise<unknown> {
        this.calls.push({ method: 'renderNow', args });
        return Promise.resolve(this.renderNowResult);
    }
    isClosed(): boolean { return this.closed; }
}

class FakeGate {
    public enabled = true;
    public client: FakeClient | null = new FakeClient();
    isEnabled(): boolean { return this.enabled; }
    getOrSpawn(): Promise<FakeClient | null> { return Promise.resolve(this.client); }
}

function build() {
    const gate = new FakeGate();
    const log: string[] = [];
    const events = {
        onPreviewImageReady: () => { /* noop */ },
        onRenderFailed: () => { /* noop */ },
        onClasspathDirty: () => { /* noop */ },
    };
    // Cast: the scheduler's constructor accepts a DaemonGate; FakeGate has
    // the same isEnabled/getOrSpawn surface area for the methods exercised
    // here. The dispose/bootstrap/etc. surface isn't reached.
    const scheduler = new DaemonScheduler(
        gate as unknown as ConstructorParameters<typeof DaemonScheduler>[0],
        events,
        { appendLine: (s) => log.push(s) },
    );
    return { gate, scheduler, log };
}

describe('DaemonScheduler', () => {
    it('dedupes setVisible when the visible set is unchanged', async () => {
        const { gate, scheduler } = build();
        await scheduler.setVisible('mod', ['a', 'b'], []);
        await scheduler.setVisible('mod', ['a', 'b'], []); // no-op
        await scheduler.setVisible('mod', ['b', 'a'], []); // same set, different order — still no-op
        const visibleCalls = gate.client!.calls.filter(c => c.method === 'setVisible');
        assert.strictEqual(visibleCalls.length, 1);
    });

    it('caps speculative renderNow at the budget', async () => {
        const { gate, scheduler } = build();
        const predicted = ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'];
        await scheduler.setVisible('mod', ['v1'], predicted);
        const renderCalls = gate.client!.calls.filter(c => c.method === 'renderNow');
        assert.strictEqual(renderCalls.length, 1);
        const params = renderCalls[0].args as { previews: string[]; tier: string };
        assert.strictEqual(params.previews.length, 4);
        assert.strictEqual(params.tier, 'fast');
        // The four selected must be a prefix of `predicted` (preserves the
        // webview's ranked-by-velocity order — see PREDICTIVE.md § 2).
        assert.deepStrictEqual(params.previews, predicted.slice(0, 4));
    });

    it('does not re-speculate IDs already in the visible set', async () => {
        const { gate, scheduler } = build();
        await scheduler.setVisible('mod', ['a', 'b'], ['b', 'c', 'd']);
        const renderCalls = gate.client!.calls.filter(c => c.method === 'renderNow');
        assert.strictEqual(renderCalls.length, 1);
        const params = renderCalls[0].args as { previews: string[] };
        // 'b' is currently visible — daemon's reactive queue handles it. We
        // only speculate 'c' and 'd'.
        assert.deepStrictEqual(params.previews, ['c', 'd']);
    });

    it('skips daemon traffic entirely when the gate is disabled', async () => {
        const { gate, scheduler } = build();
        gate.enabled = false;
        gate.client = null;
        await scheduler.fileChanged('mod', '/x.kt');
        await scheduler.setFocus('mod', ['a']);
        await scheduler.setVisible('mod', ['a'], ['b']);
    });

    it('classifies file kinds for fileChanged', async () => {
        const { gate, scheduler } = build();
        await scheduler.fileChanged('mod', '/proj/src/main/kotlin/Foo.kt');
        await scheduler.fileChanged('mod', '/proj/src/main/res/values/strings.xml');
        await scheduler.fileChanged('mod', '/proj/gradle/libs.versions.toml');
        const kinds = gate.client!.calls
            .filter(c => c.method === 'fileChanged')
            .map(c => (c.args as { kind: string }).kind);
        assert.deepStrictEqual(kinds, ['source', 'resource', 'classpath']);
    });
});
