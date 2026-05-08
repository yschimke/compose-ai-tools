// Pure-DOM helpers for the per-card frame carousel — extracted from
// `FrameCarouselController` so the prev / indicator / next strip and the
// `idx / total · label` indicator update can be exercised under happy-dom
// without dragging in `previewStore`, `buildErrorPanel`, or the
// pointer-input handlers (which the controller's `step` / `show` paint
// pass needs but these two pieces don't).
//
// The controller wires its own dependencies into the callbacks: click /
// arrow-key navigation calls `onStep(card, ±1)` so this module never
// reaches into the captures map; the indicator-seed-after-mount call
// becomes `onIndicatorSeed(card)` so the `requestAnimationFrame` →
// `previewStore` lookup stays on the controller side. Tests can pass
// jest-style spies for both.

/**
 * Build the prev / indicator / next strip placed under the image on
 * multi-capture cards. Caller appends the result to the card.
 *
 * Click handlers and the keydown handler call [onStep] with `±1`. The
 * post-mount indicator seed (originally
 * `requestAnimationFrame(() => updateIndicator(card))` on the controller)
 * becomes [onIndicatorSeed] so this module never reaches into the
 * captures store.
 */
export function buildFrameControls(
    card: HTMLElement,
    onStep: (card: HTMLElement, delta: number) => void,
    onIndicatorSeed: (card: HTMLElement) => void,
): HTMLElement {
    const bar = document.createElement("div");
    bar.className = "frame-controls";

    const prev = document.createElement("button");
    prev.className = "icon-button frame-prev";
    prev.setAttribute("aria-label", "Previous capture");
    prev.title = "Previous capture";
    prev.innerHTML =
        '<i class="codicon codicon-chevron-left" aria-hidden="true"></i>';
    prev.addEventListener("click", () => onStep(card, -1));

    const indicator = document.createElement("span");
    indicator.className = "frame-indicator";
    indicator.setAttribute("aria-live", "polite");

    const next = document.createElement("button");
    next.className = "icon-button frame-next";
    next.setAttribute("aria-label", "Next capture");
    next.title = "Next capture";
    next.innerHTML =
        '<i class="codicon codicon-chevron-right" aria-hidden="true"></i>';
    next.addEventListener("click", () => onStep(card, 1));

    bar.appendChild(prev);
    bar.appendChild(indicator);
    bar.appendChild(next);

    // Arrow keys when the carousel has focus. Stop propagation so
    // the document-level focus-mode nav doesn't also advance the card.
    bar.tabIndex = 0;
    bar.addEventListener("keydown", (e) => {
        if (e.key === "ArrowLeft") {
            onStep(card, -1);
            e.preventDefault();
            e.stopPropagation();
        } else if (e.key === "ArrowRight") {
            onStep(card, 1);
            e.preventDefault();
            e.stopPropagation();
        }
    });

    // Seed indicator text so it's not blank before any image arrives.
    onIndicatorSeed(card);
    return bar;
}

/**
 * Update the `idx / total · label` indicator and disable the boundary
 * buttons. Reads `card.dataset.currentIndex` (falling back to `"0"`) and
 * the supplied [captures] array.
 *
 * No-ops if [captures] is undefined / empty or the card has no
 * `.frame-indicator` child. Missing per-capture labels render as `"—"`.
 */
export function updateFrameIndicator(
    card: HTMLElement,
    captures: ReadonlyArray<{ label: string }> | undefined,
): void {
    const indicator = card.querySelector<HTMLElement>(".frame-indicator");
    const prevBtn = card.querySelector<HTMLButtonElement>(".frame-prev");
    const nextBtn = card.querySelector<HTMLButtonElement>(".frame-next");
    if (!indicator) return;
    if (!captures || captures.length === 0) return;
    const idx = parseInt(card.dataset.currentIndex || "0", 10);
    const capture = captures[idx];
    const label = capture && capture.label ? capture.label : "—";
    indicator.textContent = idx + 1 + " / " + captures.length + " · " + label;
    if (prevBtn) prevBtn.disabled = idx === 0;
    if (nextBtn) nextBtn.disabled = idx === captures.length - 1;
}
