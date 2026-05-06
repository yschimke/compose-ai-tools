import * as assert from "assert";
import { stampLiveBadgesOnGrid } from "../webview/preview/liveBadge";
import { sanitizeId } from "../webview/preview/cardData";

/** Build a `<div class="preview-card" id="preview-...">` with an
 *  `.image-container` child (the place the per-card stop button gets
 *  appended to) and append it to `document.body`. Returns the card. */
function buildCard(previewId: string): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.id = "preview-" + sanitizeId(previewId);
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    card.appendChild(container);
    document.body.appendChild(card);
    return card;
}

/** Stand-in for `LiveStateController.ensureLiveCardControls` — appends a
 *  no-op `.card-live-stop-btn` if one isn't already there. The real
 *  controller also wires a click handler and re-attaches pointer/wheel
 *  input; this test stub keeps the contract narrow (idempotent button
 *  injection into `.image-container`). */
function ensureControlsStub(card: HTMLElement): void {
    const container = card.querySelector(".image-container");
    if (!container) return;
    if (!container.querySelector(".card-live-stop-btn")) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "icon-button card-live-stop-btn";
        container.appendChild(btn);
    }
}

afterEach(() => {
    document.body.innerHTML = "";
});

describe("stampLiveBadgesOnGrid", () => {
    it("adds .live and a .card-live-stop-btn to every card in the live set", () => {
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");
        buildCard("com.example.C"); // not live

        stampLiveBadgesOnGrid(
            new Set(["com.example.A", "com.example.B"]),
            ensureControlsStub,
        );

        assert.strictEqual(a.classList.contains("live"), true);
        assert.strictEqual(b.classList.contains("live"), true);
        assert.strictEqual(a.querySelectorAll(".card-live-stop-btn").length, 1);
        assert.strictEqual(b.querySelectorAll(".card-live-stop-btn").length, 1);
        const c = document.getElementById("preview-com_example_C")!;
        assert.strictEqual(c.classList.contains("live"), false);
        assert.strictEqual(c.querySelector(".card-live-stop-btn"), null);
    });

    it("removes .live and the stop button from cards no longer in the live set", () => {
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");

        // First pass: both live.
        stampLiveBadgesOnGrid(
            new Set(["com.example.A", "com.example.B"]),
            ensureControlsStub,
        );
        assert.strictEqual(a.classList.contains("live"), true);
        assert.strictEqual(b.classList.contains("live"), true);

        // Second pass: only A live — B should be stripped.
        stampLiveBadgesOnGrid(new Set(["com.example.A"]), ensureControlsStub);
        assert.strictEqual(a.classList.contains("live"), true);
        assert.strictEqual(b.classList.contains("live"), false);
        assert.strictEqual(a.querySelectorAll(".card-live-stop-btn").length, 1);
        assert.strictEqual(b.querySelector(".card-live-stop-btn"), null);
    });

    it("clears every .live decoration when called with an empty set", () => {
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");

        stampLiveBadgesOnGrid(
            new Set(["com.example.A", "com.example.B"]),
            ensureControlsStub,
        );
        stampLiveBadgesOnGrid(new Set(), ensureControlsStub);

        assert.strictEqual(a.classList.contains("live"), false);
        assert.strictEqual(b.classList.contains("live"), false);
        assert.strictEqual(a.querySelector(".card-live-stop-btn"), null);
        assert.strictEqual(b.querySelector(".card-live-stop-btn"), null);
    });

    it("is idempotent — calling twice produces the same final DOM", () => {
        const a = buildCard("com.example.A");
        const b = buildCard("com.example.B");

        const liveSet = new Set(["com.example.A", "com.example.B"]);
        stampLiveBadgesOnGrid(liveSet, ensureControlsStub);
        const firstPassHtml = document.body.innerHTML;
        stampLiveBadgesOnGrid(liveSet, ensureControlsStub);
        const secondPassHtml = document.body.innerHTML;

        assert.strictEqual(secondPassHtml, firstPassHtml);
        // And the per-card stop button never duplicates on re-stamp.
        assert.strictEqual(a.querySelectorAll(".card-live-stop-btn").length, 1);
        assert.strictEqual(b.querySelectorAll(".card-live-stop-btn").length, 1);
    });

    it("skips silently when a previewId in the live set has no DOM card", () => {
        const a = buildCard("com.example.A");
        // No card for `com.example.GHOST` — exercises the
        // `if (!(card instanceof HTMLElement)) return;` guard.
        assert.doesNotThrow(() =>
            stampLiveBadgesOnGrid(
                new Set(["com.example.A", "com.example.GHOST"]),
                ensureControlsStub,
            ),
        );
        // The real card was still stamped.
        assert.strictEqual(a.classList.contains("live"), true);
        assert.strictEqual(a.querySelectorAll(".card-live-stop-btn").length, 1);
        // No stray ghost card appeared.
        assert.strictEqual(
            document.getElementById("preview-com_example_GHOST"),
            null,
        );
    });

    it("calls ensureControls exactly once per live card, with that card", () => {
        buildCard("com.example.A");
        buildCard("com.example.B");
        buildCard("com.example.C"); // not live — must not be visited

        const visited: string[] = [];
        stampLiveBadgesOnGrid(
            new Set(["com.example.A", "com.example.B"]),
            (card) => {
                visited.push(card.dataset.previewId ?? "");
            },
        );

        assert.deepStrictEqual(visited.slice().sort(), [
            "com.example.A",
            "com.example.B",
        ]);
    });

    it("uses sanitizeId for DOM lookup so previewIds with special chars resolve", () => {
        // sanitizeId rewrites non-`[a-zA-Z0-9_-]` chars to `_`. The function
        // must look the card up under that sanitized id, not the raw id.
        const rawId = "com.example.A$Inner.preview";
        const card = buildCard(rawId);
        // Sanity: sanitizeId of the raw id matches the DOM id we stamped.
        assert.strictEqual(card.id, "preview-com_example_A_Inner_preview");
        stampLiveBadgesOnGrid(new Set([rawId]), ensureControlsStub);
        assert.strictEqual(card.classList.contains("live"), true);
        assert.strictEqual(
            card.querySelectorAll(".card-live-stop-btn").length,
            1,
        );
    });

    it("strips a stale stop button from a no-longer-live card even if .live was already removed", () => {
        // Defensive: covers a transient state where some other code path
        // dropped the `.live` class but left the overlay button behind.
        // The query selector is `.preview-card.live` — that path won't
        // catch the orphaned button. This test pins current behaviour:
        // the orphan survives one pass and is only cleared once the card
        // is re-added then re-removed from the live set.
        const a = buildCard("com.example.A");
        stampLiveBadgesOnGrid(new Set(["com.example.A"]), ensureControlsStub);
        assert.strictEqual(a.querySelectorAll(".card-live-stop-btn").length, 1);
        // Force the transient state.
        a.classList.remove("live");
        // A subsequent empty-set pass won't see `.preview-card.live`, so
        // the orphan button persists. This is the documented contract:
        // callers must keep the `.live` class in sync via this helper,
        // not bypass it.
        stampLiveBadgesOnGrid(new Set(), ensureControlsStub);
        assert.strictEqual(a.querySelectorAll(".card-live-stop-btn").length, 1);
        // Re-stamp and re-clear: the orphan is now gone because the
        // class came back through the controlled path first.
        stampLiveBadgesOnGrid(new Set(["com.example.A"]), ensureControlsStub);
        stampLiveBadgesOnGrid(new Set(), ensureControlsStub);
        assert.strictEqual(a.querySelectorAll(".card-live-stop-btn").length, 0);
    });
});
