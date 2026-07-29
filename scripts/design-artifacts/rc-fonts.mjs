/**
 * rc-fonts.mjs — the typefaces the Remote Compose browser lane renders with.
 *
 * The client-side player asks for the concrete faces Android's `fonts.xml` resolves its generic
 * families to (`cssFontStackFor` in the vendored player's `src/web/CanvasPaintContext.ts`). A
 * browser page only honours that request if the faces are actually registered; otherwise it falls
 * through to the host's generics and every glyph differs from the baked raster, which shows up as a
 * permanent parity residual no layout work can close.
 *
 * The files here are the *same ones the snapshot renderer rasterizes with* — vendored from
 * Robolectric's nativeruntime — so registering them makes the two lanes comparable rather than
 * merely similar. Families and weights mirror `FAMILY_FILES` in `render-fonts-manifest.mjs`; keep
 * the three in sync (`rc-fonts.test.mjs` checks the player and the vendored files, and is the
 * reason a rename cannot silently reintroduce font substitution).
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * The vendored faces. `weight` is the face's nominal weight (what to load and assert on); `range`
 * is the span it *serves* in the `@font-face` rule.
 *
 * The ranges matter. Declared at discrete weights, a request for an in-between weight — Wear M3's
 * `bodyLarge` asks for 450 — is resolved by CSS's matching rules, which for a target in 400..500
 * search *upward* first and so land on Medium. The text then renders heavier than the baked raster,
 * visibly so at display sizes. Giving each file a contiguous range makes every request resolve to a
 * real file with no interpolation and no synthetic emboldening: Regular serves everything below
 * Medium's nominal 500, Medium serves 500 and up.
 */
export const FONT_FACES = [
  { family: "Roboto", weight: 400, range: "1 499", file: "Roboto-Regular.ttf" },
  { family: "Roboto", weight: 500, range: "500 1000", file: "Roboto-Medium.ttf" },
  { family: "Noto Serif", weight: 400, range: "1 1000", file: "NotoSerif-Regular.ttf" },
  { family: "Droid Sans Mono", weight: 400, range: "1 1000", file: "DroidSansMono.ttf" },
];

/**
 * Where those files live. The wasm catalog's dist is the repo's vendored copy of the renderer's
 * own faces — the same directory `render-fonts-manifest.mjs` validates its manifest against.
 */
export const DEFAULT_FONTS_DIR = path.resolve(
  HERE,
  "../../samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts",
);

/**
 * Build a `<style>` registering [FONT_FACES] from [dir], inlined as data: URIs so the page needs
 * neither a server nor the network.
 *
 * A [dir] with no readable faces yields "" and the caller renders with generic families — degraded,
 * but working. Which files were found is always logged, because "rendered in the wrong typeface" is
 * invisible in the output and scores inside the normal mismatch band.
 */
export function fontFaceCss(dir) {
  if (!dir || !fs.existsSync(dir)) {
    console.log(`rc-fonts: no font directory at ${dir ?? "(unset)"}, using generic families`);
    return "";
  }
  const rules = [];
  const missing = [];
  for (const { family, range, file } of FONT_FACES) {
    const p = path.join(dir, file);
    if (!fs.existsSync(p)) {
      missing.push(file);
      continue;
    }
    const b64 = fs.readFileSync(p).toString("base64");
    rules.push(
      `@font-face{font-family:"${family}";font-weight:${range};font-style:normal;` +
        `src:url(data:font/ttf;base64,${b64}) format("truetype");}`,
    );
  }
  if (missing.length > 0) console.log(`rc-fonts: WARNING missing in ${dir}: ${missing.join(", ")}`);
  console.log(`rc-fonts: registered ${rules.length}/${FONT_FACES.length} faces from ${dir}`);
  return rules.length > 0 ? `<style>${rules.join("")}</style>` : "";
}

/**
 * Force the declared faces to load, and verify they did.
 *
 * `@font-face` is lazy and canvas does not drive it: `ctx.font` neither triggers a load nor waits
 * for one, and `document.fonts.ready` resolves while every face is still `unloaded` — so a page can
 * look correctly configured and still paint the first frames in the fallback. Load each face
 * explicitly, then assert with `document.fonts.check()`.
 *
 * Throws when a *declared* face fails to load. That is a real fault (a corrupt or truncated file),
 * as distinct from having no font directory at all, which degrades quietly above.
 */
export async function loadAndVerifyFonts(page) {
  const spec = FONT_FACES.map(({ family, weight }) => ({ family, weight }));
  const unresolved = await page.evaluate(async (faces) => {
    await Promise.all(faces.map((f) => document.fonts.load(`${f.weight} 16px "${f.family}"`)));
    await document.fonts.ready;
    return faces
      .filter((f) => !document.fonts.check(`${f.weight} 16px "${f.family}"`))
      .map((f) => `${f.family} ${f.weight}`);
  }, spec);
  if (unresolved.length > 0) {
    throw new Error(`rc-fonts: declared faces failed to load: ${unresolved.join(", ")}`);
  }
  console.log(`rc-fonts: ${spec.length} faces loaded and verified`);
}
