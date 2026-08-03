# Remote Compose CMP/Wasm player plan

Status: implementation in progress (foundation and baseline canvas)

## Goal

Build an original Kotlin Multiplatform Remote Compose player that runs in a browser as Kotlin/Wasm.
It consumes the existing `.rc` binary protocol and renders the document as real Compose
Multiplatform layout and drawing nodes.

This is not a Wasm port of `remote-core`, and it is not another platform adaptation of the vendored
embedded player. `remote-core` is a JVM library, but the more important reason not to reproduce it is
architectural: its mutable `Operation` objects, inheritance-heavy context, and coupled
inflate/apply/layout/paint lifecycle are a poor fit for a reactive Compose interpreter.

The compatibility hierarchy is strict:

- AndroidX `remote-core` and the Android Java player are the authoritative wire and behaviour spec.
  Conformance fixtures and expected operation behaviour come from them.
- `third_party/rc-embedded-player-jvm` is a useful cross-platform pixel comparison, but a mismatch is
  resolved against the AndroidX Java player rather than assuming the JVM embedded result is correct.
- `third_party/remote-compose-player` is the currently shipped client-side JavaScript player used
  by `compose-preview serve` and a useful inventory of browser concerns. It is not a protocol or
  behavioural oracle: local fixes and incomplete operations mean its output cannot establish
  correctness. Keep it working until the CMP/Wasm player satisfies the same embed contract and
  passes the replacement gates below. Its TypeScript class hierarchy, codecs, and hand-built opcode
  registry are not inputs to the new implementation.

The first delivery surface is an embeddable Wasm player, with iOS as the first native host. The
model, interpreter, and Compose renderer are ordinary non-JVM CMP code; only browser fetch/iframe
messaging is Wasm-specific. Protocol, runtime, and renderer publish iOS x64, device arm64, and
simulator arm64 targets. The renderer also builds a static `RcComposePlayer` framework with a thin
`RcComposeViewController` UIKit entry point.

### Why `third_party/remote-compose-player` still exists

It predates this CMP/Wasm player and currently supplies the CLI's zero-daemon browser lane. The
upstream TypeScript library is not published to npm, so its source was vendored and its generated
IIFE bundle was committed under `cli/src/main/resources/rc-player/`; that keeps Node/npm out of the
CLI's pure-JVM Gradle build. It remains during migration because it supports more operations today
and gives users a fallback. Remove it only after the CMP/Wasm lane passes the replacement gates,
then remove both the vendored source and committed bundle together. Historical availability is the
reason for the directory; it confers no specification status.

## Decision: use Compose graphics, with Skiko underneath

Skiko is a good implementation choice for this player, with one qualification: use it as the
backend supplied by Compose Multiplatform, not as the public architecture.

Compose Multiplatform's Wasm UI already renders through Skiko/Skia. The player should therefore
express layout with Compose primitives and drawing with `androidx.compose.ui.graphics` and
`DrawScope`. This gives the Wasm target paths, clipping, transforms, images, text, blending, input,
and frame scheduling without creating a second canvas integration. It also keeps most code in
`commonMain` and makes it testable on the desktop target.

Direct `org.jetbrains.skia`/`org.jetbrains.skiko` calls belong only in small internal backend seams
when a required capability is absent from the common Compose API. Current text-on-path rendering
uses Skia path measurement internally because Compose's common path measure cannot advance across
contours, but uses Compose text layout so bundled browser fonts work. Every such use needs a
fallback and a focused parity test. In particular, no Skia type belongs in the operation model or
public player API.

## Proposed modules

Create original code outside `third_party`:

```text
:rc-player-protocol       KMP: commonMain + desktop test target + wasmJs + iOS
  immutable operation IR, wire reader/writer, codecs, validation, debug JSON

:rc-player-runtime        KMP: commonMain + desktop + wasmJs + iOS
  document linking, value store, expressions, clock, actions, invalidation

:rc-player-compose        KMP/CMP: commonMain + desktop + wasmJs + iOS framework
  operation interpreter, Compose layout, DrawScope renderer, input/semantics

:rc-player-wasm           wasmJs executable
  ComposeViewport, fetch/ByteArray bridge, browser host callbacks, packaging

:rc-player-compat-tests   JVM test/tooling only
  remote-core fixture writer/oracle and cross-player comparison tooling
```

