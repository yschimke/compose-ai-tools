// Shared Side / Overlay / Onion mode-bar widget for the diff overlays in the
// preview and history panels. Same DOM shape (`role=tablist` + 3 buttons),
// same `aria-selected` toggle behaviour, same CSS class (`.diff-mode-bar`).
//
// Lifted out of `preview/diffOverlay.ts` and `history/behavior.ts`'s
// `buildHistoryDiffModeBar` — both panels were carrying the same ~30-line
// function with only the persistence wiring different. The stack / pane
// builders are not shared because the two panels intentionally use different
// CSS class prefixes (`preview-diff-pane` vs `diff-pane`) to scope styling.

export type DiffMode = "side" | "overlay" | "onion";

interface DiffModeSpec {
    id: DiffMode;
    label: string;
}

const DIFF_MODES: readonly DiffModeSpec[] = [
    { id: "side", label: "Side" },
    { id: "overlay", label: "Overlay" },
    { id: "onion", label: "Onion" },
];

/**
 * Build the Side / Overlay / Onion tablist for a diff overlay. The active tab
 * is marked with both the `.active` class (for styling) and `aria-selected`
 * (for screen readers). [onChange] fires with the new mode each time a tab
 * is clicked; the caller is expected to persist the choice and re-render the
 * diff body.
 */
export function buildDiffModeBar(
    initialMode: DiffMode,
    onChange: (mode: DiffMode) => void,
): HTMLElement {
    const bar = document.createElement("div");
    bar.className = "diff-mode-bar";
    bar.setAttribute("role", "tablist");
    for (const m of DIFF_MODES) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.textContent = m.label;
        btn.dataset.mode = m.id;
        btn.setAttribute("role", "tab");
        btn.setAttribute(
            "aria-selected",
            m.id === initialMode ? "true" : "false",
        );
        if (m.id === initialMode) btn.classList.add("active");
        btn.addEventListener("click", () => {
            for (const b of bar.querySelectorAll<HTMLButtonElement>("button")) {
                b.classList.toggle("active", b.dataset.mode === m.id);
                b.setAttribute(
                    "aria-selected",
                    b.dataset.mode === m.id ? "true" : "false",
                );
            }
            onChange(m.id);
        });
        bar.appendChild(btn);
    }
    return bar;
}
