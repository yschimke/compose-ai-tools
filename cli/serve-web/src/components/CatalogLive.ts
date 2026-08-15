// `<cp-catalog-live>` — long-press a catalog card to start a live daemon session in place.
// Replaces `assets/catalog-live.js`.
//
// The grid's counterpart of the viewer's Static⇄Live toggle, without leaving the page. The card
// keeps its baked thumbnail as the stage: a `<canvas>` is mounted as an absolute overlay on the
// image's slot, seeded with the thumbnail's pixels so there is no blank flash while the socket
// connects, and the daemon's frames paint over it. Pointer, wheel and key input are forwarded to the
// composition, so a component can be pressed, dragged and typed into from the grid.
//
// Everything here is progressive enhancement over the server-rendered grid: with scripting off (or
// on a session with no live lane) the cards are exactly the links they always were.
//
// The server emits its configuration as `window.cpCatalogLive` — an object literal in an inline
// script, NOT `data-` attributes — so no preview id this element puts into a URL originates as DOM
// text (the same discipline the themed-render URLs follow).
//
// Renders nothing of its own; `serve.css` hides the tag. The decisions live next door:
// `live/pointerMap.ts` (where a press landed on the composition) and `live/session.ts` (which
// preview, which socket, and what to say when the lane refuses).

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";
import { sameOriginNavigation } from "../dom/sameOrigin.js";
import { whenParsed } from "../dom/whenParsed.js";
import { drifted, framePixel } from "../live/pointerMap.js";
import {
    closeReason,
    previewIdOf,
    socketUrl,
    startsHold,
    themeProviderOf,
    type CardEntry,
    type LiveConfig,
} from "../live/session.js";

/** How far a pointer may drift during the hold before it reads as a scroll or a drag. */
const SLOP_PX = 10;
/** How long an error stays on the card that produced it. */
const ANNOUNCE_MS = 4000;

interface Session {
    card: HTMLElement;
    canvas: HTMLCanvasElement;
    img: HTMLImageElement;
    chip: HTMLElement;
    socket: WebSocket | null;
    pointers: Map<number, { x: number; y: number; moved: boolean }>;
}

interface Press {
    card: HTMLElement;
    entry: CardEntry;
    x: number;
    y: number;
    timer: ReturnType<typeof setTimeout>;
}

declare global {
    interface Window {
        cpCatalogLive?: LiveConfig;
    }
}

@customElement("cp-catalog-live")
export class CatalogLive extends LitElement {
    private installed = false;
    private config: LiveConfig = {};
    private holdMs = 500;
    /**
     * The one live session. Only one card streams at a time, deliberately: a live seat is a render
     * daemon, and a grid is 80+ cards. Starting one ends the previous.
     */
    private active: Session | null = null;
    private press: Press | null = null;
    private suppressNextClick = false;
    private cleanups: Array<() => void> = [];

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        this.stopLive(null);
        this.cancelPress();
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.installed = false;
        super.disconnectedCallback();
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const config = window.cpCatalogLive;
        if (!config?.cards?.length) return false;
        const cards = Array.from(
            document.querySelectorAll<HTMLElement>(".cp-card"),
        );
        if (!cards.length) return false;
        this.installed = true;
        this.config = config;
        this.holdMs = config.holdMs || 500;

        cards.forEach((card, index) => {
            const entry = config.cards?.[index];
            if (!entry || (!entry.l && !entry.d)) return;
            this.wireCard(card, entry);
        });

