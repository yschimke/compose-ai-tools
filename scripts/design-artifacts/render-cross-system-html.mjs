/**
 * Render a self-contained `matches.html` for a design-artifact catalog: every
 * component of THIS system (e.g. remote-m3) paired with its declared parallel in
 * a SIBLING system (e.g. wear-m3), side by side, so you can eyeball how a ported
 * component compares to its origin across the whole system at once.
 *
 * The pairing is authored, not guessed: each component in the system's
 * `catalog.spec.json` carries a `parallel` field naming its counterpart's
 * `componentId` in the other system (see the `remote-m3` catalog, in
 * yschimke/wear-m3-catalog). This
 * module takes the local catalog (the flattened manifest, same shape
 * `renderIndexHtml`/`renderCompareHtml` read), the `parallel` map lifted from the
 * spec, and the other system's component list (also from its committed spec), and
 * computes three buckets: paired, only-here, and only-there.
 *
 * Both columns are **static PNG thumbnails that link to the live preview server**
 * (see render-preview-embed.mjs): the this-system render is referenced by the
 * catalog's own relative `images/...` path; the other system's render lives on its
 * own `design-artifacts/<other>` branch — in this repo or, when the sibling is a
 * catalog of its OWN repository, in that one (`otherRepo`) — so its thumbnail is
 * baked to that branch's `raw.githubusercontent.com` URL. The URL comes from the
 * sibling's `catalog.json`, resolved once at BUILD time (the driver passes it in
 * as `otherManifest`) — no runtime fetch, so the thumbnails render on
 * htmlpreview.github.io / file:// / the raw branch alike. Clicking either
 * thumbnail opens the component on the live server for a re-render. A parallel
 * that's declared in the sibling spec but not yet rendered on its branch shows a
 * "not rendered yet" cell with a link — never a perpetual "loading …".
 *
 * A THIRD column appears when the driver resolves design references for the pairing
 * (`designRefById`): the published design-kit reference PNG both implementations are
 * reproducing, so the row reads **kit → origin → port** rather than asking a reader to
 * hold the kit in their head. Those pixels come from a `references/index.json` on a
 * delivery branch (see design-references.mjs) and are baked to a raw URL the same way,
 * so a reference can be contributed by EITHER side of the pair — in practice by
 * whichever catalog carries the `figma:` mapping. Absent references simply leave the
 * page two-column, exactly as before.
 *
 * Pure + dependency-free: returns an HTML string. Slug derivation matches
 * render-wireframe-svg's `slug` for the only-here / only-there buckets.
 */

import { variantLabel } from "./catalog-variants.mjs";
import { slug } from "./render-wireframe-svg.mjs";
import {
  esc,
  heroImageOf,
  previewEmbed,
  previewEmbedStyles,
  variantImageOf,
} from "./render-preview-embed.mjs";
import { DEFAULT_PREVIEW_BASE, livePreviewUrl } from "./live-preview.mjs";

/**
 * The rows a catalog offers for comparison: every component, plus every **compared variant** of
 * one, flattened into a single list in reading order (a variant directly after its parent).
 *
 * A variant is compared in its own right rather than through its parent — `Button/Compact-IconOnly`
 * and `Button/Compact` are different renders that can diverge from the kit independently. Walking
 * `components` alone made "is this a component or a variant?" a question about parity coverage, and
 * pushed catalogs to declare variants as top-level components to keep them on this page.
 *
 * Only variants carrying a `parallel` appear: those are the ones with a counterpart to pair
 * against. `apply-variant-parity.mjs` is what puts them on the manifest in the first place.
 *
 * Each variant row borrows its parent's `group` (it belongs in the same section) and takes an id of
 * `<parentId> · <discriminator>`, so the row is nameable, anchorable and visibly a variant of
 * something. `variantOf` and `variant` are carried through for the renderer.
 *
 * @param {object[]} components the local manifest's `components`
 * @returns {object[]} rows shaped like components, each variant row also carrying
 *   `{ variantOf, variant }`
 */