The JVM target in the first three modules is a fast test and reference-render target, not a
production dependency on `remote-core`. Only `:rc-player-compat-tests` may depend on `remote-core`.
The Wasm distribution can reuse the repository's existing webpack-free assembly pattern from
`:samples:cmp-wasm-catalog`, including the processed Skiko runtime.

Dependencies point in one direction:

```text
protocol <- runtime <- compose <- wasm host
    ^
    +--------- compat tests (JVM oracle only)
```

No Compose type belongs in `protocol`, and no browser API belongs outside the Wasm host or a
deliberate `wasmJsMain` adapter.

## Operation model

Use small immutable data values rather than executable operation objects:

```kotlin
sealed interface RcOp
sealed interface RcDataOp : RcOp
sealed interface RcDrawOp : RcOp
sealed interface RcLayoutOp : RcOp
sealed interface RcModifierOp : RcOp
sealed interface RcActionOp : RcOp

data class DrawRect(
  val left: RcFloatWord,
  val top: RcFloatWord,
  val right: RcFloatWord,
  val bottom: RcFloatWord,
) : RcDrawOp
```

The category interfaces are for exhaustive interpreter dispatch and support reporting, not for
inheriting behaviour. Operations do not read runtime state, draw, measure, register listeners, or
mutate a document. Those behaviours live in dedicated interpreters.

The parsed document has explicit structure:

```text
RcDocument
  header/version/capabilities
  ordered top-level operations
  linked component tree
  diagnostics and unsupported-capability report
```

Parsing first preserves wire order. A separate linker turns container delimiters and referenced
blocks into trees, validates ids/references, and builds indexes. Keeping parsing and linking
separate makes malformed input attributable and allows exact codec round trips without coupling
the format to the runtime tree.

Runtime state is composition-aware but independent of the operation objects:

- `RcValueStore`: typed id spaces for float words, integers, colors, text, paths, images, lists,
  maps, and objects.
- `RcDependencyGraph`: records computed values and invalidates dependants without a continuous
  polling loop.
- `RcClock`: injected frame/document time; production uses `withFrameNanos`, tests use a manual
  clock.
- `RcHost`: image/font loading, named actions, sound, haptics, logging, and capability queries.
- `RcExecutionScope`: read-only access to resolved values for a single interpretation pass.

There is no universal `RemoteContext` superclass. Each interpreter receives only the capabilities
it needs.

## Wire serialization design

The `.rc` byte format remains the external contract. Do not put Kotlin serialization directly on
the binary path: RC has explicit big-endian fields, payload-dependent shapes, version gates, and
NaN-boxed ids whose payload bits must survive decoding. A generic object serializer would obscure
those rules and can canonicalise values that must round-trip bit-for-bit.

### Primitive layer

`RcWireReader` and `RcWireWriter` live in `commonMain` and operate on `ByteArray`:

- Bounds-checked big-endian `u8`, `u16`, `i32`, `i64`, `f32Bits`, `f64Bits`, byte block, and UTF-8
  operations.
- Every read accepts or establishes a field name so failures report byte offset, opcode, operation
  name, and field.
- Lengths and collection counts have configurable limits before allocating.
- `slice(length)` creates a bounded child reader for size-delimited payloads; the codec must consume
  the slice exactly.
- Float fields are first read as raw `Int` bits. `RcFloatWord` interprets literal, variable-id, and
  reserved NaN encodings while retaining the original bits. The same rule applies to long NaN ids.
- Decoding never silently catches truncation or malformed UTF-8.

### Codec layer

Each wire operation has one symmetric codec:

```kotlin
interface RcOpCodec<T : RcOp> {
  val spec: RcOpSpec
  fun decode(input: RcWireReader, version: RcVersion): T
  fun encode(output: RcWireWriter, version: RcVersion, value: T)
}

data class RcOpSpec(
  val opcode: UByte,
  val stableName: String,
  val category: RcOpCategory,
  val introducedIn: RcVersion,
  val framing: RcFraming,
)
```

Codecs are pure: no state registration, expression evaluation, tree nesting, or rendering during
decode. Closely related operations may share private field-codec helpers, but not mutable base
classes.

