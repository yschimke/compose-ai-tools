// Behavioural contract for `<cp-catalog-live>`.
//
// The rules are pinned next door — `livePointerMap.test.ts`, `liveSession.test.ts`. What only the
// element can answer is the gesture and the lifecycle: that a tap still follows the link while a
// hold does not, that starting one session ends the previous, and that a lane which closes without
// ever delivering a frame takes its seeded thumbnail down with it instead of passing it off as a
// live render.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/CatalogLive.js";

const HOLD_MS = 20;

interface Sockets {
    all: FakeSocket[];
    last(): FakeSocket;
}

class FakeSocket {
    static opened: FakeSocket[] = [];
    readyState = 1;
    closed = false;
    sent: string[] = [];
    onmessage: ((event: { data: string }) => void) | null = null;
    onclose: ((event: { code?: number; reason?: string }) => void) | null =
        null;
    constructor(public url: string) {
        FakeSocket.opened.push(this);
    }
    send(data: string): void {
        this.sent.push(data);
    }
    close(): void {
        this.closed = true;
    }
    /** What the daemon pushing a frame looks like from here. */
    frame(): void {
        this.onmessage?.({
            data: JSON.stringify({
                type: "frame",
                codec: "png",
                dataBase64: "AAA",
            }),
        });
    }
}

function stubSockets(): Sockets {
    FakeSocket.opened = [];
    (globalThis as Record<string, unknown>).WebSocket = FakeSocket;
    return {
        all: FakeSocket.opened,
        last: () => FakeSocket.opened[FakeSocket.opened.length - 1],
    };
}

const cardHtml = (id: string) => `
  <a class="cp-card" href="/m3/p/${id}" data-swap="0">
    <span class="cp-imgwrap"><img alt=""></span>
    <span>${id}</span>
  </a>`;

async function mount(
    config: Record<string, unknown> = {},
    cards = ["plain.Button", "plain.Card"],
): Promise<void> {
    window.cpCatalogLive = {
        holdMs: HOLD_MS,
        cards: cards.map((id) => ({ l: id })),
        ...config,
    };
    document.body.innerHTML = `
      <cp-catalog-live></cp-catalog-live>
      <div class="cp-grid">${cards.map(cardHtml).join("")}</div>`;
    await flush();
}

const cardAt = (index: number) =>
    document.querySelectorAll<HTMLElement>(".cp-card")[index];

/** A pointerdown that `startsHold` accepts. happy-dom has no PointerEvent constructor. */
function pointer(type: string, over: Record<string, unknown> = {}): Event {
    const event = new Event(type, { bubbles: true, cancelable: true });
    Object.assign(event, {
        button: 0,
        pointerId: 1,
        clientX: 50,
        clientY: 50,
        ...over,
    });
    return event;
}

/** Wait past the hold threshold. */
const held = () => new Promise((resolve) => setTimeout(resolve, HOLD_MS + 10));

const chip = () => document.querySelector(".cp-live-chip");
const errorBox = () => document.querySelector(".cp-live-error");

