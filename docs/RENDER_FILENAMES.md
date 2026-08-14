# Render output layout

## `build/compose-previews/renders/` is ephemeral

Each `composePreviewRenderAll` run rewrites this directory and deletes stale files:

- The renderer (`RobolectricRenderTest` / `DesktopRendererMain`) deletes its own stale `@PreviewParameter` fan-out siblings before writing a new fan-out — it's the only code that knows the exact filenames the provider will produce.
- `composePreviewRenderAll` deletes any PNG/GIF not referenced by the current manifest (`cleanStaleRenders` in `ComposePreviewTasks.kt`). Parameterized `<stem>_*<ext>` matches are preserved — the plugin side can't enumerate provider values, so it trusts the renderer's own cleanup.

To keep a specific render across runs, copy the file somewhere outside `build/`.

## Filename normalization

A render stem is **`<readable>-<digest>`**:

```
ActivityListPreview_Devices_Large_Round-4f9c2a17.png
└──────────── readable ──────────────┘ └ digest ┘
```

- **`<readable>`** — the function name plus any `@Preview(name = …)` variant suffix, sanitised. The charset is `[A-Za-z0-9_]`; any other character (spaces, parens, commas, Unicode dashes, emoji) collapses to a single `_`, and runs collapse together, so `Devices - Large Round` becomes `Devices_Large_Round`. A whitelist is deliberate: enumerating a blacklist of shell-hostile characters is a losing game across shells and CI systems. Capped at 80 chars so a stem plus its structural suffixes stays inside the 255-byte `NAME_MAX` that ext4, APFS and NTFS all enforce.
- **`-<digest>`** — 8 hex chars of `sha256(preview.id)`, over the id verbatim. `-` is unambiguous here because sanitisation can never emit one inside `<readable>`.

The package and class never appear. `PreviewInfo.id` keeps the full FQN — consumers keying by id (CLI state, JUnit test names) are unaffected.

### Why the digest is unconditional

It makes a stem a **pure function of one preview's own id**, and that single property carries every guarantee the filenames need:

| Property | What it buys |
|---|---|
| Stable | Adding, removing or renaming any *other* preview in the module cannot change this preview's filename — the [tie backstop](#the-tie-backstop) below is the sole exception. Commit-pinned render URLs keep resolving, and base-vs-head visual diffing sees a diff rather than a delete + add. |
| Collision-free | Distinct ids that sanitise identically (`Foo_bar` vs `Foo-bar`) get distinct digests. |
| Case-safe | `Foo_Dark` and `Foo_dark` stay distinct files on case-insensitive filesystems (APFS, NTFS), where the readable parts alone are one file. |
| Suffix-safe | The digest sits between the readable part and the structural suffixes below, so a preview genuinely named `Logo_animated` (`Logo_animated-<digestA>.png`) cannot collide with `Logo`'s Lottie sidecar (`Logo-<digestB>_animated.png`). |
| Reserved-name-safe | A preview named `CON` or `NUL` becomes `CON-<digest>`, which is not a Windows reserved device name. |

This replaced a shortest-unique-suffix walk that read every sibling preview to decide how much of the package/class path to prepend, with a positional `_<idx>` tiebreaker on top. That scheme renamed existing PNGs whenever an unrelated preview was added, renumbered on manifest reordering, and could mint a `_<idx>` name that silently overwrote a preview genuinely named that way.

### The tie backstop

If two ids ever agree on *both* readable part and truncated digest, only the tied previews are re-stemmed with a full-length sha256 — still a pure function of the id, so the rest of the module is untouched and the result stays stable under reordering.

This is the one case where a preview's filename can change because of a *sibling*, and it is a deliberate trade. Resolving a collision inherently requires knowing about the collision — that fact lives in the pair, not in either id — so no per-id-only rule can both keep names stable and keep them unique. Dropping the backstop in favour of an unconditional wider digest would turn a tie back into a **silent overwrite**, which is the bug this scheme exists to prevent. A rename is loud and recoverable; an overwrite loses a render while the manifest still reports success.

