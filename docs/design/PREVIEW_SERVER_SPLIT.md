# Preview server extraction — historical record

> **The extraction happened.** The server lives in
> [yschimke/compose-preview-server](https://github.com/yschimke/compose-preview-server) and ships as
> `ee.schimke.composeai:compose-preview-serve` from there, first released as **2.0.0**. This page is
> kept as a record of what was decided and what is still open; the original analysis is
> [issue #3824](https://github.com/yschimke/compose-ai-tools/issues/3824) and this file's git
> history.

## What is left in this repository

Nothing that this document described as preparation. `cli/serve` and `cli/serve-web` are gone and
`:cli` consumes the published `compose-preview-serve` artifact, so the three instruments built to
measure the seam went with them: the symbol ratchet (`scripts/check-serve-seam.py`), the artifact
probe (`preview-server/`, `scripts/check-preview-server-contracts.sh`) and the coupling gate
(`scripts/measure-serve-coupling.py`). A Maven coordinate is a stronger boundary than a source
scanner, so the ratchet had nothing left to measure.

One check from that work survives because it was never about the split:
[`checkDaemonLaunchSchema`](DAEMON_LAUNCH_SCHEMA.md), which polices a cross-language contract that
has no module at all.

## Two corrections the finished job earned

**The seam measured a package boundary, not a process boundary.** The symbols it counted are not all
server types: most contain no Ktor, being the render-host and history plumbing that `bundle render`,
`render matrix` and `history manifest` use offline. compose-preview-server 2.2.0 split those into
`ee.schimke.composeai:compose-preview-render-host`, which `:cli` depends on directly. Four symbols
still cross into the server proper, all from `ServeCommand.kt`, so the Ktor floor remains until the
`serve` command surface itself is decided —
[#4832](https://github.com/yschimke/compose-ai-tools/issues/4832) and
[compose-preview-server#9](https://github.com/yschimke/compose-preview-server/issues/9).

**`browse` still delegates to serve.** `BrowseCommand` runs
`ServeCommand(serveArgs(args), browseProject = true)` outright. What is narrowly true is that
`browse` names no `ee.schimke.composeai.cli.serve` type *directly* — it reaches all of them through
`ServeCommand` — so it is still a command the server's command surface has to account for.

## The one durable lesson

The measurement was only actionable because the seam was made real first. `serve` was a **package**,
not a module: with no boundary, coupling accrued invisibly, and the 151 symbols crossing it were not
knowable until something counted them. The contracts the server needed were *assumed* publishable,
and which module published a given package was assumed from its name; both assumptions were wrong
somewhere until they were checked.
