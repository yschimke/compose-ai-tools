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
| Where the declaration is published | `previews/<id>.overrides.json`, `data/fetch?kind=compose/overrides` | *nowhere yet* — see [gap 1](#gap-1) |

The reference pair to read side by side is
[`samples/cmp/.../OverridablePreviews.kt`](../../samples/cmp/src/main/kotlin/com/example/samplecmp/OverridablePreviews.kt)
and
[`ParameterKnobPreviews.kt`](../../samples/cmp/src/main/kotlin/com/example/samplecmp/ParameterKnobPreviews.kt):
the same list, the same pixels ([render evidence](evidence/parameter-knobs/README.md)), the knobs
declared each way. That pair is deliberate and must **not** be collapsed by a migration.

## The verdict

**No sample should move today.** Not because the format is wrong, but because a migrated preview
loses its editor: nothing publishes a parameter knob's declaration, so a viewer has no knob to show
and no default to show in it. That is [gap 1](#gap-1), and it is the one that decides the answer.

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

**The daemon could not resolve a parameter-knob preview at all.** `getDeclaredComposableMethod(name)`
matches only `(Composer, int)` — a preview with no value parameters. One whose parameters all
declare defaults compiles to `(realParams…, Composer, int changed, int default)`, so the lookup
threw `NoSuchMethodException` before composition started: no PNG, just an `.error.json`. The
standalone `:renderer-desktop` bake lane had a fallback for exactly this and the daemon did not,
which is why `ParameterKnobPreviews.kt` bakes correctly ([render
evidence](evidence/parameter-knobs/README.md)) and would not have rendered live. The fallback now
lives in `PreviewParameterSupport`, which both lanes call, instead of privately in one of them.

**Nothing bound a seed to a parameter.** `PreviewKnobArguments` — the binder, with its own tests —
had no caller outside those tests: no producer of the `knobs=` payload token, no consumer in either
daemon, and the daemon's `RenderSpec` had no field to carry the knobs `previews.json` already listed.
A `renderNow.overrides.namedOverrides` seed naming a parameter knob was read by the
`previewOverride*` controller, matched nothing there, and was dropped in silence.

## The gaps that remain

### <a id="gap-1"></a>1. A parameter knob is never *declared* to a client

`previewOverride*` publishes each knob as a `PreviewOverrideDeclaration` — key, type, label, default
value, current value — through the controller, into `previews/<id>.overrides.json` and
`data/fetch?kind=compose/overrides`. That is what a viewer renders its controls from and what
`preview-harness/serve-lanes.spec.mjs` selects on.

A parameter knob publishes nothing. Rendering the two `samples/cmp` twins side by side shows it
exactly:

```
$ ls samples/cmp/build/compose-previews/renders/*.overrides.json
…/OverridableListPreview_Overridable_List-c264a81f.overrides.json
# and no ParameterKnobListPreview sidecar
```

The blocker underneath is that **discovery records that a default exists, not what it is**. A
declaration needs the default *value*, and the Compose compiler leaves default expressions inside
the function body guarded by the synthetic `$default` mask rather than in a readable constant pool
entry — recovering a literal one means walking the method's bytecode, and a non-literal one
(`stringResource(...)`, which most of this repository's samples use) cannot be recovered at all.

So closing this gap means: read constant defaults from the class file where they are literals, and
publish a declaration with **no** default where they are not. Until then a migrated preview is
editable only by a client that already knows the knob's name from `previews.json`.

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

### 6. Only the desktop lanes bind a seed

`:renderer-desktop` (the offline bake) and `:daemon:desktop` (live / `serve` / VS Code) both seed a
parameter knob. The **Android (Robolectric) daemon** does not: it carries the same `previewArgs`
seam and the same `resolvePreviewInvocation` shape, but neither the `RenderSpec.knobs` field nor the
bind, so a parameter knob there renders its defaults — and, until the resolution fix is mirrored,
a defaulted preview fails to resolve at all. That is what blocks `samples/android-live-lane`
independently of gap 1.

There is also no producer of the `knobs=` payload token yet. The daemon's production lane resolves
knobs off `previews.json` and never needs it; the token exists so a caller driving the
`className=`/`functionName=` payload directly can name them, and it is parsed but not written.

## The samples, one by one

| Sample | Knobs | Verdict |
| --- | --- | --- |
| `samples/cmp/.../OverridablePreviews.kt` | 4 (`title`, `accent`, `itemCount`, `density`, indexed `rowLabel`) | **Keep as is** — it is the deliberate twin of `ParameterKnobPreviews.kt`; the comparison is what the sample is for |
| `samples/cmp/.../ParameterKnobPreviews.kt` | 5 parameter knobs | Already migrated — the reference |
| `samples/android-live-lane/.../LiveLanePreviews.kt` | 1 (`label`, on the `@Preview` itself) | **Cleanest candidate, blocked** by gaps 1 and 6. `serve-lanes.spec.mjs` asserts `overrides[].key == "label"`, which would go empty |
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
