# Kit variant cells: covering every Figma slot without minting a component for it

> **Status: proposal.** Investigation and a delivery order for the "sea of red" on a design page
> ([`/wear-m3-catalog/pages/buttons`](https://wear.preview.coo.ee/pages/buttons)). No code yet. The
> numbers below are measured from the committed `wear-m3-catalog` import
> (`design/pages/pages.json`, `design-map.json`, `design-map-variants.json`) at the time of writing.

A design page draws the kit's own specimen sheet and outlines every component on it: green for a
Code Connect link, blue for a `design-map.json` one, orange for a name match, red-dashed for
nothing. The Buttons sheet comes back **75 of 303**, and the red is not evenly spread — it is
almost entirely *cells of sets we already implement*. `Toggle+Selection-Buttons` is the sheet in
miniature: 32 cells, 12 blue, 20 red, and every one of the red ones is a `SwitchRow` /
`CheckboxRow` / `RadioRow` we already have, with a second knob turned.

So the question this document settles is not "which components are missing" — it is **what a
kit cell is, when the catalog does not want a component for it**.

---

## 1. What the red actually is

### 1.1 The denominator is wrong first

Three kinds of node are counted as unimplemented components today that
[`kit-sets.json`](https://github.com/yschimke/wear-m3-catalog/blob/main/kit-sets.json) already
states are out of scope:

| Counted as a gap | How many | Why it is not one |
| --- | --- | --- |
| The `Icons` page | 499 | An icon set, not a component inventory. `kit-sets.json` excludes it by name. |
| `Base / …` sets (`Base / SelectionControl / Switch`, `Base / Loading Icon`, …) | 24, all on Buttons | The kit's own internals — the parts each published set is assembled from. `kit-sets.json` excludes them alongside the `.`-prefixed privates that `PageNode.isPrivate` already drops. |

Correcting for both moves the whole-kit figure from a demoralising **185 / 1554 (12%)** to
**185 / 1031 (18%)**, and the Buttons sheet from 75 / 303 to **75 / 279**. That is not the
interesting change — the interesting change is that the remaining red is now *all* real.

**Fix:** teach `design-pages.mjs` the same two exclusions `kit-sets.json` states, so one repo does
not hold two disagreeing answers to "is this a component we owe an implementation". Cheap, and it
should land before anything else here, because every percentage this document quotes is otherwise
measured against furniture.

### 1.2 The real gap splits three ways

Classifying every unlinked cell by whether each of its axis values appears on *some* linked cell of
the same set:

| Class | Whole kit | Buttons sheet | What it needs |
| --- | --- | --- | --- |
| **Combination** — every axis value is one we already render, just never together | 147 | 55 | A cell that seeds more than one knob, and a resolver that can place it |
| **Unmodelled** — at least one axis value nothing renders | 556 | 173 | A knob on the composable first, then a cell |
| **Miscounted** (§1.1) | 523 | 30 | Nothing |

And within the *declared* cells — the 209 `@OverrideVariant` renders the wear catalog already
publishes — **72 do not resolve to a kit node at all**. 59 of those declare no kit axis (breakpoint
cells: `204dp`, `216dp`, …, which are a screen-size fan-out and not a kit property). The other 13
are the interesting failure, and 5 of them are the same one:

```
FilledButton / TonalButton / OutlineButton / FilledVariantButton / ChildLabelButton
  @OverrideVariant(name = "icon", booleans = ["icon=true"], kitAxis = "Icon", kitValue = "Yes")
```

That declaration is correct and still resolves to nothing, because in the kit `Icon` is **coupled**:
the base cell is `Icon=No, Icon size=n/a, Alignment=Center`, and there is no node at
`Icon=Yes, Icon size=n/a, Alignment=Center`. Turning one axis off the base lands between cells.
`Icon=Yes` drags `Icon size=26 (Default)` and `Alignment=Left` with it.

Three different problems, then, and only the second is about writing more Kotlin:

1. **Cells that seed several knobs cannot say what they are.** `OverrideVariant.kitAxis` /
   `kitValue` are singular, and the annotation's own doc says so: *"It applies to a cell seeding
   exactly ONE knob — with several there is nothing to say which of them the axis names."*
2. **Some slots have no knob at all** — a secondary label, a swapped toggle control, an icon size,
   a content alignment.
3. **Resolution is a one-axis delta from the base node**, which coupled axes defeat.

---

## 2. Cells, not components

The mechanism the catalog needs already exists and is called an override variant: an extra baked
capture of the *same* `@Preview` function with named knobs seeded, published as
`<id>_VARIANT_<name>.png`. `@PreviewAxis` goes further and emits the **cross product** of declared
axes with each cell carrying its full typed assignment as `props: {size: "xs", shape: "square"}` —
which is exactly the shape a Figma component set states its variants in. m3-catalog uses it;
wear-m3-catalog does not, and its 209 cells are all hand-written single-axis `@OverrideVariant`s.

So "synthetic components that only exist via overrides" is the right instinct with one correction
worth writing into the rules, because getting it wrong is how a catalog turns into a wall of 1554
stickers:

> **A kit cell is a render, never a `@CatalogComponent`.** A component is a call-site choice — a
> distinct Compose function, or a distinct `*Defaults` colour set standing in for one. Everything
> that is an *argument* to whichever function you picked is a cell. Never mint a component to give
> a kit node something to map to.

Three changes deliver it:

**2a. A cell may declare several kit properties.** Add a plural form to `@OverrideVariant`
alongside the singular pair it keeps:

```kotlin
@OverrideVariant(
  name = "unselected-split",
  booleans = ["checked=false", "split=true"],
  kitProps = ["Selected=No", "Split (2 tap targets)=Yes"],
)
```

`kitProps` is authoritative in the same way `kitAxis` already is: a declaration stops the resolver
guessing for that cell, so a misspelt property resolves to nothing rather than silently matching
something else. `kitAxis` + `kitValue` become sugar for a one-entry `kitProps`, and cannot be
combined with it in one annotation — two spellings of one fact, with no rule for which wins, is the
mistake `variant` / `overrides` was split to avoid in the parity locator
([COMPONENT_PARITY_WORKFLOW.md](COMPONENT_PARITY_WORKFLOW.md#2-the-locator-contract)).

**2b. `@PreviewAxis` cells carry their kit spelling.** An axis cell already publishes its full
assignment; what it cannot say is that its `checked` knob is the kit's `Selected` and that `false`
is the kit's `No`. Add `kitAxis` to `@PreviewAxis` and `kitValues` positional against `values`
(same shape and same wrong-length-is-a-warning rule as the existing `slugs`), and a declared matrix
resolves cell-for-cell with nothing hand-typed:

```kotlin
@PreviewAxis(key = "checked", values = ["true", "false"], kind = BOOLEAN, slugs = ["on", "off"],
             kitAxis = "Selected", kitValues = ["Yes", "No"])
@PreviewAxis(key = "split", values = ["false", "true"], kind = BOOLEAN, slugs = ["", "split"],
             kitAxis = "Split (2 tap targets)", kitValues = ["No", "Yes"])
@PreviewAxis(key = "enabled", values = ["true", "false"], kind = BOOLEAN, slugs = ["", "disabled"],
             kitAxis = "Disabled", kitValues = ["No", "Yes"])
```

Seven cells per component, three components, and `Toggle+Selection-Buttons` closes at 24 of its 32
— the remaining 8 being `Type=Custom - Task`, which is §3.

**2c. Resolution matches a full assignment, not a delta.** Both of the above hand the resolver a
complete property map; it should look the cell up in the set's own variant table by that map rather
than by walking one axis off the base node. That fixes the coupled-axis failure as a side effect —
`{Style: Filled, Icon: Yes, Icon size: 26 (Default), Alignment: Left, Disabled: No}` is a node, and
nothing has to know that three of those move together.

**This part is not in this repository.** `scripts/design-map.sh` in each catalog shells out to
`@yschimke/compose-design-map` (annotations → map + variant sidecar) and `@design-parity/kit-index`
(sidecar + kit index → resolved ref/previewId pairs), both pinned by exact version. 2a and 2b are
annotation changes here plus a `compose-design-map` release that carries the new fields into the
sidecar; 2c is a `kit-index` release. The sidecar schema
(`compose-preview-design-map-variants/v1`) grows a `kitProps` array per render and the resolver
prefers it over `seeds[].kitAxis` — additive, so a catalog on the old resolver keeps resolving
exactly what it resolves today.

---

## 3. Slots with no knob, and the right to disagree with the kit

The 556 unmodelled cells need a knob before they need a cell, and the top of that list says what
kind of work it is:

| Slot | Cells it unlocks | What it is in Compose |
| --- | --- | --- |
| `Alignment` / `Icon` / `Icon size` on `Button` | 40 | `icon` slot + `ButtonDefaults` icon size + content alignment |
| `Type = Custom - Task` on `Toggle+Selection-Buttons` | 8 | the `toggleControl` slot |
| Secondary label | across `Button`, `Toggle+Selection-Buttons`, `Card` | `secondaryLabel` parameter |
| `Segments` / `Type = Top Gap \| Bottom Gap` on the progress indicators | 264 | real API surface, its own investigation |

Adding a `secondaryLabel` knob raises the question the sheet makes obvious: **the kit draws two
lines and we draw one, and one line is the better default.** That is not a conflict, because a
cell and a default are different renders of the same composable:

```kotlin
@CatalogComponent(id = "Button/Filled", …)
@OverrideVariant(name = "secondary", strings = ["secondaryLabel=Secondary"],
                 kitProps = ["Label 2=Yes"])
@Composable
fun FilledButton() = Sticker {
  Button(
    label = { Text(kitCopy("label", KitCopy.PRIMARY_LABEL)) },
    secondaryLabel = previewOverrideString("secondaryLabel", "")
      .takeIf(String::isNotEmpty)?.let { { Text(it) } },
    …
  )
}
```

The base capture is unchanged — one line, the catalog's own recommendation, still what the browse
grid and the README show. The kit's two-line cell is compared against the cell that seeds the
secondary label. **Comparison happens against the cell that matches the kit; the default stays what
we would tell someone to write.** Writing that down matters because the alternative — changing the
default to make the diff go green — is the failure mode this whole surface exists to catch, and it
is one edit away at all times.

Where the catalog deliberately draws something the kit does not, `noReference` /
`--allow-stated-absence` already carries the reason. The inverse — the kit draws a cell we
deliberately will not — wants the same treatment rather than a permanent red outline: a stated,
reasoned absence, reported apart from a gap. (`Custom - Task` already has such a reason written in
`SelectionButtons.kt`; it just has nowhere structured to live.)

---

## 4. A cell is not a component, and should not be coloured like one

The legend has four entries — Code Connect (green), design-map (blue), name match (orange), not
implemented (red-dashed) — and they answer *how* a node was linked. A cell reached by seeding knobs
is linked by `design-map` like any other, so a fully-covered sheet would come back solid blue and
lose the distinction that matters most when reading it: **which of these did we build, and which
did we merely reach.**

Add a fifth swatch. `PageNode` grows one additive boolean (`cell: true`, safe under
`DesignPagesJson`'s `ignoreUnknownKeys`), set by the producer when the resolved `previewId` is an
override capture rather than a base preview; `ServeWeb`'s legend and the `data-link` stylesheet gain
a matching value. Reading the sheet then answers three questions instead of two — implemented,
reached by an override, not implemented — and the "Only what we don't implement" filter can grow a
third position for "only cells".

`PageNodeLink` itself should **not** grow a `CELL` member: link method and cell-ness are orthogonal
(a cell can be reached by Code Connect too), and folding them loses the method.

---

## 5. We already have the sibling components — use them

45% of the wear catalog's top-level components (22 of 49) share a kit set with a sibling:

| Kit set | Catalog components |
| --- | --- |
| `Button` | `FilledButton`, `FilledVariantButton`, `TonalButton`, `OutlineButton`, `ChildLabelButton` |
| `Icon-Button` | `FilledIconAction`, `FilledVariantIconAction`, `TonalIconAction`, `OutlinedIconAction`, `StandardIconAction` |
| `Card` | `ApplicationCard`, `TitledCard`, `OutlineCard`, `PlainCard` |
| `Picker` | `DateWheels`, `TimeWheels`, `SingleColumnPicker` |
| `Toggle+Selection-Buttons` | `SwitchRow`, `CheckboxRow`, `RadioRow` |
| `Page-Indicator` | `HorizontalPages`, `VerticalPages` |

These *are* the answer for the axis they cover, and the map already routes each sibling to its own
cell of the shared set — `Type=Switch` to `SwitchRow`, `Type=Checkbox` to `CheckboxRow`. Every one
of them is a genuine call-site choice under §2's rule (a different Wear Compose function, or
`filledVariantButtonColors` on the same one), so **none should be demoted to a cell, and no new
component should be minted for an axis a sibling already covers.** The only work these need is
crossing the *remaining* axes within each sibling — which is §2b, and is what the 147 combination
cells are.

The rule that falls out, worth stating because it is what stops the two mechanisms from racing:
**one axis of a kit set is covered by siblings *or* by cells, never by both.** `Style` and `Type`
are sibling axes in this catalog; `Selected`, `Split`, `Disabled`, `Size` are cell axes.

---

## 6. Where the comparison happens: bake the pixels, score in the browser

The component surfaces today are the grid card, the viewer `/{system}/p/{id}`, and the focused
comparison `/{system}/compare/{previewId}?reference=<id>` — one render against one reference. A set
with 32 cells wants a table: our cell, the kit's node, the score, per row.

**Score live; do not render live.** Both halves of that are already true and worth not undoing:

- Scoring is *already* client-side. `format-compare.js`'s `scorePlanes` runs in the visitor's
  browser, on planes capped at 192 px, and the design page's existing **Diff %** lane already scores
  every node on the sheet that way. A 32-row table is more of what the page does now, not a new
  capability, and it needs no server work at all.
- Rendering live is not available where this is read. A `?knob.<key>=…` override re-renders only
  through a carried daemon (`ServeCatalogLiveHost`); a published catalog host has baked PNGs and
  nothing to re-render with. A table whose rows only fill in when a daemon is attached is a table
  that is empty on the URL people actually share.

So the cells must be **prebaked**, which is what §2 produces anyway: a declared cell is a rendered
`_VARIANT_` capture on the delivery branch, addressable by preview id, and the variant table is a
join over data the page already has — the set's nodes from `pages.json`, the cells from the catalog,
paired by the resolved map. The live lane stays what it is for: exploring a knob nobody declared.

The cost is real and should be sized before it is spent: closing the 147 combination cells is ~147
extra captures per theme mode, and cells fold into the catalog as secondary stickers under their
primary, so a sheet-completing matrix visibly changes the browse experience too. Adopt it per set
rather than catalog-wide, largest-gap-first, and keep `@PreviewAxis`'s existing `MAX_CELLS_WARN`
honest.

---

## 7. Delivery order

Each step is a PR against an existing surface; each is useful on its own.

| # | Change | Where |
| --- | --- | --- |
| 1 | Exclude the icon page and `Base / …` sets from the page denominator (§1.1) | `scripts/design-artifacts/design-pages.mjs` |
| 2 | `cell` flag on `PageNode`, fifth legend swatch, third filter position (§4) | `api/preview-data-api`, `design-pages.mjs`, `ServeWeb` |
| 3 | `kitProps` on `@OverrideVariant`; `kitAxis` / `kitValues` on `@PreviewAxis` (§2a, §2b) | `api/preview-annotations`, discovery, sidecar emit |
| 4 | Sidecar carries them; resolver matches a full assignment (§2c) | `@yschimke/compose-design-map`, `@design-parity/kit-index` |
| 5 | Adopt `@PreviewAxis` on `Toggle+Selection-Buttons`' three siblings — 12 → 24 of 32 (§2b, §5) | `wear-m3-catalog` |
| 6 | Variant table on the comparison page, scored client-side (§6) | `ServeWeb`, `format-compare.js` |
| 7 | `secondaryLabel` and `toggleControl` knobs, cells declared against them (§3) | `wear-m3-catalog` |
| 8 | Stated, reasoned refusal of a kit cell, reported apart from a gap (§3) | map schema + page view |

Steps 1, 2 and 5 are independent of the upstream packages and can land first; 5 shows the
mechanism working end to end on one set before the annotation work generalises it.

## 8. What this does not settle

- **The progress indicators** are 264 of the 556 unmodelled cells on their own — `Segments`,
  `Top Gap` / `Bottom Gap`, `Overflow` — and are an API investigation, not a cell-mechanism one.
- **Breakpoint cells** (`204dp`, `216dp`, …) are 59 of the 72 unresolved declared cells. They are a
  screen-size fan-out, not a kit property, and the kit draws its screen cells at 192dp. They should
  probably be excluded from kit resolution explicitly rather than left failing quietly.
- **Whether a fully-covered sheet is worth browsing.** At 273 cells the Buttons sheet becomes a very
  large grid of nearly-identical renders. The sheet view is the right place for that; the catalog
  grid may not be, and §6's per-set adoption is a way to find out before committing.
