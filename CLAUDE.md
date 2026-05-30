See [docs/AGENTS.md](docs/AGENTS.md) for project context, architecture, commands, constraints, and conventions.

## Git conventions (must follow)

- **No `Co-Authored-By` trailers** in commit messages or PR bodies. Commits are attributed solely to the committer — do not add a Claude/AI co-author line, even by default.
- **Use conventional commits for PR titles and commit subjects** (`fix:`, `feat:`, `docs:`, `test:`, etc.) so squash merges feed release-please correctly.
- **Re-check PR state immediately before EVERY push, not just the first commit of a session.** A PR can merge between turns, so a check at the start is not enough. Right before pushing, `git fetch origin main` and confirm the branch head isn't already in `origin/main` (or read the PR's `state`/`merged`). If the PR has landed, STOP: do not stack commits onto the merged branch — create a fresh branch from `origin/main` for the follow-up and tell the user.

## PR workflow (must follow)

- **Open a PR automatically when finishing a coding task.** Once the change is committed and pushed to its branch, open a PR against `main` without waiting for the user to ask. Use a conventional-commit title and a short summary + test plan body. The only exceptions are pure-exploration sessions where the user explicitly said "don't open a PR" or "just investigate".
- **Always track PRs you've opened.** After creating a PR, subscribe to its activity (via `subscribe_pr_activity`) so CI failures, review comments, and review submissions wake the session. Do not ask the user whether to subscribe — subscribe by default and mention it in the turn that opens the PR.
- **Respond to PR review comments automatically.** When a `<github-webhook-activity>` event arrives for a tracked PR, investigate it and act per the harness rules: push a fix if the change is clear and in-scope, ask via `AskUserQuestion` if ambiguous, or skip silently if no action is warranted. For CI failures on tracked PRs, re-diagnose and push fixes until green or until you're genuinely stuck — don't go quiet mid-loop.
- **Stop tracking when asked.** If the user says to stop watching/babysitting/auto-fixing a PR, call `unsubscribe_pr_activity` for it and don't push further changes to that branch.
