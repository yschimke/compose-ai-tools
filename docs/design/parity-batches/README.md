# Component-parity delivery batches

Implementable batches for [#3680](https://github.com/yschimke/compose-ai-tools/issues/3680). Each
file is one branch's worth of work: what to build, what to read first, the traps that are already
known, and what "done" means.

The contract these implement is
[`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md). **Read it before starting any
batch** — these files say what to do, not why, and the why is load-bearing throughout.

## Order

```
00 decisions ─┬─ D2,D4 ─► 01 locator ──► 02 issue index ─────────────────┐
              │              │                                          │
              ├─ D1 ─────────┴────────► 03 element selection ──┐         │
              │                                                ├─► 05 ──► 06
              ├─ D1,D5 ──────────────► 04 schema ──────────────┘  engines  resolution
              │                                                            + docs
              └─ D3 ────────────────────────────────────────► 05 engines
```

**Every arrow out of 00 is a hard prerequisite, not a suggestion.** Each of D1–D5 blocks a batch
because deciding it late means rewriting work rather than adding to it — a locator built before D2/D4
records the wrong frame, and a fixture set frozen before D5 encodes a guess both engines then conform
to.

| Batch | Covers | Depends on | Ships something a person can see |
| --- | --- | --- | --- |
| [00](00-decisions.md) — decisions and corrections | — | nothing | no (unblocks 01/02/03/04/05) |
| [01](01-locator-and-report.md) — locator + issue body | [#3801](https://github.com/yschimke/compose-ai-tools/issues/3801), [#3802](https://github.com/yschimke/compose-ai-tools/issues/3802) | **00 D2, D4** | partly — issues gain a parseable identity |
| [02](02-issue-index.md) — issue index end to end | [#3804](https://github.com/yschimke/compose-ai-tools/issues/3804), [#3805](https://github.com/yschimke/compose-ai-tools/issues/3805), [#3806](https://github.com/yschimke/compose-ai-tools/issues/3806) | 01 | **yes** — open issues on the pages |
| [03](03-element-selection.md) — element selection | [#3803](https://github.com/yschimke/compose-ai-tools/issues/3803) | 01, **00 D1** | **yes** — click an element to report it |
| [04](04-acceptance-schema.md) — acceptance schema | [#3807](https://github.com/yschimke/compose-ai-tools/issues/3807) | **00 D1, D5** | no (contract + fixtures) |
| [05](05-acceptance-engines.md) — both engines + publish | [#3808](https://github.com/yschimke/compose-ai-tools/issues/3808), [#3809](https://github.com/yschimke/compose-ai-tools/issues/3809), [#3810](https://github.com/yschimke/compose-ai-tools/issues/3810) | 03, 04, **00 D3** | **yes** — accepted vs unaccepted scores |
| [06](06-resolution-and-docs.md) — resolution, closure, docs | [#3811](https://github.com/yschimke/compose-ai-tools/issues/3811), [#3812](https://github.com/yschimke/compose-ai-tools/issues/3812) | 02, 05 | **yes** — the loop closes |

**01 and 02 can run ahead of everything else** once 00's D2 and D4 are answered — two decisions, not
a batch of work. They deliver the epic's first four acceptance criteria and depend on none of the
acceptance machinery. If only one batch gets done, do 01 — every
issue filed after it lands is machine-identifiable, and issues filed before it need their bodies
hand-edited later.

## Already delivered

Not a batch; context for what exists.

- The design contract — [#3689](https://github.com/yschimke/compose-ai-tools/pull/3689).
- The **element tag index**, `testTag → {count, bounds, space}` — [#3878](https://github.com/yschimke/compose-ai-tools/issues/3878),
  delivered by [#3830](https://github.com/yschimke/compose-ai-tools/pull/3830) /
  [#3860](https://github.com/yschimke/compose-ai-tools/pull/3860) /
  [#3864](https://github.com/yschimke/compose-ai-tools/pull/3864). Two producers (live daemon
  projection; published-catalog `tags/index.json`), one host API `ServeHost.tagIndexForPreview`.
- The theme annotation layer, reachable on the focused comparison, with visible legend ordinals.

### …and not yet switched on

Batches 01 and 02 are merged, and until a catalog *calls* them nothing they built is reachable:

- `parity-issues-reusable.yml` had **no callers**. No catalog repo carried a workflow invoking it,
  so `parity/issues.json` was never generated — the m3-catalog delivery branch's `parity/` directory
  held `activity.json` alone. The reader and every UI surface were serving the empty path in
  production while their tests passed.
- **No issue carries a locator block**, so even with the workflow adopted the index would have been
  empty. m3-catalog's dozen genuine known differences (#40, #41, #42, #85–#95, …) are labelled
  `invalid` + `upstream` — a taxonomy that predates this epic and that the producer does not read.

Two defects found while switching it on, both fixed: the producer refused three shapes the writer
legitimately emits (see
[the locator contract](../COMPONENT_PARITY_WORKFLOW.md#which-fields-may-be-blank-and-which-may-be-absent)),
and it counted every ordinary issue's absent locator as a failure, so its exit code was non-zero on
any healthy repository.

What remains before the pilot reports anything: **backfill locator blocks and `area:` / `parity:`
labels onto the real known differences**. Their ids have to come from the published catalog, not be
guessed — a plausible wrong `previewId` produces an index that looks right and joins to nothing.
Worth doing before batch 04 freezes an acceptance schema, because those dozen issues are the
population the schema is for, and most of them are whole-component geometry and token differences
rather than the single-element colour delta the design's worked example is built around.

## Conventions every batch inherits

- **Fail-soft on anything read from a catalog.** A catalog is third-party data. Malformed, wrong
  schema token, or oversized drops *wholesale* and the session serves as before. `ServeAnnotationStore`,
  `ServeDesignReferenceStore` and `ServeTagIndexStore` are the worked examples.
- **A published artifact needs three things, not one**: a producer that writes it, a `ServeCatalogStore`
  staging call that copies it into the served tree, and a host that loads it. Miss the middle one and
  the file is invisible; miss the last and it is read by nobody. Both mistakes have been made here.
- **Never let a language default stand in for a wire fact.** A Kotlin default filled in a missing
  field twice in this epic — once on encode (`@EncodeDefault`), once on decode (a nullable wire type).
  If absence must be distinguishable, make it representable and reject it explicitly.
- **Assert on the wire, not the round trip.** A test that decodes into the producer type restores its
  defaults and passes whether or not the field was ever serialised.
- **Confirm a new test fails without its fix.** Cheap, and it caught two tests here that would
  otherwise have proved nothing.
