// Pointer + wheel state machine for live (interactive) previews.
//
// Both handler families attach to the `.preview-card` element, gated
// per event by an `isLive` predicate, and resolve the visible render
// surface dynamically via `liveRenderSurface(card)`:
//
//  - **Wheel** — capture-phase listener on the card. Vertical deltas
//    over the surface forward as rotary scroll; deltas over chrome are
//    still consumed so enthusiastic scrolling can't push the live
//    preview out of view.
//  - **Pointer** — bubble-phase listeners on the card. Pointerdown is
//    only hijacked when `evt.target === surface.el`, so the stop
//    button / focus toolbar / badges keep native click semantics. The
//    surface resolved at pointerdown is held in `state.surface` for
//    the lifetime of the gesture, so coords stay in a single natural-
//    pixel space even if the painter swaps the surface mid-drag (the
//    `<img>` ↔ `<canvas>` flip is asynchronous).
//
// Listeners are idempotent — flagged via
// `dataset.interactiveWheelBound` and `dataset.interactivePointerBound`
// so re-attaching on subsequent `updateImage` / live-toggle calls
// doesn't stack duplicates. The handlers stay attached after a card
// leaves live mode; they go inert because every event re-checks the
// `isLive` predicate.
//
// Coordinates the daemon expects are image-natural pixel space — the
// same space the renderer paints in. The CSS-pixel offsets the browser
// gives us are scaled by the displayed/natural ratio in `surfacePoint`.
// See docs/daemon/INTERACTIVE.md §§ 3, 6, 7.
//
// Streaming-mode rationale: the painter hides the legacy `<img>`
// (display:none → 0×0 rect, pointer events never fire on it) and paints
// into a `<canvas class="stream-canvas">` overlay. Both handler families
// hang off the card (not the img) so they keep working across that
// swap. `liveRenderSurface` prefers the canvas when present.

import type { VsCodeApi } from "../shared/vscode";
import {
    computeImagePoint,
    isEventInsideRect,
    type ImagePoint,
} from "./pointerGeometry";

export type { ImagePoint };

/** Visible live render surface: an `<img>` in the legacy capture-and-show
 *  path, or the streaming `<canvas class="stream-canvas">` once the
 *  painter has taken over. The interactive wheel/pointer plumbing only
 *  cares about its bounding rect and natural-pixel dimensions. */
export interface LiveSurface {
    el: Element;
    naturalWidth: number;
    naturalHeight: number;
}

/** Resolve the live render surface inside [card]. Streaming canvas wins
 *  when present and sized — the painter hides the legacy `<img>` once
 *  it attaches, so `<img>.getBoundingClientRect()` is 0×0 and pointer
 *  geometry against it always returns null. Returns null when neither
 *  surface has usable natural dimensions yet. */
export function liveRenderSurface(card: Element): LiveSurface | null {
    const canvas = card.querySelector<HTMLCanvasElement>(
        "canvas.stream-canvas",
    );
    if (canvas && canvas.width > 0 && canvas.height > 0) {
        return {
            el: canvas,
            naturalWidth: canvas.width,
            naturalHeight: canvas.height,
        };
    }
    const img = card.querySelector<HTMLImageElement>(
        "img.preview-image, img.preview-gif, img",
    );
    if (img && img.naturalWidth > 0 && img.naturalHeight > 0) {
        return {
            el: img,
            naturalWidth: img.naturalWidth,
            naturalHeight: img.naturalHeight,
        };
    }
    return null;
}

