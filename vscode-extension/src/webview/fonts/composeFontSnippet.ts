// Pure Compose-snippet codegen for the font browser's customiser.
//
// Everything the browser lists is a Google **downloadable** font, so the
// emitted Kotlin uses the Google Fonts provider API
// (`androidx.compose.ui.text.googlefonts`) rather than a bundled
// `R.font.<name>` resource: a `GoogleFont.Provider` plus
// `Font(googleFont = …, fontProvider = …)`. For variable fonts we also
// emit `variationSettings` — downloadable variable fonts gained
// variation support in `androidx.core:core:1.19.0`.
//
// No DOM / node deps so it's unit-tested directly and shared by the
// webview bundle.

import type { FontAxis } from "../../googleFontsCatalog";

export interface SnippetOptions {
    family: string;
    weight: number;
    italic: boolean;
    isVariable: boolean;
    /** Variable-axis values keyed by tag (only consulted when variable). */
    axisValues: Record<string, number>;
    /** Axis metadata, used to order/emit the variation settings. */
    axes: readonly FontAxis[];
    fontSizeSp: number;
    letterSpacingSp: number;
    lineHeightSp: number;
}

/** PascalCase identifier prefix for the generated `val`s. */
export function fontIdentifier(family: string): string {
    const parts = family
        .replace(/[^a-zA-Z0-9]+/g, " ")
        .trim()
        .split(/\s+/)
        .filter(Boolean);
    if (parts.length === 0) return "Custom";
    return parts.map((p) => p.charAt(0).toUpperCase() + p.slice(1)).join("");
}

function fmt(value: number): string {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

/** Escape a family name for embedding in a Kotlin string literal. */
function kotlinString(value: string): string {
    return value.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

// The standard Google Fonts downloadable-font provider — the same
// `com.google.android.gms.fonts` authority every consumer declares once.
const PROVIDER_BLOCK = [
    "val provider = GoogleFont.Provider(",
    '    providerAuthority = "com.google.android.gms.fonts",',
    '    providerPackage = "com.google.android.gms",',
    // The certs array ships in androidx.compose.ui:ui-text-google-fonts, so
    // fully-qualify its R — an unqualified `R` resolves to the app module
    // (which lacks it) under non-transitive R, the AGP default.
    "    certificates =",
    "        androidx.compose.ui.text.googlefonts.R.array" +
        ".com_google_android_gms_fonts_certs,",
    ")",
].join("\n");

/**
 * Generate the Compose downloadable-`FontFamily` + `TextStyle` snippet
 * reflecting the current customiser selections.
 */
export function generateComposeSnippet(opts: SnippetOptions): string {
    const id = fontIdentifier(opts.family);
    const styleEnum = opts.italic ? "FontStyle.Italic" : "FontStyle.Normal";

    const fontArgs: string[] = [
        `googleFont = GoogleFont("${kotlinString(opts.family)}")`,
        `fontProvider = provider`,
        `weight = FontWeight(${opts.weight})`,
        `style = ${styleEnum}`,
    ];

    if (opts.isVariable) {
        const settings: string[] = [];
        // Pin weight (+ italic) through the variation API so the
        // downloadable variable font renders exactly what the
        // customiser shows.
        settings.push(`FontVariation.weight(${opts.weight})`);
        if (opts.italic) settings.push(`FontVariation.italic(1f)`);
        for (const axis of opts.axes) {
            if (axis.tag === "wght" || axis.tag === "ital") continue;
            const value = opts.axisValues[axis.tag];
            if (value == null) continue;
            settings.push(
                `FontVariation.Setting("${axis.tag}", ${fmt(value)}f)`,
            );
        }
        const indented = settings.map((s) => `            ${s},`).join("\n");
        fontArgs.push(
            `variationSettings = FontVariation.Settings(\n${indented}\n        )`,
        );
    }

    const fontArgsBlock = fontArgs.map((a) => `        ${a},`).join("\n");

    const lines: string[] = [];
    if (opts.isVariable) {
        // variationSettings on a downloadable font needs core 1.19.0+.
        lines.push(
            "// Variable-axis variationSettings on downloadable fonts requires",
            "// androidx.core:core:1.19.0+ and ui-text-google-fonts.",
        );
    }
    lines.push(
        PROVIDER_BLOCK,
        "",
        `val ${id}FontFamily = FontFamily(`,
        `    Font(`,
        fontArgsBlock,
        `    ),`,
        `)`,
        ``,
        `val ${id}TextStyle = TextStyle(`,
        `    fontFamily = ${id}FontFamily,`,
        `    fontWeight = FontWeight(${opts.weight}),`,
        `    fontStyle = ${styleEnum},`,
        `    fontSize = ${fmt(opts.fontSizeSp)}.sp,`,
        `    letterSpacing = ${fmt(opts.letterSpacingSp)}.sp,`,
        `    lineHeight = ${fmt(opts.lineHeightSp)}.sp,`,
        `)`,
    );
    return lines.join("\n");
}
