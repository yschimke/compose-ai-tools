# Google downloadable fonts in the figma-svg export

By default the `compose/figma-svg` export names text `font-family="sans-serif"`, so whatever opens
the SVG — a browser preview, the fidelity rasteriser, Figma — substitutes *its own* sans-serif and
the typography drifts from the design.

With font embedding on (`-Dcomposeai.figma.embedFonts=true`, also implied by the fidelity harness)
the exporter resolves each text node's face to a **Google downloadable font** — the Material default
maps to **Roboto** — fetches its WOFF2 (via the Google Fonts CSS2 API, cached under the renderer's
own `composeai.fonts.cacheDir`) and embeds it as an `@font-face` data URI, naming the text with the
real family. The SVG becomes self-contained and renders the true typeface everywhere.

| Before — `sans-serif` (viewer substitutes) | After — embedded Google Roboto |
|---|---|
| ![sans-serif](text-sans-serif-before.png) | ![roboto](text-roboto-after.png) |

Both panels are the *same exported SVG* rendered by headless Chromium; the only difference is the
embedded `@font-face`. Before, Chromium falls back to its platform sans-serif (here a DejaVu/Liberation
face); after, the title renders in Roboto Medium and the body in Roboto Regular — the actual Material
type. Figma, which ships Roboto, matches by name on import; a browser/Chromium uses the embedded WOFF2.

**Scope note.** This lands the *export* side. The desktop `compose-figma-fidelity` score doesn't move
yet because the desktop Skiko render doesn't itself draw Roboto — a separate desktop-render-font gap;
on Android (where the render *is* Roboto) and in Figma the embedded face is the correct match. It's
opt-in so default renders stay deterministic and offline-safe (a failed/absent fetch degrades to the
named `sans-serif`, never an error).