describe("<cp-catalog-live>", () => {
    afterEach(() => {
        resetDom();
        delete window.cpCatalogLive;
    });

    it("offers the affordance on every livable card", async () => {
        // Invisible otherwise: nothing about a card says it can be held.
        stubSockets();
        await mount();
        assert.equal(document.querySelectorAll(".cp-live-hint").length, 2);
        assert.equal(document.querySelectorAll(".cp-card-livable").length, 2);
    });

    it("leaves a tap alone — the card is still a link", async () => {
        const sockets = stubSockets();
        await mount();
        const card = cardAt(0);
        card.dispatchEvent(pointer("pointerdown"));
        card.dispatchEvent(pointer("pointerup"));
        await held();
        assert.equal(sockets.all.length, 0, "no session from a tap");
        const click = pointer("click");
        card.dispatchEvent(click);
        assert.equal(click.defaultPrevented, false, "the link still follows");
    });

    it("goes live on a held press, and swallows the click it produces", async () => {
        // Both halves matter. Without the suppression the gesture starts a session and then
        // navigates away from it in the same breath.
        const sockets = stubSockets();
        await mount();
        const card = cardAt(0);
        card.dispatchEvent(pointer("pointerdown"));
        await held();
        assert.equal(sockets.all.length, 1);
        assert.ok(sockets.last().url.includes("/ws/plain.Button?"));
        assert.equal(card.classList.contains("cp-card-live"), true);
        assert.equal(chip()?.textContent, "connecting…");

        const click = pointer("click");
        card.dispatchEvent(click);
        assert.equal(click.defaultPrevented, true);
    });

    it("gives up the gesture when the pointer is really scrolling", async () => {
        const sockets = stubSockets();
        await mount();
        const card = cardAt(0);
        card.dispatchEvent(pointer("pointerdown"));
        card.dispatchEvent(pointer("pointermove", { clientY: 90 }));
        await held();
        assert.equal(sockets.all.length, 0);
    });

    it("reads the frame as live only once one has arrived", async () => {
        const sockets = stubSockets();
        await mount();
        cardAt(0).dispatchEvent(pointer("pointerdown"));
        await held();
        sockets.last().frame();
        assert.equal(chip()?.textContent, "live");
    });

    it("takes the seeded thumbnail down when the lane never activated", async () => {
        // The rule that keeps a card honest. The canvas is seeded from the thumbnail so there is no
        // blank flash while connecting — which means a socket that closes before any frame leaves a
        // still image sitting there passing for a live render.
        const sockets = stubSockets();
        await mount();
        const card = cardAt(0);
        card.dispatchEvent(pointer("pointerdown"));
        await held();
        sockets.last().onclose?.({ code: 1013 });
        assert.equal(card.classList.contains("cp-card-live"), false);
        assert.equal(document.querySelector(".cp-card-canvas"), null);
        assert.equal(
            errorBox()?.textContent,
            "Live preview is at capacity — try again shortly.",
        );
    });

    it("says nothing when a lane that WAS live closes", async () => {
        const sockets = stubSockets();
        await mount();
        cardAt(0).dispatchEvent(pointer("pointerdown"));
        await held();
        sockets.last().frame();
        sockets.last().onclose?.({ code: 1006 });
        assert.equal(errorBox(), null, "it worked; there is nothing to report");
    });

    it("runs one session at a time", async () => {
        // A live seat is a render daemon and a grid is 80+ cards.
        const sockets = stubSockets();
        await mount();
        cardAt(0).dispatchEvent(pointer("pointerdown"));
        await held();
        const first = sockets.last();
        cardAt(1).dispatchEvent(pointer("pointerdown"));
        await held();
        assert.equal(first.closed, true);
        assert.equal(sockets.all.length, 2);
        assert.equal(cardAt(0).classList.contains("cp-card-live"), false);
        assert.equal(cardAt(1).classList.contains("cp-card-live"), true);
    });

    it("reaches the same lane from the keyboard, and back out again", async () => {
        // A long press is a pointer gesture; without this the lane is unreachable without one.
        const sockets = stubSockets();
        await mount();
        const card = cardAt(0);
        card.dispatchEvent(
            new KeyboardEvent("keydown", { key: "l", bubbles: true }),
        );
        assert.equal(sockets.all.length, 1);
        card.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        assert.equal(card.classList.contains("cp-card-live"), false);
    });

    it("ends the session on a press anywhere else in the grid", async () => {
        stubSockets();
        await mount();
        cardAt(0).dispatchEvent(pointer("pointerdown"));
        await held();
        document.body.dispatchEvent(pointer("pointerdown"));
        assert.equal(cardAt(0).classList.contains("cp-card-live"), false);
    });

    it("follows the sign-in link instead of reporting a condition nobody can act on", async () => {
        const sockets = stubSockets();
        await mount({ signInHref: "https://evil.example/steal" });
        cardAt(0).dispatchEvent(pointer("pointerdown"));
        await held();
        // Cross-origin: refused, and said so rather than navigating.
        assert.equal(sockets.all.length, 0);
        assert.equal(
            errorBox()?.textContent,
            "Sign in with GitHub to start a live session.",
        );
    });

    it("stays inert on a grid the server gave no live config", async () => {
        stubSockets();
        document.body.innerHTML = `
          <cp-catalog-live></cp-catalog-live>
          <div class="cp-grid">${cardHtml("plain.Button")}</div>`;
        await flush();
        assert.equal(document.querySelector(".cp-live-hint"), null);
        assert.equal(
            document.querySelector(".cp-card-livable"),
            null,
            "with no live lane the cards are the links they always were",
        );
    });
});
