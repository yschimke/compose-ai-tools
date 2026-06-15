// Coverage for the client-side theme data-diff (#1872) used by the history panel. Pure-data tests
// (no DOM): a base/head pair of `compose/theme` payloads in, a `ThemeDelta` out.

import * as assert from "assert";

import {
    diffTheme,
    isThemePayload,
    themeDeltaIsEmpty,
    type ThemePayloadLike,
} from "../webview/shared/themeDiff";

function payload(resolvedTokens: unknown): ThemePayloadLike {
    return { resolvedTokens } as ThemePayloadLike;
}

describe("diffTheme", () => {
    it("detects changed, added, and removed tokens across categories", () => {
        const base = payload({
            colorScheme: { primary: "#FF0000", secondary: "#00FF00" },
            typography: { bodyLarge: { fontFamily: "Roboto", fontSize: 14 } },
            shapes: {
                small: "RoundedCorner(4dp)",
                large: "RoundedCorner(16dp)",
            },
        });
        const head = payload({
            // primary changed; secondary removed; tertiary added.
            colorScheme: { primary: "#0000FF", tertiary: "#FFFF00" },
            // bodyLarge font size changed.
            typography: { bodyLarge: { fontFamily: "Roboto", fontSize: 16 } },
            // large unchanged; small unchanged.
            shapes: {
                small: "RoundedCorner(4dp)",
                large: "RoundedCorner(16dp)",
            },
        });

        const delta = diffTheme(base, head);

        assert.deepStrictEqual(delta.added, [
            { category: "color", key: "tertiary", value: "#FFFF00" },
        ]);
        assert.deepStrictEqual(delta.removed, [
            { category: "color", key: "secondary", value: "#00FF00" },
        ]);
        assert.strictEqual(delta.changed.length, 2);
        // Colors come before typography in category order.
        assert.deepStrictEqual(delta.changed[0], {
            category: "color",
            key: "primary",
            from: "#FF0000",
            to: "#0000FF",
        });
        assert.strictEqual(delta.changed[1].category, "typography");
        assert.strictEqual(delta.changed[1].key, "bodyLarge");
        assert.ok(delta.changed[1].from.includes("14"));
        assert.ok(delta.changed[1].to.includes("16"));
    });

    it("returns an empty delta for identical token maps", () => {
        const tokens = {
            colorScheme: { primary: "#FF0000" },
            typography: { bodyLarge: { fontFamily: "Roboto", fontSize: 14 } },
            shapes: { small: "RoundedCorner(4dp)" },
        };
        const delta = diffTheme(payload(tokens), payload({ ...tokens }));
        assert.ok(themeDeltaIsEmpty(delta));
        assert.deepStrictEqual(delta.added, []);
        assert.deepStrictEqual(delta.removed, []);
        assert.deepStrictEqual(delta.changed, []);
    });

    it("tolerates missing categories on either side", () => {
        const base = payload({ colorScheme: { primary: "#FF0000" } });
        const head = payload({ shapes: { small: "Cut(4dp)" } });
        const delta = diffTheme(base, head);
        assert.deepStrictEqual(delta.removed, [
            { category: "color", key: "primary", value: "#FF0000" },
        ]);
        assert.deepStrictEqual(delta.added, [
            { category: "shape", key: "small", value: "Cut(4dp)" },
        ]);
        assert.deepStrictEqual(delta.changed, []);
    });

    it("formats typography changes as readable one-liners", () => {
        const base = payload({
            typography: {
                titleLarge: {
                    fontFamily: "Roboto",
                    fontSize: 22,
                    fontSizeUnit: "sp",
                    fontWeight: "400",
                },
            },
        });
        const head = payload({
            typography: {
                titleLarge: {
                    fontFamily: "Roboto",
                    fontSize: 22,
                    fontSizeUnit: "sp",
                    fontWeight: "700",
                },
            },
        });
        const delta = diffTheme(base, head);
        assert.strictEqual(delta.changed.length, 1);
        assert.match(delta.changed[0].from, /Roboto · 22sp · w400/);
        assert.match(delta.changed[0].to, /Roboto · 22sp · w700/);
    });
});

describe("isThemePayload", () => {
    it("accepts a { resolvedTokens } object and rejects others", () => {
        assert.ok(isThemePayload({ resolvedTokens: { colorScheme: {} } }));
        assert.ok(!isThemePayload({ resolvedTokens: null }));
        assert.ok(!isThemePayload({}));
        assert.ok(!isThemePayload(null));
        assert.ok(!isThemePayload("nope"));
    });
});
