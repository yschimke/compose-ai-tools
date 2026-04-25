import * as assert from 'assert';
import * as path from 'path';
import * as vscode from 'vscode';
import type { ComposePreviewTestApi } from '../../../extension';
import type { GradleApi } from '../../../gradleService';

/** Stub GradleApi that records every runTask invocation (task + args). */
class RecordingGradleApi implements GradleApi {
    public readonly invocations: Array<{
        taskName: string;
        args: ReadonlyArray<string>;
    }> = [];

    async runTask(opts: {
        projectFolder: string;
        taskName: string;
        args?: ReadonlyArray<string>;
        showOutputColors: boolean;
        onOutput?: (output: { getOutputBytes(): Uint8Array; getOutputType(): number }) => void;
        cancellationKey?: string;
    }): Promise<void> {
        this.invocations.push({
            taskName: opts.taskName,
            args: opts.args ?? [],
        });
    }

    async cancelRunTask(): Promise<void> {
        // No-op; we don't model cancellation in these tests.
    }
}

/** Helper that yields control to the event loop so any awaited postMessage
 *  callbacks land in `getPostedMessages()` before the assertion. */
function flushMicrotasks(): Promise<void> {
    return new Promise(resolve => setImmediate(resolve));
}

async function getApi(): Promise<ComposePreviewTestApi> {
    const ext = vscode.extensions.getExtension<ComposePreviewTestApi>('yuri-schimke.compose-preview');
    assert.ok(ext, 'compose-preview extension must be present in the test host');
    const api = await ext.activate();
    assert.ok(api, 'activate() must return ComposePreviewTestApi under COMPOSE_PREVIEW_TEST_MODE=1');
    return api;
}

interface PostedMessage {
    command: string;
    [key: string]: unknown;
}

function commandsOf(messages: unknown[]): string[] {
    return messages.map(m => (m as PostedMessage).command);
}

function findMessage(messages: unknown[], command: string): PostedMessage | undefined {
    return messages.find(m => (m as PostedMessage).command === command) as PostedMessage | undefined;
}

describe('Compose Preview lifecycle', () => {
    let api: ComposePreviewTestApi;
    let gradle: RecordingGradleApi;
    let kotlinFile: string;

    before(async () => {
        api = await getApi();
        const folders = vscode.workspace.workspaceFolders;
        assert.ok(folders && folders.length > 0, 'fixture workspace must be open');
        kotlinFile = path.join(
            folders[0].uri.fsPath,
            'sample-module', 'src', 'main', 'kotlin',
            'com', 'example', 'sample', 'Previews.kt',
        );
    });

    beforeEach(() => {
        gradle = new RecordingGradleApi();
        api.injectGradleApi(gradle);
        api.resetMessages();
    });

    it('save-driven refresh sends setLoading then setPreviews and clears the Building banner', async () => {
        await api.triggerRefresh(kotlinFile, /* force */ true, 'fast');
        await flushMicrotasks();

        const messages = api.getPostedMessages();
        const cmds = commandsOf(messages);

        // Lifecycle: Building… is the first thing the panel sees, then the
        // populated card list arrives. The webview-side renderPreviews()
        // clears the 'loading' banner the moment cards land — exercised in
        // the webview unit test; here we verify the extension at least
        // emits the right sequence.
        const loadingIdx = cmds.indexOf('setLoading');
        const setPreviewsIdx = cmds.indexOf('setPreviews');
        assert.notStrictEqual(loadingIdx, -1, `expected setLoading, got: ${cmds.join(', ')}`);
        assert.notStrictEqual(setPreviewsIdx, -1, `expected setPreviews, got: ${cmds.join(', ')}`);
        assert.ok(loadingIdx < setPreviewsIdx, 'setLoading must precede setPreviews');

        const previewsMsg = findMessage(messages, 'setPreviews');
        const previews = previewsMsg!.previews as Array<{ id: string }>;
        assert.strictEqual(previews.length, 2, 'fixture has two previews in this file');
    });

    it('tier=fast on save passes -PcomposePreview.tier=fast to renderAllPreviews', async () => {
        await api.triggerRefresh(kotlinFile, /* force */ true, 'fast');
        await flushMicrotasks();

        const renderCall = gradle.invocations.find(
            i => i.taskName === ':sample-module:renderAllPreviews',
        );
        assert.ok(renderCall, 'expected a renderAllPreviews invocation');
        assert.ok(
            renderCall.args.includes('-PcomposePreview.tier=fast'),
            `expected tier=fast in args; got: ${renderCall.args.join(' ')}`,
        );
    });

    it('tier=full on explicit refresh passes -PcomposePreview.tier=full', async () => {
        await api.triggerRefresh(kotlinFile, /* force */ true, 'full');
        await flushMicrotasks();

        const renderCall = gradle.invocations.find(
            i => i.taskName === ':sample-module:renderAllPreviews',
        );
        assert.ok(renderCall, 'expected a renderAllPreviews invocation');
        assert.ok(
            renderCall.args.includes('-PcomposePreview.tier=full'),
            `expected tier=full in args; got: ${renderCall.args.join(' ')}`,
        );
    });

    it('heavyStaleIds populated after a fast render; cleared after a full render', async () => {
        // Fast first — heavy preview should be marked stale.
        await api.triggerRefresh(kotlinFile, /* force */ true, 'fast');
        await flushMicrotasks();
        const fastSetPreviews = findMessage(api.getPostedMessages(), 'setPreviews');
        assert.deepStrictEqual(
            fastSetPreviews!.heavyStaleIds,
            ['com.example.sample.PreviewsKt.AnimatedPreview'],
            'after a fast render the animated preview should be flagged stale',
        );

        // Full render clears the flag.
        api.resetMessages();
        await api.triggerRefresh(kotlinFile, /* force */ true, 'full');
        await flushMicrotasks();
        const fullSetPreviews = findMessage(api.getPostedMessages(), 'setPreviews');
        assert.deepStrictEqual(
            fullSetPreviews!.heavyStaleIds,
            [],
            'after a full render the stale list should be empty',
        );
    });
});
