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

`previews.json` carries **base ids only** — discovery reads bytecode and cannot instantiate a provider, so it has no idea how many values there are. `PreviewManifestRouter` therefore resolves a row id at routing time: it splits `<baseId>_<row>` against the manifest entries that declare a provider (longest parameterized prefix wins, so `_Light` above stays part of the base), and hands the row token to the render body. The row render reports the id the caller asked for and writes its own `<stem>_<row>.png`, so it never clobbers the base render.

Label matching is **exact first**, falling back to a case-insensitive match only when exactly one row matches it — label derivation distinguishes `Dark` from `dark`, so a provider yielding both produces two files and folding case unconditionally would silently collapse them onto the first. An index token (`PARAM_<n>`) is positional and works whether or not the fan-out labelled that value. There is no enumeration RPC yet — the way to discover a provider's rows is to ask for one past the end (`PARAM_255`), which fails with the provider's actual row list. Both lanes are bounded by `PreviewParameterSupport.MAX_ROW_SCAN` (256), which is also the highest addressable index: an index request enumerates only as far as it must and is rejected outright above the ceiling, so neither an infinite `generateSequence` provider nor an arbitrarily large `PARAM_<n>` in a caller-supplied id can drive the renderer to exhaustion.
