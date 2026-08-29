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
import { markupEditor } from "./markup.js";
import { pickElement, pickRegion } from "./select.js";
import {
    Capture,
    addCapture,
    nextId,
    readCaptures,
    removeCapture,
    replaceCapture,
    sessionStore,
} from "./store.js";
import {
    hostedCaptureUrl,
    uploadCapture,
    withUploadedCaptures,
} from "./upload.js";

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
    wireHandOff();
    render();
    if (imageUploadEnabled()) void uploadReportCaptures();
}

/** The two forms that open a prefilled issue: `/report-bug`'s, and a preview's own. */
const REPORT_FORMS = ".cp-report-bug-form, .cp-report-form";

/**
 * Complete the image hand-off when the issue form is submitted.
 *
 * Hosted captures are already embedded in the prefilled Markdown. If hosting was unavailable, the
 * clipboard remains the reliable fallback: copying here uses the submit gesture for browser
 * permission and happens after the reporter has finished writing, so an intervening copy cannot
 * silently replace the screenshot.
 *
 * The NEWEST capture, because a clipboard holds one image and the newest is the one the pile's own
 * eviction rule already treats as the most wanted. When there are others the note says so, since
 * their Copy buttons are the only way to reach them and this page is where they still exist.
 *
 * Delegated from the document, for the same reason `chrome/reportLauncher.ts` delegates its own
 * dismissal: [installCapture] runs once per page, and `.cp-report-form` is not necessarily the one
 * that will be submitted. It is emitted by whichever surface bundle drew the preview, and the
 * comparison wall replaces its own as the wall re-renders — so a snapshot taken at install time
 * wires a form that is no longer in the document, and the failure is the silent one this whole
 * change exists to remove: the issue opens, the paste produces whatever was last copied, and
 * nothing anywhere says the hand-off did not happen.
 */
function wireHandOff(): void {
    // Guarded on its OWN attribute rather than riding [installCapture]'s. Both bundles that reach
    // this file can load it — the launcher fetches it when its panel opens, `/report-bug` on load —
    // and a delegated listener cannot be de-duplicated by the element it is attached to the way a
    // per-form one could. Two of these would copy twice and write the note twice for one submit.
    const root = document.documentElement;
    if (root.hasAttribute("data-cp-handoff-wired")) return;
    root.setAttribute("data-cp-handoff-wired", "1");
    document.addEventListener("submit", (event) => {
        const form = event.target;
        if (form instanceof HTMLElement && form.matches(REPORT_FORMS)) {
            handOff();
        }
    });
}

/**
 * Which page the report being submitted is ABOUT — not necessarily the one it is submitted from.
 *
 * On a preview's own form the two are the same page. On `/report-bug` they are not: that page is
 * the report, and the page it reports is the `?from=` the footer form carried here. Reading it back
 * is what lets [handOff] tell a capture taken for this report from one still lying in the pile.
 *
 * `from` is a path this server wrote and this page received; it is parsed for its pathname rather
 * than string-compared, so a carried query cannot make it miss.
 */
function reportedPage(): string | null {
    const from = new URLSearchParams(location.search).get("from");
    if (!from) return location.pathname;
    try {
        return new URL(from, location.href).pathname;
    } catch {
        return null;
    }
}

function handOff(): void {
    const captures = readCaptures(sessionStore());
    if (!captures.length) return;
    // The newest capture OF THE PAGE BEING REPORTED. `sessionStorage` lasts as long as the tab, so
    // a reporter who files one report with a screenshot and a second one later, from elsewhere,
    // without taking another still has the first picture in the pile — and handing that one over
    // while telling them to paste it attaches a screenshot of an unrelated page to the new issue.
    // Silence is the right answer there: they did not take a picture for this report, the pile is
    // still on screen with its Copy buttons, and the issue's own Screenshot section still asks.
    const page = reportedPage();
    const mine = captures.filter((c) => !!c.page && c.page === page);
    const latest = mine[mine.length - 1];
    if (!latest) return;
    applyHostedCaptures(mine);
    if (mine.every((capture) => !!hostedCaptureUrl(capture.uploadedUrl))) {
        note(
            mine.length === 1
                ? "Your capture is embedded in the report."
                : `Your ${mine.length} captures are embedded in the report.`,
        );
        return;
    }
    const rest =
        mine.length > 1
            ? ` The other ${mine.length - 1} are still here — press Copy on one to send it too.`
            : "";
    copyPng(blobFromDataUrl(latest.dataUrl)).then(
        () =>
            note(
                `Your capture is on the clipboard — paste it into the issue's Screenshot section.${rest}`,
            ),
        () => {
            note(
                "The clipboard refused the capture. Press Copy on it here, then paste it into the issue's Screenshot section.",
            );
            // …and make sure that sentence is somewhere it can be read. On a preview page the note's
            // only home is the capture block inside the launcher panel, which the capture flow
            // closed to take the shot and the submit just closed again — so the one message that
            // needs attention was being written into a drawer. A success needs no such rescue: the
            // clipboard holds what it should and there is nothing to act on.
            reveal();
        },
    );
}

