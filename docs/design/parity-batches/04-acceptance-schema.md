# Batch 04 — the committed known-difference schema

**Issue:** [#3807](https://github.com/yschimke/compose-ai-tools/issues/3807).
**Depends on:** [00](00-decisions.md) D1 (the `plane` discriminant is meaningless until the plane
question is settled).
**Blocks:** [05](05-acceptance-engines.md) entirely — both engines and the publish path.
**Ships:** no user-visible change. The deliverable is a contract plus **conformance fixtures**, and
the fixtures are the deliverable, not a follow-up: they are the only thing keeping three runners (the
JS scorer, `design-parity`'s suite, and the server projector's Kotlin tests) honest about one
definition.

**Read first:** [`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md) §4 **in full**.
It is the longest section and it is the specification; this file is an index into it, not a
substitute.

`compose-preview-known-differences/v1` is defined **here**, in this repo, because `serve` is a
consumer and this is where the other wire contracts already live. `design-parity` and
`@design-parity/catalog-export` consume it.

```
.design-parity/
  known-differences.json
  known-differences/
    m3-iconbutton-tonal-glyph/
      mask.png
      accepted-candidate.png
```

---

## A record carries

A stable `id`; a **mandatory** `issue` URL; the full locator scope (`system` / `component` /
`previewId` / `referenceId` / `variant` / `overrides`); an optional `element` selector with its
authoring-time `bounds` and `tolerance`; `mask` and `acceptedCandidate` paths; **three** hashes
(`referenceSha256`, `maskSha256`, `acceptedCandidateSha256`); the canonical plane the mask was
authored against (`plane` discriminant plus the resolved box); `candidateTolerance`; and free-text
`note` + `acceptedAt`.

## The validation rules are the substance

Each exists because two engines would otherwise diverge on identical bytes.

- **Scope matching uses every recorded field.** Served ids are unique only *within* a system, so
  matching on `(previewId, referenceId)` alone lets a `wear-m3` acceptance suppress pixels in `m3`.
- **Both tolerances are bounded**: `candidateTolerance` ∈ `[0, 8]` **and integer** (8-bit channel
  distance), `element.tolerance` ∈ `[0.0, 0.25]` real. Both are numbers an author can use to disable
  the model quietly, so both are range-checked, inclusive at the ceiling. The integer requirement on
  `candidateTolerance` is normative and easy to drop: JSON has one number type, so `0.5` sails through
  a range-only check in JavaScript and is rejected by a Kotlin `Int` field — a cross-engine divergence
  produced by the validator itself. Fixtures must cover a **fractional** value, not just the endpoints.
- **The mask encoding is normative**: 8-bit greyscale, no alpha, `0` unmasked / `255` masked, strictly
  binary — and **checked in the `IHDR`, not inferred from samples**, because the browser normalises
  every decode to RGBA and an indexed or RGBA mask with binary values would otherwise pass.
- **Neither artifact may be animated.** An APNG passes every other check and then hands the two
  engines different pixels — a decoder reads `IDAT`, an `<img>` advances the animation. Reject on
  `acTL`.
- **Budget before decode**: 256 acceptances, 128 megapixels, 8192 px per axis, 8 MiB encoded per
  artifact — all versioned. The area cap does **not** imply the axis cap (`1 × 128,000,000` is inside
  the budget and undecodable in every browser), and neither implies the byte cap (`ServeCatalogStore`
  refuses any catalog asset over 25 MB). **Compare as you go and short-circuit** — never accumulate a
  total that can overflow differently in Kotlin and JavaScript.
- **`id` is constrained three ways**: as a path segment, as a map key (`__proto__` is a fine path
  segment and a catastrophic object key), and against **both** dot names — `.` passes a `..`-only
  check and normalises to the `known-differences` root.
- **Artifact paths are contained *and* portable**: `[A-Za-z0-9._-]` segments joined by `/`.
  `isSafeRelativePath` rewrites `\` to `/` before splitting, so `a\b.png` is checked as two segments
  and opened as one filename on POSIX; `#` and `?` become URL syntax when the host fetches rather
  than reads.
- **Hashes compare normalised — but only the *served* one may be spelled loosely.**
  `ServeDesignReferenceStore` lowercases a reference hash to validate it and then serves the original
  spelling, so raw string inequality reports `reference-changed` for an unchanged reference; both
  sides are lowercased before comparison (equivalently, compare decoded digest bytes). Separately and
  **first**, every hash *this schema owns* — `referenceSha256`, `maskSha256`, `acceptedCandidateSha256`
  — must be exactly 64 lowercase hex characters or the record is `schema-invalid`. Collapsing the two
  rules lets one engine lowercase an uppercase *recorded* hash and accept it while another rejects it,
  so they need **separate** fixtures: a rejecting uppercase-**recorded** hash, and an accepting
  uppercase-**served** / lowercase-recorded pair.
- **Result shape** is `{raw, accepted, unaccepted, statuses, validationFailures}`. `statuses` is keyed
  by acceptance id and **absent entirely** for a document-level rejection. `validationFailures`
  carries one entry per `(record, reason)` pair; `duplicate-id` gets one entry per duplicated *value*,
  ordered by first occurrence; token ordering is fixed so two engines serialise identically.
- **Cut from v1**: `kind: producer` and the structured `finding` matcher. Neither had an authoring
  path or defined semantics, and a field consumers must guess at is worse than an absent one. Do not
  reinstate either without an authoring path in the same change.

## Traps

- The wire type must make **absence representable** wherever absence is meaningful. A Kotlin default
  filled in a missing field twice in this epic — once on encode (fixed with `@EncodeDefault`), once on
  decode (fixed with a nullable wire type distinct from the domain type). Decide per field, and write
  the rejecting test.
- **Assert on the wire, not the round trip.** A test that decodes into the producer type restores its
  defaults and passes whether or not the field was ever serialised. Assert against raw JSON.
- The fixture set is consumed by three runtimes. Keep it language-neutral: JSON plus PNG bytes plus an
  expected-result JSON, no Kotlin or JS harness assumptions baked into the directory shape.

## Done when

- Every rule above has at least one **rejecting** fixture and one accepting one.
- The fixture set includes: a valid document; duplicate ids; both dot names; a `\`-bearing path; a
  `#`-bearing path; an RGBA mask with binary values; an APNG; each budget cap breached individually;
  each tolerance out of range at both ends **and a fractional `candidateTolerance`**; an
  uppercase-**recorded** hash that must be `schema-invalid`; an uppercase-**served** hash that must
  **not** report `reference-changed`; and a document-level rejection that yields no `statuses` map at
  all.
- A conformance runner exists in this repo and passes, so batch 05's two engines have something to be
  measured against on day one.
