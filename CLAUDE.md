See [docs/AGENTS.md](docs/AGENTS.md) for project context, architecture, commands, constraints, and conventions.

## Git conventions (must follow)

- **No `Co-Authored-By` trailers** in commit messages or PR bodies. Commits are attributed solely to the committer — do not add a Claude/AI co-author line, even by default.
- **Use conventional commits for PR titles and commit subjects** (`fix:`, `feat:`, `docs:`, `test:`, etc.) so squash merges feed release-please correctly.
- **Re-check PR state immediately before EVERY push, not just the first commit of a session.** A PR can merge between turns, so a check at the start is not enough. Right before pushing, `git fetch origin main` and confirm the branch head isn't already in `origin/main` (or read the PR's `state`/`merged`). If the PR has landed, STOP: do not stack commits onto the merged branch — create a fresh branch from `origin/main` for the follow-up and tell the user.