        this.on(document, "keydown", (event) => {
            if ((event as KeyboardEvent).key === "Escape") this.stopLive(null);
        });
        // A press anywhere outside the live card ends the session — the grid is for browsing, and a
        // stream nobody is looking at is a daemon nobody is using.
        this.on(document, "pointerdown", (event) => {
            const target = event.target as Node | null;
            if (this.active && target && !this.active.card.contains(target))
                this.stopLive(null);
        });
        this.on(window, "pagehide", () => this.stopLive(null));
        return true;
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
        options?: AddEventListenerOptions,
    ): void {
        target.addEventListener(type, handler, options);
        this.cleanups.push(() =>
            target.removeEventListener(type, handler, options),
        );
    }

    /**
     * The card whose preview a DECLARED theme is currently showing, if any.
     *
     * Read off the pressed chip so a live session started from a themed grid opens under that same
     * theme rather than snapping back to the catalog's baked palette.
     */
    private themeProvider(): string {
        const pressed = document.querySelector(
            '.cp-theme-btn[aria-pressed="true"]',
        );
        return themeProviderOf(
            pressed?.getAttribute("data-theme-choice") ?? "",
        );
    }

    // ---- the session ---------------------------------------------------------

    private stopLive(reason: string | null): void {
        const session = this.active;
        if (!session) return;
        this.active = null;
        if (session.socket) {
            session.socket.onmessage = null;
            session.socket.onclose = null;
            try {
                session.socket.close();
            } catch {
                // Already closing. Nothing left to release.
            }
            session.socket = null;
        }
        session.card.classList.remove("cp-card-live");
        session.canvas.remove();
        session.chip.remove();
        session.img.style.removeProperty("visibility");
        if (reason) this.announce(session.card, reason);
    }

    /**
     * A failure has to be visible on the card that failed — a live lane that silently does nothing
     * is indistinguishable from a long press that didn't register.
     */
    private announce(card: HTMLElement, message: string): void {
        const wrap = card.querySelector(".cp-imgwrap");
        if (!wrap) return;
        wrap.querySelector(".cp-live-error")?.remove();
        const box = document.createElement("span");
        box.className = "cp-live-error";
        box.setAttribute("role", "status");
        box.textContent = message;
        wrap.appendChild(box);
        setTimeout(() => box.remove(), ANNOUNCE_MS);
    }

    private startLive(card: HTMLElement, entry: CardEntry): void {
        const previewId = previewIdOf(
            entry,
            card.getAttribute("data-swap") === "1",
            card.getAttribute("data-bg-theme"),
        );
        if (!previewId) return;
        // Sign-in gates the daemon lane on a GitHub-authed box. The press is a deliberate request
        // for the lane, so it FOLLOWS the login rather than reporting a condition the visitor can't
        // act on — the same reason the viewer offers an anchor instead of a disabled chip. (A link
        // can't be nested inside the card, which is itself an `<a>`, so the navigation IS the
        // affordance.)
        if (this.config.signInHref) {
            const href = sameOriginNavigation(
                this.config.signInHref,
                location.origin,
            );
            if (href) location.href = href;
            else
                this.announce(
                    card,
                    "Sign in with GitHub to start a live session.",
                );
            return;
        }
        this.stopLive(null);
        const img = card.querySelector("img");
        const wrap = card.querySelector(".cp-imgwrap");
        if (!img || !wrap) return;

        const canvas = document.createElement("canvas");
        canvas.className = "cp-card-canvas";
        // Seed from the thumbnail so the card shows real pixels for the whole connect window; the
        // first daemon frame overwrites the buffer.
        if (img.naturalWidth && img.naturalHeight) {
            canvas.width = img.naturalWidth;
            canvas.height = img.naturalHeight;
            try {
                canvas.getContext("2d")?.drawImage(img, 0, 0);
            } catch {
                // A tainted thumbnail cannot be read back. The connect window just starts blank.
            }
        }
        const chip = document.createElement("span");
        chip.className = "cp-live-chip";
        chip.setAttribute("role", "status");
        chip.textContent = "connecting…";
        wrap.appendChild(canvas);
        wrap.appendChild(chip);
        img.style.visibility = "hidden";
        card.classList.add("cp-card-live");

        const session: Session = {
            card,
            canvas,
            img,
            chip,
            socket: null,
            pointers: new Map(),
        };
        this.active = session;

        let socket: WebSocket;
        try {
            socket = new WebSocket(
                socketUrl(
                    this.config,
                    previewId,
                    location,
                    this.themeProvider(),
                ),
            );
        } catch {
            this.stopLive(closeReason(null));
            return;
        }
        session.socket = socket;

        let gotFrame = false;
        socket.onmessage = (event: MessageEvent) => {
            if (this.active !== session) return;
            let message: {
                type?: string;
                dataBase64?: string;
                codec?: string;
                message?: string;
            };
            try {
                message = JSON.parse(String(event.data));
            } catch {
                return;
            }
            if (message.type === "frame") {
                gotFrame = true;
                session.chip.textContent = "live";
                this.drawFrame(
                    session,
                    message.dataBase64 ?? "",
                    message.codec,
                );
            } else if (message.type === "error") {
                session.chip.textContent = message.message || "error";
            }
        };
        socket.onclose = (event: CloseEvent) => {
            if (this.active !== session) return;
            // Closed before any frame ⇒ the lane never activated. Drop the seeded thumbnail from
            // the canvas rather than letting it pass for a live render, and say why.
            this.stopLive(gotFrame ? null : closeReason(event));
        };
        this.wireInput(session);
    }

    private drawFrame(session: Session, b64: string, codec?: string): void {
        const image = new Image();
        image.onload = () => {
            if (this.active !== session) return;
            session.canvas.width = image.naturalWidth;
            session.canvas.height = image.naturalHeight;
            session.canvas.getContext("2d")?.drawImage(image, 0, 0);
        };
        image.src = `data:image/${codec || "png"};base64,${b64}`;
    }

    // ---- input forwarding ----------------------------------------------------

    private wireInput(session: Session): void {
        const canvas = session.canvas;
        const send = (message: Record<string, unknown>): void => {
            const socket = session.socket;
            if (this.active !== session || !socket || socket.readyState !== 1)
                return;
            socket.send(JSON.stringify({ type: "input", ...message }));
        };
        const pixel = (event: PointerEvent | WheelEvent) =>
            framePixel(
                canvas.getBoundingClientRect(),
                { x: canvas.width, y: canvas.height },
                { x: event.clientX, y: event.clientY },
            );

        canvas.addEventListener("pointerdown", (event) => {
            const point = pixel(event);
            if (!point) return;
            event.preventDefault();
            event.stopPropagation();
            try {
                canvas.setPointerCapture?.(event.pointerId);
            } catch {
                // Capture is a nicety; the pointer still tracks without it.
            }
            session.pointers.set(event.pointerId, { ...point, moved: false });
        });
        canvas.addEventListener("pointermove", (event) => {
            const state = session.pointers.get(event.pointerId);
            if (!state) return;
            const point = pixel(event);
            if (!point) return;
            event.preventDefault();
            // The down is withheld until the first move, so a tap can still be sent as a single
            // `click` below — the daemon's click fast-path renders between press and release, which
            // a batched down+up can race.
            if (!state.moved) {
                state.moved = true;
                send({
                    kind: "pointerDown",
                    pixelX: state.x,
                    pixelY: state.y,
                    pointerId: event.pointerId,
                });
            }
            send({
                kind: "pointerMove",
                pixelX: point.x,
                pixelY: point.y,
                pointerId: event.pointerId,
            });
        });
        canvas.addEventListener("pointerup", (event) => {
            const state = session.pointers.get(event.pointerId);
            if (!state) return;
            session.pointers.delete(event.pointerId);
            const point = pixel(event) ?? { x: state.x, y: state.y };
            event.preventDefault();
            event.stopPropagation();
            send({
                kind: state.moved ? "pointerUp" : "click",
                pixelX: point.x,
                pixelY: point.y,
                pointerId: event.pointerId,
            });
        });
        canvas.addEventListener("pointercancel", (event) => {
            session.pointers.delete(event.pointerId);
        });
        // The card is a link: a click that reached it after driving the composition must not
        // navigate.
        canvas.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
        });
        canvas.addEventListener(
            "wheel",
            (event) => {
                const point = pixel(event);
                if (!point) return;
                event.preventDefault();
                send({
                    kind: "rotaryScroll",
                    pixelX: point.x,
                    pixelY: point.y,
                    scrollDeltaY: event.deltaY,
                });
            },
            { passive: false },
        );
    }

    // ---- the long press ------------------------------------------------------
    //
    // A card is a link, so the gesture has to be unambiguous in both directions: a press held past
    // the threshold goes live AND must not follow the link, while a tap, a drag (a scroll on touch)
    // or a right-click keeps the card behaving exactly as before.

    private cancelPress(): void {
        if (!this.press) return;
        clearTimeout(this.press.timer);
        this.press.card.classList.remove("cp-card-pressing");
        this.press = null;
    }

    private wireCard(card: HTMLElement, entry: CardEntry): void {
        card.classList.add("cp-card-livable");
        // Discoverability: the affordance is invisible otherwise. A plain span (not a button) — a
        // card is an `<a>`, and interactive content may not nest inside one.
        const wrap = card.querySelector(".cp-imgwrap");
        if (wrap && !wrap.querySelector(".cp-live-hint")) {
            const hint = document.createElement("span");
            hint.className = "cp-live-hint";
            hint.setAttribute("aria-hidden", "true");
            hint.textContent = "hold for live";
            wrap.appendChild(hint);
        }

        this.on(card, "pointerdown", (event) => {
            const pointer = event as PointerEvent;
            if (!startsHold(pointer)) return;
            // Already live — the canvas owns the pointer.
            if (this.active?.card === card) return;
            this.cancelPress();
            card.classList.add("cp-card-pressing");
            this.press = {
                card,
                entry,
                x: pointer.clientX,
                y: pointer.clientY,
                timer: setTimeout(() => {
                    const target = this.press;
                    this.cancelPress();
                    if (!target) return;
                    // The press became a gesture, so the click it will produce is not a navigation.
                    this.suppressNextClick = true;
                    this.startLive(target.card, target.entry);
                }, this.holdMs),
            };
        });
        this.on(card, "pointermove", (event) => {
            const pointer = event as PointerEvent;
            if (this.press?.card !== card) return;
            if (
                drifted(
                    this.press,
                    { x: pointer.clientX, y: pointer.clientY },
                    SLOP_PX,
                )
            )
                this.cancelPress();
        });
        for (const type of ["pointerup", "pointercancel", "pointerleave"]) {
            this.on(card, type, () => this.cancelPress());
        }
        // Touch platforms pop a context menu / selection callout on a long press; that is exactly
        // the gesture being claimed here.
        this.on(card, "contextmenu", (event) => {
            if (this.press?.card === card) event.preventDefault();
        });
        this.on(card, "click", (event) => {
            if (this.suppressNextClick || this.active?.card === card) {
                this.suppressNextClick = false;
                event.preventDefault();
                event.stopPropagation();
            }
        });
        // Keyboard equivalent: a long press is a pointer gesture, so `L` on a focused card is how
        // the same lane is reached without one. Escape leaves it (as does clicking off the card).
        this.on(card, "keydown", (event) => {
            const key = event as KeyboardEvent;
            if (key.ctrlKey || key.metaKey || key.altKey) return;
            if (key.key === "l" || key.key === "L") {
                key.preventDefault();
                if (this.active?.card === card) this.stopLive(null);
                else this.startLive(card, entry);
            } else if (key.key === "Escape" && this.active?.card === card) {
                key.preventDefault();
                this.stopLive(null);
            }
        });
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-catalog-live": CatalogLive;
    }
}
