// Text / i18n bundle presenter (#1057 Cluster D). Pins the three
// sub-section row shapes (drawn text, fonts, translations), the Google
// Fonts allowlist + `provider="google"` short-circuit, the overlay
// generation for truncated / overflowed text, and the wire payload
// shapes that the host posts back for `openResourceFile` (translation
// rows) and `openExternal` (Google Fonts cell).

import * as assert from "assert";
import {
    computeTextBundleData,
    cssFontStack,
    cssFontStackForPreview,
    fontPreviewFamilyName,
    googleFontsAllowlistSize,
    googleFontsSpecimenUrl,
    isGoogleFontFamily,
    translationOpenResourcePayload,
    type FontRow,
    type TranslationRow,
} from "../webview/preview/textBundlePresenter";
import {
    MAX_FONT_PREVIEW_BYTES,
    readFontPreview,
    resolveFontPreviewPath,
} from "../fontPreviewLoader";
import * as path from "path";

function stringEntry(overrides: Record<string, unknown> = {}): unknown {
    return {
        nodeId: "node-1",
        boundsInScreen: "0,0,100,30",
        localeTag: "en-US",
        fontScale: 1.0,
        text: "Hello",
        fontSize: "16sp",
        foregroundColor: "#000000",
        backgroundColor: "#ffffff",
        ...overrides,
    };
}

function fontEntry(overrides: Record<string, unknown> = {}): unknown {
    return {
        requestedFamily: "Roboto",
        resolvedFamily: "Roboto",
        weight: 400,
        style: "normal",
        consumerNodeIds: ["node-1"],
        ...overrides,
    };
}

function translationEntry(overrides: Record<string, unknown> = {}): unknown {
    return {
        rendered: "Hello",
        resourceName: "R.string.hello",
        sourceFile: "/abs/app/src/main/res/values/strings.xml",
        boundsInScreen: "0,0,100,30",
        translations: { "en-US": "Hello", "fr-FR": "Bonjour" },
        untranslatedLocales: ["de-DE"],
        ...overrides,
    };
}

