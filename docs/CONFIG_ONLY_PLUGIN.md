# The configuration-only plugin

`ee.schimke.composeai.preview.config` lets you **commit Compose Preview configuration to your
build without pinning the rendering runtime**. You apply it, write a `composePreview { … }` block,
and the `compose-preview` CLI / VS Code extension supply the actual rendering engine at *their* own
version when they drive a render.

If you don't need to commit any configuration, you don't need this plugin at all — the CLI and the
VS Code extension **auto-inject** the full plugin into your build on the fly (see
[Isolated Projects vs. the auto-inject workflow](isolated-projects-autoinject.md)). The
configuration-only plugin is for when you *do* want a `composePreview { … }` block checked into your
repo (a non-default `variant`, daemon tuning, resource-preview axes, …) that every render — local,
CI, agent, or editor — picks up consistently.

## What it is (and isn't)

Applying `ee.schimke.composeai.preview.config`:

- registers the `composePreview { … }` DSL extension, and
- registers the `composePreviewApplied` marker task (`build/compose-previews/applied.json`) so
  tooling can discover the module and read its configured intent.

It deliberately does **not**:

- register any render / discovery tasks,
- pull in the Android Gradle Plugin or the renderer artifacts, or
- enforce a minimum Gradle version.

That last point is the whole reason it exists: *merely carrying preview configuration must never
break a build* when previews aren't being rendered. The heavy lifting (AGP wiring, classpath
resolution, Robolectric / Desktop rendering, the Gradle-version floor) all lives in the full runtime
plugin `ee.schimke.composeai.preview`, which the CLI injects only when it actually renders. When both
are present in one build they share a single `composePreview` extension instance, so your configured
values flow straight into the render path.

## How to apply it

**Pin the version once, centrally, and apply it without a version per module.** This is the pattern
that stays compatible with CLI auto-injection (see [the collision note](#avoid-a-per-module-version)
below).

In `settings.gradle.kts`:

```kotlin
pluginManagement {
  plugins {
    id("ee.schimke.composeai.preview.config") version "<version>"
  }
}
```

Then in each module that should carry preview configuration, apply it **without** a version — the
version is resolved from the central `pluginManagement` pin above:

```kotlin
plugins {
  id("ee.schimke.composeai.preview.config")   // no version here — resolved from pluginManagement
}

composePreview {
  variant = "demoRelease"
  // daemon { … }, resourcePreviews { … }, previewExtensions { … }, etc.
}
```

Look up the latest version on
[Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-config).

> **Don't apply it through a version-catalog `alias(...)` in a module's `plugins { }` block.** A
> catalog plugin entry carries a version, so `alias(libs.plugins.composePreviewConfig)` *requests*
> that version in the module — which is exactly the versioned per-module apply that collides with the
> CLI-injected runtime (see [below](#avoid-a-per-module-version)). Pin the version once in
> `pluginManagement { plugins { … } }` and apply the bare `id(...)` per module instead.

## How the CLI picks it up

When you run `compose-preview …` (or render from VS Code), the tool auto-injects the full runtime
plugin `ee.schimke.composeai.preview` at **its own** version via a Gradle init script. That injected
plugin finds the `composePreview` extension your config-only plugin already created and renders using
your committed configuration — you don't pass `--variant` or re-specify anything the build already
declares.

So the division of labour is:

| Concern | Owner |
| --- | --- |
| *What* to render (variant, daemon knobs, resource axes, …) | **you**, via `composePreview { }` + the config-only plugin |
| *How* to render (engine version, AGP wiring, Robolectric/Desktop) | the **CLI / editor**, via the auto-injected runtime plugin |

## The binary-stability contract

`compose-preview-config` is the one artifact that must stay binary-compatible across versions,
because it can end up on the buildscript classpath at two versions at once: the version *you* pinned,
and the version the runtime plugin (injected by the CLI) depends on. Gradle conflict-resolves those
to a single copy, so the public `composePreview { }` DSL surface is kept backwards-compatible. In
practice this means you can pin the config-only plugin once and rarely bump it, while the CLI's
engine moves independently.

## Avoid a per-module `version`

Do **not** apply the config-only plugin with an inline version inside a module's `plugins { }`
block:

```kotlin
// ⚠️ avoid
plugins {
  id("ee.schimke.composeai.preview.config") version "<version>"        // don't pin the version here
  // alias(libs.plugins.composePreviewConfig)  // ⚠️ same problem — a catalog alias requests a version
}
```

When the CLI auto-injects the runtime plugin, `compose-preview-config` is pulled onto that module's
buildscript classpath transitively. Gradle's `plugins { }` DSL rejects a *versioned* request
(`id(...) version "..."`, or `alias(libs.plugins.…)` — a catalog alias always carries a version) for
a plugin that is *also* on the buildscript classpath ("the plugin is already on the classpath with an
unknown version, so compatibility cannot be checked"). Pinning the version **centrally** in
`pluginManagement { plugins { … } }` and applying the bare `id(...)` **without a version** per module
sidesteps this — applying a classpath plugin by id with no requested version is allowed.

## What lands in the marker

The `composePreviewApplied` task writes a small JSON sidecar per module that tooling uses for
discovery. It records the configured intent so a config-only build is describable without running the
runtime:

```json
{
  "schema": "compose-preview-applied/v1",
  "pluginVersion": "<config-plugin version>",
  "modulePath": ":app",
  "moduleName": "app",
  "variant": "demoRelease",
  "enabled": true
}
```

## See also

- [Isolated Projects vs. the auto-inject workflow](isolated-projects-autoinject.md) — how the CLI
  injects the runtime plugin, and why.
- [How it works](HOW_IT_WORKS.md) — the discovery → render pipeline the runtime plugin drives.
- [Architecture (contributor)](AGENTS.md) — the `:gradle-plugin-config` module split, class by
  class.