/** Open every disclosure standing between a status note and the reporter. */
function reveal(): void {
    document.querySelectorAll<HTMLElement>(".cp-shot-note").forEach((el) => {
        let box = el.closest("details");
        while (box) {
            box.open = true;
            box = box.parentElement?.closest("details") ?? null;
        }
    });
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

const originalBodies = new WeakMap<HTMLInputElement, string>();
let uploadGeneration = 0;

/** Upload this report's captures in the background while the reporter writes the summary. */
async function uploadReportCaptures(): Promise<void> {
    const generation = ++uploadGeneration;
    const page = reportedPage();
    const mine = readCaptures(sessionStore()).filter(
        (capture) => !!capture.page && capture.page === page,
    );
    const pending = mine.filter(
        (capture) => !hostedCaptureUrl(capture.uploadedUrl),
    );
    // An edit clears `uploadedUrl`. Remove the old embed immediately, before the replacement
    // upload begins; if that upload fails, falling back to the clipboard must not leave the issue
    // body pointing at the unannotated pixels.
    applyHostedCaptures(mine);
    if (!pending.length) {
        return;
    }
    const submit = document.querySelector<HTMLButtonElement>(".cp-bug-submit");
    if (submit) submit.disabled = true;
    note(
        pending.length === 1
            ? "Uploading your capture…"
            : `Uploading ${pending.length} captures…`,
    );
    try {
        for (const capture of pending) {
            const uploaded = await uploadCapture(capture);
            // An edit or removal starts a new generation. Never let this older request restore its
            // pre-edit pixels (and URL) after the replacement has reached sessionStorage.
            if (generation !== uploadGeneration) return;
            replaceCapture(sessionStore(), {
                ...capture,
                uploadedUrl: uploaded.url,
            });
        }
        if (generation !== uploadGeneration) return;
        const current = readCaptures(sessionStore()).filter(
            (capture) => !!capture.page && capture.page === page,
        );
        applyHostedCaptures(current);
        note(
            current.length === 1
                ? "Capture uploaded — it will be embedded in the report."
                : `${current.length} captures uploaded — they will be embedded in the report.`,
        );
        render();
    } catch {
        if (generation !== uploadGeneration) return;
        note(
            "This server could not host the capture. It will be copied when you open the issue, so paste it into the Screenshot section.",
        );
    } finally {
        if (generation === uploadGeneration && submit) submit.disabled = false;
    }
}

function imageUploadEnabled(): boolean {
    return (
        document
            .querySelector(".cp-shots")
            ?.getAttribute("data-cp-image-upload") === "true"
    );
}

function applyHostedCaptures(captures: Capture[]): void {
    const input = document.querySelector<HTMLInputElement>("#cp-bug-body");
    if (!input) return;
    if (!originalBodies.has(input)) originalBodies.set(input, input.value);
    input.value = withUploadedCaptures(
        originalBodies.get(input) ?? input.value,
        captures,
    );
    const preview = document.querySelector<HTMLElement>("#cp-bug-preview");
    if (preview) preview.textContent = input.value;
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
    const markUp = document.createElement("button");
    markUp.type = "button";
    markUp.className = "cp-shot-action";
    markUp.textContent = "Mark up";
    markUp.title = "Draw a box, arrow, note, or freehand mark on this capture";
    markUp.addEventListener("click", () => {
        li.querySelector(".cp-markup")?.remove();
        const editor = markupEditor(
            capture,
            (updated) => {
                replaceCapture(sessionStore(), updated);
                render();
                if (imageUploadEnabled()) {
                    void uploadReportCaptures();
                }
            },
            () => li.querySelector(".cp-markup")?.remove(),
        );
        li.append(editor);
    });
    actions.append(markUp);
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
            if (imageUploadEnabled()) {
                void uploadReportCaptures();
            }
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
        // Stamped here, on the page it is a picture of, because nowhere later can recover it — and
        // it is what lets the hand-off on `/report-bug` tell this report's screenshot from one the
        // tab has simply been carrying around. See [Capture.page].
        page: location.pathname,
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
