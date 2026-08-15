// What the render-history menu decides to show, as a table.

import assert from "node:assert/strict";
import {
    historyMenuOf,
    type ManifestEntry,
} from "../src/viewer/historyModel.js";
import type { HistorySource } from "../src/viewer/historyUrls.js";

const HOSTED: HistorySource = {
    repoPath: "o/n",
    blobBase: null,
    blobQuery: "",
    local: false,
};
const LOCAL: HistorySource = {
    repoPath: null,
    blobBase: "/h/",
    blobQuery: "",
    local: true,
};
const sha = (c: string) => c.repeat(40);

const entry = (over: Partial<ManifestEntry> = {}): ManifestEntry => ({
    path: "renders/m3/Button.png",
    versions: [
        {
            commit: "aaaaaaa",
            date: "2026-08-15T10:00:00Z",
            sourceSha: "src1111",
        },
        { commit: "bbbbbbb", date: "2026-08-01T10:00:00Z" },
    ],
    observations: 7,
    ...over,
});

describe("historyMenuOf", () => {
    it("draws nothing for a single version", () => {
        // One version is not a timeline — the control would say nothing the page does not.
        assert.equal(
            historyMenuOf(HOSTED, entry({ versions: [{ commit: "aaaaaaa" }] })),
            null,
        );
        assert.equal(historyMenuOf(HOSTED, entry({ versions: [] })), null);
        assert.equal(historyMenuOf(HOSTED, null), null);
    });

    it("skips a version it cannot address rather than drawing a dead control", () => {
        const menu = historyMenuOf(
            HOSTED,
            entry({
                versions: [
                    { commit: "aaaaaaa", date: "2026-08-15T10:00:00Z" },
                    { commit: "main", date: "2026-08-10T10:00:00Z" },
                    { commit: "bbbbbbb", date: "2026-08-01T10:00:00Z" },
                ],
            }),
        );
        assert.equal(menu?.rows.length, 2, "the ref-named version is dropped");
        assert.equal(menu?.label, "2 versions");
    });

    it("falls back to nothing when skipping leaves fewer than two", () => {
        // The floor applies to what could be ADDRESSED, not to what the manifest claimed.
        assert.equal(
            historyMenuOf(
                HOSTED,
                entry({
                    versions: [
                        { commit: "aaaaaaa" },
                        { commit: "not-a-sha" },
                        { commit: "" },
                    ],
                }),
            ),
            null,
        );
    });

    it("marks only the newest hosted version as current", () => {
        const menu = historyMenuOf(HOSTED, entry());
        assert.equal(menu?.rows[0].current, true);
        assert.equal(menu?.rows[0].meta, "current");
        assert.equal(menu?.rows[1].current, false);
    });

    it("marks nothing current in project mode", () => {
        // The stage comes from the working tree there; the timeline is published baselines, so the
        // newest entry is the last publish rather than what you are looking at.
        const menu = historyMenuOf(
            LOCAL,
            entry({
                versions: [
                    { blob: sha("a"), date: "2026-08-15T10:00:00Z" },
                    { blob: sha("b"), date: "2026-08-01T10:00:00Z" },
                ],
            }),
        );
        assert.equal(
            menu?.rows.every((r) => !r.current),
            true,
        );
        assert.ok(menu?.note.includes("working tree"), menu?.note);
    });

    it("prefers the source sha over the publish marker", () => {
        const menu = historyMenuOf(HOSTED, entry());
        // The delivery-branch commit is just a publish marker; the source sha is the change
        // someone is actually looking for.
        assert.equal(menu?.rows[1].meta, "bbbbbbb");
        assert.ok(
            menu?.rows[0].title.includes("source src1111"),
            menu?.rows[0].title,
        );
    });

    it("shows how many publishes carried the same bytes", () => {
        const menu = historyMenuOf(
            HOSTED,
            entry({
                versions: [
                    { commit: "aaaaaaa", commits: 4 },
                    { commit: "bbbbbbb", commits: 1 },
                ],
            }),
        );
        assert.equal(menu?.rows[0].span, "×4");
        assert.ok(menu?.rows[0].spanTitle?.includes("4 publishes"));
        assert.equal(menu?.rows[1].span, null, "a single publish says nothing");
    });

    it("carries the instability warning with its count", () => {
        const menu = historyMenuOf(
            HOSTED,
            entry({ unstable: true, flapCount: 3 }),
        );
        assert.equal(menu?.unstable, true);
        assert.ok(
            menu?.unstableTitle.includes("3 returns"),
            menu?.unstableTitle,
        );
    });

    it("counts publishes from observations, falling back to the version count", () => {
        assert.ok(
            historyMenuOf(HOSTED, entry())?.note.includes("over 7 publishes"),
        );
        assert.ok(
            historyMenuOf(
                HOSTED,
                entry({ observations: undefined }),
            )?.note.includes("over 2 publishes"),
        );
    });
});
