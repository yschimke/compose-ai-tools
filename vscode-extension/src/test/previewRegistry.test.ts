import * as assert from "assert";
import { PreviewRegistry } from "../previewRegistry";
import { PreviewInfo } from "../types";

function preview(
    id: string,
    sourceFile: string,
    functionName?: string,
): PreviewInfo {
    return {
        id,
        functionName: functionName ?? id.split(".").pop()!,
        className: id.substring(0, id.lastIndexOf(".")),
        sourceFile,
        params: {
            name: null,
            device: null,
            widthDp: null,
            heightDp: null,
            fontScale: 1,
            showSystemUi: false,
            showBackground: false,
            backgroundColor: 0,
            uiMode: 0,
            locale: null,
            group: null,
        },
        captures: [],
    } as unknown as PreviewInfo;
}

describe("PreviewRegistry.replaceModule preserves image bytes", () => {
    it("keeps imageBase64 for previewIds that survive a refresh", () => {
        // Regression for the verify-exposed bug: replaceModule was wipe-then-recreate, so
        // every refresh dropped the bytes preload had registered. The webview then
        // re-rendered a placeholder until the daemon's 15-25 s spawn finished.
        const reg = new PreviewRegistry();
        const p1 = preview("com.example.PreviewsKt.RedBox", "Previews.kt");
        reg.replaceModule(":app", [p1]);
        reg.setImage(p1.id, "REDBYTES");
        assert.strictEqual(reg.getImage(p1.id), "REDBYTES");
        // Same manifest landing again — common when refresh re-reads previews.json.
        reg.replaceModule(":app", [p1]);
        assert.strictEqual(reg.getImage(p1.id), "REDBYTES");
    });

    it("preserves bytes when the metadata changes but the previewId is stable", () => {
        // A capture-label refresh or label-only manifest update must not drop the image.
        const reg = new PreviewRegistry();
        const p1 = preview("com.example.PreviewsKt.RedBox", "Previews.kt");
        reg.replaceModule(":app", [p1]);
        reg.setImage(p1.id, "REDBYTES");
        const p1_refreshed = {
            ...p1,
            params: { ...p1.params, name: "Red Box renamed" },
        } as PreviewInfo;
        reg.replaceModule(":app", [p1_refreshed]);
        assert.strictEqual(reg.getImage(p1.id), "REDBYTES");
    });

    it("drops bytes for previewIds that disappear from the fresh set", () => {
        const reg = new PreviewRegistry();
        const p1 = preview("com.example.PreviewsKt.RedBox", "Previews.kt");
        const p2 = preview("com.example.PreviewsKt.BlueBox", "Previews.kt");
        reg.replaceModule(":app", [p1, p2]);
        reg.setImage(p1.id, "REDBYTES");
        reg.setImage(p2.id, "BLUEBYTES");
        // p2 deleted from the source file → manifest no longer lists it.
        reg.replaceModule(":app", [p1]);
        assert.strictEqual(reg.getImage(p1.id), "REDBYTES");
        assert.strictEqual(reg.getImage(p2.id), null);
    });

    it("does not touch bytes belonging to a different module", () => {
        // Regression: replaceModule must scope to its module argument. A refresh of :app
        // must not clobber :lib's registered images.
        const reg = new PreviewRegistry();
        const appPreview = preview(
            "com.example.PreviewsKt.RedBox",
            "Previews.kt",
        );
        const libPreview = preview(
            "com.lib.LibPreviewsKt.LibPreview",
            "LibPreviews.kt",
        );
        reg.replaceModule(":app", [appPreview]);
        reg.replaceModule(":lib", [libPreview]);
        reg.setImage(appPreview.id, "APPBYTES");
        reg.setImage(libPreview.id, "LIBBYTES");
        // Refresh :app only — :lib's entry survives untouched.
        reg.replaceModule(":app", [appPreview]);
        assert.strictEqual(reg.getImage(libPreview.id), "LIBBYTES");
    });

    it("re-keys the source-file lookup when the manifest renames the file", () => {
        // The (sourceFile, functionName) key shifts if a manifest update changes either
        // field. The previewId stays stable; the bytes must survive AND `find()` must
        // resolve via the new key.
        const reg = new PreviewRegistry();
        const p1 = preview("com.example.PreviewsKt.RedBox", "Previews.kt");
        reg.replaceModule(":app", [p1]);
        reg.setImage(p1.id, "REDBYTES");
        const p1_renamed = {
            ...p1,
            sourceFile: "PreviewsRenamed.kt",
        } as PreviewInfo;
        reg.replaceModule(":app", [p1_renamed]);
        assert.strictEqual(reg.getImage(p1.id), "REDBYTES");
    });
});

describe("PreviewRegistry.setImage lazy-creates entries", () => {
    it("stores bytes against a previewId even before the manifest registers it", () => {
        // Regression: setImage used to silently no-op when byId.get returned undefined,
        // which is exactly what happened on a cold start where the daemon's first render
        // raced replaceModule. The verify command surfaced the result: registry=0 for
        // every preview even though every PNG was on disk.
        const reg = new PreviewRegistry();
        reg.setImage("com.example.PreviewsKt.RedBox", "REDBYTES");
        assert.strictEqual(
            reg.getImage("com.example.PreviewsKt.RedBox"),
            "REDBYTES",
        );
    });

    it("a placeholder entry is adopted by the next replaceModule call", () => {
        // The byte-only entry created by an early setImage must survive the next
        // replaceModule and merge with the manifest metadata — not be wiped as a stale
        // orphan.
        const reg = new PreviewRegistry();
        reg.setImage("com.example.PreviewsKt.RedBox", "REDBYTES");
        const p1 = preview("com.example.PreviewsKt.RedBox", "Previews.kt");
        reg.replaceModule(":app", [p1]);
        assert.strictEqual(reg.getImage(p1.id), "REDBYTES");
        const entry = reg.find("Previews.kt", "RedBox");
        // Once the metadata has landed, find() must resolve too (it keys on
        // sourceFile + functionName, which only setImage's placeholder couldn't supply).
        assert.ok(entry, "find should resolve once metadata lands");
        assert.strictEqual(entry?.imageBase64, "REDBYTES");
    });

    it("overwrites the bytes on a subsequent setImage for the same previewId", () => {
        const reg = new PreviewRegistry();
        reg.setImage("p1", "A");
        reg.setImage("p1", "B");
        assert.strictEqual(reg.getImage("p1"), "B");
    });
});
