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
:rc-player-trace          KMP: commonMain + jvm + wasmJs + iOS
  androidx.tracing 2.x facade every module above opens spans through

:rc-player-protocol       KMP: commonMain + jvm test target + wasmJs + iOS
  immutable operation IR, wire reader/writer, codecs, validation, debug JSON

:rc-player-runtime        KMP: commonMain + jvm + wasmJs + iOS
  document linking, value store, expressions, clock, actions, invalidation

:rc-player-compose        KMP/CMP: commonMain + jvm + wasmJs + iOS framework
  operation interpreter, Compose layout, DrawScope renderer, input/semantics

:rc-player-wasm           wasmJs executable
  ComposeViewport, fetch/ByteArray bridge, browser host callbacks, packaging

:rc-player-compat-tests   JVM test/tooling only
  remote-core fixture writer/oracle and cross-player comparison tooling

:rc-player-profile        JVM application only
  installs an androidx.tracing driver and profiles four reference documents headlessly
```

### Published coordinates

The four library modules publish to Maven Central under `ee.schimke.composeai`, through the same
`composeai.maven-publishing` convention and the same release chain as every other artifact here —
the root-level `publishAndReleaseToMavenCentral` fans out to whatever applies the plugin, so there
is no list to keep in step.

| module | artifact | targets |
|---|---|---|
| `:rc-player-trace` | `rc-player-trace` | `jvm`, `wasmJs`, `iosArm64`, `iosSimulatorArm64` |
| `:rc-player-protocol` | `rc-player-protocol` | same |
| `:rc-player-runtime` | `rc-player-runtime` | same |
| `:rc-player-compose` | `rc-player-compose` | same |

`:rc-player-wasm` (an executable), `:rc-player-profile` (an application), `:rc-player-metrics` and
`:rc-player-compat-tests` (fixture generators) are not published. The Wasm *distribution* is a
different problem and is tracked in #4067; the iOS framework in #4068.

One target set across all four, deliberately: `iosX64` is gone everywhere rather than present on the
three non-Compose modules, because CMP 1.11 dropped the Intel iOS simulator and a stack that
resolves three of its four artifacts on one target is worse than one that offers none. See
[RENDERER_COMPATIBILITY.md](../RENDERER_COMPATIBILITY.md) for what the published POMs pin.

Tracing and profiling are written up separately in
[RC_PLAYER_PROFILING.md](RC_PLAYER_PROFILING.md) — what each span covers, why the tracing seam is an
`expect`/`actual` facade (androidx.tracing 2.x ships no wasmJs or Apple klib), and the current
per-phase numbers.

The JVM target in the first three modules is a fast test and reference-render target, not a
production dependency on `remote-core`. It is declared as an **unnamed `jvm()`**, so the source sets
are `jvmMain`/`jvmTest` and the test task is `:rc-player-<module>:jvmTest`. That is deliberate and
matters for publishing: the artifact then carries the conventional `-jvm` classifier rather than
`-desktop`, which would misname bytecode Android also resolves (#4063, and the same decision
`runtimes/slots/build.gradle.kts` records). Only `:rc-player-compat-tests` may depend on `remote-core`.
The Wasm distribution can reuse the repository's existing webpack-free assembly pattern from
`:samples:cmp-wasm-catalog`, including the processed Skiko runtime.

Dependencies point in one direction:

```text
trace <- protocol <- runtime <- compose <- wasm host
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
  test prevents drift. The current disposition is 135 implemented, 0 parse-only, 26 pending,
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
  Typed `ImageLayout` components reuse the same decoder and authoritative eight-mode scaling math,
  provide bitmap intrinsic wrap size, clip to measured content bounds, and apply variable alpha.
