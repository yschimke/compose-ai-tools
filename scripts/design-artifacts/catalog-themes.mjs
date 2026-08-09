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
 * `dark` is deliberately left unset. Nothing in the pipeline *declares* whether a theme is dark —
 * the annotation carries a name and a group — and design-parity#307 made the field a declaration
 * precisely so it wouldn't become a luminance guess made at some arbitrary layer. A consumer that
 * needs the mode can still read the theme's own `surface` out of its published tokens.
 *
 * @param {{previews?: Array<{id?: string, params?: Record<string, unknown>}>}} bundle the packed
 *   bundle, whose `previews.json` entries carry each specimen's provider FQN, name and group.
 * @param {Array<{theme?: string, previewId?: string, providerFqn?: string, tokens?: object}>}
 *   themeTokenSets `themeTokenSetsFromBundle(bundle)`.
 * @param {(previewId: string, theme: string) => void} [onSkip] called for each dropped theme.
 * @returns {Array<{id: string, name?: string, group?: string, tokens: object}>} in the order given.
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
    out.push(entry);
  }
  return out;
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
