/**
 * The declared themes a catalog publishes, mapped from a bundle onto the export's
 * `Catalog.themes` (design-parity#307).
 *
 * A system's `tokens.dtcg.json` is the SYSTEM token set — the one resolved theme the stickers were
 * rendered under. A module that declares `@ThemeCatalog` / `@WearThemeCatalog` providers has more,
 * and each specimen render already resolved its own: the renderer writes them into the bundle as a
 * theme-tagged `previews/<id>.catalog.json`, and `@design-parity/candidate`'s
 * `themeTokenSetsFromBundle` reads them back keyed by theme (design-parity#313).
 *
 * This is the last hop — turning those token sets into the shape the export publishes, one
 * `themes/<slug>.dtcg.json` per theme. What it adds beyond the tokens is *identity*: a theme's
 * stable id is its **provider FQN**, because that is what a preview server addresses it by
 * (`?theme=theme:<providerFqn>`), so a consumer can join a published token set to the theme a page
 * is showing. The name and group come from the same `previews.json` entry the FQN does.
 */

import { themeTokensPath } from "@design-parity/catalog-export";

/**
 * Build the export's `themes` from a bundle's per-theme token sets.
 *
 * A theme with **no resolvable provider FQN** is skipped rather than published under a substitute
 * id. The id is the whole point of publishing these — a `themes[]` entry keyed on something a
 * preview server cannot address is data no consumer can attach to the theme it belongs to, which is
 * worse than the absence a consumer already handles. Skips are reported through [onSkip] so a
 * publishing job can say so rather than silently shipping a thinner catalog.
 *
 * `dark` is resolved from the theme's **own resolved `surface`**, the colour the renderer actually
 * composed that specimen on. It is deliberately decided *here* rather than left to consumers: the
 * point of the field is that a consumer pinning a page's colour scheme to the selected theme
 * shouldn't have to re-derive it, and every consumer re-deriving it independently is how two
 * surfaces end up disagreeing about the same theme. Absent only when a theme published no surface
 * at all, which is the one case nothing can answer.
 *
 * @param {{previews?: Array<{id?: string, params?: Record<string, unknown>}>}} bundle the packed
 *   bundle, whose `previews.json` entries carry each specimen's provider FQN, name and group.
 * @param {Array<{theme?: string, previewId?: string, providerFqn?: string, tokens?: object}>}
 *   themeTokenSets `themeTokenSetsFromBundle(bundle)`.
 * @param {(previewId: string, theme: string) => void} [onSkip] called for each dropped theme.
 * @returns {Array<{id: string, name?: string, group?: string, dark?: boolean, tokens: object}>} in
 *   the order given.
 */
export function catalogThemesFromBundle(bundle, themeTokenSets, onSkip) {
  const byId = previewsById(bundle);
  const out = [];
  const seen = new Set();
  for (const set of themeTokenSets ?? []) {
    const previewId = typeof set?.previewId === "string" ? set.previewId : "";
    const id = typeof set?.providerFqn === "string" ? set.providerFqn.trim() : "";
    const theme = typeof set?.theme === "string" ? set.theme : "";
    if (!id || !set?.tokens) {
      onSkip?.(previewId, theme);
      continue;
    }
    // One provider is one theme, and "same theme" means "same FILE": the exporter slugs an FQN
    // into `themes/<slug>.dtcg.json`, lowercasing it, so two providers differing only in case
    // collide on disk while comparing as distinct ids. Keying on the emitted path — asked of the
    // exporter rather than re-derived — makes the check agree with what is actually written. The
    // first wins and the rest are reported like any other skip.
    const slot = themeTokensPath(id);
    if (seen.has(slot)) {
      onSkip?.(previewId, theme);
      continue;
    }
    seen.add(slot);
    const entry = { id, tokens: set.tokens };
    const params = byId.get(previewId)?.params ?? {};
    const name = theme || str(params.name);
    if (name) entry.name = name;
    const group = str(params.group);
    if (group) entry.group = group;
    const dark = themeIsDark(set.tokens);
    if (dark !== undefined) entry.dark = dark;
    out.push(entry);
  }
  return out;
}

/**
 * The luminance below which a surface counts as dark.
 *
 * The same 0.45 the preview server's `ServeThemeCss` uses to decide which mode a catalog baked
 * (`catalogIsDark`), over the same WCAG relative-luminance formula. Deliberately not an independent
 * judgement: the server is the main consumer of this field, and a theme that this call says is
 * light while the server's own palette projection treats it as dark would paint a page against
 * itself. If one moves, both move.
 */
const DARK_LUMINANCE = 0.45;

/**
 * Whether [tokens] describe a dark theme, from the surface the renderer resolved for it — or
 * undefined when the theme published no surface to judge by.
 *
 * `surface` first, then `background`, matching the server's fallback order. A surface carrying
 * alpha is composited over white before it is measured, for the same reason the server does it: a
 * translucent colour is not a colour until something is behind it, and white is the only sensible
 * assumption at this layer.
 */
function themeIsDark(tokens) {
  const colors = tokens?.colors ?? {};
  const rgb = parseHex(colors.surface) ?? parseHex(colors.background);
  return rgb === undefined ? undefined : luminance(rgb) < DARK_LUMINANCE;
}

/**
 * `#rrggbb` / `#rrggbbaa` (the form `argbToCssHex` emits) → `[r, g, b]`, alpha composited over
 * white. Undefined for anything else, including a value that isn't a string.
 */
function parseHex(value) {
  if (typeof value !== "string") return undefined;
  const hex = value.trim().replace(/^#/, "");
  if (!/^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(hex)) return undefined;
  const byte = (at) => parseInt(hex.slice(at, at + 2), 16);
  const rgb = [byte(0), byte(2), byte(4)];
  if (hex.length === 6) return rgb;
  const alpha = byte(6) / 255;
  return rgb.map((c) => Math.round(c * alpha + 255 * (1 - alpha)));
}

/** WCAG relative luminance of an `[r, g, b]` triple. */
function luminance([r, g, b]) {
  const channel = (v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** `previews.json` entries keyed by id, so a token set can find the entry it came from. */
function previewsById(bundle) {
  const map = new Map();
  for (const preview of bundle?.previews ?? []) {
    if (typeof preview?.id === "string") map.set(preview.id, preview);
  }
  return map;
}

/** [value] when it is a non-blank string, else undefined. */
function str(value) {
  return typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : undefined;
}
