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

import { breakpointMatcher, catalogBreakpoints } from "./catalog-breakpoints.mjs";
import { captureGutterPx } from "./catalog-preview-declarations.mjs";

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
  // The theme is read off the id BEFORE the reseed suffix. Discovery builds an `@OverrideVariant`
  // id as `<base id>_VARIANT_<name>`, so the base's own `…_Light` / `…_Dark` segment is no longer
  // the tail and `themeOfPreviewId` saw none — every reseed came back with an unknown theme. That
  // cost nothing while a reseed could not be selected; it decides the wrong render the moment one
  // can be. Stripped by the override's own NAME rather than by searching for the marker, which is
  // a legal substring of a Kotlin function name.
  const overrideName = preview.overrides?.name ?? null;
  const reseedTag = overrideName ? `_VARIANT_${overrideName}` : null;
  const id = String(preview.id ?? "");
  const suffixTheme = themeOfPreviewId(
    reseedTag && id.endsWith(reseedTag) ? id.slice(0, -reseedTag.length) : id,
  );
  const night = uiModeIsNight(params.uiMode)
    ? true
    : suffixTheme
      ? suffixTheme === "dark"
      : null;
  return {
    id: preview.id,
    density:
      typeof params.density === "number" && Number.isFinite(params.density) && params.density > 0
        ? params.density
        : undefined,
    night,
    // The annotation's own device id — the axis a multipreview expansion actually varies. Scored
    // ahead of width below: two Wear round devices can render at the same width, and a device the
    // annotation names is a statement rather than a fingerprint.
    device: typeof params.device === "string" ? params.device : null,
    widthDp: typeof params.widthDp === "number" ? params.widthDp : null,
    fontScale:
      typeof params.fontScale === "number" && params.fontScale !== 1
        ? params.fontScale
        : null,
    // Not an identity field — carried so [stampPreviewDensities] can publish the capture gutter in
    // the same pass, on every catalog. The declarations pass that also publishes it runs only where
    // a live lane is bridged, and a gutter is not a live-lane concern: a static catalog's sheet
    // lays its images out exactly like a bridged one's (m3-catalog#179).
    captureGutter: params.captureGutter ?? null,
    locale: typeof params.locale === "string" ? params.locale : null,
    // Also not an identity field, and carried for exactly the reason the gutter is: a
    // second-tier cell is a fact about the ANNOTATION that drew this image, and the declarations
    // pass that also publishes it joins on `image.previewId` and runs only where a live lane or a
    // buildable source exists. A baked-only catalog — which the public server serves read-only —
    // therefore never saw the flag and listed every second-tier cell in full.
    secondary: preview.overrides?.secondary === true,
    // The `@OverrideVariant` this candidate reseeds, or null for a base capture. Read off the spec
    // rather than the `_VARIANT_` id suffix, which is a legal substring of a Kotlin function name.
    // [expandDeferredRecords] selects on it: a deferred record naming an override STATE must route
    // to that reseed, not to the base annotation that happens to share its theme and size.
    overrideName,
    // The axis assignment a `@PreviewAxis` cell reseeds with, as an object. That cell is identified
    // by its PROPS, not by a name: `applyVariantAxisProps` deliberately leaves `state` at its
    // default for one, because structured props match a kit by property rather than by spelling.
    // So a deferred record for such a cell names no state, and only these can tell it apart from
    // the base annotation it shares every `@Preview` parameter with.
    overrideProps: overridePropsOf(preview.overrides?.props),
  };
}

