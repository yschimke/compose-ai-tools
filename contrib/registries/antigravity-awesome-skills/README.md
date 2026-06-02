# antigravity-awesome-skills publishing

Automated publishing of the `compose-preview` and `compose-preview-review`
skill bundles to the [antigravity-awesome-skills](https://github.com/sickn33/antigravity-awesome-skills)
registry — a 1,400+ skill, installable `SKILL.md` library with an npm installer
that targets Claude Code, Cursor, Codex CLI, Gemini CLI, Antigravity, Copilot,
and more.

This is one of the skill-registry rows tracked in
[issue #772](https://github.com/yschimke/compose-ai-tools/issues/772).

## Why this registry

Of the skill registries surveyed in #772 it is the one with a fully documented,
schema-backed, automatable contribution path:

- skills are plain `skills/<name>/SKILL.md` files validated against a
  [published JSON schema](https://github.com/sickn33/antigravity-awesome-skills/blob/main/schemas/skills-index.v1.schema.json)
  and a [strict validator](https://github.com/sickn33/antigravity-awesome-skills/blob/main/tools/scripts/validate_skills.py);
- contributions are **source-only** (we ship only the two `SKILL.md` files; the
  registry regenerates `skills_index.json`, `CATALOG.md`, etc.);
- the installer fans the same `SKILL.md` out to every supported host
  (`--claude`, `--cursor`, `--gemini`, …), which matches the multi-host goal of
  #772.

By contrast, Smithery has no CLI skill-publish path (install only), and
agentskills.so / awesome-mcp-style lists are one-off submissions — so neither is
a clean fit for *automated* publishing today.

## What's here

```
skills/compose-preview/SKILL.md          # render @Preview composables to PNG
skills/compose-preview-review/SKILL.md   # diff @Preview renders across a PR
publish.sh                               # validate + (optionally) open the PR
```

The two `SKILL.md` bundles are the source of truth; their bodies link back to
the canonical skills in [`yschimke/skills`](https://github.com/yschimke/skills).

## How publishing runs

[`.github/workflows/publish-antigravity-skills.yml`](../../../.github/workflows/publish-antigravity-skills.yml):

- **validate** — on every PR/push that touches these bundles, clones the
  registry, drops our `SKILL.md` in, and runs the upstream **strict** validator.
  No network writes.
- **publish** — called from
  [`release-please.yml`](../../../.github/workflows/release-please.yml) off the
  same release tag as the GitHub-release tarballs. It pushes the bundles to a
  fork we control and opens a PR against the registry.

The publish step self-guards on `ANTIGRAVITY_SKILLS_TOKEN`: until that secret
exists it runs in dry-run mode, so the automation can land before the token
does.

### One-time setup to enable the live PR

1. Fork `sickn33/antigravity-awesome-skills` to an account/org we control.
2. Add repo secret `ANTIGRAVITY_SKILLS_TOKEN` — a token with push rights to the
   fork and PR-open rights on the registry.
3. (Optional) repo variables: `ANTIGRAVITY_SKILLS_FORK` (default
   `yschimke/antigravity-awesome-skills`), `ANTIGRAVITY_SKILLS_GIT_NAME`,
   `ANTIGRAVITY_SKILLS_GIT_EMAIL` (a human identity for the commit).

## Run it locally

```bash
# Validate the bundles against the live registry (no writes):
DRY_RUN=1 contrib/registries/antigravity-awesome-skills/publish.sh

# Open the PR (needs GH_TOKEN + a fork you can push to):
GH_TOKEN=... AAS_GIT_NAME='You' AAS_GIT_EMAIL='you@example.com' \
  TAG_NAME=v0.0.0 contrib/registries/antigravity-awesome-skills/publish.sh
```
