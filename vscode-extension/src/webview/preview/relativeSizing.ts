// Card sizing helper used by `cardBuilder.ts`'s `setPreviews` post-render
// pass. Lifted into its own file so the DOM operation is testable under
// happy-dom without dragging cardBuilder's wider DOM-bound transitive
// imports (Lit-decorator-using `<message-banner>` etc.) into the host
// tsconfig.
//
// The previews carry `widthDp` / `heightDp` annotations from the Compose
// `@Preview` parameters; we use them to set CSS custom properties on each
// card so fixed-layout modes can render variants at their relative sizes
// (e.g. `wearos_large_round` 227dp vs `wearos_small_round` 192dp side-by-
// side). Variants without dimensions fall back to the default CSS (full
// card width, auto aspect ratio).

import { sanitizeId } from "./cardData";
import type { PreviewInfo } from "../shared/types";

/**
 * Walk the manifest and stamp each card's `--size-ratio` /
 * `--aspect-ratio` CSS custom properties from its `widthDp` / `heightDp`.
 *
 * Idempotent: subsequent calls overwrite the custom properties cleanly,
 * and previews whose dimensions become unknown lose both vars (the CSS
 * falls back to the default layout). Cards that disappeared from the
 * manifest aren't touched — `renderPreviews` removes them separately.
 */
export function applyRelativeSizing(previews: readonly PreviewInfo[]): void {
    const widths = previews
        .map((p) => p.params.widthDp ?? 0)
        .filter((w) => w > 0);
    const maxW = widths.length > 0 ? Math.max(...widths) : 0;
    for (const p of previews) {
        const card = document.getElementById("preview-" + sanitizeId(p.id));
        if (!card) continue;
        const w = p.params.widthDp;
        const h = p.params.heightDp;
        if (w && h && maxW > 0) {
            card.style.setProperty("--size-ratio", (w / maxW).toFixed(4));
            card.style.setProperty("--aspect-ratio", w + " / " + h);
        } else {
            card.style.removeProperty("--size-ratio");
            card.style.removeProperty("--aspect-ratio");
        }
    }
}
