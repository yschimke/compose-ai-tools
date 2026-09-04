# Live source in the UI builder — highlighting, and a real compile check

Status: **spec, ready to start** (2026-09). The side-by-side live generation is
built; this scopes the two things it is missing. Written to be picked up by
someone (or something) that has not read the rest of this session.

Parent design: [UI_BUILDER.md](UI_BUILDER.md). The compile half rides an existing
shipped contract — [PLAYGROUND.md](PLAYGROUND.md) §4.

---

## What already works

`ScreenBuilderApp` (`samples/cmp-wasm-catalog/.../ScreenBuilder.kt`) runs the loop
**entirely in the browser** against the real M3 catalog: pick a component, it is
added to the selected container; select a node, edit its knobs; the middle pane
re-renders and the right pane regenerates Compose source. Every keystroke already
regenerates — `ScreenCodegen.generate(screen, catalogComponentSpecs)` is called in
composition, so "live" is the existing behaviour, not a thing to add.

The pieces it stands on, all landed and tested:

| Piece | Where | Tests |
|---|---|---|
| `Screen` / `ScreenNode`, the composition document | `screen/model/.../ScreenModel.kt` | `ScreenCodegenTest` |
| Tree edits (add / setKnob / remove), pure over an immutable document | `screen/model/.../ScreenEdits.kt` | `ScreenEditsTest` |
| `ScreenCodegen` → Compose source + a `problems` list | `screen/model/.../ScreenCodegen.kt` | `ScreenCodegenTest` |
| The catalog's id→call table | `samples/design-catalog-m3-shared/.../CatalogSpecs.kt` | via codegen tests |
| `CatalogScreen`, rendering a tree with per-instance knobs | `samples/design-catalog-m3-shared/.../CatalogScreen.kt` | — |

**Why per-instance editing works with no component change.** A screen's knobs
flatten to `key[index]` (`Screen.knobSeeds()`), which is the seed-key scheme the
catalog's knob lookups already use. `CatalogScreen` composes each node under its
own `LocalCatalogInstance`, and `catalogOverride*` resolves `label[3]` before the
bare `label`. Two buttons on one screen therefore carry different text.

**Why this is M3-only.** `androidx.wear.compose` does not compile to `wasmJs`, so a
browser-side compositor is a mobile-M3 builder by construction. Wear runs the same
document server-side on the Robolectric lane. See UI_BUILDER.md §0.4.

---

## Work item 1 — syntax highlighting

**Do not write a Kotlin lexer.** The builder highlights source *it generated*, and
codegen knows what every token is at the moment it writes it. Re-lexing its own
output would be a parser that can disagree with the thing it parses.

### Shape

Add a span-emitting sibling to the existing entry point, in
`screen/model/.../ScreenCodegen.kt`:

```kotlin
public enum class SourceTokenKind { KEYWORD, ANNOTATION, CALL, STRING, NUMBER, COMMENT, PLAIN }

public data class SourceToken(val start: Int, val end: Int, val kind: SourceTokenKind)

public data class GeneratedScreen(
  val source: String,
  val problems: List<String> = emptyList(),
  val tokens: List<SourceToken> = emptyList(),   // NEW — half-open ranges into `source`
)
```

`generate` records a token as it appends each fragment; the offsets are just
`builder.length` before and after. Nothing else about codegen changes.

### Acceptance

* Tokens **tile the source exactly**: sorted, non-overlapping, and every offset in
  `source.indices` is covered by at most one token. Assert this as a property over
  the existing test screens rather than by eyeballing one output — it is the
  invariant a renderer depends on, and an off-by-one shows as one wrongly coloured
  character that nobody notices.
* `source.substring(token.start, token.end)` is the token's text for every token.
* An `import` line's `import` is `KEYWORD`; `@Composable` is `ANNOTATION`; a call
  name (`Button`, `LazyColumn`) is `CALL`; `"Open"` including quotes is `STRING`;
  `4.0.dp` is `NUMBER` up to `.dp`; a `// TODO …` line is `COMMENT`.
* The existing `generate` assertions on `source` are unchanged — highlighting must
  not move a single character of output.

### Rendering it

In `ScreenBuilder.kt`, build an `AnnotatedString` from `tokens` and drop it into
the existing `Text`. Keep the palette in one `Map<SourceTokenKind, Color>` derived
from `MaterialTheme.colorScheme` so it works in both themes — the builder honours
`?uiMode=dark`, and a hardcoded IDE palette is unreadable on one of them.

**Effort: small.** One file in the model, one composable change, no new deps.

---

## Work item 2 — the compile check

The Kotlin compiler does not run in wasm. It does not have to: the preview server
already exposes a compile endpoint, and it returns more than a yes/no.

### The contract (already shipped — PLAYGROUND.md §4)

```
POST /api/{version}/compiler/run
{ "args": "", "confType": "…", "files": [ { "name": "Screen.kt", "text": "<generated>", "publicId": "" } ] }
```

Response carries diagnostics in two shapes (`diagnostics`, a flat list; `errors`,
upstream's map keyed by file name), plus **`image`** — a first-frame PNG as a
`data:` URI — and **`previewToken`**, which opens a live interactive session.

So "checks it compiles" is the floor. The same call also hands back a rendered
frame and a token that makes the assembled screen *runnable*, which is the loop
UI_BUILDER.md §0.2 describes.

### Shape

* A `CompileClient` in the wasm app: `suspend fun check(source: String): CompileResult`,
  over the browser `fetch`. Host from a `?compileHost=` param — **absent means the
  feature is off**, and the builder still generates and highlights. It must not
  require a server to be useful; the browser-only loop is the thing that works
  today and must keep working.
* **Debounce, and drop stale responses.** Every keystroke regenerates the source;
  posting each one would hammer the host and, worse, race — an older response
  arriving late would paint stale errors over a newer edit. Track a request
  sequence and ignore anything but the newest.
* Diagnostics map onto the source by offset. The generated file has no line
  mapping problem *if* the request sends exactly the text the pane shows — do not
  reformat between generating and posting.

### Acceptance

* With no `?compileHost=`, the builder behaves exactly as it does now (assert the
  compile pane is absent, not empty).
* A generated screen that should compile reports no errors; one containing a
  `// TODO` from an unspecced component reports the compiler's error and the pane
  shows it against the right line.
* A slow host does not block editing: the render and code panes stay live while a
  check is in flight.

### The honest risk

The generated file imports `androidx.compose.material3.*`. The compile host must
have those on its classpath — that is what `confType` selects, and the M3 catalog
mode is the one to use. **Verify this before building the UI**: post a
hand-written `Button(onClick = {}) { Text("x") }` to a real serve host and confirm
it compiles. If the available `confType` does not carry M3, that is a
server-side gap and belongs in `compose-preview-server`, not here — find it out
first, cheaply, rather than after the client is written.

**Effort: medium**, and mostly in the two things that are not the happy path —
debouncing/staleness, and the classpath question above.

---

## What this does not cover

* **Hand-editing the generated source.** The pane is output. Editing it and
  round-tripping back into a `Screen` is a genuinely different feature (it needs a
  parser, and an answer for what happens to edits when a knob changes).
* **Named slots in the builder UI.** `ScreenNode.slot` and `ComponentSpec.slots`
  exist and codegen emits them; nothing in the UI sets a slot yet, so every added
  node is an ordered child.
* **Variant selection** ("select the style"). `@CatalogVariant` cells exist; the
  document has no field for one.
