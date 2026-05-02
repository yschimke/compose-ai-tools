# Reviewing a PR via the MCP server

The default PR-review flow in [AGENT_PR.md](./AGENT_PR.md#reviewing-a-pr-agent-workflow)
shells out to the CLI inside two worktrees. That works without any setup, but
it pays Gradle's cold-config cost twice and gives the agent no way to react to
edits while the review is open. When the agent is already attached to the
[MCP server](../../compose-preview/design/MCP.md), prefer the flow below: same
two-worktree shape, but driven over MCP.

## When this is worth it

- The repo has more than a handful of `@Preview`s and you want **push
  notifications** (`resources/updated`) instead of re-running `compose-preview
  show` on every iteration.
- You expect to **edit on top of the PR** during review (suggesting fixes,
  trying alternative renders) and want the agent to react to file saves.
- You're reviewing **multiple PRs in one session** — register them all as
  workspaces, watch the relevant module on each, compare across.

For a one-shot "render base, render head, post a comment, done" review,
`compose-preview show --json` in two worktrees is still simpler.

## Two-workspace flow

1. **Worktree the base.** Same as the CLI flow — both classpaths must exist
   on disk, MCP can't dodge that.

   ```bash
   BASE=$(gh pr view <N> --json baseRefName -q .baseRefName)
   git worktree add ../_pr_base "origin/$BASE"
   ```

2. **Bootstrap descriptors on both sides.** `mcp install` is idempotent and
   batched per Gradle invocation — running it on each worktree is a small
   one-time cost that subsequent renders amortise.

   ```bash
   compose-preview mcp install --project /abs/path/to/your-repo
   compose-preview mcp install --project /abs/path/to/your-repo/../_pr_base
   ```

3. **Register both workspaces.** From the agent, after `initialize`:

   ```jsonc
   { "method": "tools/call", "params": { "name": "register_project",
     "arguments": { "path": "/abs/path/to/your-repo",
                    "modules": [":app"] } } }
   { "method": "tools/call", "params": { "name": "register_project",
     "arguments": { "path": "/abs/path/to/your-repo/../_pr_base",
                    "modules": [":app"] } } }
   ```

   Each call returns a `workspaceId`. They differ by construction —
   `WorkspaceId.derive` hashes the canonical path, so two worktrees of the
   same repo never collide.

4. **Watch both.** This propagates as `setVisible` / `setFocus` to the
   daemon, so the supervisor prioritises rendering the previews you're about
   to read:

   ```jsonc
   { "method": "tools/call", "params": { "name": "watch",
     "arguments": { "workspaceId": "<head-id>", "module": ":app" } } }
   { "method": "tools/call", "params": { "name": "watch",
     "arguments": { "workspaceId": "<base-id>", "module": ":app" } } }
   ```

   Wait for `notifications/resources/list_changed` on each — the daemon's
   discovery completes asynchronously.

5. **Read the PNGs by URI.** For every preview FQN the head workspace
   advertises, read the same FQN from the base workspace too. The URI
   suffix after `<workspaceId>/<encodedModulePath>/` is the stable preview
   id; bucketing into `changed` / `new` / `removed` is identical to the
   CLI flow.

   ```jsonc
   { "method": "resources/read",
     "params": { "uri": "compose-preview://<head-id>/_app/com.example.HomeScreen_dark" } }
   { "method": "resources/read",
     "params": { "uri": "compose-preview://<base-id>/_app/com.example.HomeScreen_dark" } }
   ```

   `resources/read` returns the PNG inline (`BlobResourceContents`,
   base64). Diff client-side; the bytes returned already include the
   daemon's per-render `sha256` so you can short-circuit on equality
   without decoding.

6. **(Optional) iterate.** When you edit source on the PR head as part of
   the review (proposing a fix), tell the daemon directly rather than
   re-rendering the world:

   ```jsonc
   { "method": "tools/call", "params": { "name": "notify_file_changed",
     "arguments": { "workspaceId": "<head-id>",
                    "path": "/abs/path/.../HomeScreen.kt", "kind": "source" } } }
   ```

   The daemon's classloader-swap path picks up the bytecode change after
   the agent runs `./gradlew :app:compileKotlin`. Subscribed URIs fire
   `resources/updated`; the agent re-reads only those.

7. **Tear down.** When the review is done:

   ```bash
   git worktree remove ../_pr_base
   ```

   Connected agents see `unregister_project` calls (or just disconnect —
   the supervisor cleans up on stdin EOF).

## Posting the review

Same as the CLI flow — see
[AGENT_PR.md § Default: show the human the diffs inline, post a text comment](./AGENT_PR.md#2-default-show-the-human-the-diffs-inline-post-a-text-comment).
The MCP path saved you per-render Gradle re-bootstrap cost; it doesn't
change what the human reading the comment sees.

## Why two workspaces, not one with a branch flip

The natural-sounding alternative — register one workspace, `git checkout
base`, render, `git checkout head`, render — does not work:

- The daemon's classloader is pinned to whichever bytecode was on disk when
  it spawned. A branch flip on the same `build/` output produces stale
  renders unless you also `compileKotlin` between flips and send
  `notify_file_changed` for every touched file.
- `classpathDirty` will eventually fire and respawn the daemon, but the
  intermediate state is mixed-version PNGs.

Two worktrees give each daemon its own pristine `build/` and remove the
ordering problem entirely. Worktree creation is cheap (`git worktree add`
hard-links the object database).

## Caveats

- **Cold-start cost is per workspace.** First read on each side pays the
  daemon's spawn time (~600 ms desktop, ~5–10 s Robolectric). After that,
  everything is sub-second.
- **`@Preview` ids must match.** A renamed function counts as `removed`
  on base + `new` on head. The CLI flow has the same blind spot; flag
  rename suspicions in the review text rather than rubber-stamping.
- **Per-call overrides** (`render_preview` with `overrides`) only affect
  one render, not subsequent `resources/read` calls on the same URI. If
  you want a lasting variant — say "head at `fontScale=1.3`" — pass the
  override in the qualifier (`?config=fontScale_1.3`) so it's part of the
  URI.

## See also

- [`compose-preview/design/MCP.md`](../../compose-preview/design/MCP.md) —
  setup of the MCP server itself, `compose-preview mcp install/serve/doctor`.
- [`AGENT_PR.md`](./AGENT_PR.md) — CLI-driven review, agent-authored PRs,
  comment style, image hosting choices.
- [`CI_PREVIEWS.md`](./CI_PREVIEWS.md) — `compose-preview/main` baselines
  branch + PR-comment GitHub Actions for the no-agent path.
