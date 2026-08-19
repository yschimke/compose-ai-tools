// The capture tool as the visitor meets it: three buttons, a status line, and the pile of what has
// been captured so far — in the launcher panel on any page, and again on `/report-bug`, where the
// pile has survived a navigation and is waiting to be pasted.
//
// The controls are server-rendered `hidden` (see `ServeWeb.captureControlsHtml`) and unhidden from
// here, once. That order is the point: capability is a browser fact, and a button that offers a
// screenshot and then explains that your browser cannot take one is worse than no button.

import {
    Frame,
    blobFromDataUrl,
    captureSupported,
    copyPng,
    crop,
    grabFrame,
    toDataUrl,
    whole,
} from "./capture.js";
import { elementLabel, elementMarkdown } from "./markdown.js";
import { pickElement, pickRegion } from "./select.js";
import {
    Capture,
    addCapture,
    nextId,
    readCaptures,
    removeCapture,
    sessionStore,
} from "./store.js";

type Mode = "view" | "region" | "element";

/** Wire every capture surface on this page. Safe to call twice; the second call is a no-op. */
export function installCapture(): void {
    if (document.documentElement.hasAttribute("data-cp-capture-ready")) return;
    document.documentElement.setAttribute("data-cp-capture-ready", "1");
    if (captureSupported()) {
        document
            .querySelectorAll<HTMLElement>(".cp-shot")
            .forEach((block) => (block.hidden = false));
        document
            .querySelectorAll<HTMLElement>("[data-cp-capture]")
            .forEach((btn) =>
                btn.addEventListener("click", () =>
                    run(
                        (btn.getAttribute("data-cp-capture") || "view") as Mode,
                    ),
                ),
            );
    }
    render();
}

/** Every list on the page, refreshed from the store. */
function render(): void {
    const captures = readCaptures(sessionStore());
    document
        .querySelectorAll<HTMLElement>(".cp-shot-list")
        .forEach((list) => fill(list, captures));
    document
        .querySelectorAll<HTMLElement>(".cp-shots-empty")
        .forEach((note) => (note.hidden = captures.length > 0));
}

function fill(list: HTMLElement, captures: Capture[]): void {
    list.replaceChildren(...captures.map(item));
}

/**
 * One capture as a row.
 *
 * Built with `createElement` rather than an HTML string, and not out of caution about the data —
 * an element's own tag name and class are hardly hostile — but because half of these values are
 * DOM-derived text and the other half are attributes (`src`, `href`, `download`). Assembling that
 * by concatenation is how a label with a quote in it becomes an attribute injection, and there is
 * no version of this list worth that risk.
 */
function item(capture: Capture): HTMLElement {
    const li = document.createElement("li");
    li.className = "cp-shot-item";

    const img = document.createElement("img");
    img.className = "cp-shot-thumb";
    img.src = capture.dataUrl;
    img.alt = `capture: ${capture.label}`;
    img.loading = "lazy";

    const meta = document.createElement("div");
    meta.className = "cp-shot-meta";
    const label = document.createElement("span");
    label.className = "cp-shot-label";
    label.textContent = capture.label;
    const size = document.createElement("span");
    size.className = "cp-shot-size";
    size.textContent = `${capture.width}×${capture.height}`;
    meta.append(label, size);

    const actions = document.createElement("div");
    actions.className = "cp-shot-actions";
    actions.append(
        action("Copy", "Copy the picture — then paste it into the issue", () =>
            // Pressed inside the click, so the gesture that authorises a clipboard write is still
            // in hand; `copyPng` takes the encode as a promise for the same reason.
            copyPng(blobFromDataUrl(capture.dataUrl)),
        ),
    );
    if (capture.markdown) {
        const markdown = capture.markdown;
        actions.append(
            action(
                "Copy as text",
                "Copy the same table as markdown, so it can be read and quoted",
                () => navigator.clipboard.writeText(markdown),
            ),
        );
    }
    const download = document.createElement("a");
    download.className = "cp-shot-action";
    download.textContent = "Save";
    download.href = capture.dataUrl;
    download.download = `${capture.id}.png`;
    actions.append(download);
    actions.append(
        action("Remove", "Discard this capture", () => {
            removeCapture(sessionStore(), capture.id);
            render();
            return Promise.resolve();
        }),
    );

    li.append(img, meta, actions);
    return li;
}

/**
 * A row action that reports its own outcome in place.
 *
 * The label flip is the only feedback a clipboard write can honestly give — there is no reading the
 * clipboard back — and it is the same pattern, and the same 1.4s, as the viewer's Copy buttons.
 */
