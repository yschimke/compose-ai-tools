import * as assert from "assert";
import { MissingImageDataProduct } from "../scrollDataProductRender";
import { modulesNeedingViewportBackfill } from "../viewportScrollBackfill";

function missing(
    previewId: string,
    output = "data/render-scroll-long/x.png",
): MissingImageDataProduct {
    return {
        previewId,
        captureIndex: 0,
        renderOutput: output,
        absolutePath: `/ws/whatever/build/compose-previews/${output}`,
    };
}

describe("modulesNeedingViewportBackfill", () => {
    it("returns no candidates when nothing is missing", () => {
        const result = modulesNeedingViewportBackfill(
            new Map(),
            ["com.example.A"],
            [],
            new Set(),
        );
        assert.deepStrictEqual(result, []);
    });

    it("returns no candidates when the viewport is empty", () => {
        // Webview hasn't reported any visible cards yet — defer the backfill until
        // we actually know what the user is looking at. The current behaviour is to
        // burn a module-wide Gradle invocation on the activation event regardless;
        // the viewport-gated trigger is the improvement.
        const result = modulesNeedingViewportBackfill(
            new Map([[":wear", [missing("com.example.Long")]]]),
            [],
            [],
            new Set(),
        );
        assert.deepStrictEqual(result, []);
    });

    it("returns the module when one of its missing previews is visible", () => {
        const result = modulesNeedingViewportBackfill(
            new Map([
                [
                    ":wear",
                    [missing("com.example.Long"), missing("com.example.Gif")],
                ],
            ]),
            ["com.example.Long"],
            [],
            new Set(),
        );
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].modulePath, ":wear");
        assert.strictEqual(result[0].missing.length, 2);
    });

    it("returns the module when one of its missing previews is in the predicted set (about to scroll into view)", () => {
        // Pre-warm parity with the daemon's speculative renderNow path — backfill
        // fires for cards the panel believes the user is about to see, so the
        // placeholder window stays short rather than waiting for the card to land
        // in the strict visible set first.
        const result = modulesNeedingViewportBackfill(
            new Map([[":wear", [missing("com.example.Long")]]]),
            [],
            ["com.example.Long"],
            new Set(),
        );
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].modulePath, ":wear");
    });

    it("skips a module whose missing previews are entirely outside the viewport", () => {
        // Headline case the viewport gate solves: a project with 50 scroll
        // previews where the user only sees the top 3 shouldn't trigger a full
        // module re-render until the user actually scrolls down to one of the
        // missing-PNG cards. Pre-fix the trigger fired regardless; here it waits.
        const result = modulesNeedingViewportBackfill(
            new Map([
                [
                    ":wear",
                    [
                        missing("com.example.Bottom1"),
                        missing("com.example.Bottom2"),
                    ],
                ],
            ]),
            ["com.example.Top1", "com.example.Top2", "com.example.Top3"],
            ["com.example.Top4"],
            new Set(),
        );
        assert.deepStrictEqual(result, []);
    });

    it("skips a module that's already been backfilled this session", () => {
        // Session dedup keeps the trigger one-shot per module — a project whose
        // @ScrollingPreview wiring is broken shouldn't loop Gradle invocations
        // every time the user scrolls back to the failing card.
        const result = modulesNeedingViewportBackfill(
            new Map([[":wear", [missing("com.example.Long")]]]),
            ["com.example.Long"],
            [],
            new Set([":wear"]),
        );
        assert.deepStrictEqual(result, []);
    });

    it("returns each module that has its own viewport intersection independently", () => {
        const result = modulesNeedingViewportBackfill(
            new Map([
                [":wear", [missing("com.example.WearScroll")]],
                [":android", [missing("com.example.AndroidScroll")]],
                [":cmp", [missing("com.example.CmpScroll")]],
            ]),
            ["com.example.WearScroll"],
            ["com.example.AndroidScroll"],
            new Set(),
        );
        // :cmp's scroll isn't in viewport → skipped. :wear visible, :android
        // predicted → both backfill.
        const paths = result.map((c) => c.modulePath).sort();
        assert.deepStrictEqual(paths, [":android", ":wear"]);
    });
});
