// `:samples:design-catalog-m3` — a Compose **Multiplatform (desktop)** design
// catalog: one `@Preview` per component in its primary modes, authored so the
// `compose-preview` renderer turns the module into an importable sticker sheet
// (see `@design-parity/catalog-export` in yschimke/design-parity).
//
// This is the code-led source of truth for the M3 sticker sheet: the renders,
// the `compose/theme` token set, the `compose/semantics-wireframe` layout
// variant, and the a11y findings all come from these previews. The component
// bodies live once in `:samples:design-catalog-m3-shared` (`commonMain`), shared
// with the in-browser wasm tier (`:samples:cmp-wasm-catalog`); this module owns
// the `@Preview` sticker layer + theme.
//
// **It's a desktop CMP module, not Android** — it applies `org.jetbrains.compose`
// without any AGP plugin, so the compose-preview plugin routes it to the Desktop
// renderer (`ImageComposeScene`, no Robolectric / Android SDK). That's what lets
// the public **desktop-only** preview server build + live re-render it via the
// daemon (`serve --allow-render-trusted`), which it could never do while the
// catalog was an Android module.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

dependencies {
  // The shared, authoritative M3 component set (its `desktop` JVM variant).
  implementation(project(":samples:design-catalog-m3-shared"))

  // `previewOverride*` for the scaffold template's editable knobs (title / FAB / per-row text).
  // The shared module keeps this JVM-only runtime as a non-`api` desktop dependency, so this
  // consumer declares it directly. `PreviewSlot` is already reachable via the shared module's
  // `api(":slot-preview-runtime")`.
  implementation(project(":data-preview-overrides-runtime"))

  // Desktop CMP compose — mirrors the sibling `:samples:cmp` desktop sample.
  implementation(compose.desktop.currentOs)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.ui.tooling)
  // Republishes `androidx.compose.ui.tooling.preview.Preview` — the FQN
  // `PreviewDiscovery` scans for — on the desktop JVM target.
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)

  // Compose Multiplatform string resources: the scaffold template's title + message copy resolve
  // from the shared module's generated (public) `Res`, so a `localeTag` override renders the
  // template in the target language. Reachable transitively via the shared module's
  // `api(compose.components.resources)`, but declared directly since this module uses it head-on.
  implementation(libs.jetbrains.compose.components.resources)
}

// --- Published-preview runtime pinning
// -------------------------------------------------------------
// ⚠️ RELEASES ARE EFFECTIVELY REQUIRED FOR PUBLISHED PREVIEWS ⚠️
//
// The preview runtimes this catalog uses (`:data-preview-overrides-runtime` for `previewOverride*`,
// and `:slot-preview-runtime` transitively via the shared module) are `project(...)` deps, which
// `bundle pack` can only INLINE (a project jar has no re-resolvable coordinate) — ~80 KB of runtime
// jars carried in the published bundle, and, with per-preview bundles, in EVERY one.
//
// When design-artifacts builds the PUBLISHED bundle it sets the gate
// `ORG_GRADLE_PROJECT_composeaiUseReleasedRuntimes=true`. That swaps those project deps for their
// RELEASED Maven coordinates at [composeaiReleasedRuntimeVersion] (a release-please-managed
// property
// in `gradle.properties`), so `bundle pack` records small `ClasspathEntry.Maven` references
// (re-resolved from Maven Central at serve time) instead of inlining the jars.
//
// The consequence is deliberate: a NEW preview-runtime API/annotation added on `main` can't be used
// by a published preview until it's RELEASED — the released coordinate won't carry it (so the
// compile fails HERE, which is the guard), AND the live preview server that re-renders the bundle
// runs the RELEASED CLI/daemon, so it couldn't act on an unreleased annotation anyway. Ship the
// runtime change in a release first, then use it in the catalog.
//
// The pinned version is owned by release-please (it bumps `composeaiReleasedRuntimeVersion` on each
// release), so published previews target the current release's runtimes. Local / normal-CI builds
// leave the GATE unset, so they keep the `project(...)` deps and compile + test against HEAD.
if (providers.gradleProperty("composeaiUseReleasedRuntimes").orNull.toBoolean()) {
  val version =
    providers.gradleProperty("composeaiReleasedRuntimeVersion").orNull
      ?: error(
        "composeaiUseReleasedRuntimes is set but composeaiReleasedRuntimeVersion is missing from " +
          "gradle.properties"
      )
  logger.lifecycle(
    ":samples:design-catalog-m3: pinning preview-runtime deps to released $version for the " +
      "published bundle (new runtime APIs must be released before a published preview can use them)."
  )
  configurations.all {
    resolutionStrategy.dependencySubstitution {
      substitute(project(":data-preview-overrides-runtime"))
        .using(module("ee.schimke.composeai:data-preview-overrides-runtime:$version"))
        .because("published previews reference released preview-runtimes (see build note)")
      substitute(project(":slot-preview-runtime"))
        .using(module("ee.schimke.composeai:slot-preview-runtime:$version"))
        .because("published previews reference released preview-runtimes (see build note)")
    }
  }
}
