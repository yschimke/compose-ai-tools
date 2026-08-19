# API stability — design

Cross-cutting design doc for what counts as a public contract in this repo, how each contract evolves, and how the two sides of each contract negotiate compatibility. Companion to [VERSIONING.md](VERSIONING.md) (the policy).

## 1. The contracts

Nine externally-observable surfaces, each with its own evolution story. Anything **not** in this list is internal and may move without notice — with the caveat below, and read alongside [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced), which records how much of the machinery described here is actually implemented.

> **The list is not exhaustive.** At least two versioned formats are exchanged with detached consumers without appearing above: `bundle.json` inside a packed preview bundle (read by the CLI and the bundle viewer, carrying `BUNDLE_SCHEMA_VERSION`) and `daemon-launch.json` from the published `daemon-launch-builder` (read by VS Code's `daemonProcess.ts`, which requires `schemaVersion` to equal its own exactly and otherwise discards the descriptor and forces a re-run, and by the non-Gradle integrations in [NON_GRADLE_INTEGRATION.md](NON_GRADLE_INTEGRATION.md)). Both carry their own schema version and both would strand existing artifacts or producers if changed carelessly. Treat "not in this list" as "not yet classified", not as licence to break them.

| # | Surface | Lives in | Consumed by | Stability tier |
|---|---|---|---|---|
| 1 | Daemon JSON-RPC over stdio | `daemon/core/.../protocol/Messages.kt`, [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md) | VS Code extension, MCP supervisor, any future client | Stable |
| 2 | `previews.json` on disk | `gradle-plugin/.../DiscoverPreviewsTask.kt` | Daemon, CLI, CI workflows | Stable |
| 3 | Per-data-product payload schemas | `data/<feature>/connector/`, [docs/daemon/DATA-PRODUCTS.md](daemon/DATA-PRODUCTS.md) | Daemon clients (versioned per-kind) | Stable per-kind |
| 4 | Gradle plugin DSL (`composePreview { … }`) | `gradle-plugin/gradle-plugin-config/.../PreviewExtension.kt` — published as `ee.schimke.composeai:compose-preview-config` | Consumer `build.gradle.kts` | Stable |
| 5 | AGP × Kotlin × Compose × Robolectric matrix | [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md), [docs/AGENTS.md](AGENTS.md) | Consumers' transitive resolution | Documented, gated |
| 6 | Preview annotations (`@ScrollingPreview`, `@AnimatedPreview`, `@SettledPreview`) | `api/preview-annotations/` | Consumer source code | Stable |
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

**Test:** the JSON fixture corpus under [docs/daemon/protocol-fixtures/](daemon/protocol-fixtures/). Kotlin round-trips it; **TypeScript does not** — `daemonProtocol.test.ts` parses with `JSON.parse(...) as T` and asserts selected properties on a subset of the files, so an unasserted field can drift. Adding a message ⇒ add the fixture in the same PR (nothing enforces this — see [VERSIONING.md § 9](VERSIONING.md#9-compatibility-testing)). Renaming a field ⇒ either bump `protocolVersion` or revert.

### 2.2 `previews.json` (surface 2)

**Versioning:** intended to be a top-level `schemaVersion: int`, but **the field is not written today** — neither the daemon's nor the viewer's `PreviewManifest` carries one, so a reader cannot tell which shape it has. What is real is tolerant reading: unknown fields ignored, missing optional fields defaulted.

**Negotiation:** none in-band, and — with no `schemaVersion` written — **none possible**. The plugin and daemon ship in lockstep, which is what makes this work today; the CLI / VS Code extension treat `previews.json` as opaque except for fields they explicitly model and ignore what they don't understand. A reader cannot detect a shape change, so lockstep is load-bearing rather than belt-and-braces.

**Additive change is free:** new optional fields, new optional metadata blocks.

**Breaking change:** until the `schemaVersion` field exists there is nothing to bump, so a shape change relies entirely on plugin and daemon shipping together — bump the daemon `protocolVersion` in the same release so a stale client fails the handshake instead of misreading the manifest. Add the carrier first if you need a change readers can actually detect.

### 2.3 Data products (surface 3)

Each kind owns its own `schemaVersion: Int`. Producers evolve independently of the envelope. A client subscribing to `compose/recomposition` schemaVersion 2 against a daemon that only produces schemaVersion 1 receives the schemaVersion-1 payload — the client either degrades gracefully or refuses based on its own logic.

`DataProductCapability` advertises the schema version the daemon supports. Clients gate against it; they don't fail closed on a mismatch unless their feature genuinely requires the newer version.

### 2.4 Gradle plugin DSL (surface 4)

**Where the DSL lives.** Not in `:gradle-plugin`. `PreviewExtension` / `DaemonExtension` are in the separately published `:gradle-plugin-config` (`ee.schimke.composeai:compose-preview-config`, plugin id `…preview.config`), which the runtime plugin `api`-depends on. That artifact is the **load-bearing** one for compatibility: a consumer pins the config plugin while the CLI injects the runtime plugin at its own version, and Gradle conflict-resolves the two to a single version on the buildscript classpath. It is the artifact any binary-compatibility gate should target first — see [AGENTS.md](AGENTS.md) on the config-only plugin.

**Stability tiers.** As of 1.0.0 there are two, not three — the DSL has no opt-in tier, which makes the surface *stricter* than the scheme below, not looser:
- Public — semver-governed. Property type changes, removals, and renames are breaking changes that bump the plugin major. **Enforced by review, not by tooling** — no ABI gate is wired up on `:gradle-plugin-config` ([VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)). The only module set that has one is the Remote Compose player stack (§ 5).
- Internal — `internal` Kotlin visibility. No contract.

The `@Stable` / `@Incubating` split, with `@Incubating` opt-in via `composePreview { incubating = true }` and a warning at apply time, is **not in 1.0.0** — it lands in a later `1.x` (see [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)). Until it does, a new DSL property is public and semver-governed the moment it ships, so add one only when you're willing to keep it.

**Negotiation:** none. Gradle resolves the plugin coordinate. `apply()` checks the Gradle version only — see § 2.5 for what it does not check.

**Additive change is free:** new `Property<T>` with a `convention(...)`, new nested extension blocks, new enum values on properties (because Gradle doesn't strict-decode user input).

**Breaking change:** retype, rename, or remove. Goes through the deprecation cycle in [VERSIONING.md § 5](VERSIONING.md#5-deprecation-policy).

**Enforced by:** review. Kotlin BCV is named throughout these docs as the gate, but it is not configured on this module ([VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)). The player stack (§ 5) shows what wiring it looks like.

### 2.5 Toolchain matrix (surface 5)

**Negotiation:** intended to be `apply()` reading the resolved AGP version (and, where it can, Kotlin and Compose) and failing out-of-range consumers with "compose-preview X.Y supports AGP A.B–C.D; found E.F". **Not implemented** — `apply()` gates the Gradle version and nothing else, so an unsupported toolchain surfaces as whatever error it happens to produce.

**Documented:** nowhere, as a matrix. [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) is titled "Renderer compatibility notes" and is exactly that — skew failure modes, mitigations, and desktop Compose/Skiko floors — not a table of supported AGP × Kotlin × Compose × Robolectric combinations. Nothing generates one, and `apply()` consults nothing; `compose-preview doctor` is the closest thing and it reports rather than gates.

**Tested:** the `integration` workflow runs sample renders on every PR — against fixtures (including an `agp8-min` floor) and moving external `main` refs, **not** pinned current / current-1 / next-RC corners. It catches real breakage; it does not prove matrix coverage.

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
- **No automatic enum-value conversion.** Where tolerant decode exists, unknown maps to `UNKNOWN` and the client decides what to do. Most wire enums don't have it yet (§ 2.1), so today an unknown value throws instead.

## 5. Stability tags in code

The intended scheme — each public type carrying one of these tags — is **not applied as of 1.0.0**; it lands in a later `1.x` (see [VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)):

- `// API: stable` — semver-governed.
- `// API: incubating` — opt-in, may change.
- No tag — internal; do not depend on.

Kotlin BCV is intended to run on the plugin module and the annotations module. **It is not configured on either** ([VERSIONING.md § 10](VERSIONING.md#10-what-is-and-is-not-enforced)), so no tooling currently catches an API break there. The daemon protocol would be governed by the fixture corpus rather than BCV in any case, since internal types may move freely as long as the wire shape is stable.

### 5.1 The one module set that is gated — the Remote Compose player

`:rc-player-trace`, `:rc-player-protocol`, `:rc-player-runtime` and `:rc-player-compose` are the first modules in this repo with a mechanical API gate. Two pieces, both in each module's `build.gradle.kts`:

- **`explicitApi()`** — a missing visibility modifier or a missing public return type is a compile error, so a declaration cannot become public by omission. The player modules were already written this way; this makes it enforced rather than habitual.
- **`abiValidation()`** — Kotlin's own ABI validation, built into the Kotlin Gradle plugin since 2.2 (still `@ExperimentalAbiValidation` at 2.4), so it needs no extra plugin on the buildscript classpath. It writes two dumps per module under `<module>/api/`: `<module>.api` for the JVM target and `<module>.klib.api` covering the klib targets (iOS + `wasmJs`) together. `checkKotlinAbi` diffs the real ABI against them and is wired into `check`; regenerate with `./gradlew :rc-player-<module>:updateKotlinAbi`.

**Why here first rather than repo-wide.** The player stack is the surface actively being reshaped ahead of its first release, so it is where an unrecorded change costs the most, and — unlike an Android library — its dumps need no AGP variant wiring. The klib dump only builds on macOS, so `checkKotlinAbi` runs in the `rc-player-tests` CI job (the repo's one macOS job) rather than in the general `check`. Extending the same two lines to `:gradle-plugin-config` is the obvious next step and is not blocked by anything here.

## 6. References

- [VERSIONING.md](VERSIONING.md) — the policy that operationalises this design.
- [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md) — wire format.
- [docs/daemon/DATA-PRODUCTS.md](daemon/DATA-PRODUCTS.md) — per-kind schemas.
- [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — toolchain matrix.
- [docs/RELEASING.md](RELEASING.md) — release-please mechanics.
