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
//
// `size` is a third optional trailing segment, for exactly the same reason as `theme`: a spec may
// split a component's breakpoints across separate `@Preview` functions, and those must not collide
// with a component whose sizes come from one function's several annotations. The resolver below
// tries the most-qualified key first and degrades, so every pre-existing catalog stays on the path
// it took before either axis existed.
function variantKey(componentId, state, props, theme, size) {
  const NUL = String.fromCharCode(0);
  const propsPart = Object.entries(props ?? {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}=${v}`)
    .join(",");
  const base = propsPart
    ? `${componentId}${NUL}${state}${NUL}${propsPart}`
    : `${componentId}${NUL}${state}`;
  const themed = theme ? `${base}${NUL}theme=${theme}` : base;
  return size ? `${themed}${NUL}size=${size}` : themed;
}

/** Android's `UI_MODE_NIGHT_YES` (0x20) under the `UI_MODE_NIGHT_MASK` (0x30). */
function uiModeIsNight(uiMode) {
  return typeof uiMode === "number" && (uiMode & 0x30) === 0x20;
}

/**
 * The variant identity of one daemon preview: which `@Preview` annotation on its function produced
 * it. `night`/`widthDp`/`fontScale` are null when the preview carries no signal for that axis, so
 * a candidate never loses a comparison for an axis it simply doesn't declare.
 *
 * `night` prefers the annotation's own `uiMode` bits and falls back to the id suffix, because the
 * two catalogs disagree about where the theme lives: `@CatalogModes` mints `Foo_Light`/`Foo_Dark`
 * ids, while a hand-written `@Preview(uiMode = UI_MODE_NIGHT_YES)` (Jetsnack's `"dark theme"`, say)
 * names itself anything at all and only the bits know.
 */
function variantIdentity(preview) {
  const params = preview.params ?? {};
  const suffixTheme = themeOfPreviewId(preview.id);
  const night = uiModeIsNight(params.uiMode)
    ? true
    : suffixTheme
      ? suffixTheme === "dark"
      : null;
  return {
    id: preview.id,
    night,
    widthDp: typeof params.widthDp === "number" ? params.widthDp : null,
    fontScale:
      typeof params.fontScale === "number" && params.fontScale !== 1
        ? params.fontScale
        : null,
  };
}

/**
 * Pick the daemon preview whose `@Preview` annotation actually produced [image], out of every
 * candidate sharing its function.
 *
 * This is the fix for #2883. The old lookup kept ONE id per function ("first wins"), so a screen
 * with default / dark / large-font annotations resolved all three stickers to whichever annotation
 * the bundle happened to list first — and since the per-variant figma-svg emit keys off
 * `image.previewId`, all three variants were handed the same vector. The PNGs never showed it: they
 * come from the Gradle render path, which renders each annotation in its own right.
 *
 * Axes are scored rather than matched exactly so a candidate that declares nothing for an axis
 * stays eligible, and an image that constrains nothing keeps taking the first candidate — the
 * pre-existing behaviour for every single-annotation function.
 */
/**
 * The spec `@Preview` function backing [image], trying the most-qualified `variantKey` first and
 * degrading one optional axis at a time. Order matters: `theme+size` → `theme` → `size` → bare,
 * so a spec that folds only one axis into separate functions still resolves, and a spec that folds
 * neither lands on the bare key it always used.
 */
function resolveFunction(previewForState, componentId, image, state) {
  const keys = [];
  if (image.theme && image.size)
    keys.push(
      variantKey(componentId, state, image.props, image.theme, image.size),
    );
  if (image.theme)
    keys.push(variantKey(componentId, state, image.props, image.theme));
  if (image.size)
    keys.push(
      variantKey(componentId, state, image.props, undefined, image.size),
    );
  keys.push(variantKey(componentId, state, image.props));
  for (const key of keys) {
    const fn = previewForState.get(key);
    if (fn) return fn;
  }
  return undefined;
}

function pickVariantId(candidates, image, widthForSize) {
  if (candidates.length === 0) return undefined;
  if (candidates.length === 1) return candidates[0].id;
  const wantNight =
    image.theme === "dark" ? true : image.theme === "light" ? false : null;
  const wantWidth = widthForSize(image.size);
  const wantFontScale = requestedFontScale(image.props);
  let best;
  let bestScore = -Infinity;
  for (const candidate of candidates) {
    let score = 0;
    if (wantNight !== null) {
      if (candidate.night !== null) score += candidate.night === wantNight ? 2 : -2;
    } else if (candidate.night === true) {
      // A sticker that names NO theme is the catalog's default one — its path is
      // `ideal__default__compact`, with no theme segment, precisely because light IS the default
      // (only the dark sibling gets tagged). Treating that as "unconstrained" let it tie with the
      // dark annotation and take whichever the bundle listed first, which is how Jetsnack's
      // Snack/Card and Search/Categories kept shipping the DARK vector against a light PNG after
      // the first pass at #2883. Same shape as the font-scale preference below: absent means
      // "prefer the untagged annotation", scored ±1 so it breaks the tie without ever outvoting a
      // matching width or an explicit theme.
      score -= 1;
    } else {
      score += 1;
    }
    if (wantWidth !== null && candidate.widthDp !== null) {
      score += candidate.widthDp === wantWidth ? 2 : -2;
    }
    // Font scale is the one axis with no dedicated image field, so a spec expresses it as a props
    // variant. It still has to be scored, or two annotations that differ ONLY by `fontScale` tie
    // and the first id wins for both — the same collapse this function exists to prevent. Weaker
    // than the other axes because the no-hint case is a preference, not a constraint: an image
    // that asks for nothing should land on the unscaled annotation rather than an arbitrary one,
    // but must not out-vote a matching theme or width.
    if (wantFontScale !== null) {
      score += candidate.fontScale === wantFontScale ? 2 : -2;
    } else {
      score += candidate.fontScale === null ? 1 : -1;
    }
    if (score > bestScore) {
      bestScore = score;
      best = candidate;
    }
  }
  return best?.id;
}

/**
 * The font scale an image's `props` ask for, or null when it names none. Accepts a number or the
 * numeric strings a hand-written spec tends to carry (`"1.5"`, `"1.5x"`); `1` is normalised to
 * null so an explicitly-default variant matches an annotation that simply omits `fontScale`.
 */
function requestedFontScale(props) {
  const raw = props?.fontScale;
  if (raw == null) return null;
  const value =
    typeof raw === "number" ? raw : Number.parseFloat(String(raw).replace(/x$/i, ""));
  if (!Number.isFinite(value) || value === 1) return null;
  return value;
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
        // silently took the LIGHT preview's id. `v.size` is the same story one axis over.
        if (v.preview && (v.state || v.props || v.theme || v.size)) {
          previewForState.set(
            variantKey(
              component.componentId,
              v.state ?? "default",
              v.props,
              v.theme,
              v.size,
            ),
            v.preview,
          );
        }
      }
    }
  }
  // Every daemon preview a function produced, in bundle order, with the variant identity of the
  // `@Preview` annotation behind it. Keeping the whole list — rather than the first id per
  // function, which is what shipped one vector to every variant of a multi-annotation screen
  // (#2883) — is what lets `pickVariantId` route each sticker to its own render.
  const previewsByFn = new Map();
  // Every preview id in the bundle, so the `@OverrideVariant` fallback below can confirm a
  // reconstructed `<baseId>_VARIANT_<state>` id actually rendered before routing to it.
  const allPreviewIds = new Set();
  // Earlier bundles win, matching `figmaSvgByFunctions`' fold: the primary render is the
  // authority, and a supplement only contributes previews the primary doesn't have. Falsy
  // entries are skipped so callers can pass `[bundle, extraBundle]` with no extra render.
  for (const bundle of Array.isArray(bundles) ? bundles : [bundles]) {
    if (!bundle) continue;
    for (const preview of bundle.previews ?? []) {
      if (allPreviewIds.has(preview.id)) continue;
      allPreviewIds.add(preview.id);
      const fn = preview.functionName ?? preview.id;
      const list = previewsByFn.get(fn) ?? [];
      list.push(variantIdentity(preview));
      previewsByFn.set(fn, list);
    }
  }
  // `size` → the width the spec's breakpoints declare for it, so a sticker's size axis can be
  // compared against a candidate's `@Preview(widthDp = …)`. Empty for a spec with no breakpoints,
  // in which case the size axis simply doesn't constrain the pick.
  const widthBySize = new Map(
    (spec.breakpoints ?? [])
      .filter(
        (b) => typeof b?.size === "string" && typeof b?.widthDp === "number",
      )
      .map((b) => [b.size, b.widthDp]),
  );
  const widthForSize = (size) =>
    size == null ? null : (widthBySize.get(size) ?? null);
  let mapped = 0;
  for (const component of manifest.components ?? []) {
    for (const image of component.images ?? []) {
      // Most-qualified key first, degrading to the bare one. A theme- or size-folded component
      // registers only its qualified key, so it resolves to its own per-theme / per-size
      // function; a component whose axes come from one function's several annotations registers
      // only the bare key, so its stickers miss the qualified lookups and land on the same entry
      // they always did — and `pickVariantId` then separates the annotations.
      const state = image.state ?? "default";
      const fn = resolveFunction(previewForState, component.componentId, image, state);
      if (fn && !overriddenFunctions.has(fn)) {
        // Route to the annotation that actually produced this sticker, not to the function's
        // first-listed preview (#2883).
        const daemonId = pickVariantId(
          previewsByFn.get(fn) ?? [],
          image,
          widthForSize,
        );
        if (daemonId) {
          image.previewId = daemonId;
          mapped++;
          continue;
        }
      }
      // `@OverrideVariant` fallback: a non-default state with NO spec `variants` entry is a
      // synthetic `<baseId>_VARIANT_<state>` preview (discovery mints the id that way). Its
      // functionName equals the base's, so the candidate list only holds the BASE ids;
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
          const baseDaemonId = pickVariantId(
            previewsByFn.get(baseFn) ?? [],
            image,
            widthForSize,
          );
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
