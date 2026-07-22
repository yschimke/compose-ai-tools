// Pure host-side builder for the resource-preview hover markdown.
//
// Lives outside the VS Code provider so Mocha unit tests can exercise it
// without the extension host — Mocha can't load `vscode` module imports.
// The thin wrapper (`manifestResourceHoverProvider.ts`) is what reads PNG
// bytes off disk, constructs a `MarkdownString`, and registers with the
// editor.
//
// Each adaptive-icon `ResourcePreview` can have up to four captures
// (FULL_COLOR / THEMED_LIGHT / THEMED_DARK / LEGACY) under the same
// resource id; vector / animated-vector resources collapse to a single
// capture. The markdown shows every available capture side by side,
// labelled with shape + style so the user sees what a launcher icon
// actually looks like under the themed-icon mode without leaving the
// editor.

import { ResourceCapture, ResourcePreview } from "./types";

/**
 * Square edge of each rendered variant `<img>` in the hover. Small enough
 * that four adaptive-icon variants fit one row in VS Code's hover popover;
 * large enough that the masked-shape and themed palette are legible.
 * Matches the 120px peak used by `previewHoverProvider`'s single-image
 * case at roughly two-thirds the area — four variants together stay
 * under the popover's vertical budget.
 */
export const VARIANT_HOVER_IMG_PX = 80;

/**
 * One image the hover renderer wants to display. Host code reads PNG
 * bytes off `<workspaceRoot>/<module.projectDir>/build/compose-previews/
 * <renderOutput>` and base64-encodes them before handing to the builder
 * — keeping I/O outside this module makes it trivially testable and
 * portable to a future CLI surface that wants the same markdown.
 */
export interface VariantImage {
    /** Module-relative path matched against `ResourceCapture.renderOutput`. */
    renderOutput: string;
    /** Base64-encoded PNG (or GIF for animated-vector) bytes. */
    base64: string;
}

/**
 * One capture that produced no PNG, and why — read from the renderer's
 * `resource-render-errors.json` sidecar. Lets the hover explain a missing
 * variant ("⚠ render failed: …") instead of silently dropping it.
 */
export interface ResourceRenderError {
    /** Module-relative path matched against `ResourceCapture.renderOutput`. */
    renderOutput: string;
    /** `failed` | `skipped` | `not-found`. */
    status: string;
    message: string;
}

export interface VariantHoverInput {
    resource: ResourcePreview;
    images: VariantImage[];
    /**
     * Per-capture render errors (from the `resource-render-errors.json` sidecar). A capture with no
     * image but a matching error is rendered as a warning line; a capture with neither is skipped
     * silently (the host chose not to render that variant). Optional for back-compat.
     */
    errors?: ResourceRenderError[];
}

/**
 * Build the markdown body for [input]. Returns the raw markdown string;
 * the caller wraps it in `new vscode.MarkdownString(...)` and sets
 * `supportHtml` per provider conventions (the hover does NOT need
 * `isTrusted` — see `manifestResourceHoverProvider.ts`). Captures whose
 * `renderOutput` doesn't appear in [VariantHoverInput.images] are
 * skipped silently — the host decides which variants are worth the
 * base64 cost (a tier=fast render might have skipped the heavy
 * adaptive captures, for instance).
 *
 * `resource.id` and `resource.type` originate from the workspace
 * `resources.json`, which is user-controlled input — both fields are
 * escaped before being interpolated into markdown so a crafted manifest
 * cannot inject markdown / HTML structure into the hover.
 */