export interface InteractiveInputConfig {
    /**
     * Predicate keyed on a card's `data-preview-id`. Returns true when
     * the card should currently consume pointer/wheel input — i.e. it
     * is in the `interactivePreviewIds` set OR the `recordingPreviewIds`
     * set. Both states forward input to the daemon.
     *
     * The predicate is called on every event, so non-live cards
     * naturally pass events through.
     */
    isLive(previewId: string): boolean;
    /**
     * Issue #1203 — predicate keyed on an interactive control kind wire spelling
     * (`'keyDown'` / `'keyUp'` / `'rotaryScroll'`). Returns true when the daemon
     * advertised support for that kind via `ServerCapabilities.interactiveControlKinds`.
     * The wheel listener doesn't gate on this — rotary scrolling is a daemon-side
     * dispatch concern, and a daemon that doesn't dispatch will see the event arrive
     * and harmlessly drop it. The keyboard listener does gate, so we don't burn focus
     * + keystrokes on a daemon that can't act on them.
     *
     * Defaulted to "false on every kind" so callers that don't supply the predicate
     * keep the pre-#1203 behaviour (no keyboard listener attached).
     */
    supportsControl?(kind: string): boolean;
    /**
     * Issue #1203 — predicate keyed on `data-preview-id`. Returns true when the user has
     * flipped the focus-bar "Controls" toggle on for this preview. Called on every keyboard
     * event so toggling controls off mid-session immediately stops forwarding; the listener
     * itself stays attached (cheaper than rebinding on every toggle).
     *
     * Omitted entirely → the gate is bypassed (every keystroke that passes the `isLive`
     * check is forwarded), matching the pre-toggle behaviour. When supplied, returning
     * `false` drops the keystroke before the wire post.
     */
    isControlsEnabled?(previewId: string): boolean;
    vscode: VsCodeApi<unknown>;
}

