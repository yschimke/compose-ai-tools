// Pins the Compose snippet codegen for the font browser's customiser.

import * as assert from "assert";
import {
    fontIdentifier,
    generateComposeSnippet,
} from "../webview/fonts/composeFontSnippet";
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

describe("composeFontSnippet", () => {
    it("derives PascalCase identifiers", () => {
        assert.strictEqual(fontIdentifier("Open Sans"), "OpenSans");
        assert.strictEqual(fontIdentifier("roboto-flex"), "RobotoFlex");
    });

    it("emits a downloadable static FontFamily without variation settings", () => {
        const out = generateComposeSnippet({
            family: "Lora",
            weight: 700,
            italic: true,
            isVariable: false,
            axisValues: {},
            axes: [],
            fontSizeSp: 16,
            letterSpacingSp: 0.5,
            lineHeightSp: 24,
        });
        // Downloadable Google Fonts provider — not a bundled R.font resource.
        assert.ok(out.includes("GoogleFont.Provider("));
        // Certs array is fully-qualified to the ui-text-google-fonts R so the
        // snippet compiles under non-transitive R (the AGP default).
        assert.ok(
            out.includes(
                "androidx.compose.ui.text.googlefonts.R.array.com_google_android_gms_fonts_certs",
            ),
        );
        assert.ok(out.includes("val LoraFontFamily = FontFamily("));
        assert.ok(out.includes('googleFont = GoogleFont("Lora")'));
        assert.ok(out.includes("fontProvider = provider"));
        assert.ok(!out.includes("R.font."));
        assert.ok(out.includes("weight = FontWeight(700)"));
        assert.ok(out.includes("style = FontStyle.Italic"));
        assert.ok(!out.includes("FontVariation"));
        assert.ok(out.includes("fontSize = 16.sp"));
        assert.ok(out.includes("letterSpacing = 0.50.sp"));
        assert.ok(out.includes("fontStyle = FontStyle.Italic"));
    });

    it("emits FontVariation.Settings for downloadable variable fonts", () => {
        const out = generateComposeSnippet({
            family: "Roboto Flex",
            weight: 500,
            italic: false,
            isVariable: true,
            axisValues: { wght: 500, opsz: 40 },
            axes: [wght, opsz],
            fontSizeSp: 32,
            letterSpacingSp: 0,
            lineHeightSp: 40,
        });
        assert.ok(out.includes('googleFont = GoogleFont("Roboto Flex")'));
        assert.ok(out.includes("variationSettings = FontVariation.Settings("));
        assert.ok(out.includes("FontVariation.weight(500)"));
        assert.ok(out.includes('FontVariation.Setting("opsz", 40f)'));
        // wght goes through the dedicated helper, not a generic Setting.
        assert.ok(!out.includes('FontVariation.Setting("wght"'));
        // The variation-on-downloadable-font core requirement is called out.
        assert.ok(out.includes("androidx.core:core:1.19.0"));
    });
});