Keep a checked-in operation manifest as the single inventory of protocol opcodes and metadata. A
small Gradle generator produces the registry, support matrix, and exhaustive inventory test from
that manifest. Field layouts remain explicit Kotlin codecs because generated reflection-like
serialization would hide the protocol's exceptional cases. This gives one authoritative opcode
table without creating an unreviewable schema language for every field.

The generated registry has these guarantees:

- duplicate opcodes and stable names fail generation;
- every manifest entry is `Implemented`, `ParseOnly`, or `Unsupported(reason, issue)`;
- every implemented entry has exactly one codec and one interpreter disposition;
- the public support report is generated from the same data;
- version checks happen before a codec is selected.

For legacy unsized operations, an unknown opcode is fatal at its exact offset because its end cannot
be known safely. For a size-delimited operation, preserve an `UnknownSizedOp(opcode, payload)` and
continue. Never use the TypeScript player's current “warn and discard the rest” behaviour as normal
control flow. A header claiming a newer protocol produces a structured compatibility error or an
explicit best-effort result, selected by the caller.

### Debug serialization

Use `kotlinx.serialization` separately for a stable diagnostic JSON form of `RcDocument` and
`RcOp`. It is for fixture review, diffs, and failure messages, not document transport. Encode binary
blobs as a digest plus optional sidecar; encode `RcFloatWord` with both raw hex bits and its decoded
meaning. JSON round-trip tests are useful, but binary encode(decode(bytes)) is the compatibility
gate.

## Runtime and rendering pipeline

The player runs explicit stages:

1. **Decode** bytes to the immutable, ordered operation list.
2. **Validate and link** containers, component ids, references, patterns, and resource declarations.
3. **Load constants/resources** into typed stores. Image/font fetches are suspendable host work.
4. **Build computed graph** for expressions and attributes.
5. **Compose** the linked component tree with standard Compose layouts and modifiers.
6. **Draw** canvas operation lists through `DrawScope` in their original order.
7. **Dispatch input/actions** through `RcHost`, updating the value store transactionally.
8. **Advance time** only while a time/animation dependency is live.

This retains the useful idea of mapping RC layout to Compose layout, but it does not reproduce the
embedded player's reflective `CoreDocument` access, mutable operation application, or global
composition locals. Pass a stable `RcPlayerState` at the root and use narrow internal locals only
where the Compose tree makes parameter threading genuinely awkward.

For raw canvas documents, render a single `Canvas`. For component documents, map box/row/column/etc.
to Compose primitives and attach recorded draw lists with `drawBehind`/`drawWithContent`. Publish
measured component dimensions into a snapshot-aware store after layout, batching updates to avoid
recomposition loops.

## Ordered implementation clusters

The manifest is the final source of truth; clusters are delivery order, not separate protocols.
Each cluster lands with decode/encode, validation, runtime behaviour, Wasm rendering where visual,
and operation-level tests before the next begins.

Current checkpoint:

- The five modules exist and compile for their intended targets.
- The bounded big-endian codec preserves raw float/NaN payload bits and supports legacy and modern
  AndroidX headers.
- AndroidX Java writers generate the compatibility stream and browser fixture; byte-for-byte
  decode/encode tests do not involve the TypeScript player.
- The Wasm host fetches real `.rc` bytes and the Compose/Skiko renderer paints the baseline shape,
  paint, clip, and transform operations in a browser.
- `compose-preview serve --rc-player-wasm-dir <wasmPlayerDist>` exposes an experimental `cmp-wasm`
  backend with an isolated iframe and an explicit first-frame/error contract. The JS backend stays
  available independently.
- The checked-in manifest exactly matches all 172 public integer entries in AndroidX alpha16
  `Operations.java`; generation fails on duplicate or invalid entries, and a reflection conformance
  test prevents drift. The current disposition is 95 implemented, 0 parse-only, 66 pending,
  6 unavailable, and 5 reserved operations.
- Cluster 1 now includes path data/drawing/clipping, AndroidX theme filtering, root scaling and
  alignment, root accessibility description, typed named-value overrides, primitive constants,
  and validated `CanvasOperations` container linking. Backend support reporting also inspects
  `PaintBundle` subcommands so decoding a partially supported opcode cannot masquerade as rendering
  support.
