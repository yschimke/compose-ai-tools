# Permissions for agent workflows

Agents using this skill run the same handful of commands on every iteration
(render, read PNGs, occasionally stage copies). Most agent harnesses
(Claude Code, Cursor, Cline, Aider, Copilot, etc.) support some form of
pre-approval — allowlist entries, trusted-command lists, or auto-accept
rules. The exact syntax differs, so the lists below are patterns rather
than a specific config file. Translate them into your harness's format and
keep anything that publishes or mutates shared state on the prompt path.

**Safe to pre-approve** (read/render, only writes under gitignored `build/`):

- `compose-preview` — all subcommands write under `build/compose-previews/`.
- `./gradlew` / `gradle` — already trusted in most JVM projects.
- Reading `**/build/**` — rendered PNGs and staged copies live here.
- `mkdir -p`, `cp`, `rm -f` — for staging copies (see below).
- Read-only git: `git worktree add|remove|list`, `git ls-remote`, `git fetch`,
  `git show`, `git diff`, `git log`. Used by the PR-review workflow to render
  a base branch without touching the working copy.
- Read-only `gh`: `gh pr view`, `gh pr diff`, `gh run list|view [--log[-failed]]|watch`,
  `gh workflow list|view`, `gh release list|view`, `gh auth status`, plus GET
  on `gh api repos/<owner>/<repo>/{comments,actions,contents,…}`. The
  `gh run` calls come up constantly when a render fails on the runner.

**Never** (even with consent — these are the wrong tool for the job):

- **`rm -rf` against `build/classes/**`, `build/intermediates/**`, or
  `build/tmp/**`.** Agents that delete compiled output to "force" a fresh
  render are masking a bug in the freshness probe and risk leaving the
  module in a broken state. Use `compose-preview render --force=<reason>`
  (CLI) or `render_preview` with `force = { reason }` (MCP) instead — both
  go through the daemon's classloader-swap path without touching `build/`.
  Report each use on
  [issue #924](https://github.com/yschimke/compose-ai-tools/issues/924).
- **`./gradlew clean`** for the same reason. `--rerun-tasks` (which the
  CLI's `--force` flag sets for you) re-executes the render pipeline
  without throwing away the rest of `build/`.

**Require explicit consent** (publish or mutate shared state — keep on the
prompt path):

- `gh gist create` — public by default; even `--secret` URLs are shareable.
- `gh pr edit`, `gh pr comment`, `gh pr review`, `gh pr merge|close|reopen|ready`.
- `POST`/`PATCH`/`DELETE` via `gh api`.
- `git push`, `git commit`, `git branch -D`, `git reset --hard`.
- Uploads to external hosts (image hosts, paste services).

If the user approves a gist, PR edit, or push once, don't persist it as a
general allowlist entry — the next iteration may not want the same level of
publicity.

## Staging PNGs outside the render output

`compose-preview` writes each PNG under
`<module>/build/compose-previews/renders/<id>.png`. Reading those paths
directly works for a single iteration, but agents often want to hold
captures steady across a diff — before/after pairs, the subset a PR
touches, or images copied next to a worktree that's about to be removed.

Stage those copies **somewhere under `build/`**. Every Android/KMP project
`.gitignore`s that path, so nothing leaks into commits, and the location is
consistent across checkouts. The exact layout (`build/preview-staging/`,
`build/agent/<ts>/`, a module-local `build/…`, etc.) is up to the agent —
pick what fits the task.

Don't stage outside `build/`. Checked-in paths like `docs/` or `screenshots/`
risk committing generated binaries.
