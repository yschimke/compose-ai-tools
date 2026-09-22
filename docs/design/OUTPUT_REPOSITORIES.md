# Generated-output repositories

Status: measured proposal; the publisher seam is implemented, repository cutover is not.

## Decision

Generated delivery branches may live in a public repository separate from the source repository.
The source remains the owner of code, issues and provenance; the output repository is only the Git
transport for generated snapshots. Keep branch names unchanged (`design-artifacts/<system>`,
`design-parity/<system>`, and so on), so consumers change one repository slug rather than learning a
second branch convention.

Prefer one output repository per source repository:

| Source | Output |
| --- | --- |
| `yschimke/compose-ai-tools` | `yschimke/compose-ai-tools-out` |
| `yschimke/m3-catalog` | `yschimke/m3-catalog-out` |
| `yschimke/wear-m3-catalog` | `yschimke/wear-m3-catalog-out` |

Do not split `remote-m3` from Wear initially. They share source issues, a Figma reference cache and
the parity pipeline; another repository adds cross-output reads without making the source checkout
smaller.

## Measurement (2026-09-22)

`git rev-list --disk-usage --objects` over local full clones attributes the packed reachable bytes:

| Repository | All refs | Main | Generated refs |
| --- | ---: | ---: | ---: |
| `compose-ai-tools` | 1.19 GiB | 160.62 MiB | 916.88 MiB |
| `wear-m3-catalog` | 1.24 GiB | 38.02 MiB | 1.20 GiB |

GitHub reports `m3-catalog` at 1.32 GiB while its `main` tip tree is 114.9 MiB. Its current generated
tip trees total about 928 MiB. `design-parity` itself is 7.4 MiB: it is the publishing control plane,
not the storage problem.

The large histories had already been re-rooted on 2026-09-02. In the following twenty days,
`wear-m3-catalog` accumulated 494 commits on `design-artifacts/remote-m3` and 488 on
`design-artifacts/wear-m3-catalog`. Of those, 737 are issue-index refreshes and 243 are catalog
regenerations. Moving refs protects source development, but that rate proves a move alone does not
bound the output repository.

## Security and identity boundaries

The caller's `GITHUB_TOKEN` is scoped to the caller and cannot push another repository. A
cross-repository publish therefore passes `artifact-repository: owner/name` plus an
`artifacts_token` with Contents write access to that output repository. A GitHub App installation
token is preferred over a personal token.

Only the isolated publish job receives the write token. Render jobs execute source-project Gradle
code, so they read public reference and parity branches anonymously. This is why output repositories
are public even when a future caller's source is private.

Keep these identities separate:

* **source repository** — checkout, issue links, source provenance and
  `refs/design-artifacts/source/<system>`;
* **artifact repository** — generated branch fetch/push and raw content origin.

The source marker cannot move: it points at a source commit object that does not exist in the output
repository.

## Checkout mitigation

`actions/checkout` defines `fetch-depth: 0` as all history for all branches and tags. Metadata-only
gates using that setting must use `filter: blob:none` or fetch only their explicit before/after SHAs;
otherwise they download every historical PNG and bundle merely to inspect commit messages.

For a fresh development clone, prefer:

```bash
git clone --single-branch --branch main --filter=blob:none <url>
```

This is an immediate mitigation, not a substitute for moving the refs: ordinary full clones and
tools that manage their own clone still fetch every advertised branch.

## Retention

Output repositories need a policy independent of the move:

1. Skip byte-identical publications, including one-path issue-index updates.
2. Keep catalog history for a bounded window (initial target: 30 days), then re-root the branch at
   its current tree. The catalog publisher and preview server already tolerate a manual re-root.
3. Keep parity boards latest-only unless a consumer demonstrates a history requirement.
4. Keep only a small rollback window for reference-cache imports.
5. Monitor packed reachable size and re-root before 5 GiB; GitHub's current recommended on-disk
   maximum is 10 GB, not a target to approach.

`history.json` can retain compact observations longer than the blobs they describe. Git LFS is not
the answer here: it changes raw/archive behaviour and moves, rather than bounds, binary history.
Long-lived immutable archives belong in object storage if they become a product requirement.

## Pilot

Move only `design-artifacts/compose-m3` first. It is an internal harness catalog and exercises the
bespoke publisher, trust store and preview-server repository cutover without also changing PR
baselines or the VS Code preview branches.

1. Create public `compose-ai-tools-out`, disable Actions there, and grant the publisher App Contents
   write access.
2. Seed `design-artifacts/compose-m3` and configure the source repository variable
   `COMPOSE_M3_ARTIFACT_REPOSITORY=yschimke/compose-ai-tools-out` plus secret
   `COMPOSE_M3_ARTIFACTS_TOKEN`.
3. Verify a publish, then add the output repository to the server trust store and re-register the
   catalog there. The server's reconciliation already handles repository moves explicitly.
4. Stop/delete the source delivery branch only after the served catalog is healthy.
5. Verify a fresh full source clone no longer receives that branch's objects before moving the next
   lane.
