# API stability — design

Cross-cutting design doc for what counts as a public contract in this repo, how each contract evolves, and how the two sides of each contract negotiate compatibility. Companion to [VERSIONING.md](VERSIONING.md) (the policy) and [ROADMAP_1_0.md](ROADMAP_1_0.md) (the pre-1.0 punch list).

## 1. The contracts

Nine externally-observable surfaces, each with its own evolution story. Anything **not** in this list is internal and may move without notice.

| # | Surface | Lives in | Consumed by | Stability tier |
|---|---|---|---|---|
| 1 | Daemon JSON-RPC over stdio | `daemon/core/.../protocol/Messages.kt`, [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md) | VS Code extension, MCP supervisor, any future client | Stable |
| 2 | `previews.json` on disk | `gradle-plugin/.../DiscoverPreviewsTask.kt` | Daemon, CLI, CI workflows | Stable |
| 3 | Per-data-product payload schemas | `data/<feature>/connector/`, [docs/daemon/DATA-PRODUCTS.md](daemon/DATA-PRODUCTS.md) | Daemon clients (versioned per-kind) | Stable per-kind |
| 4 | Gradle plugin DSL (`composePreview { … }`) | `gradle-plugin/.../PreviewExtension.kt` | Consumer `build.gradle.kts` | Stable |
| 5 | AGP × Kotlin × Compose × Robolectric matrix | [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md), [docs/AGENTS.md](AGENTS.md) | Consumers' transitive resolution | Documented, gated |
| 6 | Preview annotations (`@ScrollingPreview`, `@AnimatedPreview`) | `preview-annotations/` | Consumer source code | Stable |
| 7 | CLI argv (`compose-preview …`) | `cli/.../Args.kt`, `cli/.../Main.kt` | CI scripts, agents, GH actions | Stable |
| 8 | MCP tool names + input schemas | `mcp/.../DaemonMcpServer.kt` | External AI agents | Stable |
| 9 | GH composite actions + `compose-preview/main` branch convention | `.github/actions/*/action.yml` | Consumer workflows | Stable |

The annotation library and per-data-product schemas are intentionally narrow. The other six surfaces are wide and the discipline below is what keeps them tractable.

## 2. Evolution mechanism per surface

### 2.1 Daemon JSON-RPC (surface 1)

**Negotiation:** `initialize` request carries `protocolVersion: {min, max}` (post-1.0; today a single `int`). The daemon answers with its own `{min, max}` plus a `ServerCapabilities` bag. Both sides operate at `min(client.max, server.max)`. Mismatched ranges → `InvalidRequest`, daemon exits.

**Feature detection:** capability bag, never daemon `semver`. The bag already covers `supportedOverrides`, `dataProducts`, `dataExtensions`, `previewExtensions`, `knownDevices`, `backend`, `androidSdk`, `recordingFormats`, `interactive`, `recording`. New features add a capability entry, not a behavior change under an existing field.

**Additive change is free:**
- New optional fields on existing message types.
- New notification methods.
- New request methods (clients gate on a capability flag before calling).
- New error codes in the reserved `-32000..-32099` range.

**Breaking change requires `protocolVersion` bump:**
- Renamed or removed fields.
- Field-meaning changes.
- New required fields.
- Changed default values that flip behavior.

