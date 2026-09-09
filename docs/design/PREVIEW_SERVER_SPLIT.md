# Preview server extraction — historical record

> **The extraction happened.** The server lives in
> [yschimke/compose-preview-server](https://github.com/yschimke/compose-preview-server) and ships as
> `ee.schimke.composeai:compose-preview-serve` from there, first released as **2.0.0**. This page is
> kept as a record of what was decided and what is still open; the original analysis is
> [issue #3824](https://github.com/yschimke/compose-ai-tools/issues/3824) and this file's git
> history.

## What is left in this repository

Nothing that this document described as preparation. `cli/serve` and `cli/serve-web` are gone and
`:cli` launches the published server rather than linking it, so the three instruments built to
measure the seam went with them: the symbol ratchet (`scripts/check-serve-seam.py`), the artifact
probe (`preview-server/`, `scripts/check-preview-server-contracts.sh`) and the coupling gate
(`scripts/measure-serve-coupling.py`). A Maven coordinate is a stronger boundary than a source
scanner, so the ratchet had nothing left to measure.

One check from that work survives because it was never about the split:
[`checkDaemonLaunchSchema`](DAEMON_LAUNCH_SCHEMA.md), which polices a contract that has no module at
all.

## What the finished job settled

**The seam measured a package boundary, not a process boundary.** The symbols it counted were not
all server types: most contained no Ktor, being the render-host and history plumbing that
`bundle render`, `render matrix` and `history manifest` use offline. Those are now
[`:render-host`](../../render-host) in this repository — `:cli` takes `api(project(":render-host"))`
— after compose-preview-server#180 moved the module to the repository everything it serves already
lived in.

**The Ktor floor is gone, and `serve` is a launcher.** `ServeCommand` execs the published
`compose-preview-server` binary rather than linking `ServeRunner`, and names no
`ee.schimke.composeai.cli.serve` type; `compose-preview-serve` remains only as a test dependency, so
the server is absent from the production classpath entirely. That removes the forward edge of the
dependency cycle compose-preview-server#180 described. `browse`, `design` and `ui` reach it the same
way — `BrowseCommand` runs `ServeCommand(args, browseProject = true)` — so they are launcher
callers, not server consumers.

## The one durable lesson

The measurement was only actionable because the seam was made real first. `serve` was a **package**,
not a module: with no boundary, coupling accrued invisibly, and the 151 symbols crossing it were not
knowable until something counted them. The contracts the server needed were *assumed* publishable,
and which module published a given package was assumed from its name; both assumptions were wrong
somewhere until they were checked.
