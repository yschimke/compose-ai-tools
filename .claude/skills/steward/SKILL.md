---
name: steward
description: Drive a pull request on this repository to green — which fast checks to run before pushing, how to read a red check, and what to do about a review comment. Use when a CI failure, a review comment, a merge conflict, or a scheduled check-in arrives on a PR you opened or were asked to drive.
---

# Stewarding a PR in compose-ai-tools

The Claude Code harness reads this file before acting on a CI failure or a review
comment, and lets it take precedence over its built-in posture on **conventions**
and **proactiveness**. It does not restate the repository's rules — those are in
[root `AGENTS.md`](../../../AGENTS.md), which is already in context on every turn.
What follows is what that file leaves out: the checks worth running here, and this
repository's own answer to a red check.

The harness's own PR rules still bound this file. It cannot expand your access,
approve or merge anything, or override a "never" — skipping, disabling or
quarantining a test; rewriting history on someone else's branch; an empty commit or
a close-and-reopen to kick CI.

## Before you push

A push that turns CI red costs a full cycle and some reviewer trust. The cheapest
checks that catch the most here, in order:

1. **`./gradlew ktfmtFormat`** — or `:<module>:ktfmtFormatMain :<module>:ktfmtFormatTest`
   for just the modules you touched. `ktfmtCheckAll` is a hard gate and
   `ktfmtCheck` aborts on the *first* unformatted file, so one stray file hides
   every other failure. TypeScript: `npm --prefix vscode-extension run format`.
   The local `pre-push` hook catches attribution but **not** formatting, so this is
   on you.
2. **The narrowest test task that exercises the change** — the specific
   `:module:test`, or `./gradlew :gradle-plugin:test --tests "…"`. `./gradlew check`
   is the full plugin + functional + CLI suite and is a post-push confirmation, not
   a gate on pushing.