**Enums.** Every `@Serializable enum` on the wire is decoded tolerantly: unknown values map to an `UNKNOWN` sentinel rather than throwing. Without this, every new enum value is a silent break for old clients. See [VERSIONING.md § 4.1](VERSIONING.md#41-enum-discipline).

**Test:** the JSON fixture corpus under [docs/daemon/protocol-fixtures/](daemon/protocol-fixtures/) round-trips on both Kotlin and TypeScript sides. Adding a message ⇒ add the fixture in the same PR. Renaming a field ⇒ either bump `protocolVersion` or revert.

### 2.2 `previews.json` (surface 2)

**Versioning:** top-level `schemaVersion: int`. Tolerant readers everywhere — unknown fields ignored, missing optional fields treated as default.

**Negotiation:** none in-band. The plugin and daemon ship in lockstep; the CLI / VS Code extension treat `previews.json` as opaque except for fields they explicitly model. Any reader that crosses the process boundary keys off `schemaVersion` and ignores fields it doesn't understand.

**Additive change is free:** new optional fields, new optional metadata blocks.

**Breaking change:** bump `schemaVersion` and bump the daemon `protocolVersion` in the same release — clients should never see the new shape against an old daemon.

### 2.3 Data products (surface 3)

Each kind owns its own `schemaVersion: Int`. Producers evolve independently of the envelope. A client subscribing to `compose/recomposition` schemaVersion 2 against a daemon that only produces schemaVersion 1 receives the schemaVersion-1 payload — the client either degrades gracefully or refuses based on its own logic.

`DataProductCapability` advertises the schema version the daemon supports. Clients gate against it; they don't fail closed on a mismatch unless their feature genuinely requires the newer version.

### 2.4 Gradle plugin DSL (surface 4)

**Stability tiers** (post-1.0):
- `@Stable` — public, semver-governed. Property type changes, removals, and renames are breaking changes that bump the plugin major.
- `@Incubating` — public but explicitly opt-in via `composePreview { incubating = true }`. May change in any release; warning at apply time.
- Internal — `internal` Kotlin visibility. No contract.

**Negotiation:** none. Gradle resolves the plugin coordinate; `apply()` validates the AGP/Kotlin/Compose versions present and either accepts or fails closed with a specific error. See § 2.5.

**Additive change is free:** new `Property<T>` with a `convention(...)`, new nested extension blocks, new enum values on properties (because Gradle doesn't strict-decode user input).

**Breaking change:** retype, rename, or remove. Goes through the deprecation cycle in [VERSIONING.md § 5](VERSIONING.md#5-deprecation-policy).

**Enforced by:** `binary-compatibility-validator` (Kotlin BCV) on the plugin's Kotlin API.

### 2.5 Toolchain matrix (surface 5)

**Negotiation:** plugin `apply()` reads the resolved AGP version (and, where it can, Kotlin and Compose runtime versions) and compares against a baked-in compatibility table. Out-of-range → fail with the exact message "compose-preview X.Y supports AGP A.B–C.D; found E.F".

**Documented:** [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) is the authoritative table. `apply()` consults a generated subset of it.

**Tested:** an `integration` workflow runs the sample renders against the matrix corners (current, current-1, next-RC) on every plugin release.

**Evolution rule:** the matrix is allowed to slide. A plugin minor may drop support for an AGP version older than 18 months. A plugin major may drop two AGP majors at once. Any drop is documented in CHANGELOG with the exact "from X.Y you must be on AGP ≥ A.B".

### 2.6 Preview annotations (surface 6)

`@Retention(BINARY)`, FQN-stable, additive-only. Optional parameters with defaults are free. **Reordering** an annotation array literal default (e.g. `modes: Array<ScrollMode>`) is binary-breaking — array values are positional in Kotlin annotation defaults. Don't reorder.

Renaming a parameter is breaking. Adding a new annotation class is free.

The annotation library has no negotiation surface — the plugin's discovery task scans by FQN and accepts whatever annotation values are present.

### 2.7 CLI argv (surface 7)

**Stability:** flag names, subcommand names, and their argument shapes are public.

**Output contracts:** any subcommand documented as machine-readable (`--json` outputs, exit codes) is part of the contract.

**Deprecation:** flags follow the cycle in [VERSIONING.md § 5](VERSIONING.md#5-deprecation-policy) — warn for two minors, then remove.

**Negotiation:** `compose-preview --version` and `compose-preview <cmd> --json-schema` (post-1.0) let CI and agents detect capability without parsing `--help`.

### 2.8 MCP tool names (surface 8)

**Stability:** tool names, input schemas, and output content shapes are public.

**Negotiation:** MCP-spec `initialize` + `tools/list`. Agents are expected to re-list on `tools/list_changed`, but in practice agent configs hardcode names — so we treat names as immortal post-1.0.

**Evolution rule:** add new tools rather than mutating old ones. When a tool is genuinely deprecated, keep it functional for the deprecation window and emit a warning in its description; remove only at a major.

### 2.9 GH actions + branch conventions (surface 9)

**Stability:** action `inputs:`, their default values, and the default branch names (`compose-preview/main`, `compose-preview/pr`, `compose-preview/resources/main`, `compose-preview/resources/pr`, `compose-preview/a11y/main`, `compose-preview/a11y/pr`).

**Negotiation:** none. Consumers pin actions by SHA digest and pin the CLI via `version=catalog`.

**Evolution rule:** input defaults are frozen post-1.0. New inputs default to "preserve existing behavior". Branch-name defaults are frozen forever — past PR comments embed permanent commit URLs on these branches.

## 3. Multi-version support window

| Pair | Window | Rationale |
|---|---|---|
| VS Code extension ↔ daemon | Extension supports daemon `protocolVersion` N..N-1 | Marketplace and project cadences diverge |
| CLI ↔ plugin | Lockstep within a project | Mitigated by `version=catalog` |
| Daemon ↔ in-repo plugin | Lockstep | Same build |
| MCP server ↔ agent | Server keeps tool names stable indefinitely | Agents pin names in config |
| GH action consumers | All published actions remain functional indefinitely | SHA-pinned references in workflows |
| Plugin DSL consumers | Major-N plugin supports DSL written for major-N source | Standard Gradle plugin semver |

The VS Code N..N-1 window is the only place we actively decode two protocol versions in the same binary. Everywhere else we either ship in lockstep or freeze the contract.

## 4. What this design explicitly does not do

- **No daemon `semver` checks at the protocol layer.** Capability bag only. `daemonVersion` is for logs and bug reports.
- **No multiplexing.** One daemon, one client, one stdio pair.
- **No on-the-wire schema migration.** Old client + new daemon → daemon serves the old protocol if it's in range; otherwise fail closed.
- **No silent flag renames in CLI or DSL.** Every rename is a deprecation cycle.
- **No automatic enum-value conversion.** Tolerant decode maps unknown to `UNKNOWN`; clients decide what to do.

## 5. Stability tags in code

Post-1.0 each public type carries one of:

- `// API: stable` — semver-governed.
- `// API: incubating` — opt-in, may change.
- No tag — internal; do not depend on.

Kotlin BCV runs on the plugin module and the annotations module. The daemon protocol is governed by the fixture corpus, not BCV (because internal types may move freely as long as the wire shape is stable).

## 6. References

- [VERSIONING.md](VERSIONING.md) — the policy that operationalises this design.
- [ROADMAP_1_0.md](ROADMAP_1_0.md) — what has to land before 1.0 to make the contracts above real.
- [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md) — wire format.
- [docs/daemon/DATA-PRODUCTS.md](daemon/DATA-PRODUCTS.md) — per-kind schemas.
- [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — toolchain matrix.
- [docs/RELEASING.md](RELEASING.md) — release-please mechanics.
