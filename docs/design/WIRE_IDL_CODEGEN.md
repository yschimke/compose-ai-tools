# Evaluation: single-source IDL + codegen for the cross-language wire types

**Status: evaluated; `SpatialScene` pilot landed.** Tracks
[#1729](https://github.com/yschimke/compose-ai-tools/issues/1729), the follow-up from
[RENDERER_SERVICE.md decision #5](xr-spatial/RENDERER_SERVICE.md#decisions). This document records the
evaluation and decision so it survives the issue.

> **Update — `SpatialScene` is now generated.** The spike below was actioned for the one type that
> is genuinely triplicated today: `SpatialScene` (Kotlin + TS + the native C++ one-shot compositor).
> [`schema/spatial-scene.schema.json`](../../schema/spatial-scene.schema.json) is the single source
> of truth; [`scripts/codegen/gen-spatial-scene.mjs`](../../scripts/codegen/gen-spatial-scene.mjs)
> generates all three mirrors (the CI job `SpatialScene codegen up to date` runs it with `--check`).
> A **bespoke** generator was chosen over a stock tool (quicktype) deliberately — see
> [Pilot notes](#pilot-notes-spatialscene). The broader `Messages.kt` envelope stays hand-mirrored
> until the native renderer *service* exists (the gate below).

## The problem this is about

The daemon/renderer wire types are **hand-mirrored** across languages and kept honest by
fixture-deserialization round-trip tests plus per-surface version integers:

| Mirror | Files |
|---|---|
| Kotlin | [`daemon/core/.../protocol/Messages.kt`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt) (~2100 lines), [`api/preview-data-api/.../xr/SpatialScene.kt`](../../api/preview-data-api/src/main/kotlin/ee/schimke/composeai/xr/SpatialScene.kt) |
| TypeScript | [`vscode-extension/src/daemon/daemonProtocol.ts`](../../vscode-extension/src/daemon/daemonProtocol.ts) (~1250 lines), [`vscode-extension/src/webview/shared/spatialScene.ts`](../../vscode-extension/src/webview/shared/spatialScene.ts) |
| Shared fixtures | [`docs/daemon/protocol-fixtures/`](../daemon/protocol-fixtures/) + the spatial-scene fixture |

Two mirrors (Kotlin ↔ TS), locked by a corpus of golden JSON fixtures that *both* test suites
deserialize and round-trip ([fixtures README](../daemon/protocol-fixtures/README.md)). The
[XR renderer-service RFC](xr-spatial/RENDERER_SERVICE.md) adds a **third** mirror — a native **C++**
renderer that has to parse/serialize the same JSON-RPC envelope + `SpatialScene`. Three
hand-maintained mirrors is where drift risk starts to bite: a field added in Kotlin but missed in
C++ fails only at runtime, only on one platform.

This issue is **not** about the wire *encoding* (JSON vs binary). It is about whether the *payload
types* are **generated from one source** or **hand-written per language**. Those are independent
questions, and only the second is the pain here.

## Two questions, deliberately separated

### Q1 — Binary transport vs JSON on the wire? → **Keep JSON. Not in scope to change.**

Even though every runtime we ship to (JVM, Node/TS, future C++) has a mature protobuf stack — so
binary-on-the-wire is *technically* viable everywhere, and **Node/VS Code is not the blocker** —
JSON should stay, for reasons that have nothing to do with VS Code's capabilities:

1. **MCP forces JSON on the hop that matters most.** Agents → MCP server → daemon is JSON-RPC 2.0 by
   spec ([API_STABILITY surface 8](../API_STABILITY.md#28-mcp-tool-names-surface-8)). A binary daemon
   hop would mean two serialization stacks plus a translation layer for zero gain on the AI-agent
   path.
2. **The big payload already isn't on the wire.** PNG bytes travel as `pngPath` on disk; inline
   base64 is an opt-in exception ([PROTOCOL.md](../daemon/PROTOCOL.md)). The wire carries KB-sized
   control messages — protobuf's compactness/parse-speed buys ~nothing. The bottleneck is rendering,
   not serialization.
3. **Half the contracts are on-disk JSON read by third parties** — `previews.json`
   ([surface 2](../API_STABILITY.md#22-previewsjson-surface-2)), `HistoryEntry` sidecars,
   data-product payloads ([surface 3](../API_STABILITY.md#23-data-products-surface-3)) — consumed by
   CI / `jq` / agents off `compose-preview/main`. Those stay JSON for ergonomics regardless, so a
   binary RPC just creates a mixed world.
4. **The versioning model leans on JSON tolerance** — `ignoreUnknownKeys`, structural casts on the
   TS side, tolerant enum → `UNKNOWN` decode, additive optional fields
   ([VERSIONING § 4](../VERSIONING.md)).
5. **Debuggability + the fixture corpus.** The fixtures are human-readable JSON round-tripped on both
   sides; stderr is eyeballable. Binary fixtures are opaque.

So: **do not adopt a binary protobuf transport.** Whatever we do for Q2 must preserve the JSON wire
byte-for-byte and keep the fixture corpus valid.

### Q2 — Single-source IDL with codegen vs hand-mirrored types? → **the live question, gated.**

This is the actual triplication (soon quadruplication) cost. Options, all evaluated against the
constraint "must keep the JSON wire and the fixture corpus":

| Option | Keeps JSON wire? | Cross-lang codegen | Cost / risk |
|---|---|---|---|
| **A. Status quo:** hand-mirror + fixtures | ✅ (it *is* the wire) | none | drift risk grows linearly with mirror count; cheapest at 2 mirrors |
| **B. JSON-Schema → codegen** (Kotlin/TS/C++) | ✅ unchanged | quicktype / json-schema-to-* per language | new build step; schema authoring; generators vary in quality, esp. C++ |
| **C. proto3 + canonical-JSON mapping** | ✅ via proto3 JSON | protobuf-kotlin / ts-proto / protoc-cpp — strongest story | field-number rename-safety; but doesn't cover the on-disk / MCP JSON surfaces, so those stay hand-authored → a *mixed* generation world |
| **D. `kotlinx.serialization` schema export** | ✅ | Kotlin is source; export schema → TS/C++ | Kotlin-centric; TS/C++ generators for its schema dialect are immature |

Key observation about C and D: neither covers the **whole** JSON surface. proto3-with-JSON only
describes the RPC payloads, not `previews.json` / history sidecars / data-product payloads, which are
their own schemas read by external tooling. So even the "strongest" IDL leaves a hand-authored
remainder and a split-brain "some types generated, some not" situation. JSON-Schema (B) is the only
option that can, in principle, describe *all* the JSON surfaces with one tool — at the cost of
trusting per-language JSON-Schema generators (the C++ ones are the weakest link).

## Recommendation

1. **Keep JSON-RPC + `Content-Length` on the wire. Do not adopt binary protobuf transport.** Driven
   by MCP + the on-disk contracts + tiny payloads, *not* by any VS Code limitation.
2. **Keep the current hand-mirror + fixture approach for now.** With two mirrors the fixture
   round-trip tests are cheap and the drift they'd catch is small. Codegen's build-complexity cost is
   not yet repaid.
3. **Gate adoption of a single-source IDL on the C++ mirror actually existing** — i.e. when the
   native renderer service from the RFC is built and there are genuinely three mirrors to keep in
   sync. That is the point where the per-mirror drift risk and the manual sync cost cross over the
   codegen setup cost. This matches RFC decision #5's "track for later".
4. **When that trigger hits, prefer a JSON-preserving IDL** — JSON-Schema codegen (B), or
   proto3-with-canonical-JSON-mapping (C) — over a binary wire swap, so the fixture corpus, the MCP
   hop, and the on-disk schemas all survive unchanged. Lean toward **JSON-Schema (B)** if the goal is
   one tool across *all* JSON surfaces; toward **proto3 (C)** if rename-safety on just the RPC
   payloads is the priority and a mixed generated/hand-authored world is acceptable.
5. **Whichever is chosen, the existing fixture corpus becomes the conformance suite** — generated
   types must round-trip the committed golden JSON, so the migration is incremental (generate one
   message family at a time, keep the fixtures green) rather than a big-bang flip that breaks the
   Kotlin ↔ TS contract.

## Migration sketch (only when the trigger fires)

A non-big-bang path that never breaks the fixture-locked Kotlin ↔ TS contract:

1. Author the IDL for **one** message family (e.g. `SpatialScene`, since it is the smallest and is
   the C++ entry point anyway). Generate Kotlin + TS + C++ from it.
2. Swap the hand-written `SpatialScene.kt` / `spatialScene.ts` for the generated types **behind the
   same package/exports**, and require the generated types to deserialize the existing committed
   spatial-scene fixture unchanged. Green fixtures = no wire change.
3. Repeat per message family in `Messages.kt` / `daemonProtocol.ts`. The fixture corpus is the
   ratchet: each family migrates independently, each PR keeps every fixture round-tripping.
4. Generation runs in the build; the generated sources are committed (so the repo stays readable and
   `grep`-able and the build doesn't hard-depend on a generator at every checkout) **or** generated
   at build time with a check task — decide at adoption time.

## Pilot notes (`SpatialScene`)

The migration sketch above was actioned for `SpatialScene`. Two things diverged from the naive plan
and are worth recording:

### Why a bespoke generator, not quicktype

A spike ran quicktype (one tool, Kotlin + TS + C++ from one JSON Schema). Its output did **not** hold
up as a drop-in for the existing published `preview-data-api` surface:

- **Kotlin:** re-cased `previewId` → `previewID`, emitted `version: Long` (not `Int`), turned
  `units`/`kind` into `enum class`es, dropped the `= emptyList()` / `= SPATIAL_SCENE_VERSION`
  defaults the producer and `SpatialSceneTest` rely on, and alphabetised fields. A drop-in swap would
  break the published API and its consumers.
- **C++:** pulled in a **Boost.Optional** dependency + `shared_ptr` boilerplate in a `quicktype`
  namespace — `xr-composite` is otherwise header-only (`json.hpp` + `stb`).
- **TS:** added `[property: string]: any` index signatures and replaced the string-literal unions.

So `scripts/codegen/gen-spatial-scene.mjs` is a ~300-line, dependency-free (Node stdlib) generator
that templates the schema into the *existing* idiomatic shapes: kotlinx data classes with their
defaults, TS string-literal unions + the hand `isSpatialScene` guard, and nlohmann `std::optional`
structs. Result: the generated mirrors are byte-shape-identical to the old hand-written ones, so
**zero consumer churn** and every fixture stayed green. The generated files are committed and
excluded from ktfmt/prettier (the generator is their sole formatter); the `--check` CI job is the
drift gate. The trade is maintaining a small bespoke generator vs. a stock tool's worse output — for
this small, controlled schema that is the right call. If the schema set grows a lot, revisit whether
a stock tool (with its churn) becomes worth it.

### Drift the codegen immediately surfaced

The C++ compositor was reading an `environment.glow` field that existed in **neither** the Kotlin nor
the TypeScript contract — a latent third-mirror drift exactly of the kind this issue is about. It is
now an additive optional field in the schema (hence in all three mirrors). This is the concrete
payoff that justified doing the pilot now rather than waiting: the single source caught a real gap
the moment it existed.

## Adjacent note (does not change this recommendation)

If independent release cadence for the ~150–200 MB version-keyed daemon runtime
(`compose-preview-android-daemon-<ver>.zip`) ever becomes a goal, the blocker is the
`protocolVersion` `{min,max}` range work ([ROADMAP_1_0](../ROADMAP_1_0.md) item 4), **not** the
serialization format. So it is orthogonal to the IDL question.