export function comparableEntries(components) {
  const rows = [];
  for (const component of components ?? []) {
    rows.push(component);
    for (const variant of component.variants ?? []) {
      if (!variant?.parallel) continue;
      rows.push({
        ...component,
        componentId: `${component.componentId} \u00b7 ${variantDiscriminator(variant)}`,
        caption: variant.caption ?? component.caption,
        parallel: variant.parallel,
        reference: variant.reference,
        referenceSet: variant.referenceSet,
        noReference: variant.noReference,
        variants: undefined,
        variantOf: component.componentId,
        variant,
      });
    }
  }
  return rows;
}

/**
 * How a variant row names itself after its parent: EVERY axis it declares, in the order the fold
 * writes them — state, `key=value` props, theme, selection — else the preview function name so the
 * row is never anonymous.
 *
 * Every axis, not the first one that answers. Returning on `state` alone gave two variants of one
 * parent that share a state and differ by props or theme the same visible label: `state=pressed
 * props={size: small}` and `state=pressed props={size: large}` both read `Parent · pressed`. The
 * anchor allocator kept the hidden HTML ids unique, so the page stayed navigable — but a reader
 * comparing the two rows, or quoting one in a finding, had nothing to tell them apart.
 *
 * {@link variantLabel} is the same accounting the fold's missing-render report uses, and shared for
 * that reason: two spellings of "what distinguishes this variant" drift, and the drift lands in the
 * one place a reader is trying to match a row against a report.
 */
export function variantDiscriminator(variant) {
  if (!variant) return "variant";
  return variantLabel(variant) || "variant";
}

/**
 * Split the local catalog against the other system's components using the authored
 * `parallel` map (this-system componentId → other-system componentId).
 *
 * An entry may also carry its own `parallel`, which wins over the map: that is how a compared
 * variant is paired, since the map is keyed by component id and a variant has no entry in it.
 *
 * @param {object[]} localComponents the local manifest's `components`, or the flattened rows from
 *   {@link comparableEntries}
 * @param {Record<string,string>} parallelById componentId → parallel componentId
 * @param {object[]} otherComponents the other system's spec components ({componentId, group, caption})
 * @returns {{paired: object[], onlyLocal: object[], onlyOther: object[]}}
 *   paired: { local, parallelId, other|null } — `other` is null when the parallel
 *     id isn't (yet) catalogued in the other system.
 *   onlyLocal: local components with no `parallel` declared.
 *   onlyOther: other components no local `parallel` points at.
 */
export function crossSystemMatches(localComponents, parallelById, otherComponents) {
  const otherById = new Map((otherComponents ?? []).map((c) => [c.componentId, c]));
  const referenced = new Set();
  const paired = [];
  const onlyLocal = [];
  for (const local of localComponents ?? []) {
    const parallelId = local.parallel || parallelById?.[local.componentId];
    if (!parallelId) {
      onlyLocal.push(local);
      continue;
    }
    const other = otherById.get(parallelId) ?? null;
    if (other) referenced.add(parallelId);
    paired.push({ local, parallelId, other });
  }
  const onlyOther = (otherComponents ?? []).filter((c) => !referenced.has(c.componentId));
  return { paired, onlyLocal, onlyOther };
}

/**
 * The this-system thumbnail: baked relative PNG, links to its own live preview.
 *
 * A variant row shows the variant's own render — picked back out of the parent's folded `images[]`
 * by the tags the fold wrote — never the parent's hero. Showing the hero would put the wrong
 * picture in a row whose whole purpose is comparing that variant.
 */
function localEmbed(component) {
  const hero = component.variant
    ? variantImageOf(component, component.variant)
    : heroImageOf(component);
  const id = component.componentId ?? "(unnamed)";
  if (!hero?.path) return previewEmbed({ fallback: "no render", frame: "solid" });
  return previewEmbed({ imageUrl: hero.path, liveUrl: hero.livePreview, alt: id, title: id });
}

/**
 * The other-system thumbnail. Three cases, none of which ever "load":
 *  - the parallel isn't in the sibling spec at all → an inert "no sticker yet";
 *  - it's rendered on the sibling branch → a baked PNG on that branch's raw URL,
 *    linking to the sibling's live preview;
 *  - it's declared but not (yet) rendered on the branch → "not rendered yet" + a
 *    link to the sibling catalog.
 */
