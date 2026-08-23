# `known-differences/` — conformance fixtures for `compose-preview-known-differences/v1`

**Generated. Do not hand-edit — run `node build-known-difference-fixtures.mjs` instead.**
The recipe for every byte here is in that script, so a reviewer checks a fixture by reading how
it was built rather than a hex dump.

The contract these pin is
[`COMPONENT_PARITY_WORKFLOW.md` §4](../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-normative-contract).
Three runtimes consume this tree — this repo's `known-differences.test.mjs`, `design-parity`'s own
suite, and the server projector's Kotlin tests — so nothing in the layout assumes a language.

## A case

```
cases/<case-id>/
  case.json                  # the comparison, the catalog for the orphan walk, synthesis recipes
  known-differences.json     # the document under test (raw text, so `document-unreadable` is reachable)
  artifacts/<id>/mask.png    # `.design-parity/known-differences/<id>/` stands in here
  artifacts/<id>/accepted-candidate.png
  canonical-reference.png    # the comparison's canonical-plane rasters, already resampled
  canonical-candidate.png
  expected.json              # the verdict, and which of its keys are normative
```

`expected.json` is a **partial** pin: its `pins` array names the keys a runner must check. A key
listed there must match exactly; a key that is absent is not pinned by any batch *yet*. The score
stages — `raw`, `accepted`, `unaccepted` — are the ones batch 05 adds, over these same cases.

The canonical-plane rasters arrive **already resampled**, deliberately. The portable kernel has its
own group under `resample/`, so a resampler divergence fails there rather than surfacing as a wrong
verdict in sixty gate cases at once — which is the entire reason for pinning intermediate stages.

`synthesize` is how a case expresses a file too big to commit: append `padZerosTo - length` zero
bytes to the named base file. Trailing bytes after `IEND` are ignored by every decoder and never
reached by a preflight that stops at the first `IDAT`, so the only thing they change is the encoded
byte length.

## The pilot population

Measured rather than assumed, and smaller and more awkward than a dozen known differences
suggests: **four issues across six sites**, of which exactly one is the shape the model was drawn
around. Each has a case here.

| Site | Mask | Case |
| --- | --- | --- |
| m3-catalog#40 `IconButton/Tonal` | a glyph — the worked example | `pilot-40-iconbutton-tonal-glyph` |
| m3-catalog#41 `NavigationBar/Short` | most of the bar | `pilot-41-navigationbar-short` |
| m3-catalog#87 `Checkbox/Checked` | a 2dp ring around a 20dp box | `pilot-87-checkbox-checked-ring` |
| m3-catalog#42 ×3 (`Button/`, `Card/`, `ToggleButton/Elevated`) | a shadow surrounding each component | `pilot-42-elevated-shadow-trio` |

#89 and #93 are indexable and have nothing to accept, which is why the two counts name different
issues — six issues can carry a locator, four are acceptance candidates.

## Every case

