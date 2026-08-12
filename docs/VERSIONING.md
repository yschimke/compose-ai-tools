# Versioning policy

Operational policy for evolving the contracts described in [API_STABILITY.md](API_STABILITY.md). This document is normative for everyone landing changes that touch a public surface.

## 1. Versioning schemes

Different surfaces use different schemes — pick the right one for the contract:

| Surface | Scheme | Carrier |
|---|---|---|
| Gradle plugin (`ee.schimke.composeai.preview`) | Semver | Maven Central coordinate version |
| CLI (`compose-preview`) | Semver | GitHub release tag, `compose-preview --version` |
| VS Code extension | Semver | VSIX manifest |
| MCP server | Semver | Tied to CLI release |
| Annotation library (`preview-annotations`) | Semver | Maven Central coordinate |
| Daemon JSON-RPC protocol | Integer | `protocolVersion` in `initialize` |
| `previews.json` | Integer | `schemaVersion` field |
| Per-data-product payload | Integer | `schemaVersion` per `DataProductCapability` |
| `HistoryEntry` sidecar JSON | Integer | `schemaVersion` field |
| `bundle.json` in a packed preview bundle | Integer | `schemaVersion` field (`BUNDLE_SCHEMA_VERSION`) |
| `daemon-launch.json` from `daemon-launch-builder` | Integer | `schemaVersion` field |
| GH composite actions | Semver via tag/SHA | Consumer `uses:` ref |

Plugin / CLI / extension share one release-please-driven semver chain (see [RELEASING.md](RELEASING.md)). Protocol and schema integers are independent.

> **Two of those carriers don't exist yet.** `previews.json` and the `HistoryEntry` sidecar are both listed above as carrying a `schemaVersion` field, and neither actually writes one. The per-data-product and bundle schemas do. See § 10.

## 2. Semver rules for the published artifacts

For plugin, CLI, extension, MCP, annotations:

- **Major** — breaking change to any public contract on the artifact. See § 3.
- **Minor** — additive change: new feature, new flag, new DSL property, new annotation, new CLI subcommand, new MCP tool. New optional fields on existing types.
- **Patch** — bug fix with no contract change. No new public surface.

Through the `0.x` line, minor bumps could carry breaking changes with a clear note in CHANGELOG. From `1.0.0` they may not — see § 10.

## 3. What counts as breaking

By surface:

