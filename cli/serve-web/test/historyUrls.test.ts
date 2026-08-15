// The render-history URL rules, as a table.
//
// `viewer-history.js` had no test, and its comments record that three earlier attempts got this
// wrong in the same way — validating a string and then handing the *same* string to an `href`,
// which is the `js/xss-through-dom` flow. The rule that fixes it is "match, then rebuild from the
// captured segments", and the only way to know it still holds is to try the inputs it exists to
// reject.

import assert from "node:assert/strict";
import {
    blobTemplateOf,
    historySourceOf,
    reencode,
    renderUrlAt,
    repoPathOf,
    shortDate,
    type HistorySource,
} from "../src/viewer/historyUrls.js";

const HOSTED: HistorySource = {
    repoPath: "yschimke/compose-ai-tools",
    blobBase: null,
    blobQuery: "",
    local: false,
};
const LOCAL: HistorySource = {
    repoPath: null,
    blobBase: "/history/blob/",
    blobQuery: "?token=abc",
    local: true,
};
const SHA40 = "a".repeat(40);

describe("repoPathOf", () => {
    it("accepts a real owner/name and returns identical bytes", () => {
        // Every character the pattern admits is URI-unreserved, so encoding is a no-op on real
        // values — the wire format does not change just because the value is rebuilt.
        assert.equal(
            repoPathOf("yschimke/compose-ai-tools"),
            "yschimke/compose-ai-tools",
        );
        assert.equal(repoPathOf("a.b-c_d/e.f-g_h"), "a.b-c_d/e.f-g_h");
    });

    it("rejects anything that is not exactly one owner and one name", () => {
        for (const bad of [
            "",
            "noslash",
            "a/b/c",
            "/leading",
            "trailing/",
            ".dotfirst/name",
            "owner/.dotfirst",
        ]) {
            assert.equal(repoPathOf(bad), null, `${bad} must not draw a strip`);
        }
    });

    it("rejects a value carrying URL structure or a scheme", () => {
        // These are the ones that matter: a passed-through value here lands in an href.
        for (const bad of [
            "javascript:alert(1)/x",
            "a/b?x=1",
            "a/b#frag",
            "evil.com%2Fx/y",
            "a b/c",
            "../../etc/passwd",
        ]) {
            assert.equal(repoPathOf(bad), null, `${bad} must be refused`);
        }
    });
});

describe("blobTemplateOf", () => {
    it("splits a site-relative template around the placeholder", () => {
        const t = blobTemplateOf("/history/blob/{blob}.png?token=abc");
        assert.deepEqual(t, { base: "/history/blob/", query: "?token=abc" });
    });

    it("keeps a template with no query", () => {
        assert.deepEqual(blobTemplateOf("/h/{blob}.png"), {
            base: "/h/",
            query: "",
        });
    });

    it("round-trips an already-encoded segment instead of double-encoding it", () => {
        // `%3A` → `:` → `%3A`, not `%253A`.
        const t = blobTemplateOf("/h/a%3Ab/{blob}.png");
        assert.equal(t?.base, "/h/a%3Ab/");
    });

    it("refuses a protocol-relative or absolute URL", () => {
        // The character class has to admit `/` as a separator, so the lookahead is what stops
        // `//host/…` from being read as site-relative.
        assert.equal(blobTemplateOf("//evil.com/{blob}.png"), null);
        assert.equal(blobTemplateOf("https://evil.com/{blob}.png"), null);
        assert.equal(blobTemplateOf("javascript:/{blob}.png"), null);
    });

    it("refuses a template that is not the expected shape", () => {
        for (const bad of [
            "/h/no-placeholder.png",
            "/h/{blob}.jpg",
            "relative/{blob}.png",
            "",
        ]) {
            assert.equal(blobTemplateOf(bad), null, bad);
        }
    });
});

