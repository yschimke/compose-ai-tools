/**
 * Bridge published catalog stickers to their live daemon preview ids.
 *
 * `ServeCatalogStore` builds its catalog-id -> daemon-id alias solely from each
 * `catalog.json` image's `previewId`; without it a live-bundle / source catalog
 * boots a daemon that no catalog id ever reaches. This resolves
 * `(componentId, state, props)` -> the spec `@Preview` function, then that
 * function -> the daemon preview id, for BOTH themed catalogs (compose-m3) and
 * un-themed state-variant catalogs (wear-m3 / remote-m3). Extracted from the
 * driver so it is unit-testable (the driver runs top-level on import).
 */

/**
 * Theme of a daemon preview id. The catalog's multipreview (`@CatalogModes` /
 * `@CatalogTemplate`) renders each function in `name = "Light"` / `name = "Dark"`
 * variants, so the discovered preview id ends with the mode
 * (`FilledButton_Light` / `FilledButton_Dark`) — the same signal `layoutByFunction`
 * keys off. Returns "light"/"dark", or null for an un-themed id.
 */
function themeOfPreviewId(id) {
  const s = String(id ?? "").toLowerCase();
  if (s.endsWith("dark")) return "dark";
  if (s.endsWith("light")) return "light";
  return null;
}

/**
 * Bridge the two id namespaces so a trusted live serve can answer the published
 * catalog URLs. A daemon knows previews by their function-based descriptor id
 * (`FilledButton_Dark`); the published links/routes use the componentId-slug id
 * derived from `image.path` (`button-filled__ideal__default__dark`). For each
 * catalog image, resolve `(componentId, state)` → the spec's `@Preview` function,
 * then `(function, theme)` → the desktop daemon preview id in the bundle, and
 * record it as `image.previewId`. `ServeCatalogStore.previewAliasFor` reads it back.
 *
 * Skips images with no desktop source: a state whose function isn't in the bundle
 * (nothing to run) and any function replaced by the Android-only supplement
 * (`overriddenFunctions` — its baked pixels, e.g. the inset focus ring, differ from
 * what the desktop daemon would draw). Those stay baked-PNG only, with no live lane.
 */