export function attachInteractiveInputHandlers(
    card: HTMLElement,
    config: InteractiveInputConfig,
): void {
    const previewId = card.dataset.previewId;
    if (!previewId) return;
    if (!config.isLive(previewId)) return;

    if (card.dataset.interactiveWheelBound !== "1") {
        card.dataset.interactiveWheelBound = "1";
        card.addEventListener(
            "wheel",
            (evt) => {
                const id = card.dataset.previewId;
                if (!id || !config.isLive(id)) return;
                // Live previews own wheel input while the cursor is inside the card. If the
                // wheel lands on preview pixels, forward it as rotary scroll; if it lands on
                // the card chrome, still consume it so enthusiastic scrolling cannot bubble to
                // the list and push the live preview out of view.
                //
                // Resolve the visible surface dynamically so the streaming `<canvas>` takes
                // over from the (now hidden) `<img>` once `streamingPainter` attaches — see
                // module header.
                const surface = liveRenderSurface(card);
                const point =
                    surface && eventInsideElement(surface.el, evt)
                        ? surfacePoint(surface, evt)
                        : null;
                if (surface && point) {
                    postInteractiveInput(
                        config.vscode,
                        id,
                        surface,
                        "rotaryScroll",
                        point,
                        evt.deltaY,
                    );
                }
                evt.preventDefault();
                evt.stopImmediatePropagation();
            },
            { passive: false, capture: true },
        );
    }

    // Issue #1203 — attach keyboard listener once per card, gated by daemon
    // capability. Idempotent via `dataset.interactiveKeyboardBound`. The card needs
    // `tabindex` to receive focus and thus `keydown` / `keyup`; we set it the first
    // time the listener attaches. Pointerdown re-focuses the card (existing handler
    // calls `setPointerCapture` but not `focus()`; we add that below) so a click on
    // the live surface naturally hands keyboard input to it.
    if (
        card.dataset.interactiveKeyboardBound !== "1" &&
        config.supportsControl?.("keyDown") === true &&
        config.supportsControl?.("keyUp") === true
    ) {
        card.dataset.interactiveKeyboardBound = "1";
        if (!card.hasAttribute("tabindex")) {
            // `-1` keeps the card out of the tab-order (we don't want tabbing
            // through a card grid to start firing daemon-side key events) but lets
            // `focus()` succeed.
            card.setAttribute("tabindex", "-1");
        }
        const onKey = (evt: KeyboardEvent, kind: "keyDown" | "keyUp"): void => {
            const id = card.dataset.previewId;
            if (!id || !config.isLive(id)) return;
            // Per-card "Controls" toggle gate (issue #1203). Without an explicit
            // opt-in, a card in live mode for click / drag interaction would
            // silently swallow every key the user pressed.
            if (config.isControlsEnabled && !config.isControlsEnabled(id)) {
                return;
            }
            const keyCode = domCodeToAndroidKeycode(evt.code);
            if (keyCode == null) return;
            config.vscode.postMessage({
                command: "recordInteractiveInput",
                previewId: id,
                kind,
                keyCode: String(keyCode),
            });
            evt.preventDefault();
            evt.stopPropagation();
        };
        card.addEventListener("keydown", (evt) => onKey(evt, "keyDown"));
        card.addEventListener("keyup", (evt) => onKey(evt, "keyUp"));
    }

    if (card.dataset.interactivePointerBound === "1") return;
    card.dataset.interactivePointerBound = "1";

    interface PointerState {
        pointerId: number | null;
        start: ImagePoint | null;
        last: ImagePoint | null;
        dragging: boolean;
        sentDown: boolean;
        /**
         * Surface captured at pointerdown. Held for the lifetime of a
         * drag so coords stay in a single natural-pixel space even if
         * the painter swaps surfaces mid-gesture (rare but the
         * `<img>` ↔ `<canvas>` flip is asynchronous).
         */
        surface: LiveSurface | null;
        /**
         * Latest pointerMove position not yet posted to the daemon —
         * coalesced and flushed once per animation frame. See
         * `flushPendingMove`.
         */
        pendingMove: ImagePoint | null;
        /** True between scheduling and firing the rAF flush. */
        rafScheduled: boolean;
    }
    const state: PointerState = {
        pointerId: null,
        start: null,
        last: null,
        dragging: false,
        sentDown: false,
        surface: null,
        pendingMove: null,
        rafScheduled: false,
    };

    // Coalesce pointerMove dispatch to rAF cadence. Native pointermove
    // fires faster than the painter's frame rate (typical mice ~120 Hz,
    // gaming mice up to 1 kHz; the painter consumes one frame per rAF,
    // ~60 Hz). Posting every native move overwhelms the daemon's render
    // pipeline — frames pile up faster than they can be produced, the
    // painter's newest-wins drops most of them, and the user sees motion
    // that lags and skips between cursor positions instead of cleanly
    // tracking the gesture. Sending one move per frame keeps the daemon
    // in step with what the painter can actually display.
    //
    // pointerDown / pointerUp still go through immediately so the
    // gesture's begin / end aren't delayed by up to 16 ms.
    const flushPendingMove = (): void => {
        state.rafScheduled = false;
        const move = state.pendingMove;
        state.pendingMove = null;
        if (!move || !state.surface) return;
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        postInteractiveInput(
            config.vscode,
            id,
            state.surface,
            "pointerMove",
            move,
        );
    };

    card.addEventListener("pointerdown", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (evt.button !== 0 && evt.button !== 2) return;
        const surface = liveRenderSurface(card);
        // Only hijack the gesture when it starts on the live surface.
        // Anything else (stop button, focus toolbar, badges) is chrome and
        // must keep its native click semantics — those overlay siblings
        // sit on top of the surface so `evt.target` is the truth.
        if (!surface || evt.target !== surface.el) return;
        const point = surfacePoint(surface, evt);
        if (!point) return;
        state.pointerId = evt.pointerId;
        state.start = point;
        state.last = point;
        state.dragging = false;
        state.sentDown = false;
        state.surface = surface;
        // Capture on the card so subsequent move/up route here even when
        // the cursor leaves the surface (or the surface gets swapped out
        // mid-drag by the streaming painter).
        card.setPointerCapture?.(evt.pointerId);
        // Issue #1203 — focus the card on click so keyboard input lands here.
        // `preventScroll: true` keeps the surrounding grid from jumping when the
        // browser's default focus-on-focus scroll-into-view fires on first focus.
        (card as HTMLElement).focus?.({ preventScroll: true });
        evt.preventDefault();
        evt.stopPropagation();
    });

    card.addEventListener("pointermove", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (state.pointerId !== evt.pointerId || !state.start || !state.surface)
            return;
        const next = surfacePoint(state.surface, evt);
        if (!next) return;
        const dx = next.clientX - state.start.clientX;
        const dy = next.clientY - state.start.clientY;
        if (!state.dragging && Math.hypot(dx, dy) >= 4) {
            state.dragging = true;
        }
        if (state.dragging) {
            if (!state.sentDown) {
                postInteractiveInput(
                    config.vscode,
                    id,
                    state.surface,
                    "pointerDown",
                    state.start,
                );
                state.sentDown = true;
            }
            // Stash the latest position; the rAF tick will post it. See
            // `flushPendingMove` for the rationale on coalescing.
            state.pendingMove = next;
            state.last = next;
            if (!state.rafScheduled) {
                if (typeof requestAnimationFrame === "function") {
                    state.rafScheduled = true;
                    requestAnimationFrame(flushPendingMove);
                } else {
                    // No rAF (test runner / non-browser host) — dispatch
                    // immediately so events aren't silently lost.
                    flushPendingMove();
                }
            }
            evt.preventDefault();
            evt.stopPropagation();
        }
    });

    card.addEventListener("pointerup", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (state.pointerId !== evt.pointerId || !state.start || !state.surface)
            return;
        const point =
            surfacePoint(state.surface, evt) || state.last || state.start;
        if (state.dragging) {
            if (!state.sentDown) {
                postInteractiveInput(
                    config.vscode,
                    id,
                    state.surface,
                    "pointerDown",
                    state.start,
                );
            }
            // Flush any coalesced move so the daemon sees the cursor's
            // last in-flight position before the lift-off, otherwise the
            // gesture's tail (up to a frame's worth of motion) is dropped.
            if (state.pendingMove) {
                postInteractiveInput(
                    config.vscode,
                    id,
                    state.surface,
                    "pointerMove",
                    state.pendingMove,
                );
            }
            postInteractiveInput(
                config.vscode,
                id,
                state.surface,
                "pointerUp",
                point,
            );
        } else {
            postInteractiveInput(
                config.vscode,
                id,
                state.surface,
                "click",
                point,
            );
        }
        card.releasePointerCapture?.(evt.pointerId);
        state.pointerId = null;
        state.start = null;
        state.last = null;
        state.dragging = false;
        state.sentDown = false;
        state.surface = null;
        state.pendingMove = null;
        evt.preventDefault();
        evt.stopPropagation();
    });

    card.addEventListener("pointercancel", (evt) => {
        if (state.pointerId !== evt.pointerId) return;
        card.releasePointerCapture?.(evt.pointerId);
        state.pointerId = null;
        state.start = null;
        state.last = null;
        state.dragging = false;
        state.sentDown = false;
        state.surface = null;
        state.pendingMove = null;
    });

    card.addEventListener("contextmenu", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        const surface = liveRenderSurface(card);
        if (!surface || evt.target !== surface.el) return;
        evt.preventDefault();
        evt.stopPropagation();
    });
}

