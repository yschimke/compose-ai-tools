# API stability plan for 1.0

> **Status:** proposal. Tracking issue:
> [#650](https://github.com/yschimke/compose-ai-tools/issues/650).
> This document defines what should become stable at 1.0, what must stay
> internal, and which breaking changes are worth making before 1.0.
> API stability before 1.0 is explicitly **not** a goal.

## Problem

Today the repository releases several things from one tag:

- Maven artifacts: Gradle plugin, preview annotations, renderer, daemon,
  and data-product modules.
- CLI distributions: `compose-preview` and bundled `compose-preview mcp serve`.
- VS Code extension: Marketplace/Open VSX package.

They are built together, but users can update them at different times. A
VS Code extension can auto-update while a project still applies an older
Gradle plugin. An agent can install a newer CLI/MCP binary against a
project pinned to an older plugin. A downstream build can consume a
published Maven artifact directly even when that artifact was intended as
runtime plumbing for the plugin.

Before 1.0 we should assume users upgrade everything together and make
breaking changes freely. We should prefer fixing bad boundaries over
carrying compatibility shims. From 1.0 onward, each boundary below needs
an explicit compatibility rule and graceful degradation path.

## Pre-1.0 policy

There is no API stability promise for any component before 1.0:

- Gradle plugin DSL, task names, output files, daemon descriptors, daemon
  protocol, CLI commands, MCP tools, VS Code settings, and Maven artifact
  internals may all change incompatibly.
- We should not spend engineering effort maintaining compatibility between
  arbitrary pre-1.0 versions.
- Downstream apps and editor/agent installs should upgrade all
  compose-preview components together while the project is pre-1.0.
- If a breaking pre-1.0 change removes a poor boundary, take the break and
  document the migration in the PR/release notes.

The rest of this document is about preparing the 1.0 contract, not about
supporting pre-1.0 skew.

## Stability goals

1. **One recommended release unit.** The supported path is matching
   `compose-preview` versions for Gradle plugin, CLI/MCP, daemon artifacts,
   renderer artifacts, and VS Code extension.
2. **Independent upgrades degrade cleanly.** Newer clients must detect
   older project/plugin/daemon capabilities and hide, warn, or fall back
   instead of failing mid-render.
3. **No silent skew.** If a feature requires a newer producer, the client
   says which version/capability is missing.
4. **Stable scriptable outputs.** CLI JSON, MCP tool shapes, and on-disk
   manifest/data/history schemas are public API once documented.
5. **Internal Maven artifacts are not source API.** Daemon, renderer, and
   connector artifacts may be published so the plugin can resolve them, but
   direct source/binary API compatibility for their implementation classes is
   not promised unless a package is explicitly documented as public.

## Boundary inventory

| Boundary | Producer | Consumer | Changes independently? | 1.0 rule |
|---|---|---|---|---|
| Gradle plugin ID, DSL, tasks | Maven plugin | Consumer builds | Consumer-controlled | Stable. Deprecate before removing. |
| Preview annotations | Maven artifact | Consumer source | Consumer-controlled | Stable source API. Additive annotations only within 1.x. |
| `previews.json`, `resources.json`, applied marker | Gradle plugin | VS Code, CLI, MCP, agents | Yes | Public JSON schemas with versioned envelopes. |
| Daemon launch descriptor | Gradle plugin | VS Code, MCP/CLI daemon launchers | Yes | Public JSON schema with version negotiation/fallback. |
| Daemon JSON-RPC | daemon from project/plugin version | VS Code, MCP, harness, future CLI client mode | Yes | Negotiated protocol + capabilities. Additive within major. |
| Data-product payloads | renderer/daemon/Gradle render path | VS Code, CLI, MCP, agents | Yes | Per-kind `schemaVersion`; unknown kinds ignored or rejected cleanly. |
| CLI commands and JSON stdout | CLI release | Shell scripts, agents, CI | Yes | Stable command/schema contracts after 1.0. |
| MCP tools/resources | MCP binary | MCP clients/agents | Yes | Follow MCP spec versioning; add tools/resources, keep old names. |
| VS Code settings/commands | VS Code extension | Users, keybindings, tasks | Auto-updates | Stable setting/command IDs; deprecate aliases before removal. |
| `daemon-*`, `renderer-*`, `data-*-connector` classes | Maven artifacts | Plugin/daemon runtime; possible direct users | Yes | Internal unless package/doc says public. No source compatibility promise. |

## Compatibility policy after 1.0

### Version windows

- A client surface (`VS Code`, `CLI`, `MCP`) supports the current minor and
  at least one previous minor of the project-applied Gradle plugin.
- A newer project/plugin may reject an older client for daemon-only features,
  but must leave the Gradle `renderPreviews` path working.
- Patch releases must be wire/schema-compatible inside their minor line.
- Breaking wire/schema changes require a new major protocol or schema
  version and a clear fallback for clients that do not support it.

### Degradation levels

Use the least disruptive fallback that preserves correctness:

1. **Hide** unavailable UI/actions when a capability is absent.
2. **Disable with reason** when the feature is visible but unavailable.
3. **Fallback to Gradle task path** when daemon startup/protocol support is
   missing but a cold render can still produce correct outputs.
4. **Fail closed with remediation** when no compatible path exists.

Do not silently use partial data for checks, diagnostics, or data products
that could mislead the user.

## Pre-1.0 breaking changes to make

These are worth doing before the first stable release, even if they break
existing snapshots.

### 1. Replace exact daemon protocol matching with negotiation

Current daemon initialization uses a single `protocolVersion` and rejects
mismatches. That is acceptable before 1.0 but too brittle once VS Code and
the project plugin can be updated independently.

Move to an initialize handshake that can select a common protocol:

```ts
{
  protocol: {
    name: "compose-preview-daemon";
    minVersion: 1;
    maxVersion: 2;
  };
  clientVersion: string;
  capabilities: { ... };
}
```

The daemon responds with:

```ts
{
  protocol: {
    selectedVersion: 2;
    minVersion: 1;
    maxVersion: 2;
  };
  daemonVersion: string;
  capabilities: { ... };
}
```

Rules:

- If there is no overlap, return a structured `ProtocolMismatch` error
  with daemon/client ranges and a human-readable remediation.
- Capabilities still gate features inside the selected protocol. A selected
  version says the envelope and method semantics are compatible; it does
  not imply every optional feature exists.
- Keep support for the old `protocolVersion: 1` shape until 1.0 only, then
  remove it before declaring the stable contract.

### 2. Version every on-disk schema envelope

The plugin writes files that outlive the process and are read by other
release units. These should all have a top-level schema discriminator:

- `compose-preview-previews/vN`
- `compose-preview-resources/vN`
- `compose-preview-applied/vN`
- `compose-preview-daemon-launch/vN`
- `compose-preview-history-entry/vN`
- `compose-preview-data-products/vN`
- `compose-preview-data-get/vN`

Existing CLI data commands already use schema strings. Bring the remaining
plugin/daemon outputs into the same pattern and document which fields are
required, optional, deprecated, and ignored when unknown.

### 3. Split public protocol/model DTOs from daemon runtime

`daemon-core` currently contains both protocol/model types and daemon
runtime services. That makes it tempting for CLI/MCP or third parties to
depend on more than the stable seam.

Before 1.0, split one small published artifact:

- `compose-preview-protocol` or `compose-preview-models`
  - JSON-RPC DTOs.
  - manifest/data/history schema DTOs that are intentionally shared.
  - device/override catalog value types if those are public.
  - no render hosts, no JSON-RPC server runtime, no filesystem watchers,
    no classloader/sandbox code.

Then make:

- `:daemon:core` depend on that model artifact.
- `:mcp` and CLI local-library mode depend on that model artifact rather
  than the full daemon runtime.
- VS Code generated or hand-written TypeScript mirrors stay aligned with
  that same model contract.

This also lets us state that `daemon-core` is an implementation artifact,
while the protocol/model artifact is a supported API.

### 4. Make direct Maven artifact use explicit

Published runtime artifacts should be classified:

- **Public:** Gradle plugin marker/artifact, preview annotations, stable
  protocol/model artifact, documented CLI distribution.
- **Runtime/internal:** renderer, daemon backend, connector modules.

For internal artifacts:

- Keep Maven coordinates stable enough for the plugin to resolve matching
  versions.
- Do not document direct use as a supported source API.
- Prefer internal packages and KDoc that say "runtime implementation detail".
- If a class becomes useful to third parties, move it into a public module
  deliberately rather than letting it leak from a daemon/renderer module.

### 5. Add compatibility gates in clients

Every independently updated client should have a single compatibility gate:

- VS Code: read applied marker + daemon launch descriptor + initialize
  capabilities, then compute feature availability.
- CLI: read applied marker for project-aware commands; print clear
  remediation when the project plugin is too old.
- MCP: treat daemon capabilities as source of truth; expose unavailable
  features as absent tools/resources or structured tool errors.

Feature code should not perform ad hoc version checks. It should ask the
gate whether a capability is available and what fallback is allowed.

### 6. Lock fixture-based contract tests

The current shared protocol fixtures are the right direction. Promote them
to a required compatibility gate:

- Kotlin and TypeScript both parse every daemon fixture.
- CLI parses every public CLI JSON fixture.
- MCP parses every public request/response/tool-result fixture.
- Manifest/data/history schemas have fixture corpora for current and at
  least one previous schema version.
- Removing or changing a fixture is a breaking-change review signal.

## Protobuf decision

Do **not** switch the daemon, MCP, CLI, or on-disk surfaces to binary
Protobuf as the primary transport for 1.0.

Reasons:

- The daemon protocol is already JSON-RPC over LSP-style framing, and MCP
  is JSON. Switching to binary Protobuf would add translation at the MCP
  boundary rather than remove it.
- The VS Code extension and webview are TypeScript/JSON-heavy. JSON keeps
  debugging simple and lets fixtures be read directly in reviews.
- On-disk artifacts are meant for humans, agents, and scripts. JSON is a
  better compatibility/debugging format there.
- Protobuf's unknown-field story is strongest for binary messages. Once
  converted to JSON, unknown retention and presence semantics are less
  useful than explicit optional fields + capabilities.

What we should consider instead:

- A schema source for generated validators/types. JSON Schema, TypeSpec, or
  Buf/Protobuf-with-JSON-mapping could all work, but the output should still
  be JSON on the wire.
- If we choose Protobuf/Buf, use it as an IDL for generated Kotlin and
  TypeScript DTOs plus JSON mapping tests, not as a binary protocol switch.
- Run a pre-1.0 spike comparing JSON Schema vs TypeSpec vs Buf on one real
  boundary: `InitializeParams/InitializeResult`, `DataFetchResult`, and one
  manifest file. Decide based on generated-code quality, optional-field
  ergonomics, and fixture readability.

Recommendation: keep JSON as the contract and add schema-backed validation
before 1.0. Revisit binary Protobuf only if a remote, high-throughput,
non-local transport appears and JSON payload size becomes measurable.

## Per-surface 1.0 contracts

### Gradle plugin

Stable:

- Plugin ID `ee.schimke.composeai.preview`.
- `composePreview { ... }` DSL names documented in user docs.
- Task names documented for users or editor integrations, especially
  `renderPreviews` and daemon descriptor tasks.
- Output directories documented for scripts.

Graceful degradation:

- If VS Code/CLI asks for a descriptor schema the plugin cannot produce,
  the client falls back to `renderPreviews` when possible.
- If a requested preview extension/data product is unsupported, the plugin
  emits a clear marker/capability absence rather than partial output.

### VS Code extension

Stable:

- User-facing settings and command IDs.
- Daemon client behavior against the supported protocol window.
- Webview message protocol can remain internal because it ships in the same
  extension package, but tests should still cover it.

Graceful degradation:

- Older project plugin: use Gradle task path, hide daemon/data/interactive
  controls, and show an "upgrade project plugin for live daemon features"
  action.
- Older daemon: use advertised capabilities; unknown notifications ignored.
- Newer daemon outside supported range: fail daemon mode closed, keep cold
  Gradle render path.

### CLI

Stable:

- Command names and flags documented in README/docs.
- Exit codes for scriptable failures.
- JSON schemas such as `compose-preview-doctor/v1`,
  `compose-preview-data-products/v1`, and `compose-preview-data-get/v1`.

Graceful degradation:

- Project plugin too old: explain required version/capability.
- Data product missing: preserve current explicit "not available" error.
- Daemon-backed future commands: attach only through the negotiated protocol,
  never by importing backend implementations.

### MCP

Stable:

- MCP server initialization per the MCP spec version it advertises.
- Tool names, required inputs, resource URI schemes, and result schemas.
- Mapping from daemon errors to structured MCP tool errors.

Graceful degradation:

- If the daemon lacks a capability, hide the derived MCP affordance where
  possible or return a typed tool error with remediation.
- `classpathDirty` remains a supervisor lifecycle event: respawn once, then
  stop thrashing and surface a clear failure.
- Unknown daemon data products are ignored in list surfaces and rejected
  cleanly in fetch surfaces.

### Daemon protocol

Stable:

- JSON-RPC framing.
- Method names and selected-version semantics.
- Error codes and machine-readable `error.data.kind` values.
- Capability names.

Graceful degradation:

- Optional fields default to absent behavior.
- Unknown notifications are ignored.
- Unknown requests return `MethodNotFound`.
- Unknown data-product kinds return `DataProductUnknown`.
- Unsupported override fields are either omitted from
  `supportedOverrides` or ignored with warning at higher-level clients.

### On-disk outputs

Stable:

- Versioned schema envelope.
- File locations explicitly documented for scripts.
- Additive fields inside a schema version.

Graceful degradation:

- Readers ignore unknown fields.
- Readers reject unknown major schema versions with a diagnostic that names
  the writer version and the minimum reader version needed.
- Deprecated paths can remain read aliases for one minor line before removal.

## Implementation sequence

1. Land this design and track it in
   [#650](https://github.com/yschimke/compose-ai-tools/issues/650).
2. Add schema/version envelopes to every on-disk plugin/daemon output that
   lacks one.
3. Introduce daemon protocol negotiation while still accepting the current
   `protocolVersion: 1` handshake.
4. Add client compatibility gates for VS Code, CLI, and MCP.
5. Split the protocol/model artifact from `daemon-core`.
6. Add schema-backed fixtures for daemon, CLI, MCP, and on-disk outputs.
7. Run the IDL/schema spike and decide JSON Schema/TypeSpec/Buf before 1.0.
8. Remove pre-1.0 compatibility shims and declare the 1.0 stable contract.

## Open questions

- Exact support window: one previous minor may be enough, but VS Code
  Marketplace auto-update behavior may justify supporting two previous
  plugin minors.
- Whether `renderPreviews` output paths should be promised stable or only
  discoverable through a manifest.
- Whether CLI/MCP should share one supervisor process for repeated local
  invocations, and if so whether that transport is stdio, Unix domain
  socket, or a named pipe on Windows.
- Whether any renderer APIs deserve to become public before 1.0, or whether
  all renderer modules remain implementation artifacts.
