---
title: Install
layout: default
nav_order: 2
permalink: /install/
---

# Install

Most people don't need this page — the [home page](./) covers the one-step
paths for agents, VS Code, and the CLI. This page is the full reference:
every install surface, requirements, and CI recipes.

## CLI

The `compose-preview` binary works against any Compose project with **no
build edits** — it injects the preview plugin at runtime via a bundled
Gradle init script.

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

The installer drops the CLI on your `$PATH` and (unless you pass
`--cli-only`) the `compose-preview` / `compose-preview-review` agent skills
into the Claude / Codex / Gemini skill directories. Useful flags:

| Flag | Effect |
|------|--------|
| `--cli-only` | Install the CLI only, skip the skill bundles. |
| `--android-sdk` | Also install the Android `cmdline-tools` + platform + build-tools. |
| `--jdk 17,21` | Install the listed JDK majors. |
| `VERSION` | Install a specific release instead of latest. |

Then:

```sh
compose-preview doctor    # check Java + project compatibility
compose-preview list      # scan @Preview annotations
compose-preview render    # render every @Preview to PNG
compose-preview render-matrix --id com.example.MyPreview \
    --ui-mode light,dark --font-scale 1.0,2.0   # one preview across a grid
```

Because the CLI auto-injects, projects that already apply
`com.android.application` / `com.android.library` / `org.jetbrains.compose`
work without touching `build.gradle.kts`. Projects that already declare the
plugin are detected and left alone, so mixed setups don't conflict.

## Builds that apply AGP via a convention plugin

