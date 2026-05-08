import * as assert from "assert";
import { throttleLiveOnViewportLeave } from "../webview/preview/liveViewportThrottle";

describe("throttleLiveOnViewportLeave", () => {
    let posted: unknown[];
    let post: (msg: unknown) => void;

    beforeEach(() => {
        posted = [];
        post = (msg) => {
            posted.push(msg);
        };
    });

    afterEach(() => {
        posted = [];
    });

    it("no-ops when previewId is not in the live set", () => {
        const liveSet = new Set<string>(["com.example.A"]);
        throttleLiveOnViewportLeave("com.example.B", liveSet, post);
        assert.strictEqual(posted.length, 0);
    });

    it("no-ops when the live set is empty", () => {
        const liveSet = new Set<string>();
        throttleLiveOnViewportLeave("com.example.A", liveSet, post);
        assert.strictEqual(posted.length, 0);
    });

    it("posts a requestStreamVisibility(visible=false) when previewId is in the live set", () => {
        const liveSet = new Set<string>(["com.example.A"]);
        throttleLiveOnViewportLeave("com.example.A", liveSet, post);
        assert.strictEqual(posted.length, 1);
        assert.deepStrictEqual(posted[0], {
            command: "requestStreamVisibility",
            previewId: "com.example.A",
            visible: false,
            fps: undefined,
        });
    });

    it("leaves the live set unchanged after returning (LIVE chip survives the throttle)", () => {
        const liveSet = new Set<string>(["com.example.A", "com.example.B"]);
        throttleLiveOnViewportLeave("com.example.A", liveSet, post);
        // The set is intentionally not mutated — the daemon throttles to
        // keyframes-only but the local bookkeeping still says "this card
        // is live" so the LIVE badge survives the throttle.
        assert.strictEqual(liveSet.size, 2);
        assert.strictEqual(liveSet.has("com.example.A"), true);
        assert.strictEqual(liveSet.has("com.example.B"), true);
    });

    it("leaves the live set unchanged on the no-op path too", () => {
        const liveSet = new Set<string>(["com.example.A"]);
        throttleLiveOnViewportLeave("com.example.GHOST", liveSet, post);
        assert.strictEqual(liveSet.size, 1);
        assert.strictEqual(liveSet.has("com.example.A"), true);
    });
});