describe("historySourceOf", () => {
    it("prefers the delivery repo when there is one", () => {
        const s = historySourceOf("owner/name", "/h/{blob}.png");
        assert.equal(s?.local, false);
        assert.equal(s?.repoPath, "owner/name");
    });

    it("falls back to the content-addressed lane in project mode", () => {
        const s = historySourceOf(null, "/h/{blob}.png");
        assert.equal(s?.local, true);
        assert.equal(s?.blobBase, "/h/");
    });

    it("yields nothing when neither is usable, so no strip is drawn", () => {
        assert.equal(historySourceOf(null, null), null);
        assert.equal(historySourceOf("not a repo", null), null);
        assert.equal(historySourceOf(null, "//evil.com/{blob}.png"), null);
    });
});

describe("renderUrlAt", () => {
    it("addresses a published render by commit and path", () => {
        assert.equal(
            renderUrlAt(
                HOSTED,
                { commit: "abc1234" },
                "renders/compose-m3/Button.png",
            ),
            "https://raw.githubusercontent.com/yschimke/compose-ai-tools/abc1234/renders/compose-m3/Button.png",
        );
    });

    it("requires a sha, never a ref", () => {
        // The manifest records shas; accepting a ref would let a malformed manifest point the
        // viewer at an arbitrary branch.
        for (const bad of [
            "main",
            "refs/heads/main",
            "abc123",
            "",
            "z".repeat(40),
        ]) {
            assert.equal(
                renderUrlAt(HOSTED, { commit: bad }, "renders/a.png"),
                null,
                `${bad} is not a sha`,
            );
        }
    });

    it("confines the path to renders/ and refuses traversal", () => {
        for (const bad of [
            "other/a.png",
            "renders/../../etc/passwd",
            "..",
            "",
        ]) {
            assert.equal(
                renderUrlAt(HOSTED, { commit: "abc1234" }, bad),
                null,
                bad,
            );
        }
        assert.equal(renderUrlAt(HOSTED, { commit: "abc1234" }, null), null);
    });

    it("encodes each path segment without eating the separators", () => {
        const url = renderUrlAt(
            HOSTED,
            { commit: "abc1234" },
            "renders/a b/c#d.png",
        );
        assert.ok(url?.endsWith("/renders/a%20b/c%23d.png"), url ?? "null");
    });

    it("addresses a project-mode render by its content sha", () => {
        assert.equal(
            renderUrlAt(LOCAL, { blob: SHA40 }, null),
            `/history/blob/${SHA40}.png?token=abc`,
        );
    });

    it("requires a full 40-hex blob sha", () => {
        for (const bad of [
            "a".repeat(39),
            "A".repeat(40),
            "g".repeat(40),
            "",
        ]) {
            assert.equal(renderUrlAt(LOCAL, { blob: bad }, null), null, bad);
        }
    });

    it("keys on the mode, not on which field happens to be set", () => {
        // A page carrying both must stay coherent: one flag decides both how an entry is
        // addressed and how it is labelled.
        assert.equal(
            renderUrlAt(
                LOCAL,
                { blob: SHA40, commit: "abc1234" },
                "renders/a.png",
            ),
            `/history/blob/${SHA40}.png?token=abc`,
        );
        assert.equal(
            renderUrlAt(
                HOSTED,
                { blob: SHA40, commit: "abc1234" },
                "renders/a.png",
            ),
            "https://raw.githubusercontent.com/yschimke/compose-ai-tools/abc1234/renders/a.png",
        );
    });
});

describe("reencode", () => {
    it("is idempotent on an already-encoded word", () => {
        assert.equal(reencode("a%3Ab"), "a%3Ab");
        assert.equal(reencode(reencode("a:b")), reencode("a:b"));
    });

    it("encodes a literal stray percent rather than throwing", () => {
        assert.equal(reencode("100%"), "100%25");
    });
});

describe("shortDate", () => {
    it("takes the date off an ISO timestamp", () => {
        assert.equal(shortDate("2026-08-15T09:30:00.000Z"), "2026-08-15");
    });

    it("is empty for anything that is not one", () => {
        for (const bad of ["", null, undefined, "yesterday", "15/08/2026"]) {
            assert.equal(shortDate(bad), "");
        }
    });
});