- Cluster 2 includes AndroidX-compatible path creation, append, combine, tween, expression-generated
  paths, path-derived transforms, and constant/expression/vector matrix math. `PathExpression` uses
  a platform-neutral RPN VM and path generator whose scalar, collection, spline, seeded-random, and
  cubic output are checked directly against the AndroidX Java utilities. Cluster 3 now has the basic text-value pipeline,
  list/map lookups, both AndroidX text measurement operations (including `TextAttribute` length),
  baseline-positioned `DrawText`, and built-in Android
  typeface mappings. Cluster 6's fixed ID/float lists and typed ID maps are also implemented because
  they unblock the text lookups. The color pipeline now includes `ColorAttribute`, all seven
  `ColorExpression` modes (literal/id interpolation, HSV, ARGB, and dynamic alpha), and
  `ColorTheme` fallback selection. The preview-server RC-Wasm iframe forwards its Day/Night
  control to that theme selection. `IntegerExpression` now has its bounded alpha16 wire codec and
  platform-neutral RPN evaluator; every standalone Java operator is checked against AndroidX,
  integer-ID operands resolve through runtime state, and argument-only variable tokens are rejected
  by support reporting instead of being advertised. Dynamic float lists now preserve referenced
  lengths on the wire, enforce AndroidX's 2,000-element bound, resize with Java-compatible reset
  semantics, and accept ordered literal or referenced updates for consumption by float expressions.
  Float-function definitions are linked as immutable `ContainerEnd`-delimited bodies rather than
  mutable AndroidX operation objects; calls bind literal or referenced arguments, execute the linked
  body in wire order, and reject missing definitions, excess arguments, and recursive invocation.
  The foundational layout wire layer now has typed immutable codecs for root/content, canvas, box,
  row, column, and fit-box components. The linker turns all seven into nested immutable containers,
  and AndroidX-generated byte streams verify every field including NaN-boxed row/column spacing.
  Width, height, and padding modifiers now have the same typed wire treatment, including strict
  dimension-type validation and variable-valued dimensions. The shared Compose renderer executes
  the baseline root/content/canvas tree with exact-pixel, exact-dp, fill, and cumulative-padding
  semantics on Wasm and iOS. Unsupported dimension modes are rejected by backend support reporting.
  Box layout also implements all nine AndroidX horizontal/vertical alignment combinations. Row and
  column implement main-axis start/center/end and space-between/evenly/around, cross-axis alignment,
  and AndroidX's additive `spacedBy` rule. Fit-box selects and aligns only the first child whose
  intrinsic and measured sizes fit, with headless pixel tests covering selection and visibility.
  Typed `CanvasContent` components receive their CanvasLayout content bounds, while `DrawContent`
  uses Compose `drawWithContent` to preserve AndroidX's pre-content/child/post-content paint order.
  An AndroidX-authored browser fixture exercises those operations together with box, row, and canvas
  layouts; it renders in the Wasm host without console errors. All decoded operations now have
  executable semantics; the manifest has no parse-only entries.
- Cluster 6 paint decorators have started with typed background, border, rectangular clip, and
  per-corner rounded clip operations. Their immutable records retain AndroidX reserved wire fields
  and their Compose application preserves wire order; byte-exact Java round trips and headless
  pixels cover the slice, while the same source compiles for Wasm and iOS.
  Offset and z-index modifiers likewise retain wire order, resolve dynamic float references, and
  are covered by an overlapping-sibling placement/stacking render test.
  Width/height ranges and unified optional/required dimension constraints are typed and applied to
  the component's requested size, with `-1` preserving AndroidX's unbounded sentinel.
  Variable-backed visibility implements AndroidX visible, invisible, gone, and override-bit
  precedence; render tests prove that invisible reserves layout space while gone removes it.
  Sparse graphics-layer records preserve typed attributes in wire order. Scale, three-axis
  rotation, transform origin, X/Y translation, elevation, alpha, and camera distance execute on the
  shared Compose layer; translation-Z, compositing/color, blur, and shape attributes remain
  document-level support errors until their backend semantics are implemented.
- Cluster 4 has started with AndroidX `BitmapData`, `DrawBitmap`, `DrawBitmapInt`, and
  `DrawBitmapScaled`: inline encoded PNG variants decode through the CMP/Skiko image backend and
  render with variable destination bounds, integer source cropping, and all eight authoritative
  Java scale modes. Inline RGBA8888 and alpha-only raster payloads are also decoded with strict
  size checks. URL/file encodings remain explicitly rejected by document support reporting until
  their host implementation lands.

