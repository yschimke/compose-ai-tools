# Render output layout

## `build/compose-previews/renders/` is ephemeral

Each `composePreviewRenderAll` run rewrites this directory and deletes stale files:

- The renderer (`RobolectricRenderTest` / `DesktopRendererMain`) deletes its own stale `@PreviewParameter` fan-out siblings before writing a new fan-out — it's the only code that knows the exact filenames the provider will produce.
- `composePreviewRenderAll` deletes any PNG/GIF not referenced by the current manifest (`cleanStaleRenders` in `ComposePreviewTasks.kt`). Parameterized `<stem>_*<ext>` matches are preserved — the plugin side can't enumerate provider values, so it trusts the renderer's own cleanup.

To keep a specific render across runs, copy the file somewhere outside `build/`.

## Filename normalization

`renderOutput` paths use `[A-Za-z0-9._-]` only. Any other character (spaces, parens, commas, Unicode dashes, emoji) collapses to `_`. A whitelist is deliberate: enumerating a blacklist of shell-hostile characters is a losing game across shells and CI systems.

The common dotted package prefix across all previews in a module is stripped too, so `ee.schimke.ha.previews.CardPreviewsKt.Foo.png` lands as `CardPreviewsKt.Foo.png`. `PreviewInfo.id` keeps the full FQN — consumers keying by id (CLI state, JUnit test names) are unaffected.

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