/** A variant spec's `[{key, value}]` props as a plain object; `null` when it declares none. */
function overridePropsOf(props) {
  if (!Array.isArray(props) || props.length === 0) return null;
  const out = {};
  for (const { key, value } of props) {
    if (typeof key === "string" && key && typeof value === "string") out[key] = value;
  }
  return Object.keys(out).length > 0 ? out : null;
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

function pickVariantId(candidates, image, breakpointForSize) {
  if (candidates.length === 0) return undefined;
  if (candidates.length === 1) return candidates[0].id;
  const wantNight =
    image.theme === "dark" ? true : image.theme === "light" ? false : null;
  const wantBreakpoint = breakpointForSize(image.size);
  const wantDevice = wantBreakpoint.device ?? null;
  const wantWidth = wantBreakpoint.widthDp ?? null;
  const wantFontScale = requestedFontScale(image.props);
  const wantLocale = normalizedLocale(image.props?.locale);
  let best;
  let bestConstraint = -Infinity;
  let bestPreference = -Infinity;
  for (const candidate of candidates) {
    // Two SEPARATE tiers, compared lexicographically. `constraint` scores the axes the image
    // actually states; `preference` only breaks ties between candidates the constraints rank
    // equally. Summing the two into one number is what kept #2883 alive after the first two passes:
    // a matching width (+2) with an unwanted font scale (-1) landed on exactly the same total as a
    // candidate declaring neither (0 + 1), so the tie fell back to bundle order and Jetsnack's
    // `compact` stickers took the *default* annotation's vector — a 412dp PNG paired with the
    // intrinsic-width SVG. A preference must never be able to cancel a constraint, and no choice of
    // weights within one number can guarantee that once several preferences stack.
    let constraint = 0;
    let preference = 0;
    if (wantNight !== null) {
      if (candidate.night !== null) constraint += candidate.night === wantNight ? 2 : -2;
    } else if (candidate.night === true) {
      // A sticker that names NO theme is the catalog's default one — its path is
      // `ideal__default__compact`, with no theme segment, precisely because light IS the default
      // (only the dark sibling gets tagged). Treating that as "unconstrained" let it tie with the
      // dark annotation and take whichever the bundle listed first, which is how Jetsnack's
      // Snack/Card and Search/Categories kept shipping the DARK vector against a light PNG after
      // the first pass at #2883. Absent means "prefer the untagged annotation".
      preference -= 1;
    } else {
      preference += 1;
    }
    // A size the image names resolves through the spec's breakpoints to a width, and the size axis
    // was itself derived from a candidate's `@Preview(widthDp = …)` (see `applySpecBreakpoints`) —
    // so a candidate declaring exactly that width IS, by construction, the annotation that rendered
    // this sticker. A candidate declaring no width stays neutral rather than wrong: a
    // single-annotation component whose sticker carries a size must still resolve.
    if (wantDevice !== null && candidate.device !== null) {
      constraint += candidate.device === wantDevice ? 2 : -2;
    }
    if (wantWidth !== null && candidate.widthDp !== null) {
      constraint += candidate.widthDp === wantWidth ? 2 : -2;
    }
    // Font scale is the one axis with no dedicated image field, so a spec expresses it as a props
    // variant. It still has to be scored, or two annotations that differ ONLY by `fontScale` tie
    // and the first id wins for both — the same collapse this function exists to prevent. Stated as
    // a prop it's a constraint; unstated it's only a preference for the unscaled annotation, which
    // is why the two land in different tiers.
    if (wantFontScale !== null) {
      constraint += candidate.fontScale === wantFontScale ? 2 : -2;
    } else {
      preference += candidate.fontScale === null ? 1 : -1;
    }
    // The LOCALE, in the same two tiers and for the same reason. `variantIdentity` has carried it
    // since gutters started publishing physical edges, but nothing scored it — so one function with
    // separate LTR and RTL `@Preview` annotations resolved BOTH images to whichever appears first,
    // and an asymmetric gutter was published with its left and right edges swapped for one of them.
    // That is a wrong crop rather than a missing one, which is the worse of the two.
    //
    // Compared normalised, because a catalog spells a locale the way its annotation did and `ar_XB`
    // and `ar-XB` are the same render — the same normalisation `rendersRightToLeft` applies before
    // it asks about direction.
    if (wantLocale !== null) {
      constraint += normalizedLocale(candidate.locale) === wantLocale ? 2 : -2;
    } else {
      preference += candidate.locale === null ? 1 : -1;
    }
    if (
      constraint > bestConstraint ||
      (constraint === bestConstraint && preference > bestPreference)
    ) {
      bestConstraint = constraint;
      bestPreference = preference;
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
 * A locale tag reduced to what makes two of them the same render: trimmed, `_` separators turned
 * into `-`, and lower-cased. Null for anything that is not a non-empty string, so an image stating
 * no locale constrains nothing.
 *
 * The renderer normalises the same way (`Pseudolocale.fromTag`, `LocaleDirection.isRtl`), and a
 * comparison that did not would rank `ar_XB` and `ar-XB` as different annotations.
 */
function normalizedLocale(locale) {
  if (typeof locale !== "string") return null;
  const trimmed = locale.trim();
  return trimmed === "" ? null : trimmed.replace(/_/g, "-").toLowerCase();
}

/**
 * Every daemon preview a function produced, in bundle order, as [variantIdentity] records — the
 * candidate pool [pickVariantId] routes a sticker's axes against. Earlier bundles win on a repeated
 * id, matching `figmaSvgByFunctions`' fold: the primary render is the authority and a supplement
 * only contributes previews the primary doesn't have. Falsy entries are skipped so callers can pass
 * `[bundle, extraBundle]` with no extra render.
 *
 * Keeping the whole list per function — rather than the first id, which is what shipped one vector
 * to every variant of a multi-annotation screen (#2883) — is what lets each sticker reach its own
 * render.
 */
function previewsByFunction(bundles, allPreviewIds = new Set()) {
  const previewsByFn = new Map();
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
  return previewsByFn;
}

/**
 * `size` → the breakpoint the spec declares for it, so a sticker's size axis can be compared against
 * a candidate's `@Preview(device = …)` / `@Preview(widthDp = …)`. Yields an empty breakpoint for a
 * spec that declares none (or a size it doesn't name), in which case the size axis simply doesn't
 * constrain the pick.
 *
 * Reads `catalogBreakpoints`, not `spec.breakpoints`: a Wear catalog that declares none is tagged
 * with the standard round table when its stickers are baked, so resolving the live lane against an
 * empty table would leave every size unconstrained on exactly the catalogs the axis matters most to.
 */
function breakpointForSizeOf(spec) {
  const bySize = new Map(
    (catalogBreakpoints(spec) ?? [])
      .filter((b) => typeof b?.size === "string")
      .map((b) => [b.size, b]),
  );
  return (size) => (size == null ? EMPTY_BREAKPOINT : (bySize.get(size) ?? EMPTY_BREAKPOINT));
}

const EMPTY_BREAKPOINT = Object.freeze({});

function previewForStateOf(spec) {
  const previewForState = new Map();
  for (const group of spec.groups ?? []) {
    for (const component of group.components ?? []) {
      previewForState.set(
        variantKey(component.componentId, "default"),
        component.preview,
      );
      for (const variant of component.variants ?? []) {
        if (variant.preview && (variant.state || variant.props || variant.theme || variant.size)) {
          previewForState.set(
            variantKey(
              component.componentId,
              variant.state ?? "default",
              variant.props,
              variant.theme,
              variant.size,
            ),
            variant.preview,
          );
        }
      }
    }
  }
  return previewForState;
}

/**
 * The breakpoint `size` a candidate annotation renders, or undefined — by its `@Preview(device = …)`
 * id first, then its width, through the same matcher that tagged the baked stickers.
 */
function sizeForCandidateOf(spec) {
  const matcher = breakpointMatcher(catalogBreakpoints(spec));
  return (candidate) =>
    matcher ? matcher({ device: candidate?.device, widthDp: candidate?.widthDp }) : undefined;
}

/**
 * Resolve the **deferred** (live-only) records to concrete, per-annotation stickers — the id each
 * would have been rendered under, and the daemon preview that renders it live.
 *
 * A deferred record names the `@Preview` function it came from, but not always the axes the sticker
 * would have carried, and the two differ by how the deferral was declared:
 *
 *   - **mode** deferral names its `theme` already (the export resolved it per rendered image), so
 *     the record maps 1:1 onto the annotation [pickVariantId] selects for those axes;
 *   - **entry** / **variant** deferral names none — the render never happened, so nothing recorded
 *     that the function would have produced a light AND a dark sticker (or one per breakpoint). The
 *     axes are recovered here from the function's `@Preview` annotations themselves ([variantIdentity]
 *     already reads `uiMode` / `widthDp` / the id suffix), expanding ONE spec record into one record
 *     per annotation so an entry-deferred component gets the same set of live-only cards the baked
 *     sheet would have shown.
 *
 * Records are returned as fresh objects (the input is not mutated), each carrying `previewId` when a
 * daemon twin was found. A record whose function isn't in any bundle is returned as-is with no
 * `previewId` — nothing can render it, so the serve host skips it rather than registering a card
 * that would 404. Two annotations that recover the SAME axes collapse to one record (the exporter
 * would have named them one path); first listed wins.
 */
/**
 * The reseeds a deferred record EXPLICITLY names, or null when it names none.
 *
 * `pickVariantId` scores theme, size and font scale. It has no opinion about an `@OverrideVariant`
 * reseed, and a reseed shares its base's function and every one of those annotation parameters. So
 * a record deferred for a mode scored the base and the reseed identically and took whichever came
 * first: the base. The live-only card then rendered through the base annotation, showing the
 * resting cell under the variant's name, and its per-preview declarations (`secondary` among them)
 * were the base's too.
 *
 * A reseed identifies itself in one of two ways, and both have to be read:
 *
 *   - a hand-written `@OverrideVariant` has only a NAME, which the export writes as the record's
 *     `state`;
 *   - a `@PreviewAxis` cell has a structured assignment, which `applyVariantAxisProps` writes as
 *     the record's `props` while deliberately leaving `state` at its default — the props are the
 *     identity there, matching a kit by property rather than by spelling.
 *
 * Reading only the first would route every axis cell to its base, which is the same defect one
 * spelling over. Null for a record that names neither, and null rather than an empty list when it
 * names one this function has no reseed for: that is a spelling nothing can act on, and dropping
 * the route would lose a card rather than mis-address one.
 */
function keyedCandidates(candidates, record) {
  const state = record?.state;
  const named = typeof state === "string" && state !== "" && state !== "default" ? state : null;
  if (named) {
    const byName = candidates.filter((c) => c.overrideName === named);
    return byName.length > 0 ? byName : null;
  }
  const wanted = record?.props;
  if (wanted && typeof wanted === "object") {
    // Every axis the reseed declares must be the one this record asks for. A subset match, not an
    // equality: the record may also carry a `fontScale` or a spec-authored prop from another axis.
    const byProps = candidates.filter(
      (c) =>
        c.overrideProps &&
        Object.entries(c.overrideProps).every(([k, v]) => String(wanted[k]) === String(v)),
    );
    if (byProps.length > 0) return byProps;
  }
  return null;
}

/** The base annotations, for a record naming no reseed; the whole list when a function is all reseeds. */
function baseCandidates(candidates) {
  const base = candidates.filter((c) => c.overrideName === null);
  return base.length > 0 ? base : candidates;
}

export function expandDeferredRecords(deferred, spec, bundles) {
  const previewsByFn = previewsByFunction(bundles);
  const breakpointForSize = breakpointForSizeOf(spec);
  const sizeForCandidate = sizeForCandidateOf(spec);
  const out = [];
  for (const record of deferred ?? []) {
    const candidates = previewsByFn.get(record?.preview) ?? [];
    if (candidates.length === 0) {
      out.push({ ...record });
      continue;
    }
    // Axes already known (a mode deferral), or a single-annotation function: one record, routed to
    // the annotation those axes select — exactly a baked sticker's resolution.
    // What this record names, if anything. Read BEFORE the branch: an axis-keyed record with no
    // theme falls into the expansion below, and expanding every candidate there emitted the base
    // AND its reseed carrying the same `props` — one derived path with two conflicting `previewId`s.
    // The expansion is for a record that names no axes at all; one that names a cell selects it.
    const keyed = keyedCandidates(candidates, record);
    if (record?.theme || candidates.length === 1) {
      const daemonId = pickVariantId(
        keyed ?? baseCandidates(candidates),
        record ?? {},
        breakpointForSize,
      );
      out.push(daemonId ? { ...record, previewId: daemonId } : { ...record });
      continue;
    }
    // Font scale is the third recoverable axis, and the one with no dedicated record field — the
    // exporter expresses it as a `props` entry. A record that already names one (a props variant the
    // spec declared) SELECTS among the annotations rather than expanding over them; a record that
    // names none RECOVERS each annotation's own scale. Without that, a function's large-text
    // annotation shares its unscaled sibling's theme and size, so the key below would call the two
    // one sticker and the large-text live-only route would never be published at all.
    const wantedScale = requestedFontScale(record?.props);
    const expandable = keyed ?? candidates;
    const scaled =
      wantedScale === null ? expandable : expandable.filter((c) => c.fontScale === wantedScale);
    const pool = scaled.length > 0 ? scaled : expandable;
    const seen = new Set();
    for (const candidate of pool) {
      const theme =
        candidate.night === true ? "dark" : candidate.night === false ? "light" : undefined;
      const size = record?.size ?? sizeForCandidate(candidate);
      // Only a RECOVERED scale becomes props; a record that named its own keeps it verbatim, so the
      // spec's exact spelling ("1.5x", "2.0") is what reaches the path.
      const scale = wantedScale === null ? candidate.fontScale : null;
      // A reseed is its OWN cell, and the record has to say so in the axes `catalogImagePath` reads
      // — otherwise the route it derives names the resting cell while rendering the variant, which
      // is the mis-addressing this pass exists to stop, one path over. Its identity also joins the
      // dedup key: a reseed shares its base's theme, size and scale, so keying on those alone
      // collapsed the two and the component lost the card entirely.
      //
      // Props win over a name where the reseed has them, matching `applyVariantAxisProps`: the two
      // describe the same cell, and stamping both would double-count it in the fold.
      const cell = candidate.overrideProps
        ? { props: candidate.overrideProps }
        : candidate.overrideName
          ? { state: candidate.overrideName }
          : {};
      const key = [
        theme ?? "",
        size ?? "",
        scale ?? "",
        candidate.overrideName ?? "",
      ].join("\u0000");
      if (seen.has(key)) continue;
      seen.add(key);
      const props = {
        ...(record?.props ?? {}),
        ...(cell.props ?? {}),
        ...(scale != null ? { fontScale: formatFontScale(scale) } : {}),
      };
      out.push({
        ...record,
        ...(theme ? { theme } : {}),
        ...(size ? { size } : {}),
        ...(cell.state ? { state: cell.state } : {}),
        ...(Object.keys(props).length > 0 ? { props } : {}),
        previewId: candidate.id,
      });
    }
  }
  return out;
}

/**
 * A recovered `@Preview(fontScale = …)` as the exporter spells it in a path segment. The annotation
 * carries a number (`2`), while a spec-declared props variant carries the string the author wrote
 * (`"2.0"`) — and it is that string the baked sticker's `…__fontscale-2.0.png` name comes from. So a
 * whole number is written back with one decimal place, keeping a recovered route identical to the
 * one the same annotation would have published had it been baked.
 */
function formatFontScale(value) {
  return Number.isInteger(value) ? value.toFixed(1) : String(value);
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
  const previewForState = previewForStateOf(spec);
  // Every preview id in the bundle, so the `@OverrideVariant` fallback below can confirm a
  // reconstructed `<baseId>_VARIANT_<state>` id actually rendered before routing to it — filled in
  // as `previewsByFunction` folds the bundles.
  const allPreviewIds = new Set();
  const previewsByFn = previewsByFunction(bundles, allPreviewIds);
  const breakpointForSize = breakpointForSizeOf(spec);
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
          breakpointForSize,
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
            breakpointForSize,
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

/**
 * Stamp each baked image with the density of the preview annotation that rendered it. This is
 * independent of live alias publication: static catalogs and intentionally unbridged supplement
 * images still need the density to export Figma references at the correct physical scale.
 *
 * "At the correct physical scale" is the whole job, and an unstamped image does not degrade —
 * `FigmaRestRasterizer.scaleFor` has no scale to request without a density, so it throws and the
 * reference is DROPPED. That is why the `@OverrideVariant` fallback below matters far more here
 * than the phrase "still need the density" suggests: on m3-catalog it was the difference between 4
 * and 436 published variant references, and every `@OverrideVariant` state — every tab content
 * axis, every button state cell — served its page with no design-spec lane at all.
 */
export function stampPreviewDensities(manifest, spec, bundles) {
  const previewForState = previewForStateOf(spec);
  // Extra renders replace primary renders on a function clash, so their annotation metadata wins
  // just as their baked pixels do.
  const orderedBundles = (Array.isArray(bundles) ? bundles : [bundles]).filter(Boolean).reverse();
  const previewsByFn = previewsByFunction(orderedBundles);
  const breakpointForSize = breakpointForSizeOf(spec);
  let stamped = 0;
  for (const component of manifest.components ?? []) {
    for (const image of component.images ?? []) {
      const state = image.state ?? "default";
      const fn = resolveFunction(previewForState, component.componentId, image, state);
      const candidates = previewsByFn.get(fn) ?? [];
      const candidateId = pickVariantId(candidates, image, breakpointForSize);
      const candidate = candidates.find((it) => it.id === candidateId);
      // One resolution for everything this image inherits from the annotation that drew it. The
      // fallback used to return a bare density, so a synthetic `@OverrideVariant` cell kept its
      // export scale and silently lost every other fact — its capture gutter included, which would
      // have left those cells the only ones still drawn smaller than their siblings.
      const resolved =
        candidate ??
        overrideVariantCandidate({
          previewForState,
          previewsByFn,
          breakpointForSize,
          componentId: component.componentId,
          image,
          state,
          fn,
        })
      const density = resolved?.density;
      if (density !== undefined) {
        image.density = density;
        stamped++;
      }
      // The tier rides the same resolution, for the same reason the gutter does: this is the one
      // pass that reaches every catalog, and it needs no `previewId` to know which annotation drew
      // the pixels. See [variantIdentity] for what the declarations pass misses.
      if (resolved?.secondary === true) image.secondary = true;
      // The gutter rides on the same resolution: pixels need the density this pass just picked, so
      // there is no second place that could disagree about which annotation rendered this image.
      const gutter = captureGutterPx(resolved?.captureGutter, {
        density,
        locale: resolved?.locale,
      });
      if (gutter) {
        image.previewParams = { ...(image.previewParams ?? {}), captureGutter: gutter };
      }
    }
  }
  return stamped;
}

/**
 * The candidate preview behind a synthetic `@OverrideVariant` sticker, or undefined — the gap that made
 * [stampPreviewDensities] the one place the fallback [bridgeLivePreviewIds] and
 * [resolveSemanticsIds] both carry was missing, and the one whose absence went unnoticed longest
 * because it fails as a missing *reference* rather than a missing live lane.
 *
 * A non-default state with no spec `variants` entry is a synthetic `<baseId>_VARIANT_<state>`
 * preview. `PreviewDiscovery.overrideVariantPreview` derives it as `base.copy(id = base.id + tag)`,
 * which decides both halves of this lookup: the variant keeps the base's `functionName` — so it is
 * already in the base function's candidate list, and `resolveFunction` still finds nothing for the
 * state — and it keeps the base's `params`, so it carries the base's density verbatim.
 *
 * So this narrows that list to the tagged ids and picks among them, rather than reconstructing an
 * id from the base's. Reconstruction is what the sibling fallbacks do, and it is a trap here: the
 * variants share the base's function, so `pickVariantId` can return one AS the base and build a
 * doubled `…_VARIANT_icon_VARIANT_icon` that matches nothing.
 *
 * Which theme the pick lands on cannot change the answer — density is a `@Preview` param, identical
 * across a `@CatalogModes` pair — but it is separated properly anyway so the value always comes from
 * a record describing these exact pixels.
 */
function overrideVariantCandidate({
  previewForState,
  previewsByFn,
  breakpointForSize,
  componentId,
  image,
  state,
  fn,
}) {
  if (state === "default" || fn) return undefined;
  // Props-bearing images are a spec-declared axis, not an `@OverrideVariant` — the same guard the
  // sibling fallbacks apply, so a props variant with a missing spec entry stays unstamped rather
  // than borrowing a density from a sticker it isn't.
  if (image.props != null && Object.keys(image.props).length > 0) return undefined;
  const baseFn = previewForState.get(variantKey(componentId, "default"));
  const tag = `_VARIANT_${state}`;
  const candidates = (previewsByFn.get(baseFn) ?? []).filter((candidate) =>
    candidate.id.endsWith(tag),
  );
  // Empty ⇒ this state rendered no variant preview, and a density is a statement about the
  // annotation that drew the pixels. Inventing one from the base would hand the rasteriser an
  // export scale for a render that never happened.
  const picked = pickVariantId(candidates, image, breakpointForSize);
  return candidates.find((candidate) => candidate.id === picked);
}

/**
 * `functionName → candidates`, where a **later bundle REPLACES an earlier one** for any function it
 * also renders — rather than adding to it.
 *
 * [previewsByFunction] cannot express that. It dedupes by preview *id* and appends, so feeding it
 * reversed bundles only reorders the candidates: a primary and a supplement preview of the same
 * function, carrying differently-qualified ids (a theme-folded `Foo_Dark` against a bare `Foo`),
 * both stay in the list and [pickVariantId] can score the primary higher. The picked tree would then
 * describe pixels the supplement drew — wrong bounds, published as fact, which is worse than the
 * absent entry it replaced.
 *
 * Pass bundles in priority order, primary first: the supplement's baked pixels win, so its tree must
 * win with them.
 */
function previewsByFunctionReplacing(bundles) {
  const out = new Map();
  for (const bundle of (Array.isArray(bundles) ? bundles : [bundles]).filter(Boolean)) {
    const perBundle = new Map();
    const seen = new Set();
    for (const preview of bundle.previews ?? []) {
      if (seen.has(preview.id)) continue;
      seen.add(preview.id);
      const fn = preview.functionName ?? preview.id;
      const list = perBundle.get(fn) ?? [];
      list.push(variantIdentity(preview));
      perBundle.set(fn, list);
    }
    for (const [fn, list] of perBundle) out.set(fn, list);
  }
  return out;
}

/**
 * `image.path → daemon preview id` for every image, **ignoring live-alias eligibility**.
 *
 * The sibling of [stampPreviewDensities] and there for the same reason it is: that function already
 * documents why a second, unfiltered pass exists — "static catalogs and intentionally unbridged
 * supplement images still need the density". Carried semantics are the same kind of fact.
 *
 * [bridgeLivePreviewIds] withholds `previewId` from an image whose function the Android-only
 * supplement overrode, because those baked pixels are not what the *primary desktop daemon* would
 * draw, so routing a live request there would serve the wrong thing. That is a statement about
 * which daemon may re-render the image — not about whether a semantics tree for those exact pixels
 * exists. It does: the supplement's own bundle carries it. Reusing the live alias to find semantics
 * therefore silently drops the tag index for precisely those variants (compose-m3's inset
 * focus-ring stickers among them), and an element gate could never run on them.
 *
 * So this resolves the id from the render bundles directly, with no `overriddenFunctions` filter and
 * no mutation of the manifest. Callers that need the *live* alias must keep using `image.previewId`.
 */
export function resolveSemanticsIds(manifest, spec, bundles) {
  const previewForState = previewForStateOf(spec);
  const allPreviewIds = new Set();
  for (const bundle of (Array.isArray(bundles) ? bundles : [bundles]).filter(Boolean)) {
    for (const preview of bundle.previews ?? []) allPreviewIds.add(preview.id);
  }
  const previewsByFn = previewsByFunctionReplacing(bundles);
  const breakpointForSize = breakpointForSizeOf(spec);
  const out = new Map();
  for (const component of manifest?.components ?? []) {
    for (const image of component?.images ?? []) {
      if (typeof image?.path !== "string") continue;
      const state = image.state ?? "default";
      const fn = resolveFunction(previewForState, component.componentId, image, state);
      const daemonId = pickVariantId(previewsByFn.get(fn) ?? [], image, breakpointForSize);
      if (daemonId) {
        out.set(image.path, daemonId);
        continue;
      }
      // `@OverrideVariant` fallback, mirroring [bridgeLivePreviewIds]. A non-default state with no
      // spec `variants` entry is a synthetic `<baseId>_VARIANT_<state>` preview, so `resolveFunction`
      // finds nothing for it. Without this the images would carry no tag index at all on a catalog
      // built with neither `--publish-live-bundle` nor `--source-module` — where the bridge never
      // runs, so there is no `image.previewId` to fall back to either.
      if (
        state !== "default" &&
        !fn &&
        (image.props == null || Object.keys(image.props).length === 0)
      ) {
        const baseFn = previewForState.get(variantKey(component.componentId, "default"));
        const baseId = pickVariantId(previewsByFn.get(baseFn) ?? [], image, breakpointForSize);
        const variantId = baseId && `${baseId}_VARIANT_${state}`;
        // Only when it actually rendered — a reconstructed id that no bundle carries would key
        // nothing, and silently yield no entry anyway.
        if (variantId && allPreviewIds.has(variantId)) out.set(image.path, variantId);
      }
    }
  }
  return out;
}