### Operations unavailable in the authoritative Java profile

These are not implementation backlog. AndroidX alpha16 exposes their integer constants, but its
Java player cannot provide a readable wire operation with executable semantics. The CMP player
therefore publishes no support for them and must not invent behavior. Capability profiles should
exclude all six:

| Opcode | Constant | Why unavailable |
|---:|---|---|
| 4 | `LOAD_BITMAP` | No registered reader and no operation implementation in remote-core. |
| 57 | `DRAW_TEXT_ON_CIRCLE` | A source class exists, but AndroidX deliberately comments its reader out of the default Java operation map. |
| 132 | `MATRIX_SET` | Constant only; no registered reader or operation wire class. |
| 162 | `PARTICLE_PROCESS` | Constant only; particle processing is represented by other registered operations. |
| 174 | `MODIFIER_DRAW_CONTENT` | Its wire class is readable, but it is not a `PaintOperation`; `ComponentModifiers.paint()` never executes it and `LayoutComponent.inflate()` never attaches the parent required by its stated purpose. |
| 195 | `UPDATE` | Constant only; no registered reader or operation wire class. |

Opcodes 251–255 are separately classified as reserved extension markers. They are inventory rows,
not player capabilities. If a later AndroidX release makes any unavailable opcode executable, the
Java audit must supply its wire class, semantics, and fixtures before its status can change.

### 0. Foundation and executable spike

- Create the four modules and a minimal `ComposeViewport` host.
- Implement wire primitives, header/version handling, manifest generation, diagnostics, strict
  limits, and support reporting.
- Fetch and decode a tiny fixture in Wasm and render a hard-coded Compose rectangle from its parsed
  values.
- Prove desktop common tests and browser Wasm tests run in CI before implementing breadth.

Exit: real `.rc` bytes cross the browser boundary, are decoded by common code, and cause a visible
frame; malformed/truncated/future-version inputs fail deterministically.

### 1. Baseline static canvas

- Data: `Header`, `TextData`, `FloatConstant`, `ColorConstant`, `PaintData`, `PathData`, `Theme`,
  `RootContentBehavior`, `RootContentDescription`, `NamedVariable`.
- Canvas structure: `CanvasOperations`, `CanvasContent`, `DrawContent`.
- Paint and primitives: `DrawRect`, `DrawCircle`, `DrawLine`, `DrawOval`, `DrawRoundRect`, `DrawArc`,
  `DrawSector`.
- Stack/geometry: `MatrixSave`, `MatrixRestore`, `MatrixTranslate`, `MatrixScale`, `MatrixRotate`,
  `MatrixSkew`, `ClipRect`.

Exit: deterministic static shape fixtures render on desktop and Wasm with ordering, clipping,
paint-state, density, and save/restore tests.

### 2. Paths and advanced geometry

- `DrawPath`, `DrawTweenPath`, `ClipPath`, `MatrixFromPath`.
- `PathCreate`, `PathAppend`, `PathCombine`, `PathExpression`, `PathTween`.
- `MatrixConstant`, `MatrixExpression`, `MatrixVectorMath`.

Exit: path parsing preserves all verbs and raw float words; path morphing and matrix fixtures match
the reference renderer within defined raster tolerance.

### 3. Text and fonts

- `TextFromFloat`, `TextMerge`, `TextLength`, `TextSubtext`, `TextLookup`, `TextLookupInt`,
  `TextTransform`, `TextMeasure`, `TextAttribute`.
- `FontData`, `TextStyle`, `CoreText`, `TextLayout`.
- `DrawText`, `DrawTextAnchored`, `DrawTextOnPath`.
- `BitmapFontData`, `BitmapTextMeasure`, `DrawBitmapFontText`, `DrawBitmapTextAnchored`,
  `DrawBitmapFontTextOnPath`.

Use Compose text measurement/shaping first. Put any direct Skiko metric or text-on-path work behind
the text backend interface and record expected cross-font-platform tolerances.

`DrawTextOnCircle` is deliberately absent: opcode 57 is not readable by the authoritative
AndroidX Java profile and therefore cannot appear in either the Java-readable or CMP-Wasm profile.