describe("computeTextBundleData", () => {
    it("returns empty rows + empty overlay when all payloads are null", () => {
        const data = computeTextBundleData(null, null, null);
        assert.strictEqual(data.drawnText.length, 0);
        assert.strictEqual(data.fonts.length, 0);
        assert.strictEqual(data.translations.length, 0);
        assert.strictEqual(data.overlay.length, 0);
        // jsonPayload preserves nulls so the copy-JSON action stays
        // round-trippable without conditional cases in the caller.
        assert.strictEqual(data.jsonPayload.textStrings, null);
        assert.strictEqual(data.jsonPayload.fontsUsed, null);
        assert.strictEqual(data.jsonPayload.i18nTranslations, null);
    });

    it("populates drawn-text rows and flags overflow rows as warning overlays", () => {
        const data = computeTextBundleData(
            {
                texts: [
                    stringEntry({ nodeId: "node-a", text: "OK" }),
                    stringEntry({
                        nodeId: "node-b",
                        text: "Truncated…",
                        truncated: true,
                        boundsInScreen: "10,10,50,30",
                    }),
                    stringEntry({
                        nodeId: "node-c",
                        text: "Too wide",
                        didOverflowWidth: true,
                        boundsInScreen: "5,5,200,40",
                    }),
                ],
            },
            null,
            null,
        );
        assert.strictEqual(data.drawnText.length, 3);
        assert.strictEqual(data.drawnText[0].text, "OK");
        assert.strictEqual(data.drawnText[1].truncated, true);
        assert.strictEqual(data.drawnText[2].didOverflowWidth, true);
        // 3 rows have parseable bounds → 3 overlay boxes.
        assert.strictEqual(data.overlay.length, 3);
        // First row clean → info; the other two warn.
        assert.strictEqual(data.overlay[0].level, "info");
        assert.strictEqual(data.overlay[1].level, "warning");
        assert.strictEqual(data.overlay[2].level, "warning");
        // Tooltip carries the flag list so a hover surface shows why
        // the row is highlighted.
        assert.ok(data.overlay[1].tooltip?.includes("truncated"));
        assert.ok(data.overlay[2].tooltip?.includes("overflow-w"));
    });

    it("matches Google Fonts via the bundled allowlist", () => {
        // The producer-side `provider` field was reverted to fix a
        // Kotlin data-class binary-compat break against pre-#1081
        // consumers (#1057 schema bump back to v1). The bundled
        // allowlist is the only Google-Fonts signal today; any future
        // provenance tag will need to land via a non-breaking shape.
        const data = computeTextBundleData(
            null,
            { fonts: [fontEntry({ requestedFamily: "Roboto" })] },
            null,
        );
        assert.strictEqual(data.fonts.length, 1);
        assert.strictEqual((data.fonts[0] as FontRow).isGoogleFont, true);
        // A truly local family that isn't in the allowlist must NOT
        // light up as a Google link.
        const data2 = computeTextBundleData(
            null,
            { fonts: [fontEntry({ requestedFamily: "AcmeSans" })] },
            null,
        );
        assert.strictEqual((data2.fonts[0] as FontRow).isGoogleFont, false);
    });

    it("emits translation rows with locale counts derived from supportedLocales", () => {
        const data = computeTextBundleData(null, null, {
            supportedLocales: ["en-US", "fr-FR", "de-DE"],
            renderedLocale: "en-US",
            defaultLocale: "en-US",
            strings: [translationEntry()],
        });
        assert.strictEqual(data.translations.length, 1);
        const row = data.translations[0] as TranslationRow;
        assert.strictEqual(row.rendered, "Hello");
        assert.strictEqual(row.resourceName, "R.string.hello");
        assert.strictEqual(row.supportedLocaleCount, 3);
        assert.strictEqual(row.untranslatedLocaleCount, 1);
        assert.deepStrictEqual(row.untranslatedLocales, ["de-DE"]);
        // Translated locales are surfaced (sorted) so the chip tooltip
        // is deterministic.
        assert.deepStrictEqual(row.translatedLocales, ["en-US", "fr-FR"]);
    });

    it("builds the openResourceFile payload with resourceType=string and packageName=null", () => {
        const data = computeTextBundleData(null, null, {
            supportedLocales: ["en-US"],
            renderedLocale: "en-US",
            defaultLocale: "en-US",
            strings: [
                translationEntry({
                    resourceName: "R.string.welcome",
                    sourceFile: "/abs/strings.xml",
                }),
            ],
        });
        const payload = translationOpenResourcePayload(data.translations[0]);
        // Strip `R.string.` so the host-side resolver finds the bare
        // `<string name="welcome">` entry in the XML; without the
        // prefix-strip the jump-to-resource action silently no-ops.
        assert.deepStrictEqual(payload, {
            command: "openResourceFile",
            resourceType: "string",
            resourceName: "welcome",
            resolvedFile: "/abs/strings.xml",
            packageName: null,
        });
    });

    it("passes through resourceName when it lacks the R.string. prefix", () => {
        const data = computeTextBundleData(null, null, {
            supportedLocales: ["en-US"],
            renderedLocale: "en-US",
            defaultLocale: "en-US",
            strings: [
                translationEntry({
                    resourceName: "bare_name",
                    sourceFile: null,
                }),
            ],
        });
        const payload = translationOpenResourcePayload(data.translations[0]);
        assert.strictEqual(payload.resourceName, "bare_name");
    });

    it("produces a fonts.google.com specimen URL with URI-encoded family names", () => {
        // The `openExternal` payload is just `{ command, url }`; we pin
        // the URL builder here since it's the one piece the cell uses
        // when assembling the message.
        assert.strictEqual(
            googleFontsSpecimenUrl("Roboto"),
            "https://fonts.google.com/specimen/Roboto",
        );
        assert.strictEqual(
            googleFontsSpecimenUrl("Source Sans 3"),
            "https://fonts.google.com/specimen/Source%20Sans%203",
        );
        // Allowlist size is bounded so accidental "every font in the
        // world" regressions trip the test rather than the bundle size
        // budget.
        assert.ok(googleFontsAllowlistSize() >= 40);
        assert.ok(googleFontsAllowlistSize() <= 80);
    });

    it("isGoogleFontFamily is case-insensitive against the allowlist", () => {
        assert.strictEqual(isGoogleFontFamily("ROBOTO", null), true);
        assert.strictEqual(isGoogleFontFamily("roboto", undefined), true);
        assert.strictEqual(isGoogleFontFamily("Custom Family", null), false);
        // Empty / missing provider should still consult the allowlist
        // so v1 producers (no schema-v2 field) keep working.
        assert.strictEqual(isGoogleFontFamily("Inter", null), true);
    });

    it("skips drawn-text rows with no nodeId so a malformed payload doesn't crash the table", () => {
        const data = computeTextBundleData(
            {
                texts: [
                    stringEntry({ nodeId: "node-1" }),
                    // Missing nodeId — dropped.
                    { boundsInScreen: "0,0,1,1", text: "ghost" },
                    // Non-object — dropped.
                    "not-an-object",
                ],
            },
            null,
            null,
        );
        assert.strictEqual(data.drawnText.length, 1);
        assert.strictEqual(data.drawnText[0].nodeId, "node-1");
    });

    it("cssFontStack escapes both backslashes and quotes in family names", () => {
        // CodeQL regression: backslashes were not escaped before the
        // quoted CSS literal, so a malicious or pathological family
        // (e.g. `a\"; color:red;`) could break out of the string. Quote
        // *and* backslash escapes are now both applied; the resulting
        // CSS literal must round-trip through a regex unescape to the
        // original family.
        const fam = 'has"quote and\\backslash';
        const stack = cssFontStack(fam);
        // Open with opening quote, end with the fallback stack.
        assert.ok(
            stack.startsWith('"'),
            "result must start with a quoted family",
        );
        assert.ok(
            stack.endsWith(", system-ui, sans-serif"),
            "result must chain generic fallbacks",
        );
        // Pull the quoted segment and verify the literal escape
        // sequences for both characters survived.
        const quoted = stack.slice(0, stack.indexOf(", system-ui"));
        assert.ok(
            quoted.includes('\\"'),
            "double-quote inside the family must be backslash-escaped",
        );
        assert.ok(
            quoted.includes("\\\\"),
            "backslash inside the family must be doubled",
        );
    });

    it("cssFontStack passes generic families through unquoted", () => {
        assert.strictEqual(cssFontStack("monospace"), "monospace");
        assert.strictEqual(cssFontStack("serif"), "serif");
    });

    it("cssFontStackForPreview prepends the synthetic family when bytes have loaded", () => {
        // Pins the cache lookup vs the loading state: in the loading
        // state (no bytes yet) the cell renders against the CSS-only
        // fallback, identical to what `cssFontStack` returns. Once the
        // host has responded with bytes, the synthetic
        // `preview-<rowId>` family is prepended so the `@font-face`
        // rule wins in the font-family chain.
        const loading = cssFontStackForPreview("font-0", "Roboto", {
            hasBytes: false,
        });
        assert.strictEqual(loading, cssFontStack("Roboto"));
        const loaded = cssFontStackForPreview("font-0", "Roboto", {
            hasBytes: true,
        });
        assert.ok(
            loaded.startsWith('"preview-font-0", '),
            "synthetic family must be first in the stack",
        );
        assert.ok(
            loaded.endsWith(cssFontStack("Roboto")),
            "the resolved-family CSS stack must remain as a fallback",
        );
        assert.strictEqual(fontPreviewFamilyName("font-0"), "preview-font-0");
    });
});