/** DOM-bound shim around `computeImagePoint` — extracts the surface's
 *  bounding rect and lets the pure helper do the natural-pixel math. */
function surfacePoint(
    surface: LiveSurface,
    evt: { clientX: number; clientY: number },
): ImagePoint | null {
    const rect = surface.el.getBoundingClientRect();
    return computeImagePoint(
        surface.naturalWidth,
        surface.naturalHeight,
        rect.width,
        rect.height,
        rect.left,
        rect.top,
        evt.clientX,
        evt.clientY,
    );
}

/** DOM-bound shim around `isEventInsideRect`. */
function eventInsideElement(
    el: Element,
    evt: { clientX: number; clientY: number },
): boolean {
    return isEventInsideRect(
        el.getBoundingClientRect(),
        evt.clientX,
        evt.clientY,
    );
}

/**
 * Issue #1203 — translate a DOM `KeyboardEvent.code` (the physical-key spelling, e.g.
 * `"KeyA"` / `"ArrowLeft"`) to the Android `KEYCODE_*` int the daemon wire expects.
 *
 * The Kotlin-side `InteractiveKeyCodes` is the canonical authority for the integer
 * spellings; mismatches between the two tables would only show up as silent drops
 * (daemon doesn't recognise the code) rather than crashes. Covers letters, digits,
 * navigation, modifiers, and the common editing keys — same set the daemon advertises.
 * Returns `null` for codes outside the table so the listener skips the post; pressing
 * an unmapped key in the panel is harmless.
 */
export function domCodeToAndroidKeycode(code: string): number | null {
    return DOM_CODE_TO_ANDROID_KEYCODE.get(code) ?? null;
}