Exit: unit tests pin anchors, baselines, alignment, bidi, shaping, fallback, and path placement;
visual fixtures include Latin, CJK, RTL, emoji, and missing-font behaviour.

### 4. Images and offscreen drawing

- `BitmapData`, `ImageAttribute`, `ImageLayout`.
- `DrawBitmap`, `DrawBitmapInt`, `DrawBitmapScaled`, `DrawToBitmap`.
- Host image loading, inline encodings, scaling modes, alpha, malformed data, cache lifecycle, and
  same-origin/CORS failures.

Exit: encoded and raw bitmap fixtures render with correct crop/scale/filter/alpha, and offscreen
targets have lifetime and memory-limit tests.

### 5. Baseline component layout

- `RootLayoutComponent`, `LayoutComponentContent`, `ContainerEnd`.
- `CanvasLayout`, `BoxLayout`, `RowLayout`, `ColumnLayout`, `FitBoxLayout`.
- Size/position modifiers: `Width`, `Height`, `WidthIn`, `HeightIn`, dimension constraints,
  `Padding`, `Offset`, `ZIndex`, `AlignBy`.
- Visual modifiers: background, border, rectangular/rounded clip, graphics layer, draw-content.

Exit: measurement is tested independently from pixels; nested layout fixtures cover constraints,
intrinsics, RTL, density, z-order, and draw-with-content ordering.

### 6. Values, expressions, and collections

- Integer/boolean/long constants and float/integer/color expressions.
- `FloatFunctionDefine`, `FloatFunctionCall`, `ComponentValue`, `ColorTheme`, `ColorAttribute`.
- `DataListIds`, `DataListFloat`, `DataDynamicListFloat`, `UpdateDynamicFloatList`, `DataMapIds`,
  `DataMapLookup`, `IdLookup`.
- Conditional operations and component visibility.

Exit: table-driven evaluator tests cover every token/operator, NaN-boxed reference, cycle,
missing value, overflow, and invalidation edge. A recomposition-count test proves one source update
only invalidates dependent readers.

### 7. Advanced layout and repeated content

- `FlowLayout`, collapsible row/column and priority, `StateLayout`.
- `LoopOperation` and `LayoutComputeOperation`.
- Referenced operations and Loom/pattern define, argument, block, inflation, for-each, and include.
- `Custom` with an explicit host registry and an unknown-custom fallback.

Exit: linker/property tests cover nesting, id remapping, empty/large loops, recursion/depth limits,
state switching, and stable Compose keys.

### 8. Interaction, actions, time, and semantics

- Click areas; click/multi-click/touch modifiers; `TouchExpression`; scroll, marquee, ripple.
- Host/named/run actions and integer/float/string/expression change actions.
- `TimeAttribute`, `WakeIn`, impulse operations, animation specifications, haptics.
- `CoreSemantics` and content descriptions mapped to Compose semantics.

Exit: browser pointer/keyboard tests drive real hit testing; manual-clock tests are deterministic;
host actions are capability-gated and never execute implicitly during decode or composition.

### 9. Specialized rendering and media

- `ShaderData` and shader paint, with capability reporting and a non-crashing fallback before full
  SkSL support.
- Particles create/compare/loop.
- Sound data/expression/play through opt-in browser host support.
- `DebugMessage`, `Skip`, and any manifest entries not assigned above.

Exit: the generated inventory reports every opcode implemented, intentionally parse-only, or
blocked by a documented browser capability. “All supported” means there are no accidental gaps;
temporary parse-only entries have issue links and conformance fixtures.

## Test strategy: operation by operation

Every operation gets a row in a generated conformance matrix and must pass the applicable layers:

| Layer | Required assertion |
| --- | --- |
| Codec unit | minimum/typical/boundary payloads decode to the expected immutable value |
| Binary round trip | `encode(decode(bytes))` is byte-identical, including NaN payload bits |
| Negative codec | truncation, invalid count/id/enum/version, and allocation limits fail at the exact field |
| Link/validate | ordering, nesting, references, and illegal placement are accepted/rejected explicitly |
| Runtime unit | state reads/writes, dependencies, time, and actions have deterministic effects |
| Render fixture | a document isolating the operation produces a reviewed image |
| Composition behaviour | recomposition, measurement, and input semantics are asserted where applicable |
| Wasm smoke | the same fixture runs in a real headless browser, with console errors treated as failures |

