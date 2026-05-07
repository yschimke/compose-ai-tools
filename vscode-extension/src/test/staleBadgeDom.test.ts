import * as assert from "assert";
import { applyStaleBadge } from "../webview/preview/staleBadgeDom";

/** Build a `<div class="preview-card">` with a `.card-title-row` child
 *  (the place the stale badge gets appended to) and append it to
 *  `document.body`. Returns the card. */
function buildCard(previewId = "com.example.A"): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.dataset.previewId = previewId;
    const titleRow = document.createElement("div");
    titleRow.className = "card-title-row";
    card.appendChild(titleRow);
    document.body.appendChild(card);
    return card;
}

afterEach(() => {
    document.body.innerHTML = "";
});

describe("applyStaleBadge", () => {
    it("stamps a .card-stale-btn into .card-title-row and adds .is-stale when isStale=true", () => {
        const card = buildCard();
        applyStaleBadge(card, true, () => {});

        const titleRow = card.querySelector(".card-title-row")!;
        const btn = titleRow.querySelector(".card-stale-btn") as HTMLElement;
        assert.ok(btn, "badge button should be appended to title row");
        assert.strictEqual(btn.tagName, "BUTTON");
        assert.strictEqual(
            btn.classList.contains("icon-button"),
            true,
            "should keep the shared .icon-button class",
        );
        assert.strictEqual(
            btn.getAttribute("aria-label"),
            "Keep stale capture fresh",
        );
        assert.ok(
            btn.querySelector("i.codicon.codicon-warning"),
            "should embed the warning codicon",
        );
        assert.strictEqual(card.classList.contains("is-stale"), true);
    });

    it("removes both the badge and .is-stale when isStale=false", () => {
        const card = buildCard();
        applyStaleBadge(card, true, () => {});
        applyStaleBadge(card, false, () => {});

        assert.strictEqual(card.querySelector(".card-stale-btn"), null);
        assert.strictEqual(card.classList.contains("is-stale"), false);
    });

    it("is idempotent: repeat calls in the same direction are no-ops", () => {
        const card = buildCard();
        applyStaleBadge(card, true, () => {});
        const afterFirstStamp = card.innerHTML;
        applyStaleBadge(card, true, () => {});
        applyStaleBadge(card, true, () => {});
        assert.strictEqual(card.innerHTML, afterFirstStamp);
        assert.strictEqual(
            card.querySelectorAll(".card-stale-btn").length,
            1,
            "no duplicate buttons",
        );

        applyStaleBadge(card, false, () => {});
        const afterFirstClear = card.innerHTML;
        applyStaleBadge(card, false, () => {});
        applyStaleBadge(card, false, () => {});
        assert.strictEqual(card.innerHTML, afterFirstClear);
        assert.strictEqual(card.querySelector(".card-stale-btn"), null);
    });

    it("survives stale → fresh → stale → fresh round trips", () => {
        const card = buildCard();
        for (let i = 0; i < 3; i++) {
            applyStaleBadge(card, true, () => {});
            assert.strictEqual(card.classList.contains("is-stale"), true);
            assert.strictEqual(
                card.querySelectorAll(".card-stale-btn").length,
                1,
            );
            applyStaleBadge(card, false, () => {});
            assert.strictEqual(card.classList.contains("is-stale"), false);
            assert.strictEqual(card.querySelector(".card-stale-btn"), null);
        }
    });

    it("invokes the onClick callback when the badge is clicked", () => {
        const card = buildCard();
        let clicks = 0;
        applyStaleBadge(card, true, () => {
            clicks++;
        });

        const btn = card.querySelector(".card-stale-btn") as HTMLElement;
        btn.click();
        btn.click();
        assert.strictEqual(clicks, 2);
    });

    it("stops the click event from bubbling to ancestor handlers", () => {
        const card = buildCard();
        let cardClicks = 0;
        card.addEventListener("click", () => {
            cardClicks++;
        });
        applyStaleBadge(card, true, () => {});

        const btn = card.querySelector(".card-stale-btn") as HTMLElement;
        btn.click();
        assert.strictEqual(
            cardClicks,
            0,
            "stopPropagation should keep the card-level click handler from firing",
        );
    });

    it("silently skips when the card has no .card-title-row", () => {
        const card = document.createElement("div");
        card.className = "preview-card";
        document.body.appendChild(card);

        assert.doesNotThrow(() => applyStaleBadge(card, true, () => {}));
        assert.strictEqual(card.querySelector(".card-stale-btn"), null);
        assert.strictEqual(
            card.classList.contains("is-stale"),
            false,
            "must not stamp .is-stale without the title row to host the badge",
        );
        assert.doesNotThrow(() => applyStaleBadge(card, false, () => {}));
    });

    it("does not register a duplicate click handler on a re-stamp after class drift", () => {
        // Defensive: if some other code path strips the badge button but
        // leaves `.is-stale` on the card, the next true-pass should still
        // succeed without double-binding the click handler.
        const card = buildCard();
        let clicks = 0;
        applyStaleBadge(card, true, () => {
            clicks++;
        });
        // Force the drift state.
        card.querySelector(".card-stale-btn")!.remove();
        applyStaleBadge(card, true, () => {
            clicks++;
        });

        const btn = card.querySelector(".card-stale-btn") as HTMLElement;
        btn.click();
        assert.strictEqual(
            clicks,
            1,
            "the previous (now-removed) button's handler should not fire",
        );
    });
});