- Cluster 8 interaction now includes click, touch-down/up/cancel, and multi-click containers linked
  as immutable, wire-ordered action blocks. Single, long, and double gestures share one common CMP
  pointer recognizer so ordinary click and multi-click actions cannot compete or reorder. It uses
  platform view-configuration timeouts, AndroidX haptic mappings, and the Java player's clipped
  two-phase ripple. AndroidX-authored byte streams round-trip exactly; held-pointer and rapid-click
  tests cover all gesture variants, and the Wasm browser fixture proves single and double dispatch
  without action leakage. Legacy document-level `ClickArea` registration now follows Java's
  resolved-string replacement, half-open hit bounds, overlapping dispatch, and single-click-only
  behavior. Explicit `HapticFeedback` actions retain all raw AndroidX values and use a separate
  player-local effect channel, with the Java player's 21 haptic families mapped to portable CMP
  feedback. The same implementation links in the iOS framework.
- Cluster 9 diagnostics include byte-exact UTF-8 `Rem` comments and typed `DebugMessage` events.
  Dynamic float references resolve through shared state; Wasm forwards diagnostics to the browser
  console and parent frame, while iOS callers receive the same event from the UIKit host callback.
- Control flow now includes immutable `ConditionalOperations` and `LoopOperation` containers.
  All seven Java predicates, stateful changed detection, exclusive loop bounds, dynamic float
  operands, and a 10,000-iteration safety ceiling are shared by Wasm and iOS.

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

- `TouchExpression`; scroll and marquee.
- Host/named/run actions and integer/float/string/expression change actions.
- Particle operations. `AnimationSpec` bounds, fade, slide, and rotate branches are implemented;
  particle-dependent exit variants are rejected explicitly by both CMP profiles until the particle
  runtime exists.
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

### Replacement-gate evidence

The remote-m3 replacement corpus is guarded in CI, not recorded as a one-off manual result:

- All 27/27 current documents render through CMP/Wasm, and *that* is the lane's gate: a document the
  player cannot render fails the run unless it is named in the dated, reasoned allowlist.
- **Per-preview pixel mismatch is measured and reported, not gated.** It used to be: a strict 1%
  default with a checked-in file of reviewed per-preview ceilings. That check ran in the publish job,
  on `main`, after the change that moved the number had already merged — so it could never stop a
  regression arriving, only stop the catalog being republished afterwards. On 7 Aug 2026 five
  text-bearing `remote-m3` rows drifted past 1% and the delivery branch froze for five days on the
  3 Aug render, whose CMP/Wasm column still showed 0/24 from player bugs fixed on the 4th and 7th —
  the gate hiding four days of *fixes* to punish one regression it had already let through. Every
  row's mismatch stays in `rc-compare-summary.json`, on the comparison page, and in the job summary
  (`pixel parity (report-only)`), so a divergence is still visible in the place a human looks.
- **The blocking question is asked on the pull request instead**, by the `CMP/Wasm Parity` job in
  [`ci.yml`](../../.github/workflows/ci.yml) and
  [`rc-compare-regression.mjs`](../../scripts/design-artifacts/rc-compare-regression.mjs). It
  renders the *published* `design-artifacts/remote-m3` bundle — fixed documents plus their baked
  references, so nothing but the player can move — through the PR's `wasmPlayerDist`, and fails on
  either of the two things a PR can actually be responsible for: a document that rendered on the
  baseline and no longer does, or a mismatch that grew by more than 0.25 pp. A delta, not a bar:
  absolute mismatch is dominated by backend and reference-lane differences nobody on the PR
  introduced, while the 0.26% → 2.17% jump that froze the branch is unmistakably the diff. Two
  independent runs over the same corpus measure identically, so that threshold has ~8× headroom over
  the regression it is sized to catch. Rows the baseline never measured are reported, never judged.
  Written up with its before/after in
  [`evidence/rc-compare-publish-gate/`](evidence/rc-compare-publish-gate/README.md).