function otherEmbed(pair, opts) {
  const { parallelId, other } = pair;
  if (!other) {
    return `<span class="pv-embed"><span class="pv-frame pv-frame--solid"><span class="pv-missing">no <code>${esc(parallelId)}</code> sticker yet</span></span></span>`;
  }
  const hero = opts.otherHeroById?.get(parallelId);
  if (hero?.path) {
    const imageUrl = opts.otherBranchBase + hero.path;
    const liveUrl = hero.livePreview || livePreviewUrl(opts.previewBase, opts.otherSystem, hero.path);
    return previewEmbed({ imageUrl, liveUrl, alt: parallelId, title: parallelId });
  }
  return `<span class="pv-embed" data-parallel="${esc(slug(parallelId))}"><span class="pv-frame pv-frame--solid"><span class="pv-missing">not rendered yet</span></span><a class="pv-open" href="${esc(opts.otherIndexUrl)}" target="_blank" rel="noopener">open ${esc(opts.otherSystem)} ↗</a></span>`;
}

/**
 * The design-kit thumbnail for a pairing, or an inert "no reference" cell.
 *
 * The record is resolved by the driver (already an absolute URL, whichever branch it
 * came from) so this module stays a pure formatter. `from` names the catalog whose
 * design-map contributed it — worth showing, because a reader looking at a row where
 * only one implementation is mapped to the kit should be able to tell which.
 */
/**
 * The authored reason a row reaches no kit reference, or `""` when none was stated.
 *
 * A `noReference` is a finding, not a gap — "the kit exports no `Text=No` cell, so the honest
 * reference is the sibling's own render" — and it is the one thing the kit column can publish for a
 * row that has no picture to show.
 */
/**
 * The `designRefById` a page shows the kit column from when nothing resolved but something was
 * stated. Frozen-by-convention and never written to: `designEmbed` only reads it, and every lookup
 * misses, which is the point — every cell falls to the stated-absence branch.
 */
const EMPTY_DESIGN_REFS = new Map();

function statedAbsence(entry) {
  return typeof entry?.noReference === "string" ? entry.noReference.trim() : "";
}

function designEmbed(pair, opts) {
  const ref = opts.designRefById?.get(pair.local.componentId);
  if (!ref?.url) {
    // A `noReference` is an authored finding — "the kit exports no `Text=No` cell, so the honest
    // reference is the sibling's own render" — and printing the generic cell over it published a
    // deliberate absence and an unaudited gap as the same thing, which is the one distinction the
    // field exists to make. It reaches a variant row more often than a component one: the row a
    // variant flattens to never has a `designRefById` entry (the design map is keyed by component
    // id), so this branch is where a compared variant always lands.
    const stated = statedAbsence(pair.local);
    const missing = stated ? `no kit reference — ${esc(stated)}` : "no kit reference";
    const title = stated ? ` title="${esc(stated)}"` : "";
    return `<span class="pv-embed"><span class="pv-frame pv-frame--solid"><span class="pv-missing"${title}>${missing}</span></span></span>`;
  }
  const title = ref.uri ? `${ref.from ?? "design"} — ${ref.uri}` : (ref.from ?? "design");
  return previewEmbed({
    imageUrl: ref.url,
    liveUrl: ref.uri?.startsWith("http") ? ref.uri : undefined,
    alt: `${pair.local.componentId ?? "component"} design reference`,
    title,
  });
}

/** True when both sides carry a rendered thumbnail (the pair fully renders). */
function rendersBothSides(pair, opts) {
  const localOk = Boolean(
    (pair.local.variant ? variantImageOf(pair.local, pair.local.variant) : heroImageOf(pair.local))
      ?.path,
  );
  const otherOk = Boolean(pair.other && opts.otherHeroById?.get(pair.parallelId)?.path);
  return localOk && otherOk;
}