describe("fontPreviewLoader", () => {
    const root = process.platform === "win32" ? "C:\\ws" : "/ws";

    it("rejects paths outside the workspace root (no path-traversal)", () => {
        const evil = path.join(root, "..", "etc", "passwd.ttf");
        assert.strictEqual(resolveFontPreviewPath(evil, root), null);
    });

    it("rejects relative paths", () => {
        assert.strictEqual(
            resolveFontPreviewPath("res/font/roboto.ttf", root),
            null,
        );
    });

    it("rejects unsupported extensions", () => {
        // `.xml` font descriptors (Android `res/font/foo.xml`) point at
        // the real face but are NOT themselves the bytes — refuse them
        // so the response stays `dataUri: null` and the webview keeps
        // the CSS-only fallback.
        const xml = path.join(root, "res", "font", "roboto.xml");
        assert.strictEqual(resolveFontPreviewPath(xml, root), null);
    });

    it("accepts a supported font extension inside the workspace", () => {
        const ttf = path.join(root, "app", "fonts", "Inter.ttf");
        const resolved = resolveFontPreviewPath(ttf, root);
        assert.ok(resolved);
        assert.strictEqual(resolved!.mime, "font/ttf");
        assert.strictEqual(resolved!.absolutePath, path.normalize(ttf));
    });

    it("maps each supported extension to its IANA media type", () => {
        const cases: ReadonlyArray<[string, string]> = [
            ["a.ttf", "font/ttf"],
            ["a.otf", "font/otf"],
            ["a.woff", "font/woff"],
            ["a.woff2", "font/woff2"],
            // Casing tolerance — Android assets occasionally ship as
            // `Roboto-Regular.TTF`.
            ["a.WOFF2", "font/woff2"],
        ];
        for (const [name, mime] of cases) {
            const resolved = resolveFontPreviewPath(
                path.join(root, name),
                root,
            );
            assert.ok(resolved, "should resolve " + name);
            assert.strictEqual(resolved!.mime, mime);
        }
    });

    it("rejects null / empty sourceFile or workspaceRoot", () => {
        assert.strictEqual(resolveFontPreviewPath(null, root), null);
        assert.strictEqual(resolveFontPreviewPath(undefined, root), null);
        assert.strictEqual(resolveFontPreviewPath("", root), null);
        assert.strictEqual(
            resolveFontPreviewPath(path.join(root, "a.ttf"), null),
            null,
        );
    });

    it("readFontPreview base64-encodes the bytes into a data URI", async () => {
        const bytes = Buffer.from([0x00, 0x01, 0x02, 0x03]);
        const dataUri = await readFontPreview(
            { absolutePath: "/whatever.ttf", mime: "font/ttf" },
            async () => bytes,
        );
        assert.strictEqual(
            dataUri,
            "data:font/ttf;base64," + bytes.toString("base64"),
        );
    });

    it("readFontPreview resolves to null on read failure", async () => {
        const dataUri = await readFontPreview(
            { absolutePath: "/whatever.ttf", mime: "font/ttf" },
            async () => {
                throw new Error("ENOENT");
            },
        );
        assert.strictEqual(dataUri, null);
    });

    it("readFontPreview rejects files larger than the 5 MB cap", async () => {
        // Synthesise an over-cap buffer cheaply by lying about its
        // length — we never inspect the bytes past the size check.
        const oversize = Buffer.alloc(MAX_FONT_PREVIEW_BYTES + 1);
        const dataUri = await readFontPreview(
            { absolutePath: "/big.ttf", mime: "font/ttf" },
            async () => oversize,
        );
        assert.strictEqual(dataUri, null);
    });
});
