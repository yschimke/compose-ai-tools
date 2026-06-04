// Pins the webview-side font-browser helpers (font-face CSS, face
// picking, axis defaults, variation settings).

import * as assert from "assert";
import {
    buildFontFaceCss,
    cssVariationSettings,
    defaultAxisValues,
    pickFace,
    sliderAxes,
    webviewFamilyName,
    weightRange,
    type DownloadedFontView,
} from "../webview/fonts/fontBrowserLogic";
import type { FontAxis } from "../googleFontsCatalog";

const wght: FontAxis = {
    tag: "wght",
    displayName: "Weight",
    min: 100,
    max: 900,
    defaultValue: 400,
};
const opsz: FontAxis = {
    tag: "opsz",
    displayName: "Optical Size",
    min: 8,
    max: 144,
    defaultValue: 14,
};

const staticFont: DownloadedFontView = {
    family: "Roboto",
    familyId: "roboto",
    category: "Sans Serif",
    isVariable: false,
    axes: [],
    faces: [
        {
            style: "normal",
            weightMin: 400,
            weightMax: 400,
            uri: "vscode://r400",
            format: "woff2",
        },
        {
            style: "normal",
            weightMin: 700,
            weightMax: 700,
            uri: "vscode://r700",
            format: "woff2",
        },
        {
            style: "italic",
            weightMin: 400,
            weightMax: 400,
            uri: "vscode://i400",
            format: "woff2",
        },
    ],
};

const variableFont: DownloadedFontView = {
    family: "Roboto Flex",
    familyId: "roboto-flex",
    category: "Sans Serif",
    isVariable: true,
    axes: [wght, opsz],
    faces: [
        {
            style: "normal",
            weightMin: 100,
            weightMax: 900,
            uri: "vscode://vf",
            format: "woff2",
        },
    ],
};

describe("fontBrowserLogic", () => {
    it("namespaces webview font families", () => {
        assert.strictEqual(webviewFamilyName("roboto"), "gfb-roboto");
    });

    it("builds @font-face rules with mapped formats and weight ranges", () => {
        const css = buildFontFaceCss(variableFont);
        assert.ok(css.includes('font-family: "gfb-roboto-flex"'));
        assert.ok(css.includes("font-weight: 100 900"));
        assert.ok(css.includes('src: url("vscode://vf") format("woff2")'));
    });

    it("picks the nearest weight within the requested style", () => {
        assert.strictEqual(
            pickFace(staticFont, 600, false)!.uri,
            "vscode://r700",
        );
        assert.strictEqual(
            pickFace(staticFont, 450, false)!.uri,
            "vscode://r400",
        );
        assert.strictEqual(
            pickFace(staticFont, 400, true)!.uri,
            "vscode://i400",
        );
    });

    it("falls back across styles when the requested style is absent", () => {
        // No italic faces → falls back to the normal pool.
        assert.strictEqual(
            pickFace(variableFont, 400, true)!.uri,
            "vscode://vf",
        );
    });

    it("matches any weight inside a variable face's range", () => {
        assert.strictEqual(
            pickFace(variableFont, 250, false)!.uri,
            "vscode://vf",
        );
    });

    it("derives weight range from the wght axis or static faces", () => {
        assert.deepStrictEqual(weightRange(variableFont), {
            min: 100,
            max: 900,
        });
        assert.deepStrictEqual(weightRange(staticFont), { min: 400, max: 700 });
    });

    it("defaults axis values and excludes wght/ital from sliders", () => {
        assert.deepStrictEqual(defaultAxisValues(variableFont.axes), {
            wght: 400,
            opsz: 14,
        });
        assert.deepStrictEqual(
            sliderAxes(variableFont).map((a) => a.tag),
            ["opsz"],
        );
    });

    it("formats font-variation-settings", () => {
        assert.strictEqual(
            cssVariationSettings({ wght: 500, opsz: 40 }),
            '"wght" 500, "opsz" 40',
        );
        assert.strictEqual(cssVariationSettings({}), "normal");
    });
});
