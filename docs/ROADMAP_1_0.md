# 1.0 readiness — pre-release punch list

What has to land before we can credibly cut `1.0.0` and honour the contracts in [API_STABILITY.md](API_STABILITY.md) under the policy in [VERSIONING.md](VERSIONING.md). Roughly ordered by leverage.

Items marked **P0** are blockers. **P1** is "should land before 1.0 but a known-issues note is acceptable". **P2** is "nice to have at 1.0; can ship in 1.1".

## P0 — wire-format hardening

### 1. Tolerant enum decoding everywhere on the wire

**Problem.** Every `@Serializable enum` in `daemon/core/.../protocol/Messages.kt` strict-decodes by default. Adding `BackendKind.IOS`, `RecordingFormat.HEVC`, `UiMode.HIGH_CONTRAST`, etc. throws on every old client. This silently invalidates the "additive enum values are non-breaking" promise in `PROTOCOL.md § 7`.

**Affected enums.** `RenderTier`, `UiMode`, `Orientation`, `BackendKind`, `RecordingFormat`, `DataProductFacet`, `DataProductTransport`, `LogLevel`, `RenderErrorKind`, `ChangeType`, `FileKind`, `LeakDetectionMode`, `SandboxRecycleReason`, `WallpaperPaletteStyle`, `HistoryDiffMode`, `PruneReasonWire`, `InteractiveInputKind`, `RecordingScriptEventStatus`, `DetectLeaks`, `ClasspathDirtyReason`.

**Plan.**
- Add an `UNKNOWN` variant to each enum (or a sealed-interface alternative with `Known(kind)` / `Unknown(raw: String)`).
- Write a tolerant `KSerializer` factory and apply via a single `@Serializable(with = …)` annotation pattern.
- Same on the TypeScript side (`vscode-extension/src/daemon/daemonProtocol.ts`): decode unknown strings to `"unknown"` and document the contract.
- Add fixture corpus entries with synthetic future values for every enum to prove tolerance round-trips.
- Audit every `when (enum)` to add an explicit `else`.

### 2. Add `schemaVersion` to `previews.json`

**Problem.** No top-level version. The shape crosses three process boundaries (plugin → daemon → CLI/CI/VS Code). A silent rename has no detection mechanism.

**Plan.**
- `DiscoverPreviewsTask` writes `"schemaVersion": 1` at the top level.
- All readers (`PreviewIndex`, CLI `previews.json` parser, VS Code `previewRegistry.ts`) read the field and gate on it.
- Tolerant readers ignore unknown fields.
- Document the schema in `docs/PREVIEWS_JSON.md` (new) with the same additive-vs-breaking rules as the daemon protocol.

### 3. Add `schemaVersion` to `HistoryEntry` sidecar JSON

**Problem.** Same as previews.json. Read by daemon, MCP, and any third-party tool that consumes `compose-preview/main` baselines.

**Plan.** Mirror item 2 for `daemon/core/.../history/HistoryEntry.kt`. Document in `docs/daemon/HISTORY.md`.

### 4. Switch `initialize.protocolVersion` to a `{min, max}` range

**Problem.** Today's int handshake is fail-closed — when we ship `protocolVersion: 2` and a VS Code extension still on protocol 1 attaches, the daemon exits. We need the daemon to serve both for a window.

**Plan.**
- Wire shape becomes `{ "protocolVersion": { "min": 1, "max": 2 } }` on both sides; legacy int still accepted on the way in for one release.
- Both sides operate at `min(client.max, server.max)`, fail closed only if the ranges don't overlap.
- Document in `PROTOCOL.md § 3` and add fixtures.
- Daemon serves N-1 for one minor cycle after a bump.

## P0 — toolchain compatibility

### 5. Plugin `apply()` validates AGP/Kotlin/Compose versions

**Problem.** `RENDERER_COMPATIBILITY.md` documents the load-bearing tightrope; nothing enforces it. Consumers discover skew at render time with cryptic Robolectric errors.