Fixtures should be generated by a JVM-only AndroidX `remote-core` fixture builder where public writer
APIs exist, then committed as tiny `.rc` binaries plus reviewed diagnostic JSON and metadata. Expected
behaviour and reference pixels come from the AndroidX Java player. Hand-crafted byte fixtures cover
malformed input and exact bit patterns, but are checked against AndroidX reads before becoming
conformance inputs. Never generate expected bytes with the codec under test or derive field layouts
from the TypeScript player.

Use three comparison modes:

- Exact structural comparison for decoded operations, linked trees, state, and semantics.
- Exact pixels for deliberately controlled primitives with bundled fonts and fixed density.
- Perceptual/tolerance comparison for antialiasing, system-font, and backend-sensitive output. The
  tolerance is declared per fixture and may not grow automatically.

For each cluster, render its catalog through:

1. the existing Android/View or embedded reference lane as applicable;
2. the existing JVM embedded player;
3. the new CMP desktop target;
4. the new CMP Wasm target in Chromium, with periodic Firefox/Safari coverage.

Differences must be classified as protocol, interpreter, Compose/Skia backend, or accepted platform
parity. Store that classification next to the fixture rather than normalising a bad baseline.

Add fuzz/property testing for the wire reader and expression/path/pattern engines. Feed arbitrary
bytes with strict memory/depth/time limits; the invariant is a parsed result or a structured error,
never a hang, browser crash, or unbounded allocation.

## Delivery gates

Do not measure progress as “number of classes ported.” A cluster is complete only when:

- all of its manifest rows are implemented or explicitly capability-gated;
- codec and interpreter dispatch are exhaustive;
- common desktop tests and Wasm browser tests pass;
- its focused render catalog has reference comparisons;
- the public support matrix is regenerated and contains no unreviewed downgrade;
- bundle size and first-frame time stay within a recorded budget.

After cluster 1, wire the player behind an experimental viewer flag so real documents can find
unknown operations early. Keep the static/snapshot player visible until the Wasm player posts a
successful first-frame signal, matching the existing catalog's no-flash handoff.

The existing `JS` backend remains available during this rollout. Replace it only after the CMP/Wasm
lane supports its document corpus, named overrides, browser-host features, and first-frame contract,
and the comparison catalog shows no unexplained regression. At that point remove the committed
TypeScript bundle and vendored sources together; until then they are production code, not merely
test scaffolding.

## Initial implementation slices

The first PRs should be small and independently verifiable:

1. Module skeleton, manifest, wire primitives, diagnostics, and byte-level tests.
2. Header plus constants/data codecs and JVM-oracle fixture generator.
3. Baseline paint state and rectangle/circle/line renderer on CMP desktop.
4. Wasm host and the same fixture catalog in a real browser test.
5. Remaining baseline canvas operations, generated conformance report, and experimental embed.
6. Continue cluster by cluster in the order above; do not start advanced layout or shaders while a
   baseline operation lacks its full test row.

The deliberate early spike through Wasm avoids discovering packaging, browser test, or Skiko
constraints after building a large JVM-only protocol layer. The deliberate delay of shaders,
particles, and sound keeps specialized backend work from defining the core abstractions.

## Non-goals for the base

- Reusing `remote-core` through JVM/Wasm bridging or transpilation.
- Sharing mutable operation classes with either existing player.
- Inventing a replacement transport format for `.rc`.
- Pixel identity across different font stacks or unsupported shader languages.
- Executing host actions, network loads, sound, or haptics without an explicit host capability.
- Treating parse-only as support.

## Questions to resolve during the foundation spike

- Which protocol version range is the initial compatibility floor and ceiling? Pin both in the
  manifest; do not infer them from the dependency resolved that day.
- Which operations in that range are uniformly size-delimited? This determines precisely where
  unknown operations can be preserved and skipped.
- Can every required text/path metric stay on common Compose APIs at the pinned CMP version, or
  which minimal backend interface needs a Wasm Skiko implementation?
- What browser matrix and maximum document/resource budgets are appropriate for the embed host?
- Is full binary writing a shipped API or test/debug infrastructure only? Implement symmetric
  codecs either way, but keep the public surface minimal until a producer use case exists.
