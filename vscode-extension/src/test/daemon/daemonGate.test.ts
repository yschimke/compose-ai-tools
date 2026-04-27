import * as assert from 'assert';
import { DaemonGate } from '../../daemon/daemonGate';
import { GradleService, GradleApi } from '../../gradleService';
import { PreviewManifest } from '../../types';

/**
 * Stub GradleApi modelled on the existing `gradleService.test.ts` style.
 */
class StubGradleApi implements GradleApi {
    public runCalls: string[] = [];
    async runTask(opts: { taskName: string }): Promise<void> {
        this.runCalls.push(opts.taskName);
    }
    async cancelRunTask(): Promise<void> { /* */ }
}

class StubGradleService extends GradleService {
    public renderCalls: Array<{ module: string; tier: string }> = [];
    public discoverCalls: string[] = [];
    public manifest: PreviewManifest | null = null;

    constructor() {
        super('/ws', new StubGradleApi());
    }

    async renderPreviews(module: string, tier: 'fast' | 'full' = 'full'): Promise<PreviewManifest | null> {
        this.renderCalls.push({ module, tier });
        return this.manifest;
    }

    async discoverPreviews(module: string): Promise<PreviewManifest | null> {
        this.discoverCalls.push(module);
        return this.manifest;
    }

    readManifest(_module: string): PreviewManifest | null {
        return this.manifest;
    }
}

describe('DaemonGate', () => {

    it('falls through to gradleService when the daemon setting is disabled', async () => {
        const gradle = new StubGradleService();
        const notifications: string[] = [];
        const gate = new DaemonGate({
            workspaceRoot: '/ws',
            gradleService: gradle,
            rebootstrap: async () => { /* */ },
            clientVersion: 'test',
            readSetting: () => false,
            notify: (m) => notifications.push(m),
        });

        await gate.renderPreviews(':samples:android', 'fast');
        assert.deepStrictEqual(gradle.renderCalls, [
            { module: ':samples:android', tier: 'fast' },
        ]);
        assert.deepStrictEqual(gate.activeModules, []);
        assert.deepStrictEqual(notifications, []);
    });

    it('falls through to gradleService when the daemon setting is enabled but spawn fails', async () => {
        // No descriptor on disk → DaemonProcess throws descriptor-missing,
        // gate logs + notifies + falls back. We're testing the gate's
        // resilience here, not DaemonProcess error handling (covered in
        // daemonProcess.test.ts).
        const gradle = new StubGradleService();
        const notifications: string[] = [];
        const gate = new DaemonGate({
            workspaceRoot: '/nonexistent-workspace-for-gate-test',
            gradleService: gradle,
            rebootstrap: async () => { /* */ },
            clientVersion: 'test',
            readSetting: () => true,
            notify: (m) => notifications.push(m),
        });

        await gate.renderPreviews(':samples:android', 'fast');
        assert.deepStrictEqual(gradle.renderCalls, [
            { module: ':samples:android', tier: 'fast' },
        ]);
        // The fallback notification fires once.
        assert.strictEqual(notifications.length, 1);
        assert.match(notifications[0], /falling back to Gradle/);
        assert.deepStrictEqual(gate.activeModules, []);
    });

    it('marks the module disabled after fallback so subsequent calls skip the daemon path', async () => {
        const gradle = new StubGradleService();
        const notifications: string[] = [];
        const gate = new DaemonGate({
            workspaceRoot: '/nonexistent-workspace-for-gate-test',
            gradleService: gradle,
            rebootstrap: async () => { /* */ },
            clientVersion: 'test',
            readSetting: () => true,
            notify: (m) => notifications.push(m),
        });

        await gate.renderPreviews(':samples:android', 'fast');
        await gate.renderPreviews(':samples:android', 'fast');
        await gate.renderPreviews(':samples:android', 'fast');
        // Three GradleService calls, but only one notification (de-duped).
        assert.strictEqual(gradle.renderCalls.length, 3);
        assert.strictEqual(notifications.length, 1);
    });

    it('discoverPreviews falls through to gradleService when disabled', async () => {
        const gradle = new StubGradleService();
        const gate = new DaemonGate({
            workspaceRoot: '/ws',
            gradleService: gradle,
            rebootstrap: async () => { /* */ },
            clientVersion: 'test',
            readSetting: () => false,
            notify: () => { /* */ },
        });
        await gate.discoverPreviews(':samples:android');
        assert.deepStrictEqual(gradle.discoverCalls, [':samples:android']);
    });
});