const DOM_CODE_TO_ANDROID_KEYCODE: ReadonlyMap<string, number> = new Map([
    // Letters (KEYCODE_A = 29, KEYCODE_Z = 54).
    ["KeyA", 29],
    ["KeyB", 30],
    ["KeyC", 31],
    ["KeyD", 32],
    ["KeyE", 33],
    ["KeyF", 34],
    ["KeyG", 35],
    ["KeyH", 36],
    ["KeyI", 37],
    ["KeyJ", 38],
    ["KeyK", 39],
    ["KeyL", 40],
    ["KeyM", 41],
    ["KeyN", 42],
    ["KeyO", 43],
    ["KeyP", 44],
    ["KeyQ", 45],
    ["KeyR", 46],
    ["KeyS", 47],
    ["KeyT", 48],
    ["KeyU", 49],
    ["KeyV", 50],
    ["KeyW", 51],
    ["KeyX", 52],
    ["KeyY", 53],
    ["KeyZ", 54],
    // Digits (KEYCODE_0 = 7, KEYCODE_9 = 16). Top row only — DOM `Numpad0`…
    // `Numpad9` map to the dedicated KEYCODE_NUMPAD_* range below.
    ["Digit0", 7],
    ["Digit1", 8],
    ["Digit2", 9],
    ["Digit3", 10],
    ["Digit4", 11],
    ["Digit5", 12],
    ["Digit6", 13],
    ["Digit7", 14],
    ["Digit8", 15],
    ["Digit9", 16],
    // Whitespace / editing.
    ["Space", 62],
    ["Enter", 66],
    ["Tab", 61],
    ["Backspace", 67],
    ["Delete", 112],
    ["Escape", 111],
    // Navigation.
    ["ArrowLeft", 21],
    ["ArrowRight", 22],
    ["ArrowUp", 19],
    ["ArrowDown", 20],
    ["Home", 122],
    ["End", 123],
    ["PageUp", 92],
    ["PageDown", 93],
    // Modifiers.
    ["ShiftLeft", 59],
    ["ShiftRight", 60],
    ["ControlLeft", 113],
    ["ControlRight", 114],
    ["AltLeft", 57],
    ["AltRight", 58],
    ["MetaLeft", 117],
    ["MetaRight", 118],
    // Function keys (KEYCODE_F1 = 131 … KEYCODE_F12 = 142).
    ["F1", 131],
    ["F2", 132],
    ["F3", 133],
    ["F4", 134],
    ["F5", 135],
    ["F6", 136],
    ["F7", 137],
    ["F8", 138],
    ["F9", 139],
    ["F10", 140],
    ["F11", 141],
    ["F12", 142],
    // Numpad (KEYCODE_NUMPAD_0 = 144 … KEYCODE_NUMPAD_EQUALS = 161).
    ["Numpad0", 144],
    ["Numpad1", 145],
    ["Numpad2", 146],
    ["Numpad3", 147],
    ["Numpad4", 148],
    ["Numpad5", 149],
    ["Numpad6", 150],
    ["Numpad7", 151],
    ["Numpad8", 152],
    ["Numpad9", 153],
    ["NumpadDivide", 154],
    ["NumpadMultiply", 155],
    ["NumpadSubtract", 156],
    ["NumpadAdd", 157],
    ["NumpadDecimal", 158],
    ["NumpadComma", 159],
    ["NumpadEnter", 160],
    ["NumpadEqual", 161],
    // Punctuation.
    ["Minus", 69],
    ["Equal", 70],
    ["BracketLeft", 71],
    ["BracketRight", 72],
    ["Backslash", 73],
    ["Semicolon", 74],
    ["Quote", 75],
    ["Comma", 55],
    ["Period", 56],
    ["Slash", 76],
    ["Backquote", 68],
    // Locks.
    ["CapsLock", 115],
    ["NumLock", 143],
    ["ScrollLock", 116],
]);

type InteractiveInputKind =
    | "click"
    | "pointerDown"
    | "pointerMove"
    | "pointerUp"
    | "rotaryScroll";

function postInteractiveInput(
    vscode: VsCodeApi<unknown>,
    previewId: string,
    surface: LiveSurface,
    kind: InteractiveInputKind,
    point: ImagePoint | null,
    scrollDeltaY?: number,
): void {
    if (!point || !surface.naturalWidth || !surface.naturalHeight) return;
    vscode.postMessage({
        command: "recordInteractiveInput",
        previewId,
        kind,
        pixelX: point.pixelX,
        pixelY: point.pixelY,
        imageWidth: surface.naturalWidth,
        imageHeight: surface.naturalHeight,
        scrollDeltaY,
    });
}