function action(
    label: string,
    title: string,
    run: () => Promise<unknown>,
): HTMLButtonElement {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "cp-shot-action";
    btn.textContent = label;
    btn.title = title;
    btn.addEventListener("click", () => {
        run().then(
            () => flash(btn, label, "Copied"),
            () => flash(btn, label, "Failed"),
        );
    });
    return btn;
}

function flash(btn: HTMLElement, was: string, now: string): void {
    if (!btn.isConnected) return;
    btn.textContent = now;
    setTimeout(() => {
        if (btn.isConnected) btn.textContent = was;
    }, 1400);
}

/** The status line under the mode buttons, in every capture block on the page. */
function note(text: string): void {
    document
        .querySelectorAll<HTMLElement>(".cp-shot-note")
        .forEach((el) => (el.textContent = text));
}

/** The launcher, so a capture can close it before the shutter and reopen it after. */
function launcher(): HTMLDetailsElement | null {
    return document.querySelector<HTMLDetailsElement>(".cp-fab-menu");
}

/**
 * Two animation frames and a beat.
 *
 * The launcher panel is open when a capture starts and it covers a corner of the page, so it is
 * closed first — and `open = false` only schedules the repaint. Capturing in the same task
 * photographs the panel that was supposed to be out of the way. Two frames is the reliable "after
 * the next paint" in every engine; the timeout covers a background tab, where rAF does not fire at
 * all and the capture would otherwise hang before it started.
 */
function settle(): Promise<void> {
    return new Promise((resolve) => {
        let done = false;
        const finish = () => {
            if (done) return;
            done = true;
            resolve();
        };
        setTimeout(finish, 120);
        requestAnimationFrame(() => requestAnimationFrame(finish));
    });
}

async function run(mode: Mode): Promise<void> {
    const menu = launcher();
    const wasOpen = !!menu?.open;
    if (menu) menu.open = false;
    note("Waiting for you to allow the capture…");
    let frame: Frame;
    try {
        await settle();
        frame = await grabFrame();
    } catch {
        // A refused prompt and a browser that cannot do it at all land here alike, and the visitor
        // knows which of the two just happened far better than this does.
        if (menu && wasOpen) menu.open = true;
        note("No capture taken. Paste an ordinary screenshot instead.");
        return;
    }
    if (menu && wasOpen) menu.open = true;
    // A crop is only meaningful when the frame IS this tab: every rectangle here is in viewport
    // coordinates, and a shared window or monitor puts the page at an offset nothing can recover.
    // Rather than crop the wrong pixels, take the whole shared surface and say so.
    const tab = frame.surface === "browser";
    if (!tab && mode !== "view") {
        note("You shared a window rather than this tab — capturing all of it.");
    }
    let label = "Whole view";
    let markdown: string | undefined;
    let canvas: HTMLCanvasElement;
    if (mode === "view" || !tab) {
        canvas = whole(frame);
        if (!tab) label = "Shared screen";
    } else {
        note(
            mode === "region"
                ? "Drag a box around the part that is wrong."
                : "Click the element to capture.",
        );
        const picked =
            mode === "region" ? await pickRegion() : await pickElement();
        if (!picked) {
            note("Cancelled.");
            return;
        }
        canvas = crop(frame, picked.rect);
        label =
            mode === "region"
                ? "Region"
                : `Element · ${elementLabel(picked.element as Element)}`;
        // A picked table is worth carrying as text as well as pixels — see `markdown.ts`.
        markdown = picked.element
            ? elementMarkdown(picked.element) || undefined
            : undefined;
    }
    const store = sessionStore();
    const capture: Capture = {
        id: nextId(readCaptures(store)),
        label,
        dataUrl: toDataUrl(canvas),
        width: canvas.width,
        height: canvas.height,
        markdown,
    };
    const kept = addCapture(store, capture);
    render();
    if (!kept.some((c) => c.id === capture.id)) {
        // Every eviction path failed, which in practice means storage is unavailable or full. The
        // clipboard still works, so the capture is not lost — it just cannot ride to the report
        // page, and saying which is the difference between a bug and a limitation.
        note("Captured, but it can't be carried to the report — copy it now.");
        return;
    }
    // Copied straight away, because the gesture that started this is still the one in hand and
    // pasting is the only way a picture reaches a GitHub issue. The Copy button on the row is the
    // reliable path when a browser declines this one.
    copyPng(blobFromDataUrl(capture.dataUrl)).then(
        () => note("Copied — paste it into the issue body on GitHub."),
        () => note("Captured. Press Copy, then paste it into the issue body."),
    );
}
