# Migrating a preview's knobs to the parameter format

Two override formats exist side by side. This is the record of what happened when the repository's
own samples were tested against the newer one — which of them can move, which cannot, and what has
to land before "cannot" becomes "can". It is written so the question doesn't have to be
re-investigated from the source the next time it comes up.

The two formats, in one line each:

| | `previewOverride*` (named overrides) | Parameter knobs |
| --- | --- | --- |
| Where the knob is declared | by **executing a lookup in the composable body** | by the **function signature** |
| How a value is seeded | writing typed values into a process-static controller before composing | ordinary argument passing |
| What the preview's body contains | a harness call per knob | nothing — no harness dependency at all |
| Where the declaration is published | `previews/<id>.overrides.json`, `data/fetch?kind=compose/overrides` | `data/fetch?kind=compose/overrides` (daemon lanes); not the bundle sidecar yet |
| Where the default value comes from | the author passes it at the call site | read back out of the compiled body (`PreviewKnobDefaults`) |

The reference pair to read side by side is
[`samples/cmp/.../OverridablePreviews.kt`](../../samples/cmp/src/main/kotlin/com/example/samplecmp/OverridablePreviews.kt)
and
[`ParameterKnobPreviews.kt`](../../samples/cmp/src/main/kotlin/com/example/samplecmp/ParameterKnobPreviews.kt):
the same list, the same pixels ([render evidence](evidence/parameter-knobs/README.md)), the knobs
declared each way. That pair is deliberate and must **not** be collapsed by a migration.

## The verdict

**A preview whose knobs all carry *literal* defaults can now move without losing its editor.** Both
daemons publish a parameter knob as a `PreviewOverrideDeclaration`, so a viewer draws the same
control it draws for a `previewOverride*` knob. What still stops the samples in this repository is
narrower than it was, and specific: most of their defaults are **expressions**
(`stringResource(...)`, `Color(0xFF3366FF)`), which cannot be recovered and so cannot be declared —
plus the structural limits below, which no amount of wiring changes.

Everything below is what the investigation found on the way to that.

## What discovery already does

`ComposableSignature.knobsOf` reports a knob per value parameter, and only when:

* **every** value parameter declares a default — one non-defaulted parameter and the preview reports
  no knobs at all, so a preview cannot acquire one by accident;
* the parameter is **non-nullable** — null is the "use the default" channel on the wire, so it
  cannot also mean "set this to null"; and
* its type is one the harness can build from a seed string: `String`, `Boolean`, `Int`, `Long`,
  `Float`, `Double`, matched on the metadata classifier's FQN so a project's own `Boolean` can't
  masquerade as `kotlin/Boolean`.

`PreviewKnob.index` is the parameter's position in the **full** value-parameter list, not among the
knobs, because that is the position an argument has to be placed at. A defaulted-but-not-seedable
parameter (`modifier: Modifier = Modifier`) keeps its slot and takes its default.

That much has worked since the format landed: `previews.json` carries the knobs today.

## What testing the migration found, and fixed

Two defects, neither visible from reading the source alone. Both are fixed in the change this
document accompanies; both are the reason a migrated preview would have looked broken rather than
merely un-editable.

**Neither daemon could resolve a parameter-knob preview at all.** `getDeclaredComposableMethod(name)`
matches only `(Composer, int)` — a preview with no value parameters. One whose parameters all
declare defaults compiles to `(realParams…, Composer, changed…, default…)`, so the lookup threw
`NoSuchMethodException` before composition started: no PNG, just an `.error.json`. Both standalone
bake lanes had a fallback for exactly this and neither daemon did, which is why
`ParameterKnobPreviews.kt` bakes correctly ([render evidence](evidence/parameter-knobs/README.md))
and would not have rendered live. The fallback now lives in each renderer's
`PreviewParameterSupport`, which its daemon already calls, instead of privately in the bake path.

On Android there was a third thing missing behind those two. Composition happens inside a
Robolectric **sandbox classloader** that never sees the host-side spec — it parses the payload string
the host reshapes — so the knobs need an encoded `knobs=` token to cross at all. `PreviewKnobToken`
is that codec, and it is the one place producer and consumer agree.

