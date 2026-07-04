# Invoking an agent from a GitHub issue or PR

How to summon Claude onto any issue or PR in this repo (and sibling repos)
with a single comment, have it complete the request in a cloud session with
**visual evidence**, and continue the conversation by replying to it.

## TL;DR usage

Comment on any issue or PR (requires write access to the repo):

> `@claude` review this PR and test the focus handling — show me before and
> after visually.

> `@claude` implement the requested changes to the login screen to make it
> adaptive.

A `Claude` workflow run starts within seconds, posts a single tracking
comment it keeps updating with progress, does the work (rendering previews,
editing code, committing to a branch), and finishes with results — including
inline before/after images for anything visual. Reply with another `@claude`
comment to steer it, request follow-ups, or push back on its answer: the next
run re-reads the whole thread and the branch it already pushed, so the
conversation resumes where it left off.

## How it works

[`.github/workflows/claude.yml`](../.github/workflows/claude.yml) runs
[`anthropics/claude-code-action@v1`](https://github.com/anthropics/claude-code-action)
— a full Claude Code session on a GitHub Actions runner ("cloud session" =
the runner; see [Alternatives](#alternatives-considered) for why not
claude.ai/code).

- **Trigger**: `@claude` in an issue/PR comment, review body, inline review
  comment, or a new issue's title/body (also fires on issue assignment).
  Events: `issue_comment`, `pull_request_review_comment`,
  `pull_request_review`, `issues`.
- **Gating**: the action only accepts triggers from users with **write
  access** to the repository (checked by the action at runtime, on top of the
  workflow's `if:` filter). Bots are refused by default. So "repo admin
  replies" is covered; drive-by comments from strangers are not runnable.
- **Context**: the action feeds Claude the issue/PR body, **all comments**,
  the diff, and a full checkout. Repo conventions load automatically from
  `CLAUDE.md` / `docs/AGENTS.md`, and the workflow appends a system prompt
  enforcing the visual-evidence contract below.
- **Where changes go**:
  - mention on an **open PR** → commits pushed directly to the PR's branch;
  - mention on an **issue** (or closed/merged PR) → a new `agent/…` branch
    off `main`, finishing with a link to a prefilled PR-creation page (the
    action deliberately does not open PRs itself, preserving branch
    protection).
- **CI awareness**: `actions: read` lets it fetch workflow-run status and job
  logs for the PR, so "why is CI red?" works.

### "Resuming" the session

Each `@claude` mention is technically a fresh workflow run — there is no
cross-run `--resume` (runners are ephemeral; the action does expose a
`session_id` output, but no supported flow persists it). In practice
continuity comes from **context re-ingestion**: the next run sees every prior
comment (including Claude's own report) plus the commits it already pushed,
and on an open PR it keeps working on the same branch. Replying
`@claude the after screenshot still clips the ring on the small round device`
behaves exactly like resuming the session.

### Visual evidence contract

The appended system prompt makes the repo rule from `CLAUDE.md` operational
inside the action:

1. Render affected `@Preview`s **before and after** with the compose-preview
   pipeline (`./gradlew <module>:composePreviewRenderAll` — the workflow
   allowlists `./gradlew`, `git`, and `python3` for exactly this).
2. **Commit the PNGs to the working branch and push first**, then embed them
   in the comment/PR body as `![](https://raw.githubusercontent.com/<owner>/<repo>/<commit-sha>/<path>.png)`
   — the same commit-SHA-pinned URL scheme the preview diff bot uses, so the
   images stay live after merge.
3. When reviewing an existing PR, reuse renders already published on the
   `compose-preview/pr` / `compose-preview/main` branches instead of
   re-rendering.
4. If the affected surface has no `@Preview`, add one — that also enrolls it
   in the sticky preview-diff comment on every future PR.

GitHub comments can't carry file attachments via the API, so committed
files + `raw.githubusercontent.com` is the only durable way for an Actions
run to put pixels inline. It's also already this repo's convention.

### Attribution

Branch commits land as `claude[bot]` via the Claude GitHub App. That is
compatible with the attribution rules on purpose:

- The [`No Agent Attribution`](../.github/workflows/no-agent-attribution.yml)
  gate bans agent **no-reply emails** (`noreply@anthropic.com` /
  `noreply@openai.com`) and agent `Co-authored-by:` trailers — a
  `claude[bot]@users.noreply.github.com` GitHub App identity on a **branch**
  commit is neither, and squash-merging from the PR title + body keeps `main`
  authored by the human who opens/merges the PR.
- The workflow sets `includeCoAuthoredBy: false` so the CLI never appends a
  `Co-authored-by: Claude` trailer to commit messages (which *would* trip the
  gate — and meshcore's equivalent).
- `branch_prefix: agent/` keeps branch names within house style
  (meshcore-mobile's CI hard-rejects `claude/…` branch names).

## Enabling this on another repo (the trivial part)

Two one-time admin steps, then copy one file:

1. **Install the Claude GitHub App** on the repo:
   <https://github.com/apps/claude> (or run `/install-github-app` from the
   Claude Code CLI, which walks through both steps).
2. **Add the `CLAUDE_CODE_OAUTH_TOKEN` secret** — generate it with
   `claude setup-token` (requires a Claude Pro/Max subscription). Runs then
   bill the subscription's included usage, sharing its rolling rate windows
   with your interactive Claude Code sessions. To meter against API credits
   instead (independent of personal usage, cappable via a dedicated
   workspace), add an `ANTHROPIC_API_KEY` secret and swap the workflow input
   to `anthropic_api_key`.
3. **Copy `.github/workflows/claude.yml`** and adjust three things:
   - the toolchain setup step (this repo: JDK 17 + Gradle via
     `./.github/actions/setup`);
   - the `--allowedTools` Bash allowlist (Bash is denied by default; allow
     the build/render commands the agent needs);
   - the `--append-system-prompt` (what "visual evidence" means for that
     repo's surfaces).

Sibling repos already wired: `meshcore-mobile` (JDK 21 setup, renders via the
compose-preview `apply` action it already consumes) and `design-parity`
(Node 22, report-html comparison pages).

## Limits

- Claude cannot merge, approve, or formally review PRs; cannot force-push or
  push to branches other than its own / the PR's; cannot edit workflow files
  (the App lacks `workflows` permission).
- Runs are capped at `--max-turns 100` / 90 minutes; a follow-up `@claude`
  mention continues from whatever was pushed.
- `@claude` in a PR **description edit** doesn't fire (only new comments,
  reviews, and issue open/assign do).

## Alternatives considered

- **Claude Code on the web (claude.ai/code) sessions from a mention** — not
  supported. Cloud "Routines" can start sessions from GitHub events, but only
  PR/Release events (no comments/mentions), always fresh sessions. The
  web-session **Auto-fix PR** feature is the inverse direction: a session you
  already opened subscribes to a PR's comments/CI and wakes on activity — the
  right tool for babysitting a PR authored from an interactive session, but
  not summonable from GitHub. Hence: Actions runner as the cloud session.
- **Anthropic-hosted Code Review** (`@claude review` on a PR) — zero-workflow
  managed review with inline comments; review-only (no code changes, no
  rendering), Team/Enterprise plans, billed per review. Complementary, not a
  substitute.
- **The preview-gated AI review pipeline**
  ([PR_REVIEW_WORKFLOW.md](PR_REVIEW_WORKFLOW.md)) — event-driven (every PR),
  not conversational. `claude.yml` adds the on-demand, instruction-following
  path next to it.
