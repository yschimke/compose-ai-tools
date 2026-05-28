import * as assert from "assert";
import {
    TabGroupLike,
    anyPreviewSourceTabOpen,
    openTabFsPaths,
} from "../previewTabs";

/** Mirror of the extension's preview-source predicate, kept simple here. */
function isPreviewSource(fsPath: string): boolean {
    return fsPath.endsWith(".kt") && !fsPath.endsWith(".gradle.kts");
}

/** Build a text-document tab (TabInputText shape). */
function textTab(fsPath: string): { input: { uri: { fsPath: string } } } {
    return { input: { uri: { fsPath } } };
}

/** Build a non-text tab (webview/terminal shape — no `uri`). */
function webviewTab(viewType: string): { input: { viewType: string } } {
    return { input: { viewType } };
}

function groups(...tabs: TabGroupLike["tabs"][number][][]): TabGroupLike[] {
    return tabs.map((groupTabs) => ({ tabs: groupTabs }));
}

describe("openTabFsPaths", () => {
    it("collects fsPaths from text-document tabs across groups", () => {
        const g = groups(
            [textTab("/a/Foo.kt"), webviewTab("compose.preview")],
            [textTab("/b/Bar.kt")],
        );
        assert.deepStrictEqual(openTabFsPaths(g), ["/a/Foo.kt", "/b/Bar.kt"]);
    });

    it("skips tabs whose input has no uri (webviews, terminals)", () => {
        const g = groups([webviewTab("terminal"), webviewTab("output")]);
        assert.deepStrictEqual(openTabFsPaths(g), []);
    });

    it("tolerates undefined / malformed inputs", () => {
        const g: TabGroupLike[] = [
            { tabs: [{ input: undefined }, { input: null as unknown }] },
            { tabs: [{ input: { uri: {} } }] }, // uri without fsPath
        ];
        assert.deepStrictEqual(openTabFsPaths(g), []);
    });

    it("returns empty for no groups", () => {
        assert.deepStrictEqual(openTabFsPaths([]), []);
    });
});

describe("anyPreviewSourceTabOpen", () => {
    it("is true when a covered (non-visible) preview tab is still open", () => {
        // Focus-drift / daemon-spawn case: the .kt is open in a background
        // group while the webview holds focus. Stale cards must stay up.
        const g = groups(
            [webviewTab("compose.preview")],
            [textTab("/app/Screen.kt")],
        );
        assert.strictEqual(anyPreviewSourceTabOpen(g, isPreviewSource), true);
    });

    it("is false once the last preview editor is closed (issue #1566)", () => {
        // Only a webview tab remains — nothing left to render, so the guard
        // should let the panel clear to the empty state.
        const g = groups([webviewTab("compose.preview")]);
        assert.strictEqual(anyPreviewSourceTabOpen(g, isPreviewSource), false);
    });

    it("is false when no tabs are open at all", () => {
        assert.strictEqual(anyPreviewSourceTabOpen([], isPreviewSource), false);
    });

    it("ignores non-preview source tabs (build scripts, markdown)", () => {
        const g = groups([
            textTab("/app/build.gradle.kts"),
            textTab("/README.md"),
        ]);
        assert.strictEqual(anyPreviewSourceTabOpen(g, isPreviewSource), false);
    });

    it("is true when at least one preview tab sits among non-preview tabs", () => {
        const g = groups([
            textTab("/app/build.gradle.kts"),
            textTab("/app/Screen.kt"),
        ]);
        assert.strictEqual(anyPreviewSourceTabOpen(g, isPreviewSource), true);
    });
});
