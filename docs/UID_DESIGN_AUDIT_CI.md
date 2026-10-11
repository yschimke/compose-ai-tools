# Optional UID design audit workflow

`uid-design-audit-reusable.yml` centralizes the picture-based OpenRouter audit
used by the Home Assistant and MeshCore adaptive UID pilots. The caller only
needs its trigger, pilot directory, pinned canonical guidelines and explicit secret mapping:

```yaml
name: Adaptive UID design audit
on:
  workflow_run:
    workflows: [Adaptive UID pilot]
    types: [completed]
permissions:
  contents: read
  actions: read
jobs:
  audit:
    uses: yschimke/compose-ai-tools/.github/workflows/uid-design-audit-reusable.yml@<reviewed-commit-sha>
    with:
      pilot-directory: adaptive-uid-pilot
      guidelines-url: https://raw.githubusercontent.com/yschimke/m3-catalog/<commit-sha>/ui-builder.guidelines.json
      guidelines-sha256: <sha256-of-file-bytes>
    secrets:
      OPENROUTER_API_KEY: ${{ secrets.OPENROUTER_API_KEY }}
```

The caller must land on the app's default branch before `workflow_run` can run.
The shared workflow accepts only successful `workflow_run` events whose
`head_repository.full_name` matches the caller repository. Fork PRs and missing
head-repository metadata are skipped before allocating the credentialed job.
This limits automatic spending to repository-controlled heads; the $0.25 limit
is per invocation, not a daily quota. It checks out
that repository's **default branch**, never the PR revision. Its capture plan and guideline URL/digest therefore come from trusted default-branch
configuration. The staging implementation is pinned to design-parity.
The previous run's artifact is treated as data: only the bounded PNGs admitted by
design-parity's `scripts/uid/evidence.py --stage` reach the audit engine. UID files and PR code are not
executed with the API key.

The caller directory only needs `references.json`. The workflow fetches the
canonical guidelines over HTTPS, enforces a 1 MiB limit, verifies their SHA-256,
and saves both the exact bytes and URL/digest provenance in the audit artifact.
Use an immutable commit URL and a flat, nonempty published guideline pack; nested
includes are rejected so the archived bytes cover every rule used. No app-local
rule copy is required. A changed rule pack needs an explicit pin update.

`evidence-artifact` defaults to `adaptive-uid-evidence`; `audit-artifact` defaults
to `adaptive-uid-design-audit`. The build/render/compare workflow lives in
[design-parity](https://github.com/yschimke/design-parity/blob/main/docs/UID_PARITY_CI.md).

The engine remains pinned to CLI 2.40.0 and its matching installation action and
prompt source. Installation requires the release's SHA-256 digest and fails
closed if it cannot verify the download. It uses the same `OPENROUTER_API_KEY` name as remote-m3-catalog,
mapped only on the model step to `COMPOSE_PREVIEW_OPENROUTER_KEY`, with the
existing $0.25 limit and `--surface screen --annotate`. Missing keys and engine
errors are incomplete audits, not a clean result. The output artifact retains
capture bytes, rules, engine prompt source, run provenance, log, model response
and annotations. This workflow posts no comments or issues.

This is optional model critique, not the deterministic parity check or a claim
of measured accessibility coverage. It stays separate from design-parity's
model-free steady-state engine and is explicitly enabled by each caller.

Before changing the shared workflow, run `actionlint` on it and on the consumer
caller examples. Pin callers to immutable reviewed commits. For first adoption,
land the shared workflow before merging the caller; a provider branch commit can
be used to validate a consumer PR before landing.

Set `enabled: false` to opt out of the audit; the key can then be omitted. With
`enabled: true` (the default), configuring this caller means requesting a real
audit: a missing key or engine error fails rather than reporting clean evidence.
The deterministic parity workflow needs no model key and runs independently.
Audit artifacts overwrite the same name on reruns. When auditing several pilots
in one workflow, give each call a distinct `audit-artifact` and select its matching
`evidence-artifact`.

PR-generated images can contain text that steers the model's critique even after
image validation. Treat the response as untrusted, advisory feedback. It must not
be used as an authorization signal, an automatic merge gate, or instructions for
executing commands or changing repository state.

For a separate interactive design critique, use
[`UID_DESIGN_CRITIQUE_PROMPT.txt`](UID_DESIGN_CRITIQUE_PROMPT.txt) with the app's
README, capture plan and evidence. This shared prompt does not replace the
versioned guidelines engine prompt used by CI.
