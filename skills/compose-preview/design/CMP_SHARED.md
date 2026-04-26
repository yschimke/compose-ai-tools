# Compose Multiplatform shared modules

Guidance for applying `compose-preview` to a Kotlin Multiplatform `:shared`-style
module — most commonly the canonical CMP-on-Android layout where Compose UI
lives behind `:shared` (KMP) and a thin `:composeApp` shell consumes it.

## TL;DR

Apply the plugin to the module whose source the previews live in —
`:shared` — and have it render those previews through the Compose Multiplatform
Desktop pipeline (Skia / `ImageComposeScene`), not Robolectric. Two
prerequisites:

1. **Declare a JVM target** on the shared module
   (`kotlin { jvm("desktop") }`) so the plugin has a JVM-flavor compile
   output to render against.
2. **Put `@Preview` functions in `commonMain`** (or any source set the
   JVM target compiles), not `androidMain` only — the latter compiles
   against the Android-flavor compose-runtime AAR, which calls into
   `android.os.Parcelable` and won't load on the host JVM.

The rest of this doc explains why and walks through the canonical setup.

## Why the Desktop path

Compose Multiplatform's Android target re-uses the AndroidX compose-runtime
artifacts (`androidx.compose.runtime:runtime-android` etc.). Their bytecode
depends on `android.*` classes that exist only inside an Android-shaped runtime
(emulator, device, or Robolectric sandbox). Loading those classes on the
host JVM throws `ClassNotFoundException: android.os.Parcelable` the first
time anything calls `mutableStateOf`.

Robolectric papers over the gap for AGP `com.android.application` /
`com.android.library` consumers. The new
`com.android.kotlin.multiplatform.library` plugin doesn't expose the AGP
unit-test pipeline (`test<Variant>UnitTest`, merged manifest, resource
APK) Robolectric needs, so wiring our renderer through it would mean
re-implementing most of [`AndroidPreviewSupport`](../../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt)
against a different DSL surface.

The Desktop renderer ([`DesktopRendererMain`](../../../renderer-desktop/src/main/kotlin/ee/schimke/composeai/renderer/DesktopRendererMain.kt))
runs `ImageComposeScene` directly on the host JVM and doesn't need any of
that — it just needs JVM-flavor compose-runtime + the preview composables
compiled to JVM bytecode. That's exactly what a `jvm("desktop")` target on
the shared module produces.

So when the plugin sees `com.android.kotlin.multiplatform.library` applied,
it routes the module through `ComposePreviewTasks.registerDesktopTasks`
with `androidRuntimeClasspath` / `desktopRuntimeClasspath` /
`build/classes/kotlin/<targetName>/main` added to the discovery and render
classpath candidates.

## Authoring rules of thumb

- **Hoist state.** `@Preview` calls composables with zero arguments. The
  same stateless-inner-composable pattern that makes any composable
  previewable (described in the parent `SKILL.md`) is what makes a shared
  composable previewable on every CMP target. Wrap the stateful path
  (`HomeRoute(viewModel = …)`) in androidMain; preview the stateless
  inner (`HomeScreen(state, onClick)`) in commonMain.
- **Prefer commonMain sources.** Anything that can live in commonMain
  should — that's the only source set the JVM target compiles against
  by default. Once a preview reaches into androidMain (ContextCompat,
  Activity, Bundle, Resources, drawables) the Desktop renderer can't
  capture it.
- **Keep previews small.** `@Preview` is a smoke test of the rendered
  output. Drive heavy navigation flows via screenshot tests or
  Roborazzi / Paparazzi where a full Android runtime is available; reach
  for `@Preview` for the leaf composables — buttons, cards, lists,
  empty states.
- **Multi-preview meta-annotations work.** `@PreviewLightDark`,
  `@PreviewFontScale`, custom multi-preview wrappers — all fan out the
  same way they do in single-target modules. The discovery scan reads
  the annotation graph from compiled bytecode, not from runtime
  reflection, so the source set the meta-annotation is declared in
  doesn't matter.