export function buildResourceVariantHoverMarkdown(
    input: VariantHoverInput,
): string {
    const lines: string[] = [];
    lines.push(
        `**${escapeMarkdown(input.resource.id)}** — ${escapeMarkdown(friendlyType(input.resource.type))}`,
    );
    const byOutput = new Map(
        input.images.map((i) => [i.renderOutput, i.base64]),
    );
    const errorsByOutput = new Map(
        (input.errors ?? []).map((e) => [e.renderOutput, e]),
    );
    const imgs: string[] = [];
    const errorLines: string[] = [];
    for (const capture of input.resource.captures) {
        const base64 = byOutput.get(capture.renderOutput);
        if (base64) {
            const mime = capture.renderOutput.toLowerCase().endsWith(".gif")
                ? "image/gif"
                : "image/png";
            const label = captureLabel(capture);
            imgs.push(
                `<img src="data:${mime};base64,${base64}" ` +
                    `width="${VARIANT_HOVER_IMG_PX}" ` +
                    `height="${VARIANT_HOVER_IMG_PX}" ` +
                    `alt="${escapeAttr(label)}" ` +
                    `title="${escapeAttr(label)}" />`,
            );
            continue;
        }
        // No image for this capture. If the renderer recorded why (a failed / skipped / not-found
        // capture), surface it so the user sees the reason instead of a silently-missing variant.
        // `status` and `message` come from the sidecar — escaped before interpolation.
        const err = errorsByOutput.get(capture.renderOutput);
        if (err) {
            errorLines.push(
                `⚠ ${escapeMarkdown(captureLabel(capture))} — ` +
                    `${escapeMarkdown(friendlyErrorStatus(err.status))}: ` +
                    `${escapeMarkdown(err.message)}`,
            );
        }
        // else: skip silently — the host chose not to render this variant (e.g. a fast-tier run).
    }
    if (imgs.length === 0 && errorLines.length === 0) {
        lines.push("");
        lines.push("_No rendered captures available._");
        return lines.join("\n");
    }
    if (imgs.length > 0) {
        lines.push("");
        lines.push(imgs.join(" "));
    }
    if (errorLines.length > 0) {
        lines.push("");
        for (const line of errorLines) {
            lines.push(line);
        }
    }
    return lines.join("\n");
}

/** Human-readable label for a sidecar render-error [status]. */
function friendlyErrorStatus(status: string): string {
    switch (status) {
        case "failed":
            return "render failed";
        case "skipped":
            return "skipped";
        case "not-found":
            return "resource not found";
        default:
            return status;
    }
}

/**
 * Human-readable single-line label for a [capture]. Used both as the
 * `title`/`alt` attribute on the hover image and (in `#1418`/`#1419`)
 * as a caption when the variant gallery webview lands. Vector captures
 * with only a qualifier (no adaptive shape/style) fall back to the
 * verbatim qualifier suffix (e.g. `'xhdpi'`).
 */
export function captureLabel(capture: ResourceCapture): string {
    const v = capture.variant;
    if (!v) {
        return "default";
    }
    const parts: string[] = [];
    if (v.shape) {
        parts.push(friendlyShape(v.shape));
    }
    if (v.style) {
        parts.push(friendlyStyle(v.style));
    }
    if (parts.length === 0 && v.qualifiers) {
        parts.push(v.qualifiers);
    }
    return parts.length > 0 ? parts.join(" · ") : "default";
}

function friendlyShape(shape: string): string {
    switch (shape) {
        case "CIRCLE":
            return "Circle";
        case "SQUIRCLE":
            return "Squircle";
        case "ROUNDED_SQUARE":
            return "Rounded square";
        case "SQUARE":
            return "Square";
        default:
            return shape;
    }
}

function friendlyStyle(style: string): string {
    switch (style) {
        case "FULL_COLOR":
            return "Full colour";
        case "THEMED_LIGHT":
            return "Themed light";
        case "THEMED_DARK":
            return "Themed dark";
        case "LEGACY":
            return "Legacy";
        default:
            return style;
    }
}

function friendlyType(type: string): string {
    switch (type) {
        case "VECTOR":
            return "Vector drawable";
        case "ANIMATED_VECTOR":
            return "Animated vector";
        case "ADAPTIVE_ICON":
            return "Adaptive icon";
        default:
            return type;
    }
}

function escapeAttr(s: string): string {
    return s.replace(/[&<>"']/g, (c) => {
        switch (c) {
            case "&":
                return "&amp;";
            case "<":
                return "&lt;";
            case ">":
                return "&gt;";
            case '"':
                return "&quot;";
            case "'":
                return "&#39;";
            default:
                return c;
        }
    });
}

/**
 * Escape markdown / HTML metacharacters in a workspace-controlled string
 * (`resource.id`, `resource.type`) before splicing it into the hover.
 *
 * Defense in depth: the hover provider already renders with
 * `isTrusted = false`, so `command:` links cannot fire even if smuggled
 * in. This escaper additionally prevents structural injection — bold
 * markers, raw HTML tags, link constructors, code spans, and pipes —
 * so a crafted `resources.json` cannot rewrite the hover layout. Also
 * defangs a leading `command:` substring so it cannot read as a link
 * target if a future caller flips `isTrusted` on.
 */
function escapeMarkdown(s: string): string {
    const defanged = s.replace(/^command:/i, "command​:");
    return defanged.replace(/[\\`*_{}\[\]()#+\-!|<>]/g, (c) => `\\${c}`);
}