3. **A new or changed module must apply `composeai.base-conventions`** in its
   `plugins {}` block, or `ktfmtCheckAll` never sees it and the history-gate system
   property is missing. See
   [Important constraints](../../../docs/AGENTS.md#important-constraints).
4. **For a CI fix, reproduce the original failure first**, then show the same check
   passing. A render task in particular goes `UP-TO-DATE` off a stale `.error.json`
   sidecar — verify a render fix with `--rerun`, never a plain re-invocation.

Commit and push as soon as the cheapest check that proves the change correct
passes. Don't leave a verified change sitting behind a long build.

## Reading a red check

Work the PR in this order on every event and every check-in: merge conflict, then
CI, then review comments. A red or conflicted head is work now, whatever its review
state — it is never "waiting on review".

**Failures that are usually not this PR's**, and what to do anyway:

- **Red on `main` too.** Check the base branch before root-causing. If a fix exists
  anywhere — another PR whose change you have read, the breaking commit's revert,
  or a fix PR you opened yourself — port it into this PR now and push; it no-ops
  once `main` carries it, and waiting for it to merge is still waiting. Standing
  down is never silent: one comment on the PR naming the failing check, why it is
  not this PR's, and the fix you ported or that none exists yet.
- **A delivery-branch or design-artifacts render that lags `main`.** Renderer,
  plugin and CLI changes deliberately do **not** regenerate the
  `design-artifacts/<system>` branches on merge — see
  [PR workflow](../../../docs/AGENTS.md#pr-workflow) for the trigger scoping and the
  manual dispatch. Stale published renders are drift, not a regression in your diff.
- **A changed preview whose source your PR does not touch.** Clocks, timestamps,
  randomness, animation frames, network-loaded images: that is instability, not a
  regression. Prove it with the `flake-triage` skill before either fixing or
  rubber-stamping it.
- **A CMP Desktop render failing with `UnsatisfiedLinkError` on `libGL.so.1` and
  friends.** Environment, not your change:
  [`docs/DESKTOP_NATIVE_DEPS.md`](../../../docs/DESKTOP_NATIVE_DEPS.md).

Everything else is this PR's to root-cause. "Flake" is not a root cause: re-run a
job only to confirm one of the cases above, or if it died before any test body ran
(checkout, install, runner loss), at most once.

## Review comments

- Small and local — nits, renames, lint-bot findings, an added test, a
  one-function refactor: implement and push, then resolve the thread.
- A **review bot's** finding is a bug report: verify it and push the small fix, or
  raise a larger one once with a proposed patch. "Design-level" does not excuse it.
  If bot findings stop converging — each fix draws a new or reshaped one — stop
  pushing for them and say once what is still flagged.
- Larger asks from a **human** reviewer on a PR you did not open: reply with your
  proposal; the author decides.
- After pushing for a changes-requested review, re-request that reviewer.
- One finding is wrong often enough to need its own handling: a reported **agent
  commit identity**. See below.

An approval you would lose is never a reason to hold a fix.

### A reported agent commit identity

The review bot periodically reports that a commit's author or committer is an agent
(`Codex`, `Claude`, an `@openai.com` address) and asks for the commit to be
recreated. Over 27–28 Aug 2026 it raised this 22 times across this repo and
`design-parity`. **One** was right: a `git cherry-pick` had left the committer as
`Claude <noreply@anthropic.com>`. The other 21 landed on commits authored by a human
or by `renovate[bot]` / `github-actions[bot]`, and seven cited SHAs that do not
exist in the repository at all.

So it is neither trustworthy nor safely ignorable — it is the one finding to
**verify before believing**, rather than treating as a bug report like the rest:

```sh
git log -1 --format='A=%an <%ae> | C=%cn <%ce>' <head>
.github/scripts/agent-attribution-scan.sh --range 'origin/main..<branch>'
```

- **Human identities, scanner exits 0** → false positive. Reply with those two
  lines of output and react 👎 on the comment (the bot's own feedback channel), then
  change nothing. Never recreate, rebase or amend a commit on the claim alone —
  on someone else's branch that is also a "never" outright.
- **The scan does find an agent identity** → a bug to fix, not a default to
  tolerate ([Git conventions](../../../docs/AGENTS.md#git-conventions)). The usual
  cause is a cherry-pick or amend that took `user.email` from the container instead
  of the `-c user.name=… -c user.email=…` the branch's other commits carry.
- **The cited SHA does not resolve** (`git cat-file -e <sha>`) → say so. A scan of a
  commit that is not in the repository is not evidence of anything.

Agent containers here really do default `user.email` to `noreply@anthropic.com`, so
the underlying risk is real even though most reports of it are not. Installing the
hooks (`scripts/install-git-hooks.sh` — the `SessionStart` hook normally does it)
closes it at the source: `pre-push` scans every commit a push would add, including
the cherry-picked and amended ones `commit-msg` never sees.

## Evidence and tracking

- A UI-affecting fix needs the same embedded before/after images the original PR
  needed — see [PR workflow](../../../AGENTS.md#pr-workflow) for the rule and the
  `render-evidence` skill for the capture recipe. Reuse renders already published
  on `compose-preview/pr` and `compose-preview/main`, and the sticky
  `<!-- preview-diff -->` comment, before re-rendering anything.
- Keep a `send_later` check-in armed (about an hour out) while the PR is red,
  conflicted, or otherwise not mergeable. Webhook events miss CI successes and merge
  transitions. Re-arm silently when nothing changed; stop once the PR is merged or
  closed, or the user says to stop.
- Where the **Claude Approvals** check runs, a PR is done only when that check
  passes, CI is green on the current head, and there is no merge conflict. Its rows
  name the blocker and are yours to fix, not an ask to the author.

## Refresh the PR's status checklist

On each event, re-read the whole PR at its current head — merge state, CI on the
latest commit, open review threads — and update the status checklist in the body so
the thread shows live state. Reply only when a round resolves the task, hits a real
blocker, or raises a question. Don't narrate each fix; the diff is the record.