- **`@PreviewParameter` is supported.** Provider classes are looked up
  by FQN at render time on the Desktop renderer's JVM classpath. Put the
  provider in the same source set as the preview function.

If a preview genuinely needs androidMain (Android-specific font loading,
Material icons that ship as Android drawables, etc.), keep it in
androidMain and render it through a `:composeApp`-style sample instead —
applying the plugin to a `com.android.application` or `com.android.library`
module that depends on `:shared` puts you on the Robolectric path, where
the full Android runtime is available.

## Canonical setup

`shared/build.gradle.kts`:

```kotlin
plugins {
    // Apply the KMP/AGP/CMP plugins by id without a version — they're
    // already on the buildscript classpath because the AGP-9 bundle
    // pulls them in transitively. Using `alias(libs.plugins...)` here
    // would error with "the plugin is already on the classpath with
    // an unknown version, so compatibility cannot be checked".
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.compose.multiplatform)
    id("ee.schimke.composeai.preview")
}

kotlin {
    android {
        namespace = "com.example.shared"
        compileSdk = 36
        minSdk = 24
    }

    // Required: the JVM target gives the Desktop renderer a JVM
    // compilation to attach to. Target name is conventional ("desktop"),
    // anything works as long as the plugin's candidate list picks the
    // matching `${name}RuntimeClasspath`.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // ui-tooling-preview ships androidx.compose.ui.tooling.preview.Preview
            // on every target. The compose-multiplatform DSL's
            // `compose.components.uiToolingPreview` re-publishes it under
            // org.jetbrains.compose.ui.tooling.preview.Preview instead, which
            // discovery doesn't recognise — declare the explicit coord.
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.10.3")
        }
    }
}
```

`shared/src/commonMain/kotlin/com/example/shared/Previews.kt`:

```kotlin
package com.example.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview
@Composable
fun GreetingPreview() {
    Box(modifier = Modifier.size(200.dp).background(Color(0xFF7E57C2))) {
        Text("hello shared")
    }
}
```

Run from the project root:

```sh
./gradlew :shared:renderAllPreviews
```

Outputs land at `shared/build/compose-previews/renders/<id>.png`, identical
to any other module the plugin is applied to. The CLI / VS Code extension
discover and surface them with no additional configuration.

## Limitations

- **Previews in androidMain only are not rendered.** The plugin's
  `discoverPreviews` task scans the JVM target's compile output
  (`build/classes/kotlin/<targetName>/main`); androidMain-only sources
  don't compile into that tree. `discoverPreviews` will report 0 previews
  for them, and CI will fail on `failOnEmpty = true` if that's the only
  preview surface in the module. Move them to commonMain or render them
  via an AGP `:composeApp` shell.
- **No Robolectric features on this path.** No paused frame clock for
  infinite animations (`CircularProgressIndicator`,
  `rememberInfiniteTransition`, `withFrameNanos`), no
  `@RoboComposePreviewOptions` time fan-out, no `@ScrollingPreview`, no
  ATF accessibility checks. Those all rely on the Robolectric/AGP path.
  If a preview needs them, render it through a `com.android.application`
  / `com.android.library` consumer module that depends on `:shared`.
- **Android-flavor classes can leak in.** If something in commonMain or
  the JVM target's runtime classpath transitively pulls
  `androidx.compose.runtime:runtime-android` (e.g. a misconfigured
  multiplatform dep that resolves to the Android variant on the JVM
  classpath), the renderer will fail with
  `ClassNotFoundException: android.os.Parcelable`. Inspect
  `:shared:dependencies --configuration desktopRuntimeClasspath` and
  pin the desktop-flavor coords explicitly when this happens.

## Multi-module layouts

If the project also has a `:composeApp` (`com.android.application`) shell
that consumes `:shared`, applying the plugin to BOTH modules is fine:

- `:shared` renders commonMain previews via the Desktop renderer.
- `:composeApp` renders its own (and only its own) previews via Robolectric.

Don't expect `:composeApp` to discover previews that live transitively in
`:shared`. The plugin scans the applied module's compile output only —
applying to `:shared` is the supported way to surface `:shared` previews.
