# Live source in the UI builder — highlighting, and a real compile check

Status: **built** (2026-09). Both work items below have landed; the sections are
kept as the record of what was specified and what the build actually found. Two
things the spec got wrong are corrected in place and marked **Correction**.

Parent design: [UI_BUILDER.md](UI_BUILDER.md). The compile half rides an existing
shipped contract — [PLAYGROUND.md](PLAYGROUND.md) §4.

---

## What already works

**Verified in Chromium**, not inferred: the wasm dist was built, served, and driven
with Playwright through the whole loop — add a container, select it, add a
component into it, select that, type a label. Zero page errors. Screenshots in
[`evidence/ui-builder-wasm/`](evidence/ui-builder-wasm/).

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

## Work item 1 — syntax highlighting — **done**

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

### What landed

As specified, with one strengthening. The spec's acceptance said every offset is
covered by **at most** one token; the implementation guarantees **exactly** one —
`SpanBuilder.tile()` fills the gaps with maximal `PLAIN` runs. That is the
difference between a renderer that walks the list and one that also has to
reconstruct the gaps, and it makes the tiling assertion total rather than
one-sided. Asserted over a corpus of nine screens (`ScreenTokensTest`) that
deliberately includes every unrepresentable case, because the `// TODO` paths
append out of line with the call being written and are exactly where an offset
goes wrong.

---

## Work item 2 — the compile check — **done**, and the risk below is real

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

### The honest risk — settled, and the spec was wrong about it

The generated file imports `androidx.compose.material3.*`, so the compile host
must carry those. The spec said "that is what `confType` selects". It is not.

> **Correction.** `confType` selects the **renderer** — `PlaygroundMode.CMP` /
> `ANDROID` / `REMOTE_COMPOSE`, i.e. desktop Skiko vs Robolectric vs an `.rc`
> capture. `PlaygroundMode.fromConfType` defaults *everything it does not
> recognise* to `CMP`, so it can never report "no M3 here". What puts
> `androidx.compose.material3.*` on the compile classpath is the **`catalog`**
> field of `PlaygroundRunRequest` (or, absent one, the host's pinned
> `--playground-bundle`). `compose-m3` is the catalog to name: its resolved
> classpath *is* the unminimized Material 3 library jar, which
> `PlaygroundCompileService`'s own KDoc states outright. The server already has
> a lane that does this — `UiBuilderGeneratedPreviewAdapter` **requires** an
> exact catalog target and refuses to fall back to a default, on the grounds
> that compiling against somebody else's design system would report the wrong
> thing as an error rather than as unavailable.

So a client that sent only a `confType` would land on whatever the host pinned
and blame the screen for every unresolved reference. This one sends
`catalog = "compose-m3"` and, before that, **asks**: `GET
/api/{version}/compiler/catalogs` advertises each served catalog and the modes
it supports, and `CompileCheck.targetFor` returns null rather than substituting
when none of them is M3. The classpath question is therefore answered per host,
at runtime, in the product — not assumed once at design time.

### The probe, and what it found

The spec asked for a hand-written `Button(onClick = {}) { Text("x") }` posted to
a real serve host. Done, against this repo's own public host
(`composePreview.serveUrl` = `https://preview.coo.ee`):

| Request | Result |
|---|---|
| `GET /api/1/compiler/catalogs` | `404` |
| `POST /api/1/compiler/run` (`confType=compose-cmp`, `catalog=compose-m3`) | `404` |
| `GET /playground` | `503` — *"the playground is not enabled on this server."* |

The route exists and answers; the lane is switched off. So the answer is neither
"M3 works" nor "M3 is missing" — **there is no reachable host running the
playground lane to ask**. That is an operator/deployment gap, not a code gap:
the flag exists (`--playground-bundle compose-m3`), nothing in
`compose-preview-server` needs changing, and the client cannot be end-to-end
verified against a live compile until some host is started with it.

This is precisely why the client discovers rather than assumes. Against
`preview.coo.ee` today it reports *"could not reach … HTTP 404"* in the compile
pane and the browser-only loop carries on untouched — which is the specified
behaviour for a host that cannot compile, reached by the honest route.

**Effort: medium**, and mostly in the two things that are not the happy path —
debouncing/staleness, and the classpath question above.

---

### Where the code went, and why the split is not where the spec put it

The spec said "a `CompileClient` in the wasm app". It is split instead:

| Piece | Where | Why |
|---|---|---|
| Wire types, target selection, request building, response reading, `?compileHost=` validation, `StaleGuard` | `screen/model/.../CompileCheck.kt` | All pure functions of text — and the wasm module has **no test lane** (`:kotlinWasmToolingSetup` needs karma). In the model they are 14 JVM tests; in the app they would be verified by clicking. |
| `fetch`, the Compose state, the pane | `samples/cmp-wasm-catalog/.../CompileClient.kt` | The only genuinely untestable part is the one that talks to the network. |

**On staleness there are two mechanisms and both are wanted.** Keying the effect
on the source means a new edit *cancels* the in-flight check, so its continuation
never resumes; `StaleGuard`'s sequence then makes that explicit where the result
is applied. The first is a property of structured concurrency that a reader has
to derive; the second is one integer and a test.

### Verified in a browser

Chromium, against the built wasm dist, on top of the browser run #5109 already
did for the loop itself — see
[`evidence/ui-builder-live-code/`](evidence/ui-builder-live-code/). The pane is
**absent** with no `?compileHost=`; with one pointing at a host that cannot
compile, the render and code panes stay fully live while the compile pane states
the reason.

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
