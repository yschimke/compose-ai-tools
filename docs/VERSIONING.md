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
| GH composite actions | Semver via tag/SHA | Consumer `uses:` ref |

Plugin / CLI / extension share one release-please-driven semver chain (see [RELEASING.md](RELEASING.md)). Protocol and schema integers are independent.

## 2. Semver rules for the published artifacts

For plugin, CLI, extension, MCP, annotations:

- **Major** — breaking change to any public contract on the artifact. See § 3.
- **Minor** — additive change: new feature, new flag, new DSL property, new annotation, new CLI subcommand, new MCP tool. New optional fields on existing types.
- **Patch** — bug fix with no contract change. No new public surface.

Pre-1.0 (today): minor bumps may carry breaking changes with a clear note in CHANGELOG. Post-1.0 they may not.

## 3. What counts as breaking

By surface:

- **Plugin DSL** — removing or renaming a `Property<T>`, retyping it, removing a nested extension, removing an enum value, changing a `convention(...)` default in a way that flips semantics for an existing user, raising the supported AGP/Kotlin/Compose floor outside the matrix-slide rule (§ 6).
- **CLI** — removing or renaming a flag or subcommand, changing exit codes, changing `--json` output shape (other than additive fields), changing default behavior of an existing flag.
- **MCP** — removing a tool, renaming a tool, narrowing an input schema, changing the meaning of an existing input or output field.
- **Annotation library** — removing a parameter, renaming a parameter, retyping a parameter, reordering an annotation array literal default.
- **Daemon protocol** — anything outside the additive list in [API_STABILITY.md § 2.1](API_STABILITY.md#21-daemon-json-rpc-surface-1).
- **`previews.json` / `HistoryEntry` schemas** — same as the protocol: removed/renamed fields, semantic changes.
- **GH actions** — removing an input, changing a default that would change observed behavior for a workflow that didn't override it, renaming a default branch convention.

Anything else is additive.

## 4. The wire-format rules

These apply to surfaces 1, 2, 3, and the history sidecar.

### 4.1 Enum discipline

Every enum that crosses a process boundary is decoded **tolerantly**:

- An unknown string maps to a per-enum `UNKNOWN` value (Kotlin) or a falsy sentinel (TypeScript).
- Code branching on the enum has an explicit `else` / default arm.
- The fixture corpus has at least one fixture per enum that exercises a synthetic future value to prove tolerance.

Adding a new enum value is **additive** under this rule. Without tolerant decode, every new enum value is a silent break — the rule is what makes the additive promise real. See [ROADMAP_1_0.md § Enum tolerance](ROADMAP_1_0.md) for the migration.

### 4.2 Unknown fields

Both sides ignore unknown JSON fields. Kotlin uses `Json { ignoreUnknownKeys = true }` (already configured); TypeScript decoders use a structural cast and only key off documented fields.

### 4.3 Optional vs required

New fields are **always** optional, with a documented default. The default must preserve old-behavior semantics. Promoting an optional field to required is a breaking change.

### 4.4 Capabilities, not version checks

Clients gate features on `ServerCapabilities` entries, not on `daemonVersion` semver. New features always add a capability flag, even when they look "obviously additive".

### 4.5 protocolVersion bumps

Bumping `protocolVersion` requires:

- A coordinated daemon + every-client release.
- A migration note in [docs/daemon/PROTOCOL.md](daemon/PROTOCOL.md).
- Daemon support for the previous version for one minor cycle (clients may take longer to ship).
- Updated fixture corpus.

Post-1.0 the daemon advertises a `{min, max}` range and serves both. The VS Code extension supports `current..current-1` daemons.

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
- Dropping a corner older than 18 months is permitted at any **minor** with one release of warning (`apply()` prints "AGP X.Y is deprecated; will be unsupported in compose-preview Z.0").
- Dropping multiple majors at once requires a plugin **major**.

`apply()` enforces the matrix at configuration time. Out-of-range versions fail with a specific error message naming both the consumer's version and the supported range.

The CI integration suite covers at least three matrix points: current, current-1, and next-RC.

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

Three layers, all required to remain green on `main`:

1. **Fixture corpus** — `docs/daemon/protocol-fixtures/` round-trips on Kotlin and TypeScript.
2. **Kotlin BCV** — `binary-compatibility-validator` on `:gradle-plugin` and `:preview-annotations`.
3. **Toolchain integration matrix** — `.github/workflows/integration.yml` runs sample renders against the AGP matrix corners.

Adding a public type or annotation without updating the BCV golden file is a CI failure. Adding a wire message without a fixture is a CI failure. Bumping the AGP corner without a green integration run is a CI failure.

## 10. Pre-1.0 disclaimer

Until `1.0.0`, the rules in §§ 2–5 are aspirational; the project may break any contract in any release with a CHANGELOG note. The contracts in [API_STABILITY.md](API_STABILITY.md) become enforceable at 1.0.

The work needed to make 1.0 honourable is in [ROADMAP_1_0.md](ROADMAP_1_0.md).