/**
 * One anchor per row, in row order, each unique on the page.
 *
 * `slug` is lossy on purpose — it collapses every run of non-alphanumerics to a
 * single `-` — so two component ids that differ only in punctuation land on the
 * same string: `Button/A+B` and `Button/A B` are both `button-a-b`. Deriving the
 * anchor per row therefore emitted a duplicate `id`, and the second row became
 * unaddressable: both self-links, and any bug report quoting either, resolve to
 * the first. Unaddressable is the one thing this anchor exists to prevent.
 *
 * A colliding anchor takes a `-2`, `-3`, … suffix, and the suffix keeps counting
 * past an id that genuinely slugs to the suffixed form. Stable across builds:
 * `paired` is ordered, so the same input yields the same anchors and a link
 * published today still lands tomorrow.
 */
function rowAnchors(pairs) {
  const used = new Set();
  return pairs.map((pair) => {
    const base = `c-${slug(pair.local.componentId ?? "(unnamed)")}`;
    let anchor = base;
    for (let n = 2; used.has(anchor); n++) anchor = `${base}-${n}`;
    used.add(anchor);
    return anchor;
  });
}

/**
 * One `<tr>` per pairing. Both renders are baked static thumbnails that link to
 * the live server; nothing is resolved at view time.
 *
 * The row is ANCHORED and its component id is a self-link, on the `c-<slug>` scheme
 * `renderIndexHtml` already uses so one convention covers both pages. The anchor is
 * handed in rather than derived here, because uniqueness is a property of the whole
 * table — see [rowAnchors]. This is the page a cross-system bug report wants to point at: it is
 * the only one carrying the kit reference and both renditions of a cell side by
 * side, which is the whole argument such a report makes. Without a per-row anchor
 * the best a report could do was link the page and name the row, so three
 * upstream issues in yschimke/wear-m3-catalog (#89, #90, #91) had to commit a
 * composed triptych image apiece instead.
 */
function pairRow(pair, opts, anchor) {
  const { local, parallelId, other } = pair;
  const id = local.componentId ?? "(unnamed)";
  const group = local.group ?? "Components";
  const design = opts.designRefById ? `<td class="col-d">${designEmbed(pair, opts)}</td>` : "";
  return `<tr class="crow" id="${esc(anchor)}">
  <th scope="row" class="rowhead"><a class="cid anchor" href="#${esc(anchor)}">${esc(id)}</a><span class="grp">${esc(group)}</span></th>
  ${design}<td class="col-a">${localEmbed(local)}</td>
  <td class="col-b">${otherEmbed(pair, opts)}</td>
  <td class="rel"><code>${esc(parallelId)}</code>${other ? "" : `<span class="badge" title="the parallel isn't in the ${esc(opts.otherSystem)} catalog yet">unpaired</span>`}</td>
</tr>`;
}

/** A short inventory row for the only-here / only-there sections. */
function soloRow(component) {
  const id = component.componentId ?? "(unnamed)";
  const group = component.group ?? "Components";
  const cap = component.caption ? `<span class="cap">${esc(component.caption)}</span>` : "";
  return `<li><span class="cid">${esc(id)}</span><span class="grp">${esc(group)}</span>${cap}</li>`;
}

const DEFAULT_REPO = "yschimke/compose-ai-tools";

/**
 * Build `parallelId → hero image record` from a fetched sibling `catalog.json`
 * manifest, so the other-system column can bake a static thumbnail URL. Empty map
 * when no manifest was resolved (offline / first publish) — the column then shows
 * the "not rendered yet" fallback instead.
 */
function heroIndex(manifest) {
  const out = new Map();
  for (const component of manifest?.components ?? []) {
    const hero = heroImageOf(component);
    if (hero?.path) out.set(component.componentId, hero);
  }
  return out;
}