**Nothing bound a seed to a parameter.** `PreviewKnobArguments` — the binder, with its own tests —
had no caller outside those tests: no producer of the `knobs=` payload token, no consumer in either
daemon, and the daemon's `RenderSpec` had no field to carry the knobs `previews.json` already listed.
A `renderNow.overrides.namedOverrides` seed naming a parameter knob was read by the
`previewOverride*` controller, matched nothing there, and was dropped in silence.

## The gaps that remain

### <a id="gap-1"></a>1. A knob defaulted to an expression cannot be declared

`previewOverride*` publishes each knob as a `PreviewOverrideDeclaration` — key, type, label, default
value, current value — as a by-product of *reading* it, because the lookup knows all five. That fills
`previews/<id>.overrides.json` and `data/fetch?kind=compose/overrides`, and it is what a viewer's
control list, the serve `?knob.<key>=` UI and `serve-lanes.spec.mjs` read.

A parameter knob is declared by a signature and read by ordinary argument passing, so nothing in the
composition announces it. Both daemons now build the same declarations from `previews.json` and
record them through the same controller channel, so every consumer downstream works unchanged and
neither has to know which format a preview used. `PreviewKnobDefaults` supplies the default value the
declaration needs by reading it back out of the compiled body.

**What is left is the honest hole in that.** `PreviewOverrideDeclaration.default` is not nullable, so
a knob whose default this could not recover is left *undeclared* rather than declared with an
invented value — a viewer would otherwise show a wrong default and offer a "reset" that resets to
something the preview never said. That bites exactly the defaults the catalogs use:

| Default | Declared? |
| --- | --- |
| `title: String = "Shopping list"` | yes |
| `enabled: Boolean = true`, `count: Int = 3` | yes |
| `label: String = stringResource(Res.string.label)` | **no** |
| `accent: Color = Color(0xFF3366FF)` | **no** (and `Color` is not a knob kind anyway — gap 2) |
| `computed: Int = itemCount + 1` | **no** |

Closing it properly means a declaration that can say "this knob's default is not knowable", which is
a change to the published `data-preview-overrides-core` contract rather than to this repository.

`Long` and `Double` knobs are declared as **text**: `PreviewOverrideType` has `STRING` / `INT` /
`FLOAT` / `BOOL` / `COLOR` and no wider numerics. That costs the control's shape, not its reach — a
text seed parses against the knob's own kind on the way in — but it is why an ARGB `Long`, the
format's stand-in for an editable colour, gets a text box rather than a colour picker.

### 2. `Color` and `Dp` are not seedable kinds

An editable colour is the single most common knob in these samples
(`previewOverrideColor("accent", …)`, `catalogOverrideColor("iconColor", …)`). The parameter format
has no `Color` kind, so the workaround is an ARGB `Long` — which is what `ParameterKnobPreviews.kt`
does, and it costs the viewer its colour picker. `Dp` is the same story with `Int` dp.

A colour seed is deliberately *dropped* rather than coerced on its way to a parameter knob (see
`PreviewKnobSeeds`): `#FF42A5F5` is not a number, and reporting it as a malformed one would hide
that the kind is simply unsupported.

### 3. There is no indexed knob

`previewOverrideString("rowLabel", default = "Item ${i + 1}", index = i)` gives every row of a
repeated component its own editable value. A parameter list is fixed-arity and a per-row value is
not, so there is no equivalent. Three samples rely on it:
`samples/cmp/.../OverridablePreviews.kt`, `design-catalog-m3/.../CatalogTemplates.kt` (the message
list's `sender` / `preview`), and `design-catalog-wear-m3/.../CatalogPreviews.kt` (the pager's
`page`).

### 4. There is no closed value set

`previewOverrideChoice` declares the values a knob may take, so a viewer draws a picker over the
declared labels instead of a text field a reader has to already know spells `compact` / `cosy` /
`comfortable`. A `String` parameter carries no such set. The wear catalog's font knob is the same
shape with autocomplete over the declared typefaces.

