# Batch 04 — the committed known-difference schema

**Issue:** [#3807](https://github.com/yschimke/compose-ai-tools/issues/3807).
**Depends on:** [00](00-decisions.md) **D1 and D5**. D1 because the `plane` discriminant is
meaningless until the plane question is settled; **D5 because the fixtures encode its six answers** —
resampler, mask-edge participation, denominator, contribution sign, rounding, match metric — and a
fixture set frozen ahead of them either encodes a guess or cannot be produced at all.
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
  artifact — all versioned. The header pass reads a **4096-byte prefix** of each artifact rather than
  the whole of it, so the byte cap is enforced by something that has not already allocated past it;
  the reader reports the artifact's full length alongside, and that is what the cap is measured
  against. 4096 is provably enough: only `PLTE` and `tRNS` may precede the first `IDAT`, which bounds
  a conforming header region at 1089 bytes. A chunk before the image data that is neither is
  `header-invalid` (the preflight's refusal), where the same chunk after `IDAT` is `decode-failed`
  (the decoder's). The area cap does **not** imply the axis cap (`1 × 128,000,000` is inside
  the budget and undecodable in every browser), and neither implies the byte cap (`ServeCatalogStore`
  refuses any catalog asset over 25 MB). **Compare as you go and short-circuit** — never accumulate a
  total that can overflow differently in Kotlin and JavaScript.
- **`id` is constrained three ways**: as a path segment, as a map key (`__proto__` is a fine path
  segment and a catastrophic object key), and against **both** dot names — `.` passes a `..`-only
  check and normalises to the `known-differences` root.
- **Artifact paths are contained *and* portable**: `[A-Za-z0-9._-]` segments joined by `/`.
  `isSafeRelativePath` rewrites `\` to `/` before splitting, so `a\b.png` is checked as two segments
  and opened as one filename on POSIX; `#` and `?` become URL syntax when the host fetches rather
  than reads. **Segments cap at 255 bytes** — the component limit on ext4, APFS and NTFS alike — so a
  256-character `id` cannot be checked out at all while a URL-backed consumer evaluates it happily.
  Per segment, never per path: `PATH_MAX` belongs to the reader's working directory, so a total-length
  rule would make identical bytes legal in one checkout and refused in another.
- **Integer-valued fields are checked as written.** A box's `x`/`y`/`width`/`height` and an
  acceptance's `candidateTolerance` must be canonical JSON integers — no fraction, no exponent —
  because `9007199254740991.1` is already `…991`, and `2.00000000000000000001` already `2`, by the
  time `isSafeInteger` can look; no bound closes that, since at every magnitude some fractional
  literal is nearer an integer than the spacing of doubles there. Scoped by containing object rather
  than by member name (an unknown property called `x` is `schema-invalid`, not a document refusal),
  and firing only on tokens that *round onto* an integer, since a still-fractional value is already
  caught with better attribution.
- **The `IDAT` run is one zlib datastream, consumed whole.** An inflater stops at the end of the
  first stream, so a second one can ride inside a permitted chunk with a correct length and CRC.
- **A second `60` is legal only at `23:59:60` UTC**, offset applied first — not a check that a leap
  second was really inserted then, which would need the IERS table. Reverses an earlier decline: that
  was aimed at the table-shaped version of the finding, and this one needs no table.
- **Boundaries are computed exactly.** The resampler's footprints are integers (scale by the target
  dimension) and the element gate compares `displacement / min(width, height)` against the tolerance —
  `0.145 × 200` is `28.999999999999996`, and a `4 → 3` resample puts a true `0.5` at
  `0.49999999999999994`, so both boundaries fell the wrong way in binary.
- **`IEND` must end the file.** Bytes after it bypass the allowlist, the placement rules and every
  CRC — a decoder that stops there stops checking there — so a second `IHDR` or an `acTL` rides along
  to a gate verdict here and a `decode-failed` elsewhere. Reverses an earlier "tolerated, nothing
  reads them" note; *nothing reads them* was the problem. The suite's oversize artifacts are padded
  inside the compressed stream instead (empty stored deflate blocks, zero-length `IDAT` chunks).
- **`element.tolerance` is spelled canonically** — `"0"` or `"0."` plus one to six digits, plain
  decimal, no exponent. It is the one bounded field that is not an integer, and the hole is not only
  a range one: `0.144999999999999999999` is strictly below `0.145` as a decimal and *exactly* `0.145`
  as a double, so the inclusive gate boundary landed on the wrong side for a legal document. A
  grammar rather than "the shortest decimal that round-trips", because that phrasing is spelled
  differently by each language's formatter (`1e-7` vs `1.0E-7`) and would refuse different documents
  on each side. The six-digit cap makes every tolerance an exact multiple of `1e-6`, so the gate
  compares `displacement × 1000000` against `micros × min(width, height)` in **arbitrary-precision**
  integers — those products exceed the safe-integer range *and* `Long`, because the axis cap bounds
  raster headers and not `$defs.box`, so `BigInt` / `BigInteger` rather than a machine integer.
  Trailing zeros stay legal because they cannot change a verdict. **Reverses an
  earlier decision** that `0.25` followed by a hundred zeroes stays valid.
- **A repeated JSON member name refuses the document.** RFC 8259 leaves it undefined and runtimes
  differ — last value, first value, or a hard refusal — so `{"id":"safe","id":".."}` addresses two
  different artifact directories from one committed file. Detected **on the text**: by the time there
  is an object, the evidence is gone.
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

## What landed, and the seam with 05

***Delivered.*** The contract's rules are
[`scripts/design-artifacts/known-differences.mjs`](../../../scripts/design-artifacts/known-differences.mjs),
the document shape is
[`known-differences.schema.json`](../../../scripts/design-artifacts/known-differences.schema.json)
(shape only — every verdict-deciding rule is prose in §4 and code in the module, because none of
them is expressible in JSON Schema), the fixtures are
[`fixtures/known-differences/`](../../../scripts/design-artifacts/fixtures/known-differences/), and
the runner is `known-differences.test.mjs` in the design-artifacts driver's `node --test` job.
[`png-lite.mjs`](../../../scripts/design-artifacts/png-lite.mjs) is the bounded header preflight and
the deliberately-malformed-file writer the fixtures need; `pngjs` is a driver dependency and is not
what either job wants, since a library decode allocates the oversized raster to measure it and
refuses to write the APNG, palette mask and lying header the suite is worthless without.

**The seam.** This batch pins every *verdict*: the refusals, the five gates, the resolution test, the
status precedence, the one entry per acceptance in `statuses`, and the exact ordering of
`validationFailures`. **Not** an ordering for `statuses` — §4 defines it as an unordered map and
gives it no ordering rule, so an implementer who read an ordering into this summary would build a
wire-order requirement other runtimes cannot preserve. It does not compute
`raw` / `accepted` / `unaccepted` — that is 05's separated-plane scoring path, and inventing numbers
here would pin a scorer nobody has written. The seam is expressed in the fixtures rather than left to
be remembered: each `expected.json` is a **partial** pin whose `pins` array names the keys a runner
must check, so 05 adds score keys to these same cases instead of authoring a second tree. For the
same reason the gate cases take their canonical-plane rasters as *inputs*, already resampled, and the
resampler is pinned by a group of its own — a kernel divergence must fail as a kernel divergence
rather than as a wrong verdict in sixty gate cases at once.

**Two things `v1` gained while being written down**, both because the contract as drafted was not
implementable without them:

- **`out-of-scope` is a fifth status.** `statuses` carries one entry per member of `acceptances[]`,
  and a comparison reaches only the acceptances whose entire recorded scope matches — #42's three
  share one document and no comparison reaches more than one. Without a token for "well-formed, but
  not about this comparison" an engine must invent a status, omit the entry, or misreport it as
  `valid`. Refusal outranks it, so the refusal set stays comparison-independent.
- **A per-record `reasons` list is deduplicated.** A record has two artifacts and several tokens are
  shared between them, so both headers unreadable is one `header-invalid`, not two;
  `validationFailures` carries one entry per `(record, reason)` **pair**. The two hash tokens are
  distinct precisely so that failure *can* be told apart per artifact.

**Still not covered here, and named so it is not mistaken for done.** The Kotlin runner is batch 05's:
the fixture tree is language-neutral and `ServeParityIssuesStoreTest` is the worked example of a
Kotlin test loading one of these directly, but nothing in `serve` reads a known-differences document
yet. The stage table's `tag index` row also needs the server projector's own Kotlin tests to consume
these payloads — pinning the artifact without running the code that produces it proves nothing about
the code that produces it.

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
