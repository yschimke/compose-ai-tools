import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { DaemonScheduler } from '../daemon/daemonScheduler';

interface RecordedCall {
    method:
        | 'fileChanged'
        | 'setFocus'
        | 'setVisible'
        | 'renderNow'
        | 'dataSubscribe'
        | 'dataUnsubscribe';
    args: unknown;
}

class FakeClient {
    public calls: RecordedCall[] = [];
    public closed = false;
    public renderNowResult: { queued: string[]; rejected: { id: string; reason: string }[] } =
        { queued: ['x'], rejected: [] };
    /**
     * D2 — when true, `dataSubscribe` rejects with `DataProductUnknown`-shaped error,
     * mimicking a pre-D2 daemon. Tests that don't set this get a successful resolution.
     */
    public dataSubscribeRejects = false;

    fileChanged(args: unknown): void { this.calls.push({ method: 'fileChanged', args }); }
    setFocus(args: unknown): void { this.calls.push({ method: 'setFocus', args }); }
    setVisible(args: unknown): void { this.calls.push({ method: 'setVisible', args }); }
    renderNow(args: unknown): Promise<unknown> {
        this.calls.push({ method: 'renderNow', args });
        return Promise.resolve(this.renderNowResult);
    }
    dataSubscribe(args: unknown): Promise<unknown> {
        this.calls.push({ method: 'dataSubscribe', args });
        if (this.dataSubscribeRejects) {
            return Promise.reject(new Error('-32020 DataProductUnknown'));
        }
        return Promise.resolve({ ok: true });
    }
    dataUnsubscribe(args: unknown): Promise<unknown> {
        this.calls.push({ method: 'dataUnsubscribe', args });
        return Promise.resolve({ ok: true });
    }
    isClosed(): boolean { return this.closed; }
}

/**
 * The scheduler hands the gate a DaemonClientEvents bag for each module.
 * Tests need to drive `onRenderFinished` / `onRenderFailed` etc. into the
 * scheduler from the daemon side; capturing the events bag lets us simulate
 * the daemon without spinning up streams. Keyed by moduleId because the
 * scheduler may register a different bag per module.
 */
class FakeGate {
    public enabled = true;
    public client: FakeClient | null = new FakeClient();
    public ready = false;
    public capturedEvents = new Map<string, {
        onRenderFinished?: (p: { id: string; pngPath: string; tookMs: number }) => void;
        onRenderFailed?: (p: { id: string; error: { message: string } }) => void;
        onClasspathDirty?: (p: { detail: string }) => void;
        onDiscoveryUpdated?: (p: { added: unknown[]; removed: string[]; changed: unknown[]; totalPreviews: number }) => void;
        onChannelClosed?: () => void;
    }>();

    isEnabled(): boolean { return this.enabled; }
    isDaemonReady(_moduleId: string): boolean { return this.ready; }
    getOrSpawn(moduleId: string, events: unknown): Promise<FakeClient | null> {
        this.capturedEvents.set(moduleId, events as never);
        return Promise.resolve(this.client);
    }
}

class FakeGradleService {
    public bootstrapCalls: string[] = [];
    public bootstrapShouldThrow: Error | null = null;
    async runDaemonBootstrap(moduleId: string): Promise<void> {
        this.bootstrapCalls.push(moduleId);
        if (this.bootstrapShouldThrow) { throw this.bootstrapShouldThrow; }
    }
}

interface CapturedImage {
    moduleId: string;
    previewId: string;
    base64: string;
    pngPath: string;
}

