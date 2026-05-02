# VS Code extension

Aimed at humans working in the IDE; agents driving renders should use the
CLI or — for long-lived chat-style loops — the MCP server (see below).
With the `vscjava.vscode-gradle` extension installed, the Compose Preview
extension provides:

- A **preview panel** (webview) listing rendered previews for the active module.
- **CodeLens** and **hover** actions above every `@Preview` function in Kotlin
  files (re-render, open PNG).
- Commands:
  - `Compose Preview: Refresh` / `Render All`
  - `Compose Preview: Run for File` — render only previews in the active file.
- Auto-refresh on editor save (debounced) and on active-editor switch to a
  Kotlin file.

## VS Code + an MCP-attached agent

When the human is editing in VS Code and chatting with an agent (Claude
Code, the SDK, …) attached to `compose-preview-mcp` against the same
project, both surfaces share the **same daemon JVM per `(workspace,
module)`**. Useful properties:

- VS Code's auto-refresh-on-save and the agent's `resources/read` race
  against the same daemon. Whichever arrives second is a cache hit; PNGs
  the agent reads are byte-equal to what VS Code displays.
- `notify_file_changed` from either side invalidates the same in-memory
  state. The agent doesn't have to re-discover the source set after VS
  Code saves a file.
- A11y findings, layout trees, recomposition heat-maps — all data
  products the agent fetches via `get_preview_data` are computed against
  the same render the human just looked at.

Two gotchas worth knowing:

- **Don't double-trigger.** If the agent has a `subscribe_preview_data` /
  `watch` set up, it will receive `resources/updated` on every save. If
  the agent then immediately calls `render_preview` to "be sure", it
  forces a redundant render. Trust the notification — the daemon already
  has fresh bytes.
- **Cold-start belongs to whichever side connected first.** If the agent
  is the first to spawn the desktop daemon, VS Code's first save sees a
  warm sandbox. If VS Code spawned it via `Compose Preview: Render All`,
  the agent's first `resources/read` is fast. Order doesn't matter for
  correctness, only first-call latency.

For the protocol-level agent flow see
[`design/MCP.md`](./MCP.md). For PR review with two worktrees attached as
two workspaces see
[`compose-preview-review/design/MCP_REVIEW.md`](../../compose-preview-review/design/MCP_REVIEW.md).
