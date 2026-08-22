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

### …and now switched on

Batches 01 and 02 were merged and unreachable for a while: nothing *called* them. That is fixed.

- `parity-issues-reusable.yml` had **no callers** — no catalog repo carried a workflow invoking it,
  so `parity/issues.json` was never generated and the reader and every UI surface were serving the
  empty path in production while their tests passed. The caller is
  [yschimke/m3-catalog#170](https://github.com/yschimke/m3-catalog/pull/170), which publishes the
  index on every issue event.
- Two producer defects found while switching it on, both fixed in
  [#4404](https://github.com/yschimke/compose-ai-tools/pull/4404): it refused three shapes the
  writer legitimately emits (see
  [the locator contract](../COMPONENT_PARITY_WORKFLOW.md#which-fields-may-be-blank-and-which-may-be-absent)),
  and it counted every ordinary issue's absent locator as a failure, so its exit code was non-zero
  on any healthy repository.
- **Three issues now carry a locator block** and `area:` / `parity:` labels — m3-catalog #40, #41
  and #87, with `previewId` / `referenceId` read out of the published `references/index.json` rather
  than guessed. `parity/issues.json` carries them, and preview.coo.ee shows them on the viewer, the
  focused comparison, the grid cards and the parity dashboard.

**Read the measurement before starting batch 04.** The backfill is also the survey of the population
the acceptance schema is for, and it came out smaller and more awkward than "a dozen known
differences" suggests. Two counts, and they are not the same number:

- **Four of m3-catalog's ten can carry a locator** — #40, #41 and #87 do; #89 could, and is left out
  only because there is nothing to accept in it. Of the rest, three (#85, #95, #86) are about
  components this catalog does not publish, #91's variants are unauthored, and #42 and #93 name
  several components each, which one locator cannot say.
- **Four are acceptance candidates, across six sites** — #40, #41, #87, plus #42 three times over:
  an acceptance is per preview, and §4 lets several of them share one tracking issue, so the case
  the index cannot hold whole is still three acceptances. Only #40's mask is glyph-sized; #41's is
  most of the bar, #87's a 2dp ring, and #42's a shadow surrounding each component.

The per-issue verdicts and what each implies are in
[the pilot population](../COMPONENT_PARITY_WORKFLOW.md#the-pilot-population-measured); batch 00's D5
should be answered against those numbers rather than against the worked example.

**Four decisions have since been settled, and two of them changed the wire:**

- **D1 is answered (a)** — the tag index publishes render-pixel bounds and names its space; the
  canonical-plane transform belongs to the comparison.
- **An umbrella issue carries one locator block per component**, and index rows are keyed by issue ×
  component in both engines. This unblocks #42 and #93.
- **`element` and `bounds` are reserved in `v1`** — batch 01's requirement, landed late as an
  erratum, so batch 03 adds a selection to the existing version rather than bumping to `v2`.
- **An acceptance may be geometric**, with an element gate required wherever an element exists. #41
  and #87 are expressible; batch 04 must carry both shapes.

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
