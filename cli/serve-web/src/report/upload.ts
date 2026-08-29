// Hosting browser captures long enough for a GitHub issue to retain them.
//
// The image lane was originally a headless API. The report page uses the same POST with its signed
// OAuth cookie, then inserts the returned anonymous-read URL into the prefilled body. The URL is
// accepted only when it points back at this origin's `/i/` lane: server text never becomes arbitrary
// markdown or an off-origin tracking pixel in the reporter's issue.

import { blobFromDataUrl } from "./capture.js";
import type { Capture } from "./store.js";

export interface UploadedCapture {
    url: string;
}

/** Insert hosted captures immediately below the report's Screenshot heading. */
export function withUploadedCaptures(
    body: string,
    captures: Capture[],
): string {
    const lines = captures
        .map((capture) => ({
            capture,
            url: hostedCaptureUrl(capture.uploadedUrl),
        }))
        .filter((item): item is { capture: Capture; url: string } => !!item.url)
        .map(({ capture, url }) => `![${markdownAlt(capture.label)}](${url})`);
    if (!lines.length) return body;
    const evidence = lines.join("\n\n");
    const heading = "### Screenshot";
    const at = body.indexOf(heading);
    if (at < 0) return `${body.trimEnd()}\n\n${heading}\n\n${evidence}\n`;
    const end = at + heading.length;
    return `${body.slice(0, end)}\n\n${evidence}${body.slice(end)}`;
}

/** Upload one capture and return the checked anonymous-read URL. */
export async function uploadCapture(
    capture: Capture,
): Promise<UploadedCapture> {
    const existing = hostedCaptureUrl(capture.uploadedUrl);
    if (existing) return { url: existing };
    const endpoint = new URL("/images", location.href);
    endpoint.searchParams.set("name", `bug-report-${capture.id}.png`);
    // Private preview hosts carry their browse secret in the query, not a cookie. It stays on this
    // origin and is never part of the returned public image URL.
    const token = new URLSearchParams(location.search).get("token");
    if (token) endpoint.searchParams.set("token", token);
    const response = await fetch(endpoint, {
        method: "POST",
        credentials: "same-origin",
        headers: { Accept: "application/json", "Content-Type": "image/png" },
        body: await blobFromDataUrl(capture.dataUrl),
    });
    if (!response.ok)
        throw new Error(`image upload answered ${response.status}`);
    const payload = (await response.json()) as { url?: unknown };
    const url = hostedCaptureUrl(payload.url);
    if (!url) throw new Error("image upload returned an unsafe URL");
    return { url };
}

/** Return a canonical URL only for an image hosted by this page's image lane. */
export function hostedCaptureUrl(value: unknown): string | null {
    if (typeof value !== "string") return null;
    try {
        const url = new URL(value, location.href);
        if (url.origin !== location.origin) return null;
        if (!/^\/i\/[A-Za-z0-9_-]+\.[A-Za-z0-9]+$/.test(url.pathname))
            return null;
        return url.href;
    } catch {
        return null;
    }
}

function markdownAlt(value: string): string {
    return value.replace(/[\\[\]\r\n]/g, " ").trim() || "bug report capture";
}
