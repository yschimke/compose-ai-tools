# Preview daemon roadmap

This file tracks open daemon work. Historical task breakdowns and landed implementation diaries do
not belong here; use GitHub PRs and commit history for that detail.

## Open Work

| Area | Status | Next useful change |
| --- | --- | --- |
| Sandbox lifecycle | Planned | Emit the reserved `sandboxRecycle`, `daemonWarming`, and `daemonReady` notifications from real recycle decisions. |
| Leak detection | Planned | Add periodic active leak checks behind the existing `LeakDetectionMode` capability surface. |
| Resource invalidation | Planned | Track per-preview resource reads so `fileChanged({ kind: "resource" })` can invalidate only affected previews. |
| History | Partial — `history/diff` experimental in 1.0 | Add pixel-mode `history/diff`, git-ref write modes, and LFS/squash-GC handling, then flip the `composeai.experimental.historyDiff` gate on by default and remove it (1.1). |
| Predictive prefetch | Planned | Prove scroll-ahead speculation with telemetry before adding more prediction signals. |
| Startup time | Research | Revisit machine-resident daemon, AppCDS, and bytecode-cache options from `STARTUP.md` when startup latency becomes the bottleneck again. |

## Keep Current Contracts Elsewhere

- Wire format: `PROTOCOL.md`
- Gradle DSL: `CONFIG.md`
- Architecture: `DESIGN.md`
- Classloader/save-loop behavior: `CLASSLOADER.md`
- Sandbox pool behavior: `SANDBOX-POOL.md`
- MCP behavior: `MCP.md` and `MCP-KOTLIN.md`