**Plan.**
- Bake a compatibility table into the plugin (generated from RENDERER_COMPATIBILITY.md or hand-curated, but checked into source).
- `ComposePreviewPlugin.apply()` reads the resolved AGP version (and Kotlin/Compose where it can) and fails configuration with the specific message: "compose-preview X.Y supports AGP A.B–C.D; found E.F. See [link]".
- Out-of-range Kotlin / Compose versions print a warning, not a hard fail (we can't always detect them reliably).

### 6. Toolchain matrix in CI

**Problem.** We test against one toolchain. AGP/Compose/Kotlin minor bumps land every six weeks and can break the renderer in ways that don't surface until a consumer hits them.

**Plan.**
- New `.github/workflows/integration-matrix.yml` that runs `:samples:android:renderAllPreviews` and `:samples:cmp:renderAllPreviews` against the matrix corners: current, current-1, next-RC.
- Required for plugin releases; advisory on PRs.
- Document the matrix corners in [RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md).

## P0 — public API surface lock

### 7. Kotlin BCV on `:gradle-plugin` and `:preview-annotations`

**Problem.** No mechanism stops a refactor from silently retyping a `Property<Int>` to `Property<Long>` or removing a public function.

**Plan.**
- Apply `org.jetbrains.kotlinx.binary-compatibility-validator` to both modules.
- Generate the initial `api/` golden files from the current state.
- Required-green CI check on PR.
- Document opt-in `@Incubating` annotation usage so churning DSL stays out of the golden file.

### 8. Audit and tag the plugin DSL

**Problem.** Every public symbol on `PreviewExtension`, `PreviewExtensionsExtension`, `ResourcePreviewsExtension`, `DaemonExtension` is implicitly part of the contract. We haven't decided which.

**Plan.**
- Walk every public symbol and tag with `// API: stable` or `@Incubating`.
- Anything still under design at 1.0 is `@Incubating` (e.g. data products are stable; recording knobs may still be incubating).
- Write down the decision in [API_STABILITY.md § 5](API_STABILITY.md#5-stability-tags-in-code).

## P0 — CLI surface

### 9. Switch CLI to clikt (or equivalent)

**Problem.** `cli/src/main/kotlin/ee/schimke/composeai/cli/Args.kt` is a hand-rolled `flagValue` / `flagValuesAll` helper. No `--help` schema, no deprecation cycle, no machine-readable command catalog. A flag rename is a silent break for every CI script and agent.

**Plan.**
- Migrate `Main.kt` and every command to clikt.
- Stable `--help` output with parameter docs sourced from code.
- `compose-preview <cmd> --json-schema` (or `compose-preview commands --json`) for machine introspection.
- Deprecation infrastructure: warn-on-use for flags marked deprecated.
- Lock the public flag set in 1.0 RC.

### 10. Document CLI exit codes and `--json` output shapes

**Problem.** Several commands emit JSON to stdout (e.g. `compose-preview ls`, `compose-preview doctor`). Their shapes are de-facto contracts but undocumented.

**Plan.**
- New `docs/CLI.md` listing every subcommand, every flag, exit codes, and `--json` output schema.
- Treat the schemas as wire formats with their own `schemaVersion` if they're nontrivial.

## P0 — release process

### 11. Branch convention freeze

**Problem.** GH action defaults (`compose-preview/main` etc.) are part of the contract; PR comments embed permanent commit URLs on them. They must be frozen forever post-1.0.

**Plan.**
- Add a `[VERSIONING.md § 7](VERSIONING.md#7-branch-conventions-gh-actions)` reference to each `action.yml` that declares one of these defaults.
- CI guard that fails any PR that changes a branch-name default in `.github/actions/*/action.yml`.

### 12. CHANGELOG audit

**Problem.** Pre-1.0 minor bumps have carried breaking changes silently. Before cutting 1.0 we need a clean catalog of what's actually breaking from "today" to make sure nothing slips into the 1.0 RC.

**Plan.**
- Walk the CHANGELOG from the current minor backward; flag every breaking change since the last release that consumers might still be on.
- Fold the list into the 1.0 announcement under "migrating from pre-1.0".

## P1 — should-land

### 13. MCP tool name freeze + deprecation infrastructure

**Plan.**
- List every MCP tool name in `docs/daemon/MCP.md` and tag stable vs. experimental.
- Add deprecation infrastructure to `DaemonMcpServer` (description prefix, optional `_deprecated: true` in tool metadata even though it's not standard MCP).
- Test that `tools/list_changed` fires when a tool is added/removed.

### 14. VS Code extension supports daemon `protocolVersion` N..N-1

**Plan.**
- Once item 4 lands, the extension can negotiate the lower of its supported max and the daemon's max.
- Add a `daemonProtocolSupported.ts` table with per-version capability detection.
- Test against a fixture daemon that pretends to be N-1 and N.

### 15. Fixture corpus completeness

**Problem.** `docs/daemon/protocol-fixtures/` has decent coverage but isn't enforced as a gate. Some new messages (recording, history) may have landed without fixtures.

**Plan.**
- Audit every message type in `Messages.kt`; verify each has at least one fixture.
- CI guard that fails if a `@Serializable` data class is added in `protocol/` without a fixture.

### 16. `composePreview doctor` covers compatibility checks

**Plan.**
- Extend `DoctorCommand` to print the resolved AGP/Kotlin/Compose/Robolectric versions and gate them against the matrix.
- Surface in VS Code as a diagnostic.

### 17. Per-data-product schema documentation

**Plan.**
- Each `data/<feature>/connector/` documents its `schemaVersion`, payload shape, and additive-vs-breaking rules in a sibling `README.md` or in `docs/daemon/DATA-PRODUCTS.md`.
- Producers carry a constant `SCHEMA_VERSION` that the connector publishes via `DataProductCapability`.

## P1 — annotation library

### 18. Annotation library binary-compatibility check

**Plan.**
- Same BCV setup as item 7 but explicitly tested for annotation-array-default reordering (which BCV doesn't catch — write a custom check).

### 19. Document the migration from pre-0.7 single-mode `ScrollingPreview`

**Plan.**
- Already partially documented in code; promote to `docs/migration/SCROLLING_PREVIEW.md` if any 0.x consumers will see 1.0.

## P2 — defer to 1.1

- **Daemon `semver` range capabilities.** Today the daemon advertises one daemonVersion. Could become a richer compatibility surface, but capability bag covers the immediate need.
- **Stable plugin SPI.** `PreviewOverrideExtensions` etc. are open under the additive rules but aren't a documented public API for third-party plugin authors. Deferred.
- **Daemon multi-client support.** Architecture explicitly out of scope for v1.
- **`previews.json` schema migration.** Once `schemaVersion` exists, write a migration helper for v1→v2 — but only when a v2 actually lands.

## Tracking

Each item above should become a GH issue with a `1.0-blocker` (P0) or `1.0-target` (P1) label. The 1.0 release-please milestone gates on every P0 closing.
