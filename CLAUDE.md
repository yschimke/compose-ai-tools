See [docs/AGENTS.md](docs/AGENTS.md) for project context, architecture, commands, constraints, and conventions.

## Git conventions (must follow)

- **No `Co-Authored-By` trailers** in commit messages or PR bodies. Commits are attributed solely to the committer — do not add a Claude/AI co-author line, even by default.
- **Use conventional commits for PR titles and commit subjects** (`fix:`, `feat:`, `docs:`, `test:`, etc.) so squash merges feed release-please correctly.
- **Before adding commits to an existing PR branch, check whether the PR has already landed.** Fetch `origin` and inspect the PR state or compare against `origin/main` first. If the PR has merged, create a fresh branch from `origin/main` for follow-up work instead of stacking commits onto the merged branch.