/**
 * Render the catalog to a complete cross-system comparison page.
 * @param {object} catalog the local flattened manifest (system, title, components, …)
 * @param {object} opts
 *   {
 *     parallelById,        // componentId → sibling componentId (from the spec)
 *     otherComponents,     // sibling spec components ({componentId, group, caption})
 *     otherManifest,       // OPTIONAL fetched sibling catalog.json (for baked thumbnails)
 *     otherSystem, otherTitle?, repo?, otherRepo?, previewBase?,
 *     designRefById?,      // OPTIONAL Map<localComponentId, {url, uri?, from?}> — the kit column
 *     designTitle?,        // OPTIONAL heading for that column ("Design kit")
 *   }
 * @returns {string} a self-contained matches.html
 */
export function renderCrossSystemHtml(catalog, opts = {}) {
  // Components AND their compared variants: a variant is paired in its own right, so the page
  // walks the flattened rows rather than `components` alone. See `comparableEntries`.
  const components = comparableEntries(catalog.components ?? []);
  const meta = catalog.meta ?? catalog;
  const system = meta.system ?? "catalog";
  const title = meta.title ?? system;
  const repo = opts.repo ?? DEFAULT_REPO;
  // The sibling's delivery branch lives in ITS repo, which is only this one when the two
  // catalogs are modules of the same project. A cross-repo pairing (a Remote Compose catalog
  // comparing against the Wear kit catalog next door) is the case that made `otherRepo` exist:
  // without it every sibling thumbnail bakes a raw URL under the wrong owner, which 404s rather
  // than resolving to the wrong picture — visible, but only after a publish.
  const otherRepo = opts.otherRepo ?? repo;
  const otherSystem = opts.otherSystem ?? "other";
  const otherTitle = opts.otherTitle ?? otherSystem;
  const otherBranch = `design-artifacts/${otherSystem}`;
  const otherBranchBase = `https://raw.githubusercontent.com/${otherRepo}/${otherBranch}/`;
  const otherIndexUrl = `https://htmlpreview.github.io/?https://github.com/${otherRepo}/blob/${otherBranch}/index.html`;
  const previewBase = opts.previewBase ?? DEFAULT_PREVIEW_BASE;
  const otherHeroById = heroIndex(opts.otherManifest);
  // Three states, not two. `undefined` is "this page offers no kit column at all" — the generator
  // omits the key when the spec sets `compareWith.design: false`, and an author who turned the
  // column off must not have it handed back by a stated absence. An EMPTY MAP is the different
  // statement "the column is on, and nothing resolved", which is the absence-only case below.
  const designOffered = opts.designRefById != null;
  const kitRefs = opts.designRefById?.size ? opts.designRefById : null;
  const designTitle = opts.designTitle ?? "Design kit";

  const { paired, onlyLocal, onlyOther } = crossSystemMatches(
    components,
    opts.parallelById ?? {},
    opts.otherComponents ?? [],
  );

  // Whether the kit column has anything to say — which is NOT the same question as whether any
  // reference resolved. A catalog whose components all declare `noReference` passes an empty map,
  // and gating the column on the map alone dropped the column, and with it every authored reason:
  // the rationale showed up only when some unrelated component happened to contribute a reference.
  // A stated absence is exactly the finding this column exists to publish, so it earns the column
  // on its own.
  const statedAbsences = paired.filter((p) => statedAbsence(p.local)).length;
  const designRefById =
    kitRefs ?? (designOffered && statedAbsences ? EMPTY_DESIGN_REFS : null);

  const rowOpts = {
    otherSystem,
    otherBranchBase,
    otherIndexUrl,
    otherHeroById,
    previewBase,
    designRefById,
  };
  const anchors = rowAnchors(paired);
  const body = paired.map((p, i) => pairRow(p, rowOpts, anchors[i])).join("\n");
  const pairedReal = paired.filter((p) => rendersBothSides(p, rowOpts)).length;
  const designed = kitRefs
    ? paired.filter((p) => kitRefs.get(p.local.componentId)?.url).length
    : 0;

  const onlyLocalList = onlyLocal.length
    ? `<section class="solo"><h2>Only in ${esc(system)} <span>${onlyLocal.length}</span></h2>
       <ul>${onlyLocal.map(soloRow).join("\n")}</ul></section>`
    : "";
  const onlyOtherList = onlyOther.length
    ? `<section class="solo"><h2>Only in ${esc(otherSystem)} <span>${onlyOther.length}</span></h2>
       <ul>${onlyOther.map(soloRow).join("\n")}</ul></section>`
    : "";

  const subtitleParts = [
    // Deliberately NOT written as a kit → origin → port chain. Which of the two implementations
    // is the origin is not something this page can know (a `parallel` is a correspondence, not a
    // derivation), and the columns run this-system-first regardless — so a chain here would
    // contradict the table under it. Both are compared against the same reference; that is the
    // whole claim.
    //
    // ESCAPED, unlike the surrounding literal markup. Every other use of these three escapes, and
    // this one did not — which only became a hazard with the cross-repo form, where `otherTitle`
    // can come straight out of a FETCHED sibling `catalog.json` rather than a spec in this
    // checkout. The arrow span is ours and stays raw; the titles are data.
    kitRefs
      ? `${esc(title)} <span class="arrow">↔</span> ${esc(otherTitle)}, both against ${esc(designTitle)}`
      : `${esc(title)} <span class="arrow">↔</span> ${esc(otherTitle)}`,
    `${paired.length} paired`,
    `${pairedReal} rendered both sides`,
    // Counted off `kitRefs`, not the column: a column carried by stated absences alone would
    // otherwise announce "0 against a kit reference", which reads as a failure rather than as the
    // audited absence it is.
    ...(kitRefs ? [`${designed} against a kit reference`] : []),
    // Gated on the COLUMN, not merely on the count: this line describes what the kit column shows,
    // and on a page that has no such column it would name a finding the reader has no way to see.
    ...(designRefById && statedAbsences ? [`${statedAbsences} with a stated absence`] : []),
  ];

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(title)} ↔ ${esc(otherTitle)} — component matches</title>
<style>
  :root { color-scheme: light dark; --bg:#0f0f10; --panel:#1b1b1d; --fg:#e8e8ea; --muted:#9b9ba1; --line:#2a2a2d;
    --accent:#7dd87d; --warn:#e0c060; --link:#8ab4f8; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background:var(--bg); color:var(--fg); }
  header.top { padding:24px clamp(16px,4vw,40px); border-bottom:1px solid var(--line); }
  header.top h1 { margin:0 0 6px; font-size:22px; }
  header.top .subtitle { color:var(--muted); font-size:13px; display:flex; gap:16px; flex-wrap:wrap; }
  header.top .arrow { color:var(--accent); }
  header.top .note { margin-top:10px; color:var(--muted); font-size:12px; max-width:78ch; }
  header.top code { background:var(--panel); padding:1px 6px; border-radius:5px; }
  main { padding:8px clamp(16px,4vw,40px) 64px; }
  table { border-collapse:collapse; width:100%; }
  /* line-height is pinned, not inherited: the row offset below is arithmetic on these
     exact numbers, and a heading whose height nobody can compute is a header rows hide
     under. */
  thead th { position:sticky; top:0; background:var(--bg); text-align:left; font-size:12px; color:var(--muted);
    line-height:1.35; padding:10px 12px; border-bottom:1px solid var(--line); z-index:1; }
  tbody tr.crow { border-bottom:1px solid var(--line); }
  th.rowhead { text-align:left; font-weight:600; padding:12px; vertical-align:middle; width:22%; }
  th.rowhead .cid { display:block; word-break:break-word; }
  /* scroll-margin-top because thead th is sticky: without it a row jumped to from
     its #c-slug anchor lands underneath the header and reads as the wrong row.

     Derived, not guessed. It used to be a flat 44px — the height of a ONE-LINE
     heading — and a heading wraps: the design-reference column's "M3 Wear OS Apps
     Design Kit" does it at a narrow viewport, and the row then lands back under the
     header, which is the failure this margin exists to prevent. So the reserve is
     three heading lines, spelled as the arithmetic that produces it:
     3 lines x 1.35 x 12px, + 10px padding top and bottom, + the 1px border.

     Overshooting is free and undershooting is not — too much margin leaves a little
     empty space above the row, too little hides the row — so this reserves more than
     any current heading needs, on purpose. The page carries no script -- the
     "fully static" test pins that -- so measuring the header at view time is not on
     the table; keep these numbers in step with thead th above if either moves. */
  tr.crow { scroll-margin-top:calc(3 * 1.35 * 12px + 21px); }
  a.anchor { color:inherit; text-decoration:none; }
  a.anchor:hover, a.anchor:focus-visible { text-decoration:underline; }
  a.anchor::after { content:" #"; color:var(--muted); opacity:0; }
  tr.crow:hover a.anchor::after, a.anchor:focus-visible::after { opacity:1; }
  tr.crow:target { outline:2px solid var(--accent); outline-offset:-2px; }
  th.rowhead .grp { display:block; margin-top:3px; font-weight:400; font-size:11px; color:var(--muted); }
  td { padding:10px 12px; vertical-align:middle; }
${previewEmbedStyles({ accent: "var(--link)", muted: "var(--muted)" })}
  .pv-missing code { background:var(--panel); padding:0 4px; border-radius:4px; font-style:normal; }
  a.pv-open { font-size:11px; color:var(--link); text-decoration:none; }
  a.pv-open:hover { text-decoration:underline; }
  .rel code { font-size:12px; }
  .badge { display:inline-block; margin-left:6px; font-size:10px; padding:0 6px; border-radius:999px;
    background:rgba(224,192,96,0.12); color:var(--warn); border:1px solid var(--warn); }
  section.solo { margin-top:36px; }
  section.solo h2 { font-size:15px; border-bottom:1px solid var(--line); padding-bottom:6px; }
  section.solo h2 span { color:var(--muted); font-weight:400; }
  section.solo ul { list-style:none; padding:0; display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:6px 20px; }
  section.solo li { padding:6px 0; border-bottom:1px solid var(--line); }
  section.solo .cid { font-weight:600; }
  section.solo .grp { color:var(--muted); font-size:11px; margin-left:8px; }
  section.solo .cap { display:block; color:var(--muted); font-size:12px; }
  @media (max-width:640px){ th.rowhead { width:auto; } }
</style>
</head>
<body>
<header class="top">
  <h1>${esc(title)} ↔ ${esc(otherTitle)}</h1>
  <div class="subtitle">${subtitleParts.map((s) => `<span>${s}</span>`).join("")}</div>
  <p class="note">Each row pairs a <strong>${esc(system)}</strong> component with its declared
  <strong>${esc(otherSystem)}</strong> parallel (the <code>parallel</code> field in the catalog spec). Both
  renders are static thumbnails — the left from this branch, the right baked from the
  <code>${esc(otherBranch)}</code> branch — and each <strong>links to the live preview server</strong> on
  click, where the component re-renders under other themes / locales / devices. A parallel that isn't
  catalogued in ${esc(otherSystem)} yet is flagged <span class="badge">unpaired</span>. Components with
  no parallel on either side are listed below the table.${
    kitRefs
      ? ` The leading <strong>${esc(designTitle)}</strong> column is the published design reference BOTH
  implementations are reproducing, which is what lets a divergence be attributed to one of them rather
  than merely observed between them. It is contributed by whichever catalog carries the
  <code>figma:</code> mapping; rows where neither does read "no kit reference".`
      : designRefById
        ? ` The leading <strong>${esc(designTitle)}</strong> column carries no reference on this page:
  nothing in either catalog resolved to a <code>figma:</code> mapping. It is here for the rows whose
  absence was <em>stated</em> — a component that says WHY the kit has nothing to point at is an
  audited finding, not a gap, and that reason is the only thing this column has to publish here.`
        : ""
  }</p>
</header>
<main>
  <table>
    <thead>
      <tr>
        <th scope="col">Component</th>
        ${designRefById ? `<th scope="col">${esc(designTitle)}</th>` : ""}<th scope="col">${esc(title)}</th>
        <th scope="col">${esc(otherTitle)}</th>
        <th scope="col">Parallel</th>
      </tr>
    </thead>
    <tbody id="rows">${body}</tbody>
  </table>
  ${onlyLocalList}
  ${onlyOtherList}
</main>
</body>
</html>
`;
}
