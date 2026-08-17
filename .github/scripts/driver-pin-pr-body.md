## Summary

Moves the export-driver pin in `.github/design-artifacts-driver-pin.txt` from
`@BEFORE@` to `@SHA@` = **@TAG@**.

External callers of that workflow execute the pinned revision of
compose-ai-tools' `scripts/design-artifacts/`, so until this lands they keep
running the driver from the previous release — including any fix this repo has
already merged. Opened automatically on the tail of the @TAG@ release by
`refresh-driver-pin.yml` (issue #4107), rather than waiting for a downstream
failure to prompt a hand-written bump.

compose-ai-tools' own catalogs are unaffected either way: the driver revision
resolves to the caller commit for this repository, so this PR only changes what
external consumers run.

## Test plan

- `.github/scripts/refresh-driver-pin.sh --check` passes on the result — run in
  the workflow that opened this, and again by `ci`.
- `@TAG@` resolves to `@SHA@`, verified against the GitHub API in that run.
- The diff is the pin file only — nothing under `.github/workflows/`, which
  `GITHUB_TOKEN` may not push to.

> Opened by a workflow authenticated with `GITHUB_TOKEN`, whose pushes do not
> start workflow runs — so PR checks will not report until someone pushes to the
> branch or closes and reopens it.