- **That job reports; it does not block.** It posts a sticky PR comment with the per-row report and
  runs `continue-on-error`, because a parity delta is a judgement a human should make on the rows
  rather than a merge veto — and because a guard that depends on a delivery branch it does not
  control should earn a blocking role before it holds one. The step still goes red, and the renders
  and pixel diffs are uploaded as `rc-cmp-wasm-parity`. Cost is measured, not assumed: 41 s to render
  and diff 24 documents, 7 s for a warm `wasmPlayerDist`, so the job is dominated by the cold Gradle
  build and the Chromium download — which is why it reads the shared `buildfetch-cache`.
- The production distribution is capped at 23,000,000 raw bytes by `wasmPlayerDist`; the verified
  distribution is 22,756,717 bytes. Source maps and development-only formatters are not shipped.
- The strict comparison lane caps cold and warm navigation-to-painted-ready time at 10,000 ms and
  5,000 ms respectively. The reviewed 24-document run measured 2,361 ms cold and 2,003 ms warm.
  Those numbers only hold with the compositor's frame cap lifted (`--disable-frame-rate-limit`, in
  [`rc-chromium.mjs`](../../scripts/design-artifacts/rc-chromium.mjs)): Chromium paces
  `requestAnimationFrame` at roughly 1 fps for a page whose CSS viewport is a few dozen pixels
  tall, and the player waits three frames before posting `ready`. Without the flag the 76 dp-tall
  `remote-m3` widget previews took ~6,150 ms against the ~2,040 ms of the 124 dp-tall preview
  beside them — a *warm* row over the warm budget, which read as a startup-cost problem and was
  not one ([#3445](https://github.com/yschimke/compose-ai-tools/issues/3445)).
  [`rc-cmp-wasm-frame-pacing.test.mjs`](../../scripts/design-artifacts/rc-cmp-wasm-frame-pacing.test.mjs)
  holds the short viewport to the tall one; it skips unless `wasmPlayerDist` is built.
- `cold` and `warm` are properties of a **browser context**, and the lane keys one context per
  preview density (Playwright binds `deviceScaleFactor` at context creation, and the player reads
  its density from `devicePixelRatio`). A catalog spanning two densities therefore reports two cold
  rows, the second partway through the run — measured at ~0.3 s over a warm render, against the
  ~1.5 s the player itself waits before reporting readiness. Each row records its
  `cmpWasmDensity`, `cmpWasmViewport`, and `cmpWasmContextRender` in `rc-compare-summary.json`, so
  a slow row can be attributed rather than guessed at.
- **The player can be handed a document in place; the parity driver no longer takes that offer.**
  `window.rcPlayerLoad(src)` (installed by the player, see `installDocumentSwap` in
  [`Main.kt`](../../rc-player/wasm/src/wasmJsMain/kotlin/ee/schimke/composeai/rcplayer/wasm/Main.kt))
  points the running player at another document instead of reloading the page, which would discard
  the instantiated Wasm module, the Compose runtime and the host fonts and rebuild all three to draw
  a document of a few dozen operations
  ([#3445](https://github.com/yschimke/compose-ai-tools/issues/3445)). Over the 27-document
  `remote-m3` corpus that took the warm navigation-to-`ready` time from a mean of **819 ms to
  107 ms**.
  [`rc-cmp-wasm-document-swap.test.mjs`](../../scripts/design-artifacts/rc-cmp-wasm-document-swap.test.mjs)
  pinned the equivalence that was supposed to buy it: a swapped render byte-identical to a navigated
  one, and still so after a detour through another document.

  **That equivalence holds for two documents and fails across a corpus**
  ([#3558](https://github.com/yschimke/compose-ai-tools/issues/3558)). Run all 27 through one player
  and a *band* of the text-bearing ones comes back with no text at all — shapes drawn, every glyph
  missing, and permanently: the frame is still blank after a 5 s settle, while the same document
  navigated to renders correctly. Which band depends on the order (reverse the corpus and a
  different set loses its text) and on the machine. So `rc-compare.mjs` navigates for every
  document. Measured on the full `remote-m3` comparison that is **74 s → 88 s** end to end, and it
  buys two consecutive runs that are byte-identical on all 27 rows with every row on its correct
  value. The player keeps `rcPlayerLoad` and its guard — this is the driver declining to depend on
  the swap until it can show it has finished, and extending that test to a corpus-length sequence is
  what would let the driver go back to it. See
  [`docs/design/evidence/rc-cmp-wasm-swap-blank-text/`](evidence/rc-cmp-wasm-swap-blank-text/README.md).
- **`ready` is liveness, not settlement — the lane captures on convergence.** Three frames prove the
  composition ran; Compose resolves the host font faces asynchronously, so a text-bearing document
  draws once in a fallback face and again in the real one, and the second draw can land after the
  third frame. With the handoff tail dropped for speed the lane was capturing the intermediate draw:
  worth 2–4% mismatch on a button-with-a-label row and nothing at all on a row without text.
  [`rc-settle.mjs`](../../scripts/design-artifacts/rc-settle.mjs) screenshots until the pixels hold
  still for 500 ms instead. Measured over the corpus, that took the mean mismatch from **0.79% to
  0.49%** and the worst row from 2.98% to 2.35%, and made two consecutive runs come back
  byte-for-byte identical on all 27 rows where the capture-on-`ready` lane differed on 9 — the
  reproducibility is the point, since a lane whose pixels depend on the run cannot tell a regression
  from noise. It reaches the same pixels the player's 1,500 ms tail does, at a third of the cost.
  Each row records `cmpWasmSettleMs` beside its first-frame time.

  **Convergence is necessary and not sufficient, and that gap is the other half of #3558.** A quiet
  window says the page stopped changing; it does not say the page ever started. A document whose
  text never painted is blank now and blank in 500 ms, so it converges immediately and scores a
  perfectly stable, perfectly wrong number. The loop now takes an `expectation` the converged frame
  has to satisfy, and the lane's is "there is ink here", asked only of documents whose baked
  reference has ink — a claim made from data the driver already holds rather than a sleep tuned to
  whichever machine ran the measurement. A frame that is still blank at the timeout fails the row
  outright: `the player drew nothing in … ms while the baked reference has ink`. A missing render is
  reported as a missing render, never as a parity delta.
- **The readiness signal is deliberately late, and only for hosts that can flash.** After its three
  frames the player holds `ready` back for another 1.5 s, so viewer.js's `revealRcWasm` cannot swap
  the snapshot for a surface the compositor has not presented. `?handoffDelayMs=0` drops that tail
  and **only a host that composites the result itself may ask for it** — the parity driver does,
  because its CDP screenshot drives its own frame and every pixel is then checked against the baked
  reference. Measured across four full `remote-m3` runs, 60 rendered PNGs came back byte-for-byte
  identical to the tailed baseline while the per-preview cost fell from ~2,000 ms to 453–891 ms
  (~3 minutes saved on a 122-preview catalog). The viewer keeps the default: its hazard could not be
  reproduced under any capture available here — `page.screenshot()` and CDP screencasts both drive
  compositor frames of their own, and a control that revealed the frame 250 ms after load, long
  before the player could be ready, still captured clean. An unverifiable hazard keeps its guard.
  [`rc-cmp-wasm-handoff.test.mjs`](../../scripts/design-artifacts/rc-cmp-wasm-handoff.test.mjs)
  pins both halves: the default still waits, and the tail-free render is byte-identical to a settled
  one.
- The live viewer forwards typed named overrides, reloads after knob changes, validates same-origin
  host messages, and surfaces host/named actions as inert `CustomEvent` payloads. Decode, support,
  and resource failures remain bounded inside the iframe instead of replacing the catalog page.
- Protocol, runtime, Compose, compatibility, Wasm distribution, CLI host, and formatting checks run
  in the dedicated player CI job. The shared renderer compiles/tests for iOS x64, device arm64, and
  simulator arm64; the UIKit entry point reports decode/support/resource failures through `onError`
  and forwards player events through its host callback.

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
