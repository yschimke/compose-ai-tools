@AGENTS.md

The file above is the repository's canonical agent instructions — the CI-enforced
invariants, the PR workflow, and the map to `docs/`. Everything below is
Claude-Code-specific harness mechanics that no other agent can act on. Nothing
here restates a rule from `AGENTS.md`; if the two ever disagree, `AGENTS.md` wins.

## Claude Code

- **Repo-local skills live in [`.claude/skills/`](.claude/skills/)** and load on
  demand rather than on every turn:
  - `steward` — driving a PR you opened to green: which fast checks to run before
    pushing, what counts as a failure that is not this PR's, and the check-in
    loop. Read it before acting on a CI failure or a review comment.
  - `render-evidence` — capturing the before/after renders a UI-affecting PR
    needs, including bringing an Android SDK up in a fresh sandbox.
  - `flake-triage` — proving a preview the diff bot flagged is unstable rather
    than regressed.
- **Subscribe to every PR you open**, on the same turn you open it
  (`subscribe_pr_activity`). Don't ask — tracking is the default; mention it in
  the reply. Stop on request with `unsubscribe_pr_activity` and push nothing
  further to that branch.
- **Keep a check-in scheduled while a PR you own is red or conflicted**
  (`send_later`, roughly an hour out). Webhook events miss CI successes and merge
  transitions, so events alone are not enough. Re-arm silently when nothing
  changed; stop once the PR is merged or closed.
- **`<github-webhook-activity>` events on tracked PRs are not no-ops.** Push the
  fix when the change is clear and in scope, `AskUserQuestion` when it is
  ambiguous or architecturally significant, skip silently only for duplicates and
  echoes of your own comments. `steward` has the full posture.
