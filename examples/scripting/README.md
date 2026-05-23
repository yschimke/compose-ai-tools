# `examples/scripting` — contrib-reference `compose-preview-scripting`

Reference implementation of the `compose-preview-scripting <path>` binary that
[`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib) will host. Lives
in this repo as a validated, build-tested template that consumes only the published surface
(`:preview-data-api` + `:gradle-preview-driver`).

**No dependency on `:cli`.** That's the point — the carve-out (issue #1084, steps A–C) was about
proving the published API is expressive enough to build features against. This module is the
proof.

## What's here

- `Main.kt` — standalone `main()`. Walks up for `gradlew`, opens a `GradlePreviewDriver`,
  renders all preview modules with `--with-extension a11y`, annotates each result with the
  per-preview accessibility entry (the `annotateA11y` helper), compiles + evaluates the script,
  exits 2 on any accumulated `fail(...)`.
- `ComposePreviewScript.kt` — `@KotlinScript`-annotated base class. DSL: `previews()` /
  `show(id)` / `fail(msg)`.
- `RenderedPreview.kt` — script-facing handle. `ui.a11y.hasErrors` etc. decoded from
  `dataExtensions["a11y"]` against the `:preview-data-api` mirror types.
- `ScriptRunner.kt` — `BasicJvmScriptingHost` wrapper. Unwraps `ResultValue.Error` so runtime
  exceptions in the script body (a typo in `show("…")`, a stock `require { }` trip) surface
  cleanly.
- `ScriptState.kt` — shared host ↔ script state. Pure data carrier.

## Migration to contrib

When contrib's copy job runs:

1. Copy the `src/` tree wholesale into `compose-preview-scripting/`.
2. Replace the `composeAiMavenPublishing` / `composeai.jvm-conventions` plugin applications
   with contrib's equivalents.
3. Resolve `:preview-data-api` and `:gradle-preview-driver` via Maven coordinates
   (`ee.schimke.composeai:preview-data-api`, `ee.schimke.composeai:gradle-preview-driver`)
   instead of `project(...)`.
4. Delete this directory from `yschimke/compose-ai-tools` — its job is done at that point.

## Wire-format note

The a11y types referenced here (`AccessibilityFinding` / `AccessibilityEntry` /
`AccessibilityReport`) come from `:preview-data-api`'s deprecated v1 mirror. When the
compose-preview wire format bumps to v2:

- The mirror types disappear from `:preview-data-api`.
- Contrib's copy switches to `:data-a11y-core`'s `ee.schimke.composeai.renderer` types, which
  are the canonical shape.
- The `dataExtensions["a11y"]` payload bumps to `compose-preview-data-a11y/v2`; the
  `A11Y_PAYLOAD_SCHEMA_V1` constant gets renamed / re-pinned.

Until the v2 bump lands, the mirror is the contract.

## Building / running

```bash
./gradlew :examples-scripting:installDist
./examples/scripting/build/install/compose-preview-scripting/bin/compose-preview-scripting \
  path/to/your.composepreview.kts
```

## See also

- Issue #1084 — design discussion and original DSL sketch
- `docs/AGENTS.md` — "Built-in scripts" note explaining why features like this don't belong
  baked into the CLI
- `:cli`'s deleted `cli-scripting/` module (in git history before `refactor: remove
  :cli-scripting from this repo`) — the previous attempt that depended on `:cli`'s `Command`
  base class
