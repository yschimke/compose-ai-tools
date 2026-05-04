import * as assert from "assert";
import { formatRelativeShort } from "../relativeTime";

const NOW_MS = Date.parse("2026-05-04T12:00:00Z");
const now = () => NOW_MS;

describe("formatRelativeShort", () => {
    it("returns '(unknown)' when the input is undefined", () => {
        assert.strictEqual(formatRelativeShort(undefined, now), "(unknown)");
    });

    it("echoes the input verbatim when it isn't a parseable timestamp", () => {
        assert.strictEqual(
            formatRelativeShort("not-a-date", now),
            "not-a-date",
        );
    });

    it("uses seconds when the gap is < 60s", () => {
        assert.strictEqual(
            formatRelativeShort("2026-05-04T11:59:30Z", now),
            "30s ago",
        );
    });

    it("uses minutes when the gap is between 1m and 60m", () => {
        assert.strictEqual(
            formatRelativeShort("2026-05-04T11:30:00Z", now),
            "30m ago",
        );
    });

    it("uses hours when the gap is between 1h and 24h", () => {
        assert.strictEqual(
            formatRelativeShort("2026-05-04T07:00:00Z", now),
            "5h ago",
        );
    });

    it("uses days when the gap is >= 24h", () => {
        assert.strictEqual(
            formatRelativeShort("2026-05-01T12:00:00Z", now),
            "3d ago",
        );
    });

    it("rounds rather than truncating boundary-crossing gaps", () => {
        // 89 seconds → 1m (round(89/60) = round(1.4833) = 1).
        assert.strictEqual(
            formatRelativeShort("2026-05-04T11:58:31Z", now),
            "1m ago",
        );
        // 91 seconds → 2m (round(91/60) = round(1.5166) = 2).
        assert.strictEqual(
            formatRelativeShort("2026-05-04T11:58:29Z", now),
            "2m ago",
        );
    });

    it("uses Date.now by default when no clock is injected", () => {
        // Wall-clock comparison: a future-dated input gives a negative-second
        // result that's still a string, so we just sanity-check the shape.
        const result = formatRelativeShort(new Date().toISOString());
        assert.match(result, /^-?\d+(s|m|h|d) ago$/);
    });
});
