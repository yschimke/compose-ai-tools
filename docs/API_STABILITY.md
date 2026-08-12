# API stability — design

Cross-cutting design doc for what counts as a public contract in this repo, how each contract evolves, and how the two sides of each contract negotiate compatibility. Companion to [VERSIONING.md](VERSIONING.md) (the policy).

## 1. The contracts

Nine externally-observable surfaces, each with its own evolution story. Anything **not** in this list is internal and may move without notice — with the caveat below, and read alongside [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced), which records how much of the machinery described here is actually implemented.

> **The list is not exhaustive.** At least two versioned formats are exchanged with detached consumers without appearing above: `bundle.json` inside a packed preview bundle (read by the CLI and the bundle viewer, carrying `BUNDLE_SCHEMA_VERSION`) and `daemon-launch.json` from the published `daemon-launch-builder` (read by VS Code's `daemonProcess.ts`, which gates on its `schemaVersion`, and by the non-Gradle integrations in [NON_GRADLE_INTEGRATION.md](NON_GRADLE_INTEGRATION.md)). Both carry their own schema version and both would strand existing artifacts or producers if changed carelessly. Treat "not in this list" as "not yet classified", not as licence to break them.

| # | Surface | Lives in | Consumed by | Stability tier |
|---|---|---|---|---|
| 1 | Daemon JSON-RPC over stdio | `daemon/core/.../protocol/Messages.kt`, [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md) | VS Code extension, MCP supervisor, any future client | Stable |
| 2 | `previews.json` on disk | `gradle-plugin/.../DiscoverPreviewsTask.kt` | Daemon, CLI, CI workflows | Stable |
| 3 | Per-data-product payload schemas | `data/<feature>/connector/`, [docs/daemon/DATA-PRODUCTS.md](daemon/DATA-PRODUCTS.md) | Daemon clients (versioned per-kind) | Stable per-kind |
| 4 | Gradle plugin DSL (`composePreview { … }`) | `gradle-plugin/.../PreviewExtension.kt` | Consumer `build.gradle.kts` | Stable |
| 5 | AGP × Kotlin × Compose × Robolectric matrix | [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md), [docs/AGENTS.md](AGENTS.md) | Consumers' transitive resolution | Documented, gated |
| 6 | Preview annotations (`@ScrollingPreview`, `@AnimatedPreview`) | `api/preview-annotations/` | Consumer source code | Stable |
| 7 | CLI argv (`compose-preview …`) | `cli/.../Args.kt`, `cli/.../Main.kt` | CI scripts, agents, GH actions | Stable |
| 8 | MCP tool names + input schemas | `mcp/.../DaemonMcpServer.kt` | External AI agents | Stable |
| 9 | GH composite actions + `compose-preview/main` branch convention | `.github/actions/*/action.yml` | Consumer workflows | Stable |

The annotation library and per-data-product schemas are intentionally narrow. The other six surfaces are wide and the discipline below is what keeps them tractable.

## 2. Evolution mechanism per surface

### 2.1 Daemon JSON-RPC (surface 1)

**Negotiation:** `initialize` request carries a single `protocolVersion: Int`; the daemon answers with its own plus a `ServerCapabilities` bag, and any mismatch is `InvalidRequest` and the daemon exits. Range negotiation — client and daemon each sending `{min, max}` and operating at `min(client.max, server.max)` — is **not in 1.0.0**; it lands in a later `1.x` (see [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)).

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

**Enums.** Wire enums *should* decode tolerantly — unknown values mapping to an `UNKNOWN` sentinel rather than throwing — because without it every new enum value is a silent break for old clients. **Most existing enums in `Messages.kt` do not do this yet** (`FileKind` and `ChangeType` among them), so treat it as the rule for enums you add, not as a property of the current corpus. See [VERSIONING.md § 4.1](VERSIONING.md#41-enum-discipline).

**Test:** the JSON fixture corpus under [docs/daemon/protocol-fixtures/](daemon/protocol-fixtures/) round-trips on both Kotlin and TypeScript sides. Adding a message ⇒ add the fixture in the same PR. Renaming a field ⇒ either bump `protocolVersion` or revert.

### 2.2 `previews.json` (surface 2)

**Versioning:** intended to be a top-level `schemaVersion: int`, but **the field is not written today** — neither the daemon's nor the viewer's `PreviewManifest` carries one, so a reader cannot tell which shape it has. What is real is tolerant reading: unknown fields ignored, missing optional fields defaulted.

**Negotiation:** none in-band. The plugin and daemon ship in lockstep; the CLI / VS Code extension treat `previews.json` as opaque except for fields they explicitly model. Any reader that crosses the process boundary keys off `schemaVersion` and ignores fields it doesn't understand.

**Additive change is free:** new optional fields, new optional metadata blocks.

**Breaking change:** bump `schemaVersion` and bump the daemon `protocolVersion` in the same release — clients should never see the new shape against an old daemon.

### 2.3 Data products (surface 3)

Each kind owns its own `schemaVersion: Int`. Producers evolve independently of the envelope. A client subscribing to `compose/recomposition` schemaVersion 2 against a daemon that only produces schemaVersion 1 receives the schemaVersion-1 payload — the client either degrades gracefully or refuses based on its own logic.

`DataProductCapability` advertises the schema version the daemon supports. Clients gate against it; they don't fail closed on a mismatch unless their feature genuinely requires the newer version.

### 2.4 Gradle plugin DSL (surface 4)

**Stability tiers.** As of 1.0.0 there are two, not three — the DSL has no opt-in tier, which makes the surface *stricter* than the scheme below, not looser:
- Public — semver-governed. Property type changes, removals, and renames are breaking changes that bump the plugin major. **Enforced by review, not by tooling** — Kotlin BCV is not wired up on this module ([VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)).
- Internal — `internal` Kotlin visibility. No contract.

The `@Stable` / `@Incubating` split, with `@Incubating` opt-in via `composePreview { incubating = true }` and a warning at apply time, is **not in 1.0.0** — it lands in a later `1.x` (see [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)). Until it does, a new DSL property is public and semver-governed the moment it ships, so add one only when you're willing to keep it.

**Negotiation:** none. Gradle resolves the plugin coordinate. `apply()` checks the Gradle version only — see § 2.5 for what it does not check.

**Additive change is free:** new `Property<T>` with a `convention(...)`, new nested extension blocks, new enum values on properties (because Gradle doesn't strict-decode user input).

**Breaking change:** retype, rename, or remove. Goes through the deprecation cycle in [VERSIONING.md § 5](VERSIONING.md#5-deprecation-policy).

**Enforced by:** review. Kotlin BCV is named throughout these docs as the gate, but it is not configured on this module ([VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)).

### 2.5 Toolchain matrix (surface 5)

**Negotiation:** intended to be `apply()` reading the resolved AGP version (and, where it can, Kotlin and Compose) and failing out-of-range consumers with "compose-preview X.Y supports AGP A.B–C.D; found E.F". **Not implemented** — `apply()` gates the Gradle version and nothing else, so an unsupported toolchain surfaces as whatever error it happens to produce.

**Documented:** [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) collects the known-good combinations and skew notes. Nothing generates a table from it, and `apply()` does not consult it; `compose-preview doctor` is the closest thing, and it reports rather than gates.

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

**Negotiation:** `compose-preview --version`. The `compose-preview <cmd> --json-schema` capability probe is **not in 1.0.0** — it lands in a later `1.x` (see [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)); until then CI and agents read `--help`.

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
| VS Code extension ↔ daemon | **Intended:** extension supports daemon `protocolVersion` N..N-1, because marketplace and project cadences diverge. **Today:** lockstep — see below | |
| CLI ↔ plugin | Lockstep within a project | Mitigated by `version=catalog` |
| Daemon ↔ in-repo plugin | Lockstep | Same build |
| MCP server ↔ agent | Server keeps tool names stable indefinitely | Agents pin names in config |
| GH action consumers | All published actions remain functional indefinitely | SHA-pinned references in workflows |
| Plugin DSL consumers | Major-N plugin supports DSL written for major-N source | Standard Gradle plugin semver |

The VS Code N..N-1 window is the only place we would decode two protocol versions in the same binary — and it **does not work yet**. `daemonProtocol.ts` exports a single hard-coded `PROTOCOL_VERSION`, and `JsonRpcServer.handleInitialize` rejects any value but its own, so a marketplace extension one version behind the project's daemon fails the handshake rather than degrading. That is the exact staggered-release case the window exists for; bumping `protocolVersion` today means a coordinated release, not a supported skew. Everywhere else we ship in lockstep or freeze the contract.

## 4. What this design explicitly does not do

- **No daemon `semver` checks at the protocol layer.** Capability bag only. `daemonVersion` is for logs and bug reports.
- **No multiplexing.** One daemon, one client, one stdio pair.
- **No on-the-wire schema migration.** Old client + new daemon → daemon serves the old protocol if it's in range; otherwise fail closed.
- **No silent flag renames in CLI or DSL.** Every rename is a deprecation cycle.
- **No automatic enum-value conversion.** Tolerant decode maps unknown to `UNKNOWN`; clients decide what to do.

## 5. Stability tags in code

The intended scheme — each public type carrying one of these tags — is **not applied as of 1.0.0**; it lands in a later `1.x` (see [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)):

- `// API: stable` — semver-governed.
- `// API: incubating` — opt-in, may change.
- No tag — internal; do not depend on.

Kotlin BCV runs on the plugin module and the annotations module. The daemon protocol is governed by the fixture corpus, not BCV (because internal types may move freely as long as the wire shape is stable).

## 6. References

- [VERSIONING.md](VERSIONING.md) — the policy that operationalises this design.
- [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md) — wire format.
- [docs/daemon/DATA-PRODUCTS.md](daemon/DATA-PRODUCTS.md) — per-kind schemas.
- [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — toolchain matrix.
- [docs/RELEASING.md](RELEASING.md) — release-please mechanics.
