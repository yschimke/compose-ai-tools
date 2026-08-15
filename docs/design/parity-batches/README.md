# Component-parity delivery batches

Implementable batches for [#3680](https://github.com/yschimke/compose-ai-tools/issues/3680). Each
file is one branch's worth of work: what to build, what to read first, the traps that are already
known, and what "done" means.

The contract these implement is
[`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md). **Read it before starting any
batch** — these files say what to do, not why, and the why is load-bearing throughout.

## Order

```
00 decisions ──┬──────────────────────────────► (unblocks 02, 04, 05)
               │
01 locator ────┴──► 02 issue index ──────────────────────────────┐
      │                                                          │
      └──────────► 03 element selection ──┐                      │
                                          ├─► 05 engines ──► 06 resolution + docs
                        04 schema ────────┘
```

| Batch | Covers | Depends on | Ships something a person can see |
| --- | --- | --- | --- |
| [00](00-decisions.md) — decisions and corrections | — | nothing | no (unblocks 02/04/05) |
| [01](01-locator-and-report.md) — locator + issue body | [#3801](https://github.com/yschimke/compose-ai-tools/issues/3801), [#3802](https://github.com/yschimke/compose-ai-tools/issues/3802) | nothing | partly — issues gain a parseable identity |
| [02](02-issue-index.md) — issue index end to end | [#3804](https://github.com/yschimke/compose-ai-tools/issues/3804), [#3805](https://github.com/yschimke/compose-ai-tools/issues/3805), [#3806](https://github.com/yschimke/compose-ai-tools/issues/3806) | 01 | **yes** — open issues on the pages |
| [03](03-element-selection.md) — element selection | [#3803](https://github.com/yschimke/compose-ai-tools/issues/3803) | 01 | **yes** — click an element to report it |
| [04](04-acceptance-schema.md) — acceptance schema | [#3807](https://github.com/yschimke/compose-ai-tools/issues/3807) | 00 | no (contract + fixtures) |
| [05](05-acceptance-engines.md) — both engines + publish | [#3808](https://github.com/yschimke/compose-ai-tools/issues/3808), [#3809](https://github.com/yschimke/compose-ai-tools/issues/3809), [#3810](https://github.com/yschimke/compose-ai-tools/issues/3810) | 03, 04 | **yes** — accepted vs unaccepted scores |
| [06](06-resolution-and-docs.md) — resolution, closure, docs | [#3811](https://github.com/yschimke/compose-ai-tools/issues/3811), [#3812](https://github.com/yschimke/compose-ai-tools/issues/3812) | 02, 05 | **yes** — the loop closes |

**01 and 02 can run ahead of everything else.** They deliver the epic's first four acceptance
criteria and depend on none of the acceptance machinery. If only one batch gets done, do 01 — every
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