The trigger needs two previews to collide on the readable part *and* on 32 bits of digest. Since the readable part is function-plus-variant, a match already means two identically-named functions in different classes within one module; for 20 such previews the probability is around 5 × 10⁻⁸. The ordering the scheme commits to is **correctness > stability > brevity**.

## `@PreviewParameter` fan-out labels

Per-value suffix derivation (`PreviewParameterLabels` in each renderer module):

1. `Pair.first` → label.
2. `name` / `label` / `id` property (Kotlin property or Java-bean getter returning `String`) → label.
3. `toString()`, unless it's the default `ClassName@hash` form → label.
4. Otherwise `_PARAM_<idx>`.

Labels are sanitized against the same whitelist and capped at 32 chars. If two values produce the same sanitized label, every value in the fan-out falls back to `_PARAM_<idx>` so the filenames stay internally consistent. The `PARAM_<digits>` shape is **reserved**: a value whose derived label lands on it is treated as unlabelled and gets its own `_PARAM_<idx>` instead, so the positional row address below can never be confused with a label.

### Those suffixes are also the daemon's row addresses

The same `<stem>_<suffix>` spelling addresses one row on the **daemon** (issue #3749) — `renderNow`, `serve`, `render_preview`, `interactive/start` and `recording/start` all take a row-addressed previewId:

```
MyScreenPreview_Light            # the base id: binds the provider's value 0
MyScreenPreview_Light_Dark       # the row whose derived label is "Dark"
MyScreenPreview_Light_PARAM_4    # row 4, positionally
```

`serve` lists those rows too: its Gradle path renders before it starts the server, so `ServeParameterRows` reads the fan-out back off disk and publishes one servable preview per row. A run that didn't render (bundle-backed) keeps the base id alone.

The **CLI selectors take a row id as well** (issue #3819) — `compose-preview show --id MyScreenPreview_Light_PARAM_4` prints that row and nothing else, and `--filter` / `--preview` narrow a fan-out the same way, since they are substring rules over the same id. `render --output` accepts one too, which is how you ask for a single provider value's PNG. Selecting a row narrows the Gradle render to the row's **base** preview rather than the whole module (`PreviewRenderScope`), so precision costs nothing. An `--id` that names a preview which really exists always wins over a same-named row of some parameterized preview.

Both readers of the fan-out — `ServeParameterRows` for the server and `PreviewResultBuilder` for `show` / `list` / `render` — derive the row set through one shared rule, `PreviewParameterFanout`: which files belong to the preview, what the row token is, how rows are ordered, and the `<baseId>_<row>` id spelling. Two derivations would drift, and a drift here means selecting one row and being handed another. `CaptureResult.parameterRowId` carries the resulting id into the CLI's JSON, so an agent can select exactly what it was shown (`parameterLabel` is a lossy human coordinate — `parameter 4` — and is never a selector).

`previews.json` carries **base ids only** — discovery reads bytecode and cannot instantiate a provider, so it has no idea how many values there are. `PreviewManifestRouter` therefore resolves a row id at routing time: it splits `<baseId>_<row>` against the manifest entries that declare a provider (longest parameterized prefix wins, so `_Light` above stays part of the base), and hands the row token to the render body. The row render reports the id the caller asked for and writes its own `<stem>_<row>.png`, so it never clobbers the base render.

Label matching is **exact first**, falling back to a case-insensitive match only when exactly one row matches it — label derivation distinguishes `Dark` from `dark`, so a provider yielding both produces two files and folding case unconditionally would silently collapse them onto the first. An index token (`PARAM_<n>`) is positional and works whether or not the fan-out labelled that value. To discover a provider's rows without a rendered directory to read, call the daemon's [`preview/rows`](daemon/PROTOCOL.md#previewrows) — it returns one `{index, label, id}` per value, and an empty list for a preview that declares no provider. Both lanes are bounded by `PreviewParameterSupport.MAX_ROW_SCAN` (256), which is also the highest addressable index: an index request enumerates only as far as it must and is rejected outright above the ceiling, so neither an infinite `generateSequence` provider nor an arbitrarily large `PARAM_<n>` in a caller-supplied id can drive the renderer to exhaustion.