If your build supplies the Android Gradle Plugin through an **included build's
convention plugin** — the `build-logic` / `gradle/conventions` pattern used by
[Now in Android](https://github.com/android/nowinandroid), AndroidX, and
similar repos — auto-inject **cannot** apply the preview plugin, and discovery
will report `0 modules` with failures like:

```
NoClassDefFoundError: com/android/build/api/variant/AndroidComponentsExtension
```

This is a classloader limitation, not a bug. With the convention-plugin layout
AGP is applied by the included build, so AGP's classes live on the *convention
plugin's* classloader. Auto-inject can only add the preview plugin to each
project's **own** buildscript classpath — a sibling classloader that can't see
AGP — so the plugin throws the moment it touches `AndroidComponentsExtension`.
There is no Gradle init-script API to contribute a dependency to an included
build's classpath, so auto-inject genuinely can't reach AGP here.

**Apply the plugin from your convention plugin instead.** That puts it on the
same classloader as AGP, where it renders correctly. The CLI then detects the
included-build apply and skips auto-inject automatically (no
`--no-auto-inject` needed).

1. Add the preview plugin's **marker artifact** to your `build-logic` build's
   dependencies. This is the key step: it puts the plugin on the convention
   build's *runtime* classpath, so the compiled convention plugin can apply it
   by id. (Declaring it in `build-logic`'s `plugins {}` block with `apply
   false` only resolves it for that build script — not for the convention
   plugin applied to your app modules, which fails with `Plugin with id … not
   found`.) The marker coordinate is `<id>:<id>.gradle.plugin:<version>`:

   ```kotlin
   // build-logic/.../build.gradle.kts (the convention build)
   plugins {
       `kotlin-dsl`
   }
   dependencies {
       implementation(
           "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:<latest>"
       )
   }
   ```

   Make sure `build-logic`'s repositories include where the plugin is
   published (`mavenCentral()` / `gradlePluginPortal()`).

2. Apply it from your convention plugin alongside AGP:

   ```kotlin
   // build-logic/.../YourAndroidConventionPlugin.kt
   override fun apply(target: Project) = with(target) {
       pluginManager.apply("com.android.library") // or the AGP plugin you use
       pluginManager.apply("ee.schimke.composeai.preview")
       // …rest of your convention…
   }
   ```

3. Run the CLI as usual — it sees the included build provides the plugin and
   leaves your build alone:

   ```sh
   compose-preview render
   ```

This is the explicit "apply manually via your convention plugin" integration
mode: auto-inject is the zero-config convenience for the common
`plugins { id("com.android.application") }` layout; the convention-plugin
layout opts out of it by design.

## Gradle plugin (version-pinned)

If you'd rather wire it into your build explicitly, the plugin is published
to [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
— no auth, no PAT.

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "<latest>"
}
```

Pin the version shown on Maven Central. (The in-repo `samples/` apply it
*without* a version because they resolve it from this repository's own
included build — that's not drop-in for an external project.)

```sh
./gradlew :app:composePreviewDiscover    # scan @Preview annotations
./gradlew :app:composePreviewRenderAll   # render every @Preview to PNG
```

For direct `./gradlew` use with the auto-inject script (e.g. a CI step that
needs extra Gradle flags), materialise the init script once and thread its
path through each invocation:

```sh
INIT_SCRIPT="$(compose-preview init-script --path)"
./gradlew --init-script "$INIT_SCRIPT" :app:composePreviewRenderAll
```

## VS Code extension

Published to the
[VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
and [Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview)
(VSCodium / Cursor / Windsurf). Open the Extensions view (⇧⌘X /
Ctrl+Shift+X), search **Compose Preview**, click *Install*. It auto-injects
on every Gradle invocation it makes — no project changes needed.

## Agent skill

Point any agent that can fetch a URL at the
[`compose-preview` skill](https://github.com/yschimke/skills/blob/main/skills/compose-preview/SKILL.md)
— a complete install-and-iterate playbook. The skill checks whether the CLI
is present and bootstraps it (via the installer above) if not, so "point the
agent at the skill" and "run the installer" converge on the same place. See
[Agents & MCP](../mcp/) for the agent loop the skill drives.

## CI / GitHub Actions

Composite actions for pipelines:

- [`install`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/install) — pin the CLI on `$PATH`, with version-catalog + Renovate recipes.
- [`apply`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/apply) — unified pipeline: baselines on push, before/after PR comments, a11y + notification surfaces.

There's also a reusable, preview-gated AI PR-review workflow (Codex / Claude
/ Gemini): see
[PR review workflow](https://github.com/yschimke/compose-ai-tools/blob/main/docs/PR_REVIEW_WORKFLOW.md).

### Speeding up preview CI

Rendering is the long pole. Two levers, both about parallelism:

- **Sharding (on by default).** `composePreview { shards }` fans a module's
  previews across parallel JVM forks. The default is `0` (auto): the plugin
  sizes the fork count from the discovered preview cost and the runner's cores
  + memory, and only shards when the predicted saving is worth the extra JVM
  cold-starts — so small modules stay single-fork and heavy ones (many
  previews, GIF/animated captures) fan out automatically. Set `shards = 1` to
  force it off, or a fixed `≥ 2` to pin it. No workflow changes needed; auto
  sizing engages on the first CI run because the pipeline discovers previews in
  a separate Gradle pass before rendering.
- **Bigger runners.** Auto sharding is capped at `cores − 1` forks (leaving one
  core for the Gradle daemon), so it can only go as wide as the runner is. The
  default `runs-on: ubuntu-latest` is 2 vCPU on private repos (no sharding) and
  4 vCPU on public ones (up to 3 forks). For a large preview suite, point the
  preview job at a 4- or 8-vCPU runner — that's where the two levers compound
  into real wall-clock savings. RAM scales with cores on GitHub-hosted runners,
  which keeps the per-fork memory bound out of the way.

## Render classpath conflicts

The render JVM classpath is built from a **single** resolved dependency graph:
the plugin's renderer configuration `extendsFrom` your module's unit-test
runtime classpath (and its `screenshotTest` runtime classpath, when Google's
screenshot plugin is applied), so Gradle picks one version per module across
the renderer's dependencies and your own. Only entries that exist nowhere else
— the unit-test merged `R.jar`, generated class dirs — are appended from AGP's
test task on top.

Before this, those graphs were resolved separately and concatenated, which put
two versions of the same module in front of one classloader. Java loads the
first match per class, so a class from the winning jar could link against a
sibling only the other version defined, and you'd get a `NoSuchFieldError` or
`NoSuchMethodError` in an unrelated `<clinit>` — e.g. two `bcprov-jdk18on`
jars failing every accessibility preview inside BouncyCastle's post-quantum
`KeyFactorySpi`.

A guard runs before each render and reports any module still present at more
than one version, naming each version and the jar that wins:

| Property | Default | Effect |
| --- | --- | --- |
| `composePreview.classpathDuplicates` | `warn` | `fail` turns a duplicate into a build error (good for CI); `off` silences the check. |
| `composePreview.legacyClasspathUnion` | `false` | `true` restores the old concatenated classpath. Escape hatch only — set it if a render suddenly can't find a class that lives solely on your unit-test classpath, and please file an issue. |

If the guard reports a duplicate, align the module in your own build: a version
force, or a `belongsTo` alignment rule for a family published under several
coordinates (`org.bouncycastle:bcprov`/`bcutil`/`bcpkix` is the common one —
Gradle can only align coordinates it knows are the same module).

## Requirements

Java 17+, Gradle 8.13+, AGP 8.13.0+ (Android), Kotlin 2.0.21+, Compose
Multiplatform 1.10.3+ (Desktop). The bottom edge of this envelope is
exercised on every push by the
[`agp8-min` job](https://github.com/yschimke/compose-ai-tools/blob/main/.github/workflows/integration.yml).

Running an agent in a cloud sandbox (Claude Code on the web, etc.)? See the
[cloud sandbox setup](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md)
for the network allowlist and `install.sh --android-sdk` Setup recipe.
</content>
