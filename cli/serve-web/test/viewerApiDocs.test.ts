// Which API reference links the Source panel is willing to render.
//
// The interesting cases are all the same case: this is the one list of server-sent URLs the viewer
// deliberately follows OFF its own origin, so the check that keeps a catalog from putting an
// arbitrary href on the page is a pinned destination rather than a same-origin comparison.

import assert from "node:assert/strict";
import { usableApiDocs } from "../src/viewer/apiDocs.js";

const link = (over: Record<string, unknown> = {}) => ({
    name: "Button",
    fqn: "androidx.wear.compose.material3.Button",
    composable: true,
    url: "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/Button.composable",
    ...over,
});

describe("usableApiDocs", () => {
    it("keeps a reference page, in the order it arrived", () => {
        const docs = [
            link(),
            link({ name: "ButtonDefaults", composable: false }),
        ];
        assert.deepEqual(
            usableApiDocs(docs).map((d) => d.name),
            ["Button", "ButtonDefaults"],
        );
    });

    it("renders nothing for an older server that sends no links", () => {
        assert.deepEqual(usableApiDocs(undefined), []);
        assert.deepEqual(usableApiDocs(null), []);
        assert.deepEqual(usableApiDocs([]), []);
    });

    it("refuses any host but the one that publishes the pages", () => {
        assert.deepEqual(
            usableApiDocs([
                link({ url: "https://example.com/reference/kotlin/Button" }),
                link({ url: "https://developer.android.com.evil.test/x" }),
                link({
                    url: "http://developer.android.com/reference/kotlin/x",
                }),
                link({ url: "javascript:alert(1)" }),
                link({ url: "not a url" }),
            ]),
            [],
        );
    });

    it("drops an entry with nothing to label the link with", () => {
        assert.deepEqual(
            usableApiDocs([link({ name: "" }), link({ url: "" })]),
            [],
        );
    });
});
