# Data products

The renderer can produce structured data alongside each PNG — accessibility
findings, the a11y semantic hierarchy, layout trees, theme resolution,
recomposition heat-maps, and so on. Each is identified by a namespaced
*kind* string (`a11y/atf`, `a11y/hierarchy`, `compose/recomposition`, …)
with its own JSON schema.

Two surfaces:

- **MCP** — `list_data_products` / `get_preview_data` /
  `subscribe_preview_data` tools on the [`compose-preview-mcp`](../../../mcp/README.md)
  server. The right path for any agent that's already driving previews
  through MCP.
- **CLI / Gradle** — when a kind is enabled in the consumer's
  `composePreview { ... }` config, the renderer writes the same payload
  to `build/compose-previews/data/<previewId>/<kind>.json` after every
  render. CLI / CI consumers read those files directly. **No `--emit`
  flag** — kind selection is Gradle config, not CLI surface (see
  [`docs/daemon/DATA-PRODUCTS.md`](../../../docs/daemon/DATA-PRODUCTS.md)
  goal #6).

The full kind catalogue, per-kind schemas, transports, and re-render
cost notes live in
[`docs/daemon/DATA-PRODUCTS.md`](../../../docs/daemon/DATA-PRODUCTS.md).

## Enabling a kind

Kinds are produced only when the consumer's Gradle config asks for them.
For accessibility:

```kotlin
composePreview {
    previewExtensions {
      a11y { enableAllChecks() }
    }
}
```

That switches on `a11y/atf` (findings) and `a11y/hierarchy` (semantic
tree). Other kinds will gain their own toggles as they ship — see
[A11Y.md](./A11Y.md) for the a11y-specific knobs.

A daemon advertises only the kinds whose producers it has wired. An
agent calling a kind that isn't advertised gets `DataProductUnknown`.

## MCP workflow (the agent path)

Three calls, one of which is optional but pays for itself on repeat use:

```jsonc
// 1. Discover what kinds the daemon advertises. Empty list = pre-D2
//    daemon or producers not wired (e.g. accessibility not enabled in
//    the consumer's build script).
{ "method": "tools/call", "params": { "name": "list_data_products",
  "arguments": { "workspaceId": "<id>" } } }

// 2. (Optional but recommended for repeat use.) Subscribe so the
//    daemon attaches the kind on every renderFinished. The MCP server
//    caches the latest payload per (uri, kind).
{ "method": "tools/call", "params": { "name": "subscribe_preview_data",
  "arguments": { "uri": "compose-preview://<id>/_module/com.example.Foo",
                 "kind": "a11y/hierarchy" } } }

// 3. Fetch. With a subscription in place, the response carries
//    `cached: true` and pays no daemon round-trip. Without a
//    subscription, this falls through to data/fetch (and auto-renders
//    the preview if it hasn't rendered yet).
{ "method": "tools/call", "params": { "name": "get_preview_data",
  "arguments": { "uri": "compose-preview://<id>/_module/com.example.Foo",
                 "kind": "a11y/hierarchy" } } }
```

`subscribe_preview_data` is sticky-while-visible: when the preview
leaves the daemon's `setVisible` set the subscription auto-drops, and
the agent re-subscribes when it comes back into view. Refcounted across
MCP sessions — a subscribed kind stays subscribed on the daemon as long
as any session holds an interest, and is released cleanly on the last
unsubscribe / disconnect.

## When to subscribe vs just fetch

- **One-shot question** ("does this preview have a11y issues?") — skip
  subscribe, call `get_preview_data` directly. It auto-renders if
  needed and returns one payload. ~one render's latency.
- **Repeated questions about the same preview** ("for each preview in
  this module, give me a11y findings") — subscribe first, then fetch.
  The first fetch warms the daemon; subsequent fetches are cache hits.
- **Always-on for a workspace** — currently no operator knob (the
  speculative `--attach-data-product` CLI flag was removed; the
  agent-side equivalent isn't built yet). Use per-preview
  `subscribe_preview_data` calls for now.

## Failure modes

| Wire error | Code | Meaning |
|---|---|---|
| `DataProductUnknown` | -32020 | Kind isn't advertised. Call `list_data_products` to see what's available, and check the consumer's Gradle config enabled the producer. |
| `DataProductNotAvailable` | -32021 | Preview has never rendered. `get_preview_data` already retries automatically; only surfaces if the auto-render itself failed. |
| `DataProductFetchFailed` | -32022 | Producer-side failure (bug in the renderer or a malformed preview). Details in `data`. |
| `DataProductBudgetExceeded` | -32023 | The daemon needed to re-render to compute the kind and the per-request budget tripped. Bump `daemon.dataFetchRerenderBudgetMs` if your previews are slow, or subscribe instead so the kind rides on every render. |

## CLI / Gradle consumers

When a kind's producer is enabled in `composePreview { ... }`, the
renderer writes its payload to
`build/compose-previews/data/<previewId>/<kind>.json` on every render
of `<previewId>`. CLI agents read those files directly — no MCP, no
tool calls. Same JSON shape either way (daemon and CLI share the
renderer-side producers).

For accessibility specifically the older
`build/compose-previews/accessibility-per-preview/<id>.json` location
stays for one release as a back-compat alias, then retires.

`layout/inspector` is Android-daemon backed by Compose `RootForTest`
carried on `PreviewContext.inspection`. Use it for layout-structure
questions: parent/child shape, bounds, measured size, constraints,
z-order, and inspectable modifier values. It is intentionally separate
from `compose/semantics` and
`a11y/hierarchy`; fetch those when the question is semantic intent or
assistive-technology output.

`test/failure` is daemon fetch-only. After a `renderFailed`
notification, call `get_preview_data(..., kind = "test/failure")` to
retrieve the latest failed-render postmortem for that preview: error
type/message/top stack frame, a bounded stack trace, and explicit v1
fallback fields for partial screenshot, pending effects, animation state,
and redacted snapshot summary.