- **Plugin DSL** — removing or renaming a `Property<T>`, retyping it, removing a nested extension, removing an enum value, changing a `convention(...)` default in a way that flips semantics for an existing user, raising the supported AGP/Kotlin/Compose floor outside the matrix-slide rule (§ 6).
- **CLI** — removing or renaming a flag or subcommand, changing exit codes, changing `--json` output shape (other than additive fields), changing default behavior of an existing flag.
- **MCP** — removing a tool, renaming a tool, narrowing an input schema, changing the meaning of an existing input or output field.
- **Annotation library** — removing a parameter, renaming a parameter, retyping a parameter, reordering an annotation array literal default.
- **Daemon protocol** — anything outside the additive list in [API_STABILITY.md § 2.1](API_STABILITY.md#21-daemon-json-rpc-surface-1).
- **`previews.json` / `HistoryEntry` schemas** — same as the protocol: removed/renamed fields, semantic changes.
- **`bundle.json` / `daemon-launch.json`** — same again, and these two matter more than their absence from [API_STABILITY.md § 1](API_STABILITY.md#1-the-contracts) suggests: a packed bundle outlives the CLI that wrote it, and `daemon-launch.json` is read by VS Code and by non-Gradle producers. Bump the format's `schemaVersion` and keep readers tolerant of the older value.
- **GH actions** — removing an input, changing a default that would change observed behavior for a workflow that didn't override it, renaming a default branch convention.

Anything else is additive.

## 4. The wire-format rules

These apply to surfaces 1, 2, 3, and the history sidecar.

### 4.1 Enum discipline

Every enum that crosses a process boundary should be decoded **tolerantly**:

- An unknown string maps to a per-enum `UNKNOWN` value (Kotlin) or a falsy sentinel (TypeScript).
- Code branching on the enum has an explicit `else` / default arm.
- The fixture corpus has at least one fixture per enum that exercises a synthetic future value to prove tolerance.

Adding a new enum value is **additive** under this rule. Without tolerant decode, every new enum value is a silent break — the rule is what would make the additive promise real.

> **Not true of the existing wire enums.** Most `@Serializable` enums in `Messages.kt` are plain enums with no `UNKNOWN` member and no custom serializer — `FileKind` and `ChangeType` are two of many. `ignoreUnknownKeys` covers unknown *fields*, not unknown enum *values*, so a peer sending a newly added value today makes decoding throw. Treat this section as the rule for enums you add or touch, not as a description of the current corpus. Retrofitting the rest is [tracked work](#10-what-is-and-is-not-enforced), not a shipped guarantee.

### 4.2 Unknown fields

Both sides ignore unknown JSON fields. Kotlin uses `Json { ignoreUnknownKeys = true }` (already configured); TypeScript decoders use a structural cast and only key off documented fields.

### 4.3 Optional vs required

New fields are **always** optional, with a documented default. The default must preserve old-behavior semantics. Promoting an optional field to required is a breaking change.

### 4.4 Capabilities, not version checks

Clients gate features on `ServerCapabilities` entries, not on `daemonVersion` semver. New features always add a capability flag, even when they look "obviously additive".

### 4.5 protocolVersion bumps

Bumping `protocolVersion` requires:

- A coordinated daemon + every-client release. **This is the only mechanism that works today** — see below.
- A migration note in [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md).
- Updated fixture corpus.

Serving the previous version for one minor cycle, so clients can ship on their own schedule, is what this list *should* require. It is left out because the daemon cannot do it: there is no way to satisfy it, and a requirement no one can meet is worse than an acknowledged gap. Restore it here in the same change that implements range negotiation.

Range negotiation — the daemon advertising a `{min, max}` and serving both — is **not in 1.0.0** and is deferred to a later `1.x` (§ 10). `initialize` carries a single `protocolVersion: Int` today, and [`JsonRpcServer`](../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/JsonRpcServer.kt) fails the handshake on any mismatch. Until it lands, the VS Code extension's `current..current-1` support window does not exist in any form: the extension sends one hard-coded version and the daemon rejects anything else, which is why a coordinated release is the only way a bump works and why the previous-version requirement is held back rather than listed.

## 5. Deprecation policy

Applies to plugin DSL, CLI flags, MCP tools, and any other named public surface.

1. **Mark deprecated** in the same release that introduces the replacement.
   - Kotlin: `@Deprecated(level = WARNING, replaceWith = ...)`.
   - CLI: warning to stderr on use; mention in `--help` with strikethrough text.
   - MCP: prefix tool description with `[DEPRECATED]` and document the replacement.
   - GH action input: warning step that prints to the job log.
2. **Keep functional** for at least two minor releases (≥ 6 months elapsed, whichever is longer).
3. **Escalate to ERROR** in a subsequent minor (Kotlin: `level = ERROR`; CLI: warning becomes louder).
4. **Remove** only at a major.

Exceptions are permitted only for security fixes, documented in CHANGELOG.

## 6. Toolchain compatibility (AGP × Kotlin × Compose × Robolectric)

The plugin declares a **supported matrix** in [RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md). The matrix slides on its own cadence:

- Adding a newer corner is **additive** (any minor).
- Dropping a corner older than 18 months is permitted at any **minor** with one release of warning. That warning has to go in the CHANGELOG and release notes: `apply()` never sees an AGP, Kotlin, or Compose version, so it cannot print the "AGP X.Y is deprecated; will be unsupported in compose-preview Z.0" message this rule used to promise, and a consumer gets no in-build notice at all.
- Dropping multiple majors at once requires a plugin **major**.

**What `apply()` actually gates today is the Gradle version** ([`GradleVersionCheck.kt`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/GradleVersionCheck.kt)); there is no AGP / Kotlin / Compose comparison at configuration time, so an out-of-matrix consumer gets whatever failure the toolchain produces rather than a named supported range. `compose-preview doctor` is not a substitute: it prints the resolved AGP and Kotlin versions as an informational check and flags known dependency mismatches ([`CompatRules.kt`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/tooling/CompatRules.kt) is given dependency maps and the Gradle version, not the AGP/Kotlin versions), so it evaluates no matrix predicate at all.

The CI integration suite renders against several toolchain points, but not as explicit current / current-1 / next-RC cells — and only one cell runs per PR, with the full matrix reserved for `main` and the nightly cron (§ 9).

## 7. Branch conventions (GH actions)

The default branches are part of the public contract:

- `compose-preview/main`, `compose-preview/pr`
- `compose-preview/resources/main`, `compose-preview/resources/pr`
- `compose-preview/a11y/main`, `compose-preview/a11y/pr`

These names are **frozen forever**. PR comments published by the comment action embed permanent commit URLs on these branches; renaming them retroactively breaks every linked image in every closed PR.

Adding new branch conventions for new pipelines is fine. Renaming an existing one is not.

## 8. Release coordination

Single release train governed by release-please. The plugin, CLI, MCP, extension, and annotations all bump together at minor and major; patches are independent per artifact.

Daemon protocol versions and per-data-product schema versions are decoupled — a release may bump `protocolVersion` from 1 to 2 without bumping the artifact major (the artifact major bumps for *consumer-visible* breakage; the protocol bump is invisible to plugin/CLI consumers as long as the daemon supports the previous version).

## 9. Compatibility testing

Three layers were designed; this is what each one actually does today.

| Layer | Intended gate | Reality |
|---|---|---|
| **Fixture corpus** — `docs/daemon/protocol-fixtures/` | Adding a wire message without a fixture fails CI | Kotlin round-trips the fixtures it covers. **TypeScript does not round-trip** — `daemonProtocol.test.ts` does `JSON.parse(...) as T` and asserts selected properties on 16 of the 28 fixtures, so a renamed field nobody asserts passes. And the inventory (`MessagesTest.fixtureInventoryMatchesExpected`) is a **hand-maintained** set: a new message adds no entry and lands green. History, interactive, stream, XR, recording and extension methods are already uncovered |
| **Kotlin BCV** — named for `:gradle-plugin` and `:preview-annotations` | Changing a public API without updating the golden file fails CI | **Not wired.** No `binary-compatibility-validator` plugin, no `.api` golden files, no `apiCheck` in any workflow. Nothing catches a breaking API change. Note the named modules are also the wrong target: the DSL consumers compile against is `:gradle-plugin-config`, the artifact that gets conflict-resolved between a pinned config plugin and a CLI-injected runtime |
| **Toolchain integration matrix** — `.github/workflows/integration.yml` | Bumping a matrix corner without a green run fails CI | Two different things. **On a PR:** one cell (`wear-os-samples (ComposeStarter)`); `agp8-min` is skipped, and a diff touching only safe paths skips the matrix entirely with the required legs re-emitted green. **On `main` + the nightly cron:** the full matrix, so AGP-floor drift surfaces within a day rather than on the PR. Either way the cells are external repositories and fixtures, not pinned current / current-1 / next-RC |

The fixture corpus and the integration suite are real tests that catch real regressions. What none of the three currently provides is the *exhaustive* coverage the rules above assume.

## 10. What is and is not enforced

**Read §§ 2–9 as the intended policy, not as a description of shipped machinery.** `1.0.0` is a version number: it ended the `0.x` convention where a minor could break anything, and it says we intend to treat the surfaces in [API_STABILITY.md](API_STABILITY.md) as contracts. It did **not** turn on the enforcement these documents describe. An earlier revision of this section claimed those mechanisms were live; they are not, and the difference matters to anyone deciding how much to depend on a surface.

What holds today rests on review and convention:

- We do not knowingly break a listed surface without a major.
- The deprecation cycle in § 5 is followed when a surface is retired.
- The fixture corpus and the integration suite catch real regressions within their coverage (§ 9).

What is described but **not implemented**:

| Mechanism | Described in | State |
|---|---|---|
| Kotlin BCV on `:gradle-plugin` / `:preview-annotations` | § 9 | No plugin, no `.api` golden files, no CI check — a breaking API change ships silently |
| Tolerant enum decode across the wire | § 4.1 | Most `Messages.kt` enums have no `UNKNOWN`; a new value from a peer throws |
| `apply()`-time AGP × Kotlin × Compose gate | § 6 | Only the Gradle version is checked; `doctor` reports skew but does not gate |
| Exhaustive fixture coverage | § 9 | Inventory is a hand-maintained set; a new message lands green with no fixture |
| `schemaVersion` on `previews.json` and the `HistoryEntry` sidecar | § 1 | Field absent from both — a reader cannot tell a supported document from a future one |
| `protocolVersion: {min, max}` range negotiation | § 4.5, [API_STABILITY.md § 2.1](API_STABILITY.md#21-daemon-json-rpc-surface-1) | Single `Int`; the daemon rejects any value but its own |
| VS Code ↔ daemon `N..N-1` window | [API_STABILITY.md § 3](API_STABILITY.md#3-multi-version-support-window) | Not possible while the above holds — the extension sends one hard-coded version and a mismatch fails the handshake |
| `compose-preview <cmd> --json-schema` | [API_STABILITY.md § 2.7](API_STABILITY.md#27-cli-argv-surface-7) | Not implemented; capability detection is `--version` plus `--help` |
| `// API: stable` / `// API: incubating` source tags | [API_STABILITY.md § 5](API_STABILITY.md#5-stability-tags-in-code) | Not applied |
| `@Stable` / `@Incubating` DSL tiers | [API_STABILITY.md § 2.4](API_STABILITY.md#24-gradle-plugin-dsl-surface-4) | Not implemented — no opt-in tier exists |

Most are additive and can land in any `1.x`. Until one does, do not cite it as a guarantee — in a PR description, in docs, or to a consumer.

**Range negotiation is the exception: it cannot be done additively in the shape described.** `protocolVersion` is typed as a number on both sides (`InitializeParams.protocolVersion: Int` in `Messages.kt`, `protocolVersion: number` in the TypeScript interface and [PROTOCOL.md § 3](daemon/PROTOCOL.md)), so sending `{min, max}` in that field makes either peer fail to decode the handshake before any negotiation could happen — the one message where failing closed is guaranteed. It needs either a coordinated `protocolVersion` bump, or a staged rollout that adds the range as a **new optional field** an existing peer ignores while the numeric field keeps working, then retires the numeric one a cycle later. Chicken-and-egg worth noting: the staged path is the only one that doesn't require the coordinated release that range negotiation exists to avoid.

The earlier 1.0 readiness punch list was [issue #798](https://github.com/yschimke/compose-ai-tools/issues/798); it covered feature completeness, not the enforcement above.