### 5. A knob declared outside the `@Preview` function has no equivalent at all

This is the structural one, and it rules out the whole design-catalog family rather than any single
knob. `CatalogComponent(id: String)` is *one* dispatch composable holding every component's knobs,
called by dozens of previews; `CatalogSticker { … }` is a theme wrapper reading the font / palette /
shapes / typography knobs on behalf of every sticker. Both are the reason those previews stay clean
one-liners.

A parameter knob only exists on the preview's own signature, so moving either would mean copying
every knob of every branch onto every preview that could reach it — and `CatalogComponent(id:
String)` reports no knobs anyway, since `id` has no default.

### 6. The offline bake lanes, and the harness router

Both **daemons** — `:daemon:desktop` (live / `serve` / VS Code) and `:daemon:android` (Robolectric)
— resolve a defaulted preview, seed its parameter knobs and declare them.

The **offline bake** does neither. `:renderer-desktop` can bind a seed (`PreviewKnobArguments`) but
nothing hands it one, `:renderer-android` has no bind at all, and neither drains parameter knobs into
the `previews/<id>.overrides.json` sidecar the way it drains `previewOverride*` ones. So the twin
comparison that opened this document still comes out uneven for a *baked* bundle, and even for it the
only thing missing is threading the manifest's knobs into the render shard.

The harness-only `PreviewManifestRouter` (`composeai.harness.previewsManifest`) is the third: its
manifest wire type carries no `knobs`, so a knobbed preview rendered through a harness fixture
renders its defaults. No fixture declares one today, which is why it is a note rather than a fix.

## The samples, one by one

| Sample | Knobs | Verdict |
| --- | --- | --- |
| `samples/cmp/.../OverridablePreviews.kt` | 4 (`title`, `accent`, `itemCount`, `density`, indexed `rowLabel`) | **Keep as is** — it is the deliberate twin of `ParameterKnobPreviews.kt`; the comparison is what the sample is for |
| `samples/cmp/.../ParameterKnobPreviews.kt` | 5 parameter knobs | Already migrated — the reference |
| `samples/android-live-lane/.../LiveLanePreviews.kt` | 1 (`label`, on the `@Preview` itself) | **Migratable.** Its default is the literal `"Live lane"`, so the knob declares, and `serve-lanes.spec.mjs`'s `overrides[].key == "label"` still finds it. Not done here — worth doing as its own change, with the lane run to prove it |
| `design-catalog-m3-shared/.../CatalogComponents.kt` + `CatalogOverrides.kt` (+ actuals) | ~15 across a `when (id)` dispatch | **Cannot** — gap 5, plus `catalogOverrideColor` (gap 2) |
| `design-catalog-m3/.../CatalogTheme.kt` | 5 (font, colors, shapes, typography, fonts) | **Cannot** — gap 5: read inside the theme wrapper every sticker calls |
| `design-catalog-m3/.../CatalogTemplates.kt` | `title`, `fab` hoistable; `sender`, `preview` indexed | **Partial at best** — gap 3 |
| `design-catalog-wear-m3/.../CatalogPreviews.kt` | ~24, most directly on a `@Preview` | **Most migratable, blocked** by gaps 1 and 3 (`index = page`) and the font knob's autocomplete (gap 4) |
| `design-catalog-wear-m3/.../CatalogTheme.kt` | 4 | **Cannot** — gap 5 |
| `design-catalog-wear-m3/.../CatalogInteractive.kt` | 1, seeding a state holder | Follows whatever its preview does |

## When to reach for which

Once gap 1 closes, the split is a real design choice rather than a capability gap:

* **Parameter knobs** when the preview is a self-contained composable whose editable values are its
  own arguments, and you want the body to carry no harness dependency — the shape a preview already
  has when it is also called by real code.
* **`previewOverride*`** when the knob is a colour or a dimension, when each item of a repeated
  component needs its own value, when the value set is closed and a picker is the right control, or
  when the knob is declared once in a wrapper or dispatch helper on behalf of many previews.

Neither replaces the other, which is why both are supported.