| Case | What it pins |
| --- | --- |
| `pilot-40-iconbutton-tonal-glyph` | m3-catalog#40 — IconButton/Tonal glyph colour |
| `pilot-41-navigationbar-short` | m3-catalog#41 — ShortNavigationBar measures items at full bar width |
| `pilot-87-checkbox-checked-ring` | m3-catalog#87 — Checkbox box padding 2dp vs 4dp |
| `pilot-42-elevated-shadow-trio` | m3-catalog#42 — Elevated shadow level, three components on one issue |
| `gate-resolved-fixed-candidate` | The candidate gate fired and the region converged on the reference |
| `gate-candidate-changed` | The masked region is neither the accepted difference nor the reference |
| `gate-reference-changed` | The served reference no longer hashes to the recorded one |
| `gate-served-hash-uppercase` | An uppercase *served* reference hash must not report `reference-changed` |
| `gate-plane-changed-short-circuits-element` | A changed plane short-circuits the element gates |
| `gate-element-ambiguous` | The tag is carried by more than one node |
| `gate-element-vanished` | The tag resolves to nothing at all |
| `gate-element-moved-past-tolerance` | The resolved element moved further than `element.tolerance` allows |
| `gate-element-at-tolerance` | A displacement exactly at tolerance passes |
| `gate-multiple-causes` | Several gates fire at once |
| `set-overlapping-masks` | Two acceptances whose masks overlap |
| `set-mixed-validity` | One acceptance survives while its sibling is invalidated |
| `scope-other-system` | A `wear-m3` acceptance must not suppress pixels in `m3` |
| `scope-overrides-differ` | An acceptance authored at `fontScale=1.5` does not apply at the default frame |
| `scope-refusal-is-comparison-independent` | A record that is out of scope *and* broken is still `refused` |
| `document-over-byte-cap` | A document past the 1 MiB ceiling |
| `document-unreadable-truncated` | Truncated JSON |
| `document-unreadable-wrong-schema-token` | A document carrying a different schema token |
| `document-unreadable-acceptances-not-array` | `acceptances` is an object |
| `document-duplicate-ids` | One id used three times and a second used twice |
| `document-id-missing` | Absent, blank, numeric and object ids |
| `document-count-over-cap` | 257 acceptances — one past the cap |
| `document-count-at-cap` | 256 acceptances — exactly the cap |
| `document-combined-failures` | A duplicated id, an unkeyable record and an over-cap count at once |
| `document-pixels-at-cap` | 128 megapixels declared across the set — exactly the cap |
| `document-pixels-over-cap` | 128,008,000 megapixels declared — one raster past the cap |
| `document-axis-at-cap` | A raster exactly 8192 px on its long axis |
| `document-axis-over-cap` | A raster 8193 px on its long axis |
| `artifact-at-byte-cap` | A mask of exactly 8 MiB encoded |
| `artifact-too-large` | A mask one byte past 8 MiB encoded |
| `id-not-safe-proto` | An `id` of `__proto__` |
| `id-not-safe-single-dot` | An `id` of `.` reaching a sibling's `mask.png` |
| `id-not-safe-parent-dot` | An `id` of `..` |
| `id-not-safe-separator` | An `id` carrying a path separator |
| `path-not-contained-case-folded-collision` | Two artifact paths differing only in case |
| `accepted-at-lowercase-separators` | An `acceptedAt` using lowercase `t` and `z` |
| `schema-invalid-issue-url-untrimmed` | An `issue` with surrounding whitespace |
| `schema-invalid-accepted-at-impossible-date` | An `acceptedAt` with the right shape and impossible values |
| `schema-invalid-accepted-at-not-a-timestamp` | An `acceptedAt` that is a string but not a date-time |
| `path-not-contained-windows-reserved-name` | An artifact path segment Windows cannot open |
| `path-not-contained-trailing-dot` | An artifact path segment ending in a dot |
| `id-not-safe-windows-reserved-name` | An `id` Windows cannot open |
| `path-not-contained-backslash` | An artifact path containing a backslash |
| `path-not-contained-hash` | An artifact path containing `#` |
| `path-not-contained-parent` | An artifact path leaving the acceptance's directory |
| `path-not-contained-absolute` | An absolute artifact path |
| `mask-encoding-rgba-with-binary-samples` | An RGBA mask whose samples are strictly binary |
| `mask-encoding-palette-with-binary-samples` | An indexed mask whose palette entries are strictly binary |
| `mask-encoding-anti-aliased-sample` | A greyscale mask carrying one intermediate value |
| `mask-encoding-transparency` | A greyscale mask carrying `tRNS` |
| `mask-empty` | A mask that selects nothing |
| `animated-png-mask` | An animated mask |
| `animated-png-accepted-candidate` | An animated accepted candidate |
| `dimension-mismatch-mask-against-plane` | A mask that is not the recorded plane's size |
| `dimension-mismatch-accepted-against-mask-box` | An accepted candidate that is not the mask's bounding box |
| `hash-mismatch-both-artifacts` | Both artifacts fail their recorded hash |
| `hash-recorded-uppercase` | An uppercase **recorded** hash |
| `artifact-unreadable-missing-file` | A path that resolves to no file at all |
| `header-invalid-truncated-file` | A file that opens and holds too few bytes for an `IHDR` |
| `decode-failed-correctly-hashed-garbage` | A correctly hashed artifact that is not decodable |
| `tolerance-candidate-at-ceiling` | `candidateTolerance` of exactly 8 |
| `tolerance-candidate-at-floor` | `candidateTolerance` of exactly 0 |
| `tolerance-element-at-floor` | `element.tolerance` of exactly 0 |
| `tolerance-candidate-over-ceiling` | `candidateTolerance` of 9 |
| `tolerance-candidate-negative` | `candidateTolerance` of -1 |
| `tolerance-candidate-fractional` | `candidateTolerance` of 0.5 |
| `tolerance-element-over-ceiling` | `element.tolerance` of 0.3 |
| `tolerance-element-negative` | `element.tolerance` of -0.01 |
| `reference-hash-missing` | The targeted reference publishes no `sha256` |
| `acceptance-is-noop` | A stored candidate that already agrees with the reference |
| `acceptance-is-noop-yields-to-reference-changed` | A no-op acceptance whose reference has also moved |
| `schema-invalid-missing-issue` | A record with no `issue` |
| `schema-invalid-unparseable-issue` | An `issue` that is not a GitHub issue URL |
| `schema-invalid-unknown-element-kind` | An `element.kind` this version does not define |
| `schema-invalid-box-beyond-safe-integer` | A box coordinate past the safe-integer range |
| `schema-invalid-missing-plane` | A record with no recorded canonical plane |
| `orphaned-target-component-renamed` | The component was renamed while its ids stayed put |
| `orphaned-target-reference-detached` | The reference now hangs off a different preview |
| `orphaned-target-variant-disagrees-with-preview-id` | A recorded `variant` that disagrees with its own `previewId` |
| `document-duplicate-ids-case-folded` | Two ids differing only in case |
| `document-unreadable-unknown-property` | A document carrying a property `v1` does not define |
| `document-non-object-acceptances` | `acceptances` holding `null`, a string and an array |
| `schema-invalid-unknown-property` | A record carrying the `finding` field cut from `v1` |
| `schema-invalid-unknown-element-property` | An `element` carrying a property `v1` does not define |
| `schema-invalid-note-wrong-type` | A numeric `note` |
| `variant-empty-is-valid` | A default preview's empty `variant` |
| `decode-failed-chunk-crc-mismatch` | A hash-valid artifact whose `IDAT` CRC does not verify |
| `header-invalid-inflates-past-declared-size` | A small legal header in front of a much larger inflation |
| `decode-failed-chunk-not-permitted` | An artifact carrying an ancillary chunk |
| `decode-failed-colour-space-chunk` | An artifact carrying a colour-space chunk |
| `decode-failed-duplicate-ihdr` | A second `IHDR` |
| `decode-failed-trns-after-idat` | A `tRNS` after the image data |
| `decode-failed-non-empty-iend` | A non-empty `IEND` |
| `decode-failed-trns-on-alpha-colour-type` | A `tRNS` beside a colour type that already carries alpha |
| `zero-alpha-rgb-is-normalised` | Transparent pixels whose hidden colour differs |
| `decode-failed-plte-after-trns` | A truecolor `PLTE` placed after `tRNS` |
| `decode-failed-trns-sample-out-of-range` | A `tRNS` sample the image's bit depth cannot contain |
| `decode-failed-empty-palette-trns` | A zero-length palette `tRNS` |
| `decode-failed-palette-on-greyscale` | A `PLTE` in a greyscale image |
| `decode-failed-missing-iend` | A stream truncated after a complete `IDAT` |
| `decode-failed-unsupported-compression-method` | An `IHDR` declaring a compression method the specification does not define |
| `decode-failed-interlaced-accepted-candidate` | An interlaced accepted candidate |
| `decode-failed-16-bit-accepted-candidate` | A 16-bit accepted candidate |
| `decode-failed-unrecognized-critical-chunk` | An unrecognized **critical** chunk with a valid CRC |
| `trns-transparency-is-decoded` | An accepted candidate carrying `tRNS` |

## The resampler

| Case | What it pins |
| --- | --- |
| `downscale-2x1-average` | Four pixels averaged into one |
| `rounding-exactly-half` | An average landing exactly on .5 |
| `downscale-non-integer-ratio` | Three pixels into two — partial footprints |
| `upscale-integer-ratio` | Two pixels into four |
| `alpha-is-a-fourth-channel` | Alpha averaged without premultiplication |

## Sub-pixel rounding

Outward, to the enclosing integer box. Its own group because every gate case is handed canonical
boxes that are already integers — without these, a second engine could round inward or to nearest
and still pass the whole suite.

| Case | What it pins |
| --- | --- |
| `integer-box-is-unchanged` | A box already on the grid |
| `fractional-origin-floors` | A fractional origin |
| `fractional-far-edge-ceils` | A fractional far edge |
| `fractional-both-ends` | Fractional at both ends |
| `negative-origin` | A box whose origin is negative |
