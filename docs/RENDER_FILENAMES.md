# Render output layout

## `build/compose-previews/renders/` is ephemeral

Each `renderAllPreviews` run rewrites this directory and deletes stale files:

- The renderer (`RobolectricRenderTest` / `DesktopRendererMain`) deletes its own stale `@PreviewParameter` fan-out siblings before writing a new fan-out — it's the only code that knows the exact filenames the provider will produce.
- `renderAllPreviews` deletes any PNG/GIF not referenced by the current manifest (`cleanStaleRenders` in `ComposePreviewTasks.kt`). Parameterized `<stem>_*<ext>` matches are preserved — the plugin side can't enumerate provider values, so it trusts the renderer's own cleanup.

To keep a specific render across runs, enable `composePreview.historyEnabled` or copy the file somewhere outside `build/`.

## Filename normalization

`renderOutput` paths use `[A-Za-z0-9._-]` only. Any other character (spaces, parens, commas, Unicode dashes, emoji) collapses to `_`. A whitelist is deliberate: enumerating a blacklist of shell-hostile characters is a losing game across shells and CI systems.

The common dotted package prefix across all previews in a module is stripped too, so `ee.schimke.ha.previews.CardPreviewsKt.Foo.png` lands as `CardPreviewsKt.Foo.png`. `PreviewInfo.id` keeps the full FQN — consumers keying by id (history folders, CLI state, JUnit test names) are unaffected.

## `@PreviewParameter` fan-out labels

Per-value suffix derivation (`PreviewParameterLabels` in each renderer module):

1. `Pair.first` → label.
2. `name` / `label` / `id` property (Kotlin property or Java-bean getter returning `String`) → label.
3. `toString()`, unless it's the default `ClassName@hash` form → label.
4. Otherwise `_PARAM_<idx>`.

Labels are sanitized against the same whitelist and capped at 32 chars. If two values produce the same sanitized label, every value in the fan-out falls back to `_PARAM_<idx>` so the filenames stay internally consistent.