// Key an image/variant by componentId + state + sorted `props` + optional `theme` — so a
// props-only variant (e.g. `content: icon+label`, which keeps the default `state`) resolves
// to its own `@Preview` function instead of colliding with the label-only default.
// State-only variants carry no props, so the key stays `${id}\0${state}`, unchanged.
//
// `theme` is a separate trailing segment rather than folded into `props` because it is
// OPTIONAL on both sides of the lookup, and the two cases must not collide:
//
//   - a THEME-FOLDED component (a screen whose light/dark renders are two separate
//     `@Preview` functions, tagged via the spec's `theme` variant axis) needs
//     `(id, state, props, theme)` → its own per-theme function;
//   - a MULTIPREVIEW-THEMED component (one `@Preview` rendered `_Light`/`_Dark`) has no
//     theme in its spec at all, so its images must still resolve on the theme-less key.
//
// Hence the resolver below tries the theme-qualified key first and falls back to the bare
// one — a lookup order that leaves every pre-existing catalog on exactly the path it took
// before theme existed.
function variantKey(componentId, state, props, theme) {
  const NUL = String.fromCharCode(0);
  const propsPart = Object.entries(props ?? {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}=${v}`)
    .join(",");
  const base = propsPart
    ? `${componentId}${NUL}${state}${NUL}${propsPart}`
    : `${componentId}${NUL}${state}`;
  return theme ? `${base}${NUL}theme=${theme}` : base;
}

/**
 * [bundles] is every bundle whose previews can back this catalog — the primary render plus any
 * `--extra-renders` supplement — mirroring how `figmaSvgByFunctions` folds them. A single bundle
 * is accepted too, so existing callers keep working.
 *
 * Reading only the primary bundle silently dropped every `--extra-renders`-only component: its
 * previews never entered the id maps below, so its images got no `previewId` at all. That is not
 * cosmetic — `previewId` is what `ServeCatalogStore` aliases on (so those components had no live
 * lane) and what the per-variant figma-svg emit keys on (so they got no editable vectors). It hid
 * well because "no previewId" is also the legitimate outcome for a deliberately-unbridged image,
 * so a wholly-unbridged component looked no different from an intentional skip.
 */
export function bridgeLivePreviewIds(
  manifest,
  spec,
  bundles,
  overriddenFunctions,
) {
  const previewForState = new Map();
  for (const group of spec.groups ?? []) {
    for (const component of group.components ?? []) {
      previewForState.set(
        variantKey(component.componentId, "default"),
        component.preview,
      );
      for (const v of component.variants ?? []) {
        // `v.theme` counts as a distinguishing axis alongside state/props: a theme-only
        // variant (the split light/dark screen case) carries neither, and was previously
        // dropped here — so its sticker fell back to the component's default function and
        // silently took the LIGHT preview's id.
        if (v.preview && (v.state || v.props || v.theme)) {
          previewForState.set(
            variantKey(
              component.componentId,
              v.state ?? "default",
              v.props,
              v.theme,
            ),
            v.preview,
          );
        }
      }
    }
  }
  // Two id-keying shapes, so both a THEMED catalog (compose-m3: one `@Preview`
  // per `_Light`/`_Dark` variant, stickers tagged `theme`) and an UN-THEMED
  // state-variant catalog (wear-m3 / remote-m3: one `@Preview` per state, whose
  // stickers carry no `theme` and whose daemon ids don't end in light/dark)
  // resolve. Themed ids key on `${fn}\0${theme}`; un-themed ids key on the bare
  // function (first wins — state variants never share a function).
  const daemonIdFor = new Map();
  const unthemedIdFor = new Map();
  // Every preview id in the bundle, so the `@OverrideVariant` fallback below can confirm a
  // reconstructed `<baseId>_VARIANT_<state>` id actually rendered before routing to it.
  const allPreviewIds = new Set();
  // Earlier bundles win, matching `figmaSvgByFunctions`' fold: the primary render is the
  // authority, and a supplement only contributes previews the primary doesn't have. Falsy
  // entries are skipped so callers can pass `[bundle, extraBundle]` with no extra render.
  for (const bundle of Array.isArray(bundles) ? bundles : [bundles]) {
    if (!bundle) continue;
    for (const preview of bundle.previews ?? []) {
      allPreviewIds.add(preview.id);
      const fn = preview.functionName ?? preview.id;
      const theme = themeOfPreviewId(preview.id);
      if (theme) {
        if (!daemonIdFor.has(`${fn}\0${theme}`))
          daemonIdFor.set(`${fn}\0${theme}`, preview.id);
      } else if (!unthemedIdFor.has(fn)) unthemedIdFor.set(fn, preview.id);
    }
  }
  let mapped = 0;
  for (const component of manifest.components ?? []) {
    for (const image of component.images ?? []) {
      // Theme-qualified first, bare key second. A theme-folded component registers only
      // the qualified key, so it resolves to its own per-theme function; a
      // multipreview-themed component registers only the bare key, so its themed stickers
      // miss the first lookup and land on the same entry they always did.
      const state = image.state ?? "default";
      const fn =
        (image.theme
          ? previewForState.get(
              variantKey(
                component.componentId,
                state,
                image.props,
                image.theme,
              ),
            )
          : undefined) ??
        previewForState.get(
          variantKey(component.componentId, state, image.props),
        );
      if (fn && !overriddenFunctions.has(fn)) {
        // Prefer the theme-keyed daemon id when the sticker carries a theme; fall
        // back to the un-themed function id for state-variant catalogs (Wear/Remote).
        const daemonId =
          (image.theme
            ? daemonIdFor.get(`${fn}\0${image.theme}`)
            : undefined) ?? unthemedIdFor.get(fn);
        if (daemonId) {
          image.previewId = daemonId;
          mapped++;
          continue;
        }
      }
      // `@OverrideVariant` fallback: a non-default state with NO spec `variants` entry is a
      // synthetic `<baseId>_VARIANT_<state>` preview (discovery mints the id that way). Its
      // functionName equals the base's, so `unthemedIdFor`/`daemonIdFor` only hold the BASE id;
      // reconstruct the variant's daemon id from the base id + the `_VARIANT_<state>` tag (theme
      // stays inside the base id, e.g. `SwitchOn_Light_VARIANT_off`), and route to it only when it
      // actually rendered. Without this the `@OverrideVariant` states carry no `previewId`, so the
      // live lane can't reach them and the per-variant figma-svg emit skips them.
      if (
        state !== "default" &&
        !fn &&
        (image.props == null || Object.keys(image.props).length === 0)
      ) {
        const baseFn = previewForState.get(
          variantKey(component.componentId, "default"),
        );
        if (baseFn && !overriddenFunctions.has(baseFn)) {
          const baseDaemonId =
            (image.theme
              ? daemonIdFor.get(`${baseFn}\0${image.theme}`)
              : undefined) ?? unthemedIdFor.get(baseFn);
          const variantId = baseDaemonId && `${baseDaemonId}_VARIANT_${state}`;
          if (variantId && allPreviewIds.has(variantId)) {
            image.previewId = variantId;
            mapped++;
          }
        }
      }
    }
  }
  console.log(`[${spec.system}] bridged ${mapped} live preview id(s) → daemon`);
}
