import * as assert from "assert";
import {
    cssEscape,
    escapeHtml,
    findLatestMainHash,
    formatAbsolute,
    formatRelative,
} from "../webview/history/historyData";
import type { HistoryEntry } from "../types";

describe("escapeHtml", () => {
    it("escapes the five HTML-significant characters", () => {
        assert.strictEqual(
            escapeHtml(`<a href="x">'foo' & "bar"</a>`),
            "&lt;a href=&quot;x&quot;&gt;&#39;foo&#39; &amp; &quot;bar&quot;&lt;/a&gt;",
        );
    });

    it("returns an empty string for null / undefined", () => {
        assert.strictEqual(escapeHtml(null), "");
        assert.strictEqual(escapeHtml(undefined), "");
    });

    it("coerces non-string inputs via String()", () => {
        assert.strictEqual(escapeHtml(42), "42");
        assert.strictEqual(escapeHtml(false), "false");
    });

    it("encodes & before the more specific entities so '&amp;' isn't double-escaped", () => {
        assert.strictEqual(escapeHtml("&"), "&amp;");
        assert.strictEqual(escapeHtml("<&>"), "&lt;&amp;&gt;");
    });
});

describe("cssEscape", () => {
    it('escapes quotes and backslashes for use in a `[data-id="..."]` selector', () => {
        assert.strictEqual(cssEscape(`a"b'c\\d`), `a\\"b\\'c\\\\d`);
    });

    it("returns plain ids unchanged", () => {
        assert.strictEqual(cssEscape("simple-id_1"), "simple-id_1");
    });

    it("coerces non-string inputs via String()", () => {
        assert.strictEqual(cssEscape(42 as unknown as string), "42");
    });
});

describe("formatRelative", () => {
    // Use a fixed `now` so the bucket boundaries are deterministic.
    const NOW = Date.parse("2026-05-01T12:00:00Z");

    it("returns '(no timestamp)' for empty input", () => {
        assert.strictEqual(formatRelative("", NOW), "(no timestamp)");
        assert.strictEqual(formatRelative(undefined, NOW), "(no timestamp)");
    });

    it("returns the input verbatim for unparseable ISO strings", () => {
        assert.strictEqual(formatRelative("not-a-date", NOW), "not-a-date");
    });

    it("returns 'just now' under 5s", () => {
        const ts = new Date(NOW - 3_000).toISOString();
        assert.strictEqual(formatRelative(ts, NOW), "just now");
    });

    it("returns 'Ns ago' for under a minute", () => {
        const ts = new Date(NOW - 30_000).toISOString();
        assert.strictEqual(formatRelative(ts, NOW), "30s ago");
    });

    it("returns 'Nm ago' for under an hour", () => {
        const ts = new Date(NOW - 5 * 60_000).toISOString();
        assert.strictEqual(formatRelative(ts, NOW), "5m ago");
    });

    it("returns 'Nh ago' for under a day", () => {
        const ts = new Date(NOW - 3 * 60 * 60_000).toISOString();
        assert.strictEqual(formatRelative(ts, NOW), "3h ago");
    });

    it("returns 'Nd ago' for under a month", () => {
        const ts = new Date(NOW - 2 * 24 * 60 * 60_000).toISOString();
        assert.strictEqual(formatRelative(ts, NOW), "2d ago");
    });

    it("returns 'Nmo ago' for under a year", () => {
        const ts = new Date(NOW - 90 * 24 * 60 * 60_000).toISOString();
        assert.strictEqual(formatRelative(ts, NOW), "3mo ago");
    });

    it("returns 'Ny ago' for ages over a year", () => {
        const ts = new Date(NOW - 730 * 24 * 60 * 60_000).toISOString();
        assert.strictEqual(formatRelative(ts, NOW), "2y ago");
    });
});

describe("formatAbsolute", () => {
    it("returns an empty string for empty / unparseable input", () => {
        assert.strictEqual(formatAbsolute(""), "");
        assert.strictEqual(formatAbsolute(undefined), "");
        assert.strictEqual(formatAbsolute("nope"), "");
    });

    it("returns a non-empty localised label for a valid ISO timestamp", () => {
        // Locale output is environment-dependent — assert the shape rather
        // than the literal string. The 4-field format with a comma is the
        // common output for the en-US default options.
        const out = formatAbsolute("2026-05-01T12:00:00Z");
        assert.ok(out.length > 0, "non-empty output expected, got " + out);
    });
});

const baseEntry: HistoryEntry = {};

describe("findLatestMainHash", () => {
    it("returns null when no entries match", () => {
        assert.strictEqual(findLatestMainHash([]), null);
        assert.strictEqual(
            findLatestMainHash([
                { ...baseEntry, git: { branch: "feature/x" } },
            ]),
            null,
        );
    });

    it("ignores entries whose branch is not main", () => {
        const entries: HistoryEntry[] = [
            {
                ...baseEntry,
                pngHash: "abc",
                timestamp: "2026-05-01T10:00:00Z",
                git: { branch: "feature/x" },
            },
        ];
        assert.strictEqual(findLatestMainHash(entries), null);
    });

    it("ignores main-branch entries that lack pngHash", () => {
        const entries: HistoryEntry[] = [
            {
                ...baseEntry,
                git: { branch: "main" },
                timestamp: "2026-05-01T10:00:00Z",
            },
        ];
        assert.strictEqual(findLatestMainHash(entries), null);
    });

    it("picks the latest main entry by ISO timestamp string-compare", () => {
        const entries: HistoryEntry[] = [
            {
                ...baseEntry,
                pngHash: "old",
                timestamp: "2026-04-01T10:00:00Z",
                git: { branch: "main" },
            },
            {
                ...baseEntry,
                pngHash: "new",
                timestamp: "2026-05-01T10:00:00Z",
                git: { branch: "main" },
            },
        ];
        assert.strictEqual(findLatestMainHash(entries), "new");
    });

    it("treats missing timestamps as the empty string (oldest possible)", () => {
        const entries: HistoryEntry[] = [
            { ...baseEntry, pngHash: "first", git: { branch: "main" } },
            {
                ...baseEntry,
                pngHash: "stamped",
                timestamp: "2026-05-01T10:00:00Z",
                git: { branch: "main" },
            },
        ];
        assert.strictEqual(findLatestMainHash(entries), "stamped");
    });
});