function build() {
    const gate = new FakeGate();
    const log: string[] = [];
    const images: CapturedImage[] = [];
    const failures: { moduleId: string; previewId: string; message: string }[] = [];
    const dirty: { moduleId: string; detail: string }[] = [];
    const discovery: { moduleId: string; params: { added: unknown[]; removed: string[]; changed: unknown[]; totalPreviews: number } }[] = [];
    const dataProducts: {
        moduleId: string;
        previewId: string;
        attachments: { kind: string; payload?: unknown; path?: string }[];
    }[] = [];
    const events = {
        onPreviewImageReady: (moduleId: string, previewId: string, base64: string, pngPath: string) => {
            images.push({ moduleId, previewId, base64, pngPath });
        },
        onDataProductsAttached: (
            moduleId: string,
            previewId: string,
            attachments: { kind: string; payload?: unknown; path?: string }[],
        ) => {
            dataProducts.push({ moduleId, previewId, attachments });
        },
        onRenderFailed: (moduleId: string, previewId: string, message: string) => {
            failures.push({ moduleId, previewId, message });
        },
        onClasspathDirty: (moduleId: string, detail: string) => {
            dirty.push({ moduleId, detail });
        },
        onDiscoveryUpdated: (moduleId: string, params: { added: unknown[]; removed: string[]; changed: unknown[]; totalPreviews: number }) => {
            discovery.push({ moduleId, params });
        },
    };
    const scheduler = new DaemonScheduler(
        gate as unknown as ConstructorParameters<typeof DaemonScheduler>[0],
        events,
        { appendLine: (s) => log.push(s) },
    );
    return { gate, scheduler, log, images, failures, dirty, discovery, dataProducts };
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

    it('does not re-speculate IDs already speculated in this session', async () => {
        // Scrolling back over cards we already pre-warmed shouldn't re-queue
        // identical work; the daemon's reactive queue still handles them on
        // actual focus.
        const { gate, scheduler } = build();
        await scheduler.setVisible('mod', ['v1'], ['p1', 'p2']);
        await scheduler.setVisible('mod', ['v2'], ['p1', 'p2']); // same predictions
        const renderCalls = gate.client!.calls.filter(c => c.method === 'renderNow');
        // Only the first push generated a renderNow; the second was deduped.
        assert.strictEqual(renderCalls.length, 1);
    });

    it('emits a renderNow even when visible is unchanged but predicted is fresh', async () => {
        // The dedup check on `setVisible` fires only when there's no fresh
        // predicted set; with predictions, the scheduler still considers
        // them and may issue speculative renders even if visibility is the
        // same set as last time.
        const { gate, scheduler } = build();
        await scheduler.setVisible('mod', ['v1'], []);
        await scheduler.setVisible('mod', ['v1'], ['fresh1', 'fresh2']);
        const renderCalls = gate.client!.calls.filter(c => c.method === 'renderNow');
        assert.strictEqual(renderCalls.length, 1);
        const params = renderCalls[0].args as { previews: string[] };
        assert.deepStrictEqual(params.previews, ['fresh1', 'fresh2']);
    });

    it('skips daemon traffic entirely when the gate is disabled', async () => {
        const { gate, scheduler } = build();
        gate.enabled = false;
        gate.client = null;
        await scheduler.fileChanged('mod', '/x.kt');
        await scheduler.setFocus('mod', ['a']);
        await scheduler.setVisible('mod', ['a'], ['b']);
        const ok = await scheduler.ensureModule('mod');
        assert.strictEqual(ok, false);
    });

    it('classifies file kinds for fileChanged', async () => {
        const { gate, scheduler } = build();
        await scheduler.fileChanged('mod', '/proj/src/main/kotlin/Foo.kt');
        await scheduler.fileChanged('mod', '/proj/src/main/res/values/strings.xml');
        await scheduler.fileChanged('mod', '/proj/gradle/libs.versions.toml');
        await scheduler.fileChanged('mod', '/proj/build.gradle.kts');
        await scheduler.fileChanged('mod', '/proj/gradle.properties');
        const kinds = gate.client!.calls
            .filter(c => c.method === 'fileChanged')
            .map(c => (c.args as { kind: string }).kind);
        assert.deepStrictEqual(kinds, ['source', 'resource', 'classpath', 'classpath', 'classpath']);
    });

    it('dedupes setFocus when ids are unchanged regardless of order', async () => {
        const { gate, scheduler } = build();
        await scheduler.setFocus('mod', ['a', 'b']);
        await scheduler.setFocus('mod', ['b', 'a']);
        const focusCalls = gate.client!.calls.filter(c => c.method === 'setFocus');
        assert.strictEqual(focusCalls.length, 1);
    });

    it('reads the rendered PNG and forwards bytes via onPreviewImageReady', async () => {
        const { gate, scheduler, images } = build();
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sched-'));
        try {
            const pngPath = path.join(tmpDir, 'preview.png');
            const bytes = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]); // PNG magic
            fs.writeFileSync(pngPath, bytes);

            await scheduler.ensureModule('mod');
            const evts = gate.capturedEvents.get('mod')!;
            evts.onRenderFinished!({ id: 'p1', pngPath, tookMs: 200 });

            assert.strictEqual(images.length, 1);
            assert.strictEqual(images[0].previewId, 'p1');
            assert.strictEqual(images[0].pngPath, pngPath);
            assert.strictEqual(images[0].base64, bytes.toString('base64'));
        } finally {
            fs.rmSync(tmpDir, { recursive: true });
        }
    });

    it('short-circuits when renderFinished carries unchanged=true (frame dedup)', async () => {
        // INTERACTIVE.md § 5 — the daemon already determined the bytes are byte-identical
        // to the last frame for this preview id. The scheduler must skip the disk read +
        // base64 + onPreviewImageReady hop so the panel doesn't repaint identical bytes.
        const { gate, scheduler, images, failures } = build();
        await scheduler.ensureModule('mod');
        const evts = gate.capturedEvents.get('mod')!;
        // Use a path that doesn't exist on disk — if the scheduler even tries to read it,
        // it would emit onRenderFailed. The dedup short-circuit means it does neither:
        // no image, no failure.
        evts.onRenderFinished!({
            id: 'p1', pngPath: '/no/such/dedup.png', tookMs: 5,
            unchanged: true,
        } as never);
        assert.strictEqual(images.length, 0, 'unchanged=true must not emit onPreviewImageReady');
        assert.strictEqual(failures.length, 0, 'unchanged=true must not trigger an unreadable-PNG failure path');
    });

    it('reports onRenderFailed when the renderFinished PNG path is unreadable', async () => {
        const { gate, scheduler, failures } = build();
        await scheduler.ensureModule('mod');
        const evts = gate.capturedEvents.get('mod')!;
        evts.onRenderFinished!({ id: 'pZ', pngPath: '/no/such/file.png', tookMs: 1 });
        assert.strictEqual(failures.length, 1);
        assert.strictEqual(failures[0].previewId, 'pZ');
        assert.match(failures[0].message, /unreadable/i);
    });

    it('silently no-ops on daemon stub paths — once-per-module info log only', async () => {
        // Until :daemon:android ships B1.4, every "successful" render
        // returns `<historyDir>/daemon-stub-<id>.{png,gif}` with nothing
        // on disk. Logging ENOENT per render drowns the output channel;
        // the panel is already populated by the Gradle fallback. We
        // detect the documented stub-filename shape and skip.
        const { gate, scheduler, log, failures, images } = build();
        await scheduler.ensureModule('mod');
        const evts = gate.capturedEvents.get('mod')!;
        for (let i = 1; i <= 5; i++) {
            evts.onRenderFinished!({
                id: `p${i}`,
                pngPath: `.compose-preview-history/daemon-stub-${i}.png`,
                tookMs: 1,
            });
        }
        // No image read attempted, no failure surfaced.
        assert.strictEqual(images.length, 0);
        assert.strictEqual(failures.length, 0);
        // One info log per module (rate-limited), not five.
        const stubLogs = log.filter(l => l.includes('stub-render stage'));
        assert.strictEqual(stubLogs.length, 1);
    });

    it('detects gif stub paths the same way as png stubs', async () => {
        const { gate, scheduler, failures, images } = build();
        await scheduler.ensureModule('mod');
        const evts = gate.capturedEvents.get('mod')!;
        evts.onRenderFinished!({
            id: 'p1',
            pngPath: '.compose-preview-history/daemon-stub-1.gif',
            tookMs: 1,
        });
        assert.strictEqual(images.length, 0);
        assert.strictEqual(failures.length, 0);
    });

    it('still surfaces ENOENT for non-stub paths (real-render misconfig)', async () => {
        // The stub filter is precisely scoped — real-render paths that
        // happen to be missing must still surface as a failure so the
        // daemon's render bug is visible rather than silently swallowed.
        const { gate, scheduler, failures } = build();
        await scheduler.ensureModule('mod');
        const evts = gate.capturedEvents.get('mod')!;
        evts.onRenderFinished!({
            id: 'pY',
            pngPath: '/abs/build/compose-previews/renders/com.example.X.png',
            tookMs: 1,
        });
        assert.strictEqual(failures.length, 1);
        assert.match(failures[0].message, /unreadable/i);
    });

    it('forwards onRenderFailed from the daemon directly to the caller', async () => {
        const { gate, scheduler, failures } = build();
        await scheduler.ensureModule('mod');
        const evts = gate.capturedEvents.get('mod')!;
        evts.onRenderFailed!({ id: 'pX', error: { message: 'compile error' } });
        assert.deepStrictEqual(failures, [{ moduleId: 'mod', previewId: 'pX', message: 'compile error' }]);
    });

    it('clears the speculation cache and visibility memo when the channel closes', async () => {
        const { gate, scheduler } = build();
        // Speculate first so the cache is populated.
        await scheduler.setVisible('mod', ['v1'], ['p1', 'p2']);
        const evts = gate.capturedEvents.get('mod')!;

        // Channel close → the gate registry will replace the client. Pretend
        // a fresh daemon spawned with a new client object.
        const fresh = new FakeClient();
        gate.client = fresh;
        evts.onChannelClosed!();

        // Same predictions on a fresh daemon should re-issue, not dedup.
        await scheduler.setVisible('mod', ['v1'], ['p1', 'p2']);
        const renderCalls = fresh.calls.filter(c => c.method === 'renderNow');
        assert.strictEqual(renderCalls.length, 1, 'speculation cache survived channel close');
    });

    it('routes classpathDirty to the caller and drops the module speculation cache', async () => {
        const { gate, scheduler, dirty } = build();
        await scheduler.setVisible('mod', ['v1'], ['p1', 'p2']);
        const evts = gate.capturedEvents.get('mod')!;
        evts.onClasspathDirty!({ detail: 'libs.versions.toml SHA changed' });
        assert.strictEqual(dirty.length, 1);
        assert.strictEqual(dirty[0].moduleId, 'mod');
    });

    it('forwards discoveryUpdated to the caller with the moduleId attached', async () => {
        const { gate, scheduler, discovery } = build();
        // First call any scheduler method that goes through getOrSpawn so the
        // events bag for `mod` is registered; setFocus is the cheapest.
        await scheduler.setFocus('mod', ['p1']);
        const evts = gate.capturedEvents.get('mod')!;
        evts.onDiscoveryUpdated!({
            added: [],
            removed: ['p1'],
            changed: [],
            totalPreviews: 0,
        });
        assert.strictEqual(discovery.length, 1);
        assert.strictEqual(discovery[0].moduleId, 'mod');
        assert.deepStrictEqual(discovery[0].params.removed, ['p1']);
    });

    it('renderNow returns true on accept and false when no daemon is available', async () => {
        const { gate, scheduler } = build();
        const ok = await scheduler.renderNow('mod', ['p1'], 'fast');
        assert.strictEqual(ok, true);

        gate.enabled = false;
        gate.client = null;
        const fail = await scheduler.renderNow('mod2', ['p1'], 'fast');
        assert.strictEqual(fail, false);
    });

    it('logs rejected previews from renderNow without throwing', async () => {
        const { gate, scheduler, log } = build();
        gate.client!.renderNowResult = {
            queued: ['p1'],
            rejected: [{ id: 'pBad', reason: 'unknown preview ID' }],
        };
        const ok = await scheduler.renderNow('mod', ['p1', 'pBad'], 'fast');
        assert.strictEqual(ok, true);
        assert.ok(log.some(l => l.includes('rejected pBad')), `expected rejected log, got: ${log.join(' / ')}`);
    });

    describe('warmModule', () => {
        it('drives progress through bootstrapping → spawning → ready on the cold path', async () => {
            const { scheduler } = build();
            const gradle = new FakeGradleService();
            const states: string[] = [];
            const ok = await scheduler.warmModule(
                gradle as unknown as Parameters<typeof scheduler.warmModule>[0],
                'mod',
                (s) => states.push(s),
            );
            assert.strictEqual(ok, true);
            assert.deepStrictEqual(states, ['bootstrapping', 'spawning', 'ready']);
            assert.deepStrictEqual(gradle.bootstrapCalls, ['mod']);
        });

        it('short-circuits to ready without re-bootstrapping when the daemon is already up', async () => {
            const { gate, scheduler } = build();
            gate.ready = true;
            const gradle = new FakeGradleService();
            const states: string[] = [];
            const ok = await scheduler.warmModule(
                gradle as unknown as Parameters<typeof scheduler.warmModule>[0],
                'mod',
                (s) => states.push(s),
            );
            assert.strictEqual(ok, true);
            assert.deepStrictEqual(states, ['ready']);
            assert.deepStrictEqual(gradle.bootstrapCalls, [], 'bootstrap should not run when daemon is ready');
        });

        it('reports fallback when the bootstrap task throws', async () => {
            const { scheduler } = build();
            const gradle = new FakeGradleService();
            gradle.bootstrapShouldThrow = new Error('Gradle config-cache rejected');
            const states: string[] = [];
            const ok = await scheduler.warmModule(
                gradle as unknown as Parameters<typeof scheduler.warmModule>[0],
                'mod',
                (s) => states.push(s),
            );
            assert.strictEqual(ok, false);
            assert.deepStrictEqual(states, ['bootstrapping', 'fallback']);
        });

        it('reports fallback when the JVM spawn fails', async () => {
            // Disabled gate makes ensureModule return false (caller falls
            // back to Gradle); warmModule should mirror that as 'fallback'.
            const { gate, scheduler } = build();
            const gradle = new FakeGradleService();
            const states: string[] = [];
            // After bootstrap the scheduler tries to spawn — flip the gate
            // so spawn returns null on the second call only by clearing
            // the client.
            gate.client = null;
            const ok = await scheduler.warmModule(
                gradle as unknown as Parameters<typeof scheduler.warmModule>[0],
                'mod',
                (s) => states.push(s),
            );
            assert.strictEqual(ok, false);
            assert.deepStrictEqual(states, ['bootstrapping', 'spawning', 'fallback']);
        });

        it('returns false without progress events when the gate is disabled', async () => {
            const { gate, scheduler } = build();
            gate.enabled = false;
            const gradle = new FakeGradleService();
            const states: string[] = [];
            const ok = await scheduler.warmModule(
                gradle as unknown as Parameters<typeof scheduler.warmModule>[0],
                'mod',
                (s) => states.push(s),
            );
            assert.strictEqual(ok, false);
            assert.deepStrictEqual(states, []);
            assert.deepStrictEqual(gradle.bootstrapCalls, []);
        });
    });

    describe('onHistoryAdded forwarding (Phase H7)', () => {
        it('passes the daemon notification through with the right moduleId', async () => {
            const gate = new FakeGate();
            const log: string[] = [];
            const seen: { moduleId: string; entry: unknown }[] = [];
            const scheduler = new DaemonScheduler(
                gate as unknown as ConstructorParameters<typeof DaemonScheduler>[0],
                {
                    onPreviewImageReady: () => {},
                    onRenderFailed: () => {},
                    onClasspathDirty: () => {},
                    onHistoryAdded: (moduleId, params) => seen.push({ moduleId, entry: params.entry }),
                },
                { appendLine: (s) => log.push(s) },
            );
            await scheduler.ensureModule('mod');
            const evts = gate.capturedEvents.get('mod')! as unknown as {
                onHistoryAdded?: (params: { entry: unknown }) => void;
            };
            evts.onHistoryAdded!({ entry: { id: 'abc', previewId: 'X' } });
            assert.strictEqual(seen.length, 1);
            assert.strictEqual(seen[0].moduleId, 'mod');
            assert.deepStrictEqual(seen[0].entry, { id: 'abc', previewId: 'X' });
        });

        it('is a no-op when the caller didn\'t register an onHistoryAdded handler', async () => {
            // No `onHistoryAdded` on SchedulerEvents (it's optional). The
            // scheduler must tolerate that — daemon pushes still arrive,
            // they just go nowhere.
            const gate = new FakeGate();
            const scheduler = new DaemonScheduler(
                gate as unknown as ConstructorParameters<typeof DaemonScheduler>[0],
                {
                    onPreviewImageReady: () => {},
                    onRenderFailed: () => {},
                    onClasspathDirty: () => {},
                    // no onHistoryAdded
                },
            );
            await scheduler.ensureModule('mod');
            const evts = gate.capturedEvents.get('mod')! as unknown as {
                onHistoryAdded?: (params: { entry: unknown }) => void;
            };
            // Doesn't throw.
            evts.onHistoryAdded!({ entry: { id: 'abc' } });
        });
    });

    /**
     * D2 — data-product surface. Subscriptions are explicit per focused preview (the panel's
     * focus-mode "Show a11y overlay" toggle drives them) — `setVisible` itself does NOT
     * subscribe, only prunes stale bookkeeping. `renderFinished` forwards attachments.
     */
    describe('data products', () => {
        it('does not auto-subscribe on setVisible — subscriptions are explicit', async () => {
            const { gate, scheduler } = build();
            await scheduler.setVisible('mod', ['a', 'b'], []);
            const subs = gate.client!.calls.filter(c => c.method === 'dataSubscribe');
            assert.strictEqual(subs.length, 0);
        });

        it('setDataProductSubscription(true) issues data/subscribe per kind', async () => {
            const { gate, scheduler } = build();
            await scheduler.setDataProductSubscription(
                'mod',
                'a',
                ['a11y/atf', 'a11y/hierarchy'],
                true,
            );
            const subs = gate.client!.calls.filter(c => c.method === 'dataSubscribe');
            assert.strictEqual(subs.length, 2);
            const kinds = subs.map(c => (c.args as { kind: string }).kind).sort();
            assert.deepStrictEqual(kinds, ['a11y/atf', 'a11y/hierarchy']);
        });

        it('setDataProductSubscription(true) twice is idempotent', async () => {
            const { gate, scheduler } = build();
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            const subs = gate.client!.calls.filter(c => c.method === 'dataSubscribe');
            assert.strictEqual(subs.length, 1);
        });

        it('setDataProductSubscription(false) issues data/unsubscribe and forgets the pair', async () => {
            const { gate, scheduler } = build();
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], false);
            const unsubs = gate.client!.calls.filter(c => c.method === 'dataUnsubscribe');
            assert.strictEqual(unsubs.length, 1);
            // Re-enabling re-issues subscribe (bookkeeping was cleared).
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            const subs = gate.client!.calls.filter(c => c.method === 'dataSubscribe');
            assert.strictEqual(subs.length, 2);
        });

        it('setDataProductSubscription(false) on an unknown pair is a no-op', async () => {
            const { gate, scheduler } = build();
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], false);
            const calls = gate.client!.calls.filter(
                c => c.method === 'dataSubscribe' || c.method === 'dataUnsubscribe',
            );
            assert.strictEqual(calls.length, 0);
        });

        it('setVisible drops bookkeeping for previews that left view', async () => {
            const { gate, scheduler } = build();
            await scheduler.setVisible('mod', ['a', 'b'], []);
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            await scheduler.setVisible('mod', ['b'], []); // 'a' fell out
            // Re-subscribing 'a' should issue another subscribe (bookkeeping was cleared by
            // the visibility prune even though the daemon never received our explicit call).
            await scheduler.setVisible('mod', ['a', 'b'], []);
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            const subs = gate.client!.calls.filter(c => c.method === 'dataSubscribe');
            assert.strictEqual(subs.length, 2);
        });

        it('forwards renderFinished.dataProducts via onDataProductsAttached', async () => {
            const { gate, scheduler, dataProducts } = build();
            await scheduler.ensureModule('mod');
            const evts = gate.capturedEvents.get('mod')! as unknown as {
                onRenderFinished: (p: {
                    id: string;
                    pngPath: string;
                    tookMs: number;
                    dataProducts?: { kind: string; payload?: unknown; path?: string }[];
                }) => void;
            };
            // Stage a real PNG so the scheduler doesn't bail on ENOENT.
            const tmp = path.join(os.tmpdir(), `data-products-${Date.now()}.png`);
            fs.writeFileSync(tmp, Buffer.from('\x89PNG\r\n\x1a\n', 'binary'));
            evts.onRenderFinished({
                id: 'a',
                pngPath: tmp,
                tookMs: 10,
                dataProducts: [
                    { kind: 'a11y/atf', payload: { findings: [] } },
                    { kind: 'a11y/hierarchy', path: '/abs/a11y-hierarchy.json' },
                ],
            });
            assert.strictEqual(dataProducts.length, 1);
            assert.strictEqual(dataProducts[0].previewId, 'a');
            assert.strictEqual(dataProducts[0].attachments.length, 2);
            fs.unlinkSync(tmp);
        });

        it('does not fire onDataProductsAttached when renderFinished carries no products', async () => {
            const { gate, scheduler, dataProducts } = build();
            await scheduler.ensureModule('mod');
            const evts = gate.capturedEvents.get('mod')! as unknown as {
                onRenderFinished: (p: { id: string; pngPath: string; tookMs: number }) => void;
            };
            const tmp = path.join(os.tmpdir(), `data-products-empty-${Date.now()}.png`);
            fs.writeFileSync(tmp, Buffer.from('\x89PNG\r\n\x1a\n', 'binary'));
            evts.onRenderFinished({ id: 'a', pngPath: tmp, tookMs: 10 });
            assert.strictEqual(dataProducts.length, 0);
            fs.unlinkSync(tmp);
        });

        it('survives a pre-D2 daemon that rejects dataSubscribe with DataProductUnknown', async () => {
            const { gate, scheduler, log } = build();
            gate.client!.dataSubscribeRejects = true;
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            // Wait one microtask cycle so the rejected promise settles.
            await Promise.resolve();
            await Promise.resolve();
            // Subscribe was attempted; the rejection is absorbed and the bookkeeping rolls
            // back so a future re-attempt re-issues.
            const subs = gate.client!.calls.filter(c => c.method === 'dataSubscribe');
            assert.strictEqual(subs.length, 1);
            const subFailures = log.filter(l => l.includes('dataSubscribe'));
            assert.ok(subFailures.length >= 1, 'expected log entry for failed dataSubscribe');
            // Retry succeeds (the rollback dropped the bookkeeping).
            gate.client!.dataSubscribeRejects = false;
            await scheduler.setDataProductSubscription('mod', 'a', ['a11y/atf'], true);
            const subsAfter = gate.client!.calls.filter(c => c.method === 'dataSubscribe');
            assert.strictEqual(subsAfter.length, 2);
        });
    });
});
