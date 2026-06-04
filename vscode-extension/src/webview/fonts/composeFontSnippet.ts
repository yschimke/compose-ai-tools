// Pure Compose-snippet codegen for the font browser's customiser.
//
// Given the live attribute selections (weight, italic, and — for
// variable fonts — the per-axis values) this emits a Kotlin
// `FontFamily` + `TextStyle` the user can paste into a Compose project.
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

/** Android `res/font` resource name (lowercase, underscores only). */
export function fontResourceName(family: string): string {
    return (
        family
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "_")
            .replace(/^_+|_+$/g, "") || "font"
    );
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

/**
 * Generate the Compose `FontFamily` + `TextStyle` snippet reflecting the
 * current customiser selections.
 */
export function generateComposeSnippet(opts: SnippetOptions): string {
    const res = fontResourceName(opts.family);
    const id = fontIdentifier(opts.family);
    const styleEnum = opts.italic ? "FontStyle.Italic" : "FontStyle.Normal";

    const fontArgs: string[] = [
        `resId = R.font.${res}`,
        `weight = FontWeight(${opts.weight})`,
        `style = ${styleEnum}`,
    ];

    if (opts.isVariable) {
        const settings: string[] = [];
        // Always pin weight + italic through the variation API so the
        // variable file renders exactly what the customiser shows.
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

    return [
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
    ].join("\n");
}
