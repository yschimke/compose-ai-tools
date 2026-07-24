package ee.schimke.composeai.discovery

import io.github.classgraph.AnnotationClassRef
import io.github.classgraph.AnnotationEnumValue
import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.MethodParameterInfo
import io.github.classgraph.ScanResult
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/**
 * Pure-JVM library that scans compiled Kotlin classes for `@Preview`-annotated functions and
 * produces a [PreviewManifest] conforming to the `compose-previews/v1` schema. The Gradle plugin's
 * `DiscoverPreviewsTask` is one adapter; non-Gradle build systems (Bazel rules, Amper task
 * definitions in `yschimke/compose-ai-contrib`) call this directly to produce conforming manifests
 * without depending on Gradle or AGP.
 *
 * Logger-agnostic — diagnostics are returned as structured strings, not emitted to a Gradle
 * `Logger`. Each adapter routes them to its build system's logging surface.
 *
 * Wire-stable contract: the [Outcome.Success.manifest] is `kotlinx-serialization`-encoded by the
 * caller (typically with `Json { prettyPrint = true; encodeDefaults = true }`) and lands on disk as
 * `previews.json`.
 */
object PreviewDiscovery {

  /** Inputs the scan needs from the calling build system. All paths are absolute. */
  data class Input(
    /** Directories of compiled `.class` files belonging to the consumer module. */
    val classDirs: List<File>,
    /**
     * Dependency JARs to merge onto the scan classpath. Filtered down to a preview-relevant subset
     * by the scanner.
     */
    val dependencyJars: List<File>,
    /** Source files used to attach module-relative `sourceFile` paths to each [PreviewInfo]. */
    val sourceFiles: List<File>,
    /**
     * Logical module name surfaced via [PreviewManifest.module]. Bazel rules use the target label;
     * Gradle uses the project path.
     */
    val moduleName: String,
    /** Build variant ("debug" / "release" / "desktop"). Surfaced via [PreviewManifest.variant]. */
    val variantName: String,
    /** Module root — `PreviewInfo.sourceFile` is rendered relative to this. */
    val projectDirectory: File,
    /**
     * When `true` and zero previews are discovered, the scan returns [Outcome.Failure] with a
     * diagnostics block.
     */
    val failOnEmpty: Boolean,
    /**
     * Processed-resource roots (e.g. `build/resources/main`) scanned for Lottie animation assets.
     * Each `.json` file whose structure looks like a Lottie document, and each `.lottie` archive,
     * becomes a [PreviewKind.LOTTIE] preview with no consumer composable — "just having the file is
     * enough". Empty (the default) skips the scan entirely. Paths are absolute.
     */
    val resourceDirs: List<File> = emptyList(),
    /**
     * Subdirectory (under the `compose-previews` root) that Lottie capture `renderOutput` paths are
     * placed in. Defaults to `"renders"` — the shared primary carousel dir, used on the desktop
     * backend where the desktop renderer is the only writer. The Android backend overrides it to a
     * disjoint dir (e.g. `"lottie-renders"`) so the JVM Lottie render task doesn't share the
     * `renders/` output with the Robolectric render — keeping both tasks cacheable (overlapping
     * task outputs disable Gradle's build cache). The missing-render gate resolves `renderOutput`
     * relative to the `compose-previews` root, so any subdir validates uniformly.
     */
    val lottieRenderSubdir: String = "renders",
    /**
     * Subdirectory (under the `compose-previews` root) that [PreviewKind.SVG] capture
     * `renderOutput` paths are placed in. Same rationale as [lottieRenderSubdir]: defaults to
     * `"renders"` on the desktop backend (the desktop renderer is the only writer) and is
     * overridden to a disjoint dir (e.g. `"svg-renders"`) on the Android backend so the JVM SVG
     * render task doesn't share `renders/` with the Robolectric render.
     */
    val svgRenderSubdir: String = "renders",
    /**
     * JARs of compiled `.class` files belonging to the consumer module itself — the module's *own*
     * classes packaged as a jar rather than laid out in a [classDirs] directory. Unlike
     * [dependencyJars] these are method-walked as project classes (their `@Preview` functions are
     * discovered) and are NOT subject to the preview-relevant name/path filter. Sourced from AGP's
     * scoped `PROJECT` `CLASSES` artifact, which is populated regardless of whether Kotlin was
     * compiled by the standalone Kotlin Gradle Plugin or AGP 9.x built-in Kotlin
     * (`built_in_kotlinc`) — the legacy `build/tmp/kotlin-classes/<variant>` directory it used to
     * read is never written under built-in Kotlin. Empty (the default) for build systems / module
     * types that expose the module's classes only as directories. See issue #1924.
     */
    val projectClassJars: List<File> = emptyList(),
    /**
     * Whether this module's render backend can draw `@ColorCatalog` sheets. `true` (the default)
     * for the Android backend, which renders them; `false` for the desktop backend, which can't yet
     * (#2135). When `false`, the synthetic `CATALOG` captures are emitted `optional` so a missing
     * PNG is treated as expected — by the render gate AND every downstream consumer that reads
     * `Capture.optional` (VS Code's consistency check, its render UI). Keeping the flag on the
     * capture, rather than only in the Gradle gate, is what makes the desktop skip consistent
     * everywhere.
     */
    val catalogRenderSupported: Boolean = true,
    /**
     * Whether this is a Wear OS module (its merged manifest declares `<uses-feature
     * android:name="android.hardware.type.watch" …>`). When `true`, device-less wrap-content
     * `@Preview`s — which otherwise inherit Studio's phone default device
     * ([DeviceDimensions.DEFAULT], 400×800dp @ 2.625x) — are retargeted to the Wear default
     * ([DeviceDimensions.DEFAULT_WEAR], 227dp @ 2.0x) so a frame-less Wear sticker renders at wear
     * density and width instead of a phone canvas. A preview that pins its own `device` /
     * `widthDp`/`heightDp` is left untouched. Defaults to `false` (phone/desktop modules).
     */
    val isWear: Boolean = false,
    /**
     * Whether the Wear sticker retarget (see [isWear]) is applied at all. `true` (the default)
     * keeps the historical behaviour: on a Wear module, device-less wrap-content previews are
     * pinned to the Wear canvas (227dp @ 2.0x). Set `false` to opt out so those previews stay
     * wrap-content and the renderer crops them to their intrinsic layout bounds — needed for Wear
     * widget/tile previews (e.g. Glance `wear-tooling-preview` widgets) that are exported as
     * fixed-size drawable assets and must not carry the watch-face canvas whitespace (#2670). A
     * no-op when [isWear] is `false`. Wired from the `retargetWearPreviews` extension property /
     * `-PcomposePreview.retargetWearPreviews=false` Gradle property.
     */
    val retargetWearPreviews: Boolean = true,
  )

  /** Outcome of a [discover] call. */
  sealed class Outcome {
    /**
     * Discovery completed; [manifest] is ready to serialize. [warnings] are per-method warnings
     * (e.g. private `@Preview`, unsupported parameters) the adapter should route to its build
     * system's WARN-level log. [infoMessages] are the human-readable summary lines the gradle
     * plugin logs at LIFECYCLE level — `Discovered N previews ...` plus one bullet per preview.
     */
    data class Success(
      val manifest: PreviewManifest,
      val warnings: List<String>,
      val infoMessages: List<String>,
    ) : Outcome()

    /**
     * The scan terminated with a hard failure — only triggered by zero previews +
     * `failOnEmpty=true`. The "@Preview annotation class not reachable on the ClassGraph classpath"
     * state is a soft warning on the [Success] branch (see [Success.warnings]) because some modules
     * legitimately have zero previews; consumers that want it to break the build set
     * `composePreview.failOnEmpty=true`. [reason] is the one-line error the adapter should surface
     * as an exception message; [diagnostics] is the multi-line dump (class dirs, dependency-jar
     * sample, observed annotation FQNs) the adapter logs before the failure so users can see what
     * the scan saw. [warnings] are any per-method skip reasons collected during the scan (e.g.
     * private `@Preview`, unsupported parameters) — they're the most actionable signal when
     * discovery returned zero previews because methods were skipped, so the adapter should route
     * them to its build system's WARN-level log alongside [diagnostics] before surfacing [reason].
     * Symmetric with [Success.warnings] so adapters can emit the same WARN stream on both branches.
     */
    data class Failure(
      val reason: String,
      val diagnostics: List<String>,
      val warnings: List<String> = emptyList(),
    ) : Outcome()
  }

  private val PREVIEW_FQNS =
    setOf(
      "androidx.compose.ui.tooling.preview.Preview",
      "androidx.compose.desktop.ui.tooling.preview.Preview",
      TILE_PREVIEW_FQN,
      NOTIFICATION_PREVIEW_FQN,
      GLANCE_APPWIDGET_PREVIEW_FQN,
      XR_SUBSPACE_PREVIEW_FQN,
    )
  private val CONTAINER_FQNS =
    setOf(
      "androidx.compose.ui.tooling.preview.Preview\$Container",
      "androidx.compose.ui.tooling.preview.Preview.Container",
      // Tiles @Preview is @Repeatable, so the compiler synthesises a
      // `Preview.Container` too. Picking it up here lets us see every
      // stacked tile preview (e.g. SMALL_ROUND + LARGE_ROUND on one fn).
      "androidx.wear.tiles.tooling.preview.Preview\$Container",
      "androidx.wear.tiles.tooling.preview.Preview.Container",
    )
  // androidx.compose.ui:ui-tooling-preview 1.11.0+ — wraps each preview in a custom
  // PreviewWrapperProvider. Matched by FQN so older apps (no such class on classpath)
  // simply never surface the annotation and discovery is a no-op.
  private const val PREVIEW_WRAPPER_FQN = "androidx.compose.ui.tooling.preview.PreviewWrapper"
  // Project-side companion to @PreviewWrapper that also targets ANNOTATION_CLASS, so a
  // multi-preview meta-annotation can declare the wrapper once (androidx's @PreviewWrapper is
  // @Target(FUNCTION)-only and can't be hoisted). Carries the provider FQN as a String — see
  // `PreviewWrapperClass.kt`. FQN-matched like the other project annotations.
  private const val PREVIEW_WRAPPER_CLASS_FQN = "ee.schimke.composeai.preview.PreviewWrapperClass"
  // Our own opt-in for scrolling-screenshot capture. Matched by FQN so projects
  // that don't depend on `ee.schimke.composeai:preview-annotations` are unaffected.
  private const val SCROLLING_PREVIEW_FQN = "ee.schimke.composeai.preview.ScrollingPreview"
  // Animation-window capture — sibling annotation to @ScrollingPreview, same
  // FQN-match policy. See `AnimatedPreview.kt`.
  private const val ANIMATED_PREVIEW_FQN = "ee.schimke.composeai.preview.AnimatedPreview"
  // Focus-state capture — sibling annotation to @ScrollingPreview /
  // @AnimatedPreview, same FQN-match policy. See `FocusedPreview.kt`.
  private const val FOCUSED_PREVIEW_FQN = "ee.schimke.composeai.preview.FocusedPreview"
  private const val AMBIENT_PREVIEW_FQN = "ee.schimke.composeai.preview.AmbientPreview"
  // Wear one-handed-gesture hint capture — sibling annotation to @AmbientPreview, same FQN-match
  // policy. See `GestureHintPreview.kt`.
  private const val GESTURE_HINT_PREVIEW_FQN = "ee.schimke.composeai.preview.GestureHintPreview"
  private const val LAUNCHER_WIDGET_PREVIEW_FQN =
    "ee.schimke.composeai.preview.LauncherWidgetPreview"
  // `@OverrideVariant` — repeatable; emits one extra synthetic preview per variant with
  // `previewOverride*` values seeded, so a state/content variant rides on the same function instead
  // of a duplicated wrapper. Same FQN-match policy as the other annotations we own; the
  // `.Container`
  // FQN is the synthetic holder Kotlin generates for the repeated case. See `OverrideVariant.kt`.
  private const val OVERRIDE_VARIANT_FQN = "ee.schimke.composeai.preview.OverrideVariant"
  private const val OVERRIDE_VARIANT_CONTAINER_FQN =
    "ee.schimke.composeai.preview.OverrideVariant.Container"
  // The stable FQN is shared by both Android's ui-tooling-preview and CMP's
  // `org.jetbrains.compose.components:components-ui-tooling-preview` — Kotlin
  // `expect`/`actual` collapses onto the same `androidx...` class name on
  // every target we care about.
  private const val PREVIEW_PARAMETER_FQN = "androidx.compose.ui.tooling.preview.PreviewParameter"
  private const val COMPOSER_FQN = "androidx.compose.runtime.Composer"

  internal const val TILE_PREVIEW_FQN = "androidx.wear.tiles.tooling.preview.Preview"

  // Our own opt-in for Android notification previews. Function signature is
  // `(android.content.Context) -> android.app.Notification`; same FQN-match
  // policy as the other annotations we own. See `NotificationPreview.kt`.
  internal const val NOTIFICATION_PREVIEW_FQN = "ee.schimke.composeai.preview.NotificationPreview"

  // Glance's own preview annotation. The annotation lives in `androidx.glance:glance-preview` and
  // is `@ExperimentalGlancePreviewApi`-gated upstream — same FQN-match policy as notification /
  // tile. The annotated function is a `@Composable @GlanceComposable () -> Unit` body invoked
  // from a synthetic `GlanceAppWidget.providePreview(...)` at render time.
  internal const val GLANCE_APPWIDGET_PREVIEW_FQN = "androidx.glance.preview.Preview"

  // Our own opt-in for XR spatial (subspace) previews. The annotated function is a `@Composable`
  // whose body is an `androidx.xr.compose.spatial.Subspace { … }`; it's not captured to a single
  // PNG but rendered by a separate `:renderer-xr` Robolectric task that recovers the panel layout
  // and writes a `scene.json`. Same FQN-match policy as the other annotations we own. See
  // `XrSubspacePreview.kt`.
  internal const val XR_SUBSPACE_PREVIEW_FQN = "ee.schimke.composeai.preview.XrSubspacePreview"

  // `@LauncherWidgetResize` — fan-out annotation that emits one capture per whole-cell stop on
  // the walk between source and target sizes. The renderer renders each stop through the same
  // `LauncherWidgetExtension` the single-shot `@LauncherWidgetPreview` annotation uses. The
  // discovery side computes the stops inline via `launcherWidgetResizeStops(...)` below — the
  // canonical algorithm lives in `:data-launcher-widget-connector`'s `launcherWidgetStops(...)`
  // but the gradle plugin can't depend on the connector at discovery time.
  internal const val LAUNCHER_WIDGET_RESIZE_FQN =
    "ee.schimke.composeai.preview.LauncherWidgetResize"

  // `@ColorCatalog` — our own opt-in for auto-discovered colour-token sheets. Placed on a `Color`
  // property's backing field (BINARY retention, `@Target(FIELD)`), so unlike Showkase's
  // SOURCE-retained `@ShowkaseColor` it survives into bytecode for this FQN-match scan. See
  // `ColorCatalog.kt`.
  internal const val COLOR_CATALOG_FQN = "ee.schimke.composeai.preview.ColorCatalog"

  // `@TypographyCatalog` — the type-scale sibling of `@ColorCatalog`, on a `TextStyle` property's
  // backing field. Same BINARY / `@Target(FIELD)` FQN-match policy. See `TypographyCatalog.kt`.
  internal const val TYPOGRAPHY_CATALOG_FQN = "ee.schimke.composeai.preview.TypographyCatalog"

  // `@ShapeCatalog` — the shape-scoped sibling of `@ColorCatalog` / `@TypographyCatalog`, on a
  // `Shape` (single token) or `Shapes` (whole scale) property's backing field. Same BINARY /
  // `@Target(FIELD)` FQN-match policy. See `ShapeCatalog.kt`.
  internal const val SHAPE_CATALOG_FQN = "ee.schimke.composeai.preview.ShapeCatalog"

  // `@ThemeCatalog` — the theme-scoped sibling. Placed on a `PreviewWrapperProvider` CLASS (BINARY
  // retention, `@Target(CLASS)`), so it's an FQN match on the class annotation rather than a field.
  // See `ThemeCatalog.kt`.
  internal const val THEME_CATALOG_FQN = "ee.schimke.composeai.preview.ThemeCatalog"

  // Design-catalog inventory annotations — the code-side home for `catalog.spec.json`'s per-
  // component metadata. `@CatalogComponent` / `@CatalogVariant` land on the `@Preview` FUNCTION;
  // `@CatalogGroup` lands on the FILE (emitted onto the file's `…Kt` facade class). All BINARY /
  // FQN-match, never loaded. See `CatalogComponent.kt` and [extractCatalogEntry].
  internal const val CATALOG_COMPONENT_FQN = "ee.schimke.composeai.preview.CatalogComponent"
  internal const val CATALOG_VARIANT_FQN = "ee.schimke.composeai.preview.CatalogVariant"
  internal const val CATALOG_GROUP_FQN = "ee.schimke.composeai.preview.CatalogGroup"

  // Fallback group for a `@CatalogComponent` with no `group` argument and no file `@CatalogGroup`.
  private const val DEFAULT_CATALOG_COMPONENT_GROUP = "Components"

  // Whole-object catalog field types: a `@ColorCatalog` / `@TypographyCatalog` / `@ShapeCatalog`
  // annotation on a field of one of these types catalogs the *entire* theme object (the scheme /
  // type scale / shape scale) rather than a single token — dispatched by the field's declared type
  // descriptor at scan time (a single `Color` erases to `long`, so the whole-object types are the
  // discriminator). See [catalogTokenKindFor].
  private const val COLOR_SCHEME_TYPE = "androidx.compose.material3.ColorScheme"
  private const val TYPOGRAPHY_TYPE = "androidx.compose.material3.Typography"
  private const val SHAPES_TYPE = "androidx.compose.material3.Shapes"

  // failOnEmpty diagnostics: cap the JAR + annotation FQN sample sizes
  // so the lifecycle log stays readable on projects with huge classpaths.
  private const val DIAG_JAR_SAMPLE = 15
  private const val DIAG_ANNOTATION_SAMPLE = 20

  // Roborazzi's per-preview clock control. Opt-in: presence of the
  // annotation on a @Preview method fans out one extra manifest entry
  // per `ManualClockOptions.advanceTimeMillis` value, with filename
  // suffix `_TIME_<ms>ms`. Absent → single entry with null timing
  // (renderer falls back to its default CAPTURE_ADVANCE_MS).
  //
  // Shipped by `io.github.takahirom.roborazzi:roborazzi-annotations`.
  // We never load the class — ClassGraph reads the annotation and its
  // nested `ManualClockOptions` entries by descriptor, so the plugin
  // itself doesn't need a compile-time dep.
  private const val ROBO_COMPOSE_PREVIEW_OPTIONS_FQN =
    "com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions"

  /**
   * Both the un-resolved ([File.getAbsolutePath]) and symlink-resolved ([File.getCanonicalPath])
   * forms of [file], used to match a class's owning classpath element against the project's own
   * class dirs / jars.
   *
   * ClassGraph canonicalises the classpath element it reports for each class (resolving symlinks),
   * while Gradle/AGP hand discovery the location verbatim. On an overlay / symlinked build tree —
   * e.g. the AndroidX "androidchka" overlay — the two forms differ, so a raw `absolutePath`
   * comparison matches nothing: every class is then treated as a dependency class, never
   * method-walked, and discovery reports `Discovered 0 preview(s)` even though the annotated
   * classes are present in `classDirs`. Comparing on the union of both forms makes the match
   * symlink-agnostic — it succeeds whenever either side's absolute or canonical path coincides.
   * `canonicalPath` does I/O and can throw, so it's added best-effort. See issue #1924.
   */
  private fun pathMatchKeys(file: File): Set<String> = buildSet {
    add(file.absolutePath)
    runCatching { add(file.canonicalPath) }
  }

  fun discover(input: Input): Outcome {
    val warnings = mutableListOf<String>()
    val infoMessages = mutableListOf<String>()

    val existingClassDirs = input.classDirs.filter { it.exists() && it.isDirectory }
    // The module's OWN classes, packaged as a jar (AGP scoped PROJECT CLASSES
    // artifact). Walked as project classes like [existingClassDirs] — NOT
    // subject to the dependency-jar preview-relevance filter below. This is the
    // path that rescues discovery under AGP 9.x built-in Kotlin, where the
    // module's classes never land in the legacy `build/tmp/kotlin-classes/
    // <variant>` directory the directory scan reads. See issue #1924.
    val existingProjectJars =
      input.projectClassJars.filter {
        it.exists() && it.isFile && it.name.lowercase().endsWith(".jar")
      }
    // Match on the absolute path, not just the file name: AGP 9.x +
    // KGP 2.3 resolve AAR dependencies to `<cache>/transforms/<hash>/
    // transformed/<library>/jars/classes.jar` where the library name
    // lives in the parent directory, not the filename. Filtering on
    // `file.name` alone dropped every AAR-extracted jar — see #162.
    val filteredDependencyJars =
      input.dependencyJars.filter { file ->
        file.exists() &&
          file.name.lowercase().endsWith(".jar") &&
          run {
            val path = file.absolutePath.lowercase()
            path.contains("preview") ||
              path.contains("tooling") ||
              path.contains("compose") ||
              path.contains("annotation")
          }
      }
    // Project jars BEFORE dependency jars so a class present in both (the
    // module's own output shadowing a stale dependency copy) is attributed by
    // ClassGraph to the project element and method-walked.
    val classpath = existingClassDirs + existingProjectJars + filteredDependencyJars

    val previews = mutableListOf<PreviewInfo>()
    // Populated only on the diagnostics path (failOnEmpty + 0 previews)
    // so we can tell users whether ClassGraph saw any classes at all,
    // and which annotation FQNs it did see — which disambiguates
    // "classpath is wrong" from "@Preview FQN doesn't match" in a
    // single run.
    var scanClassCount = 0
    var scanMethodsWithAnnotations = 0
    val annotationFqnCounts = LinkedHashMap<String, Int>()
    // Which known @Preview annotation FQNs are reachable as ClassInfo
    // on the scan classpath. Empty → discovery cannot resolve multi-
    // preview annotations (they fan out via `scanResult.getClassInfo`),
    // which is almost always a misconfigured dep-jar classpath.
    var reachablePreviewFqns: List<String> = emptyList()

    // `@ColorCatalog`-annotated design-token fields collected during the scan, aggregated into
    // synthetic [PreviewKind.CATALOG] sheets after the class walk. Each token carries its resolved
    // [CatalogTokenKind] (single `Color` vs whole `ColorScheme`, etc.), dispatched by field type.
    val rawColorCatalogTokens = mutableListOf<RawCatalogToken>()
    val rawTypographyCatalogTokens = mutableListOf<RawCatalogToken>()
    val rawShapeCatalogTokens = mutableListOf<RawCatalogToken>()
    // `@ThemeCatalog`-annotated `PreviewWrapperProvider` classes → one theme catalog sheet each.
    val rawThemeCatalogs = mutableListOf<RawThemeCatalog>()

    if (classpath.isNotEmpty()) {
      ClassGraph()
        .enableMethodInfo()
        // Field scanning powers `@ColorCatalog` design-token discovery — the annotation lands on a
        // `Color` property's backing field, so we need field metadata + annotations to see it.
        // `ignoreFieldVisibility()` is required because a top-level `val`'s backing field is
        // private
        // static (mirrors `ignoreMethodVisibility()` for private `@Preview` functions).
        .enableFieldInfo()
        .ignoreFieldVisibility()
        .enableAnnotationInfo()
        .ignoreMethodVisibility()
        .overrideClasspath(classpath.map { it.absolutePath })
        .ignoreParentClassLoaders()
        .scan()
        .use { scanResult ->
          reachablePreviewFqns = PREVIEW_FQNS.filter { scanResult.getClassInfo(it) != null }
          // Project-local class FQNs — only classes loaded from the project's own
          // class output (its [classDirs] directories or its scoped PROJECT
          // [projectClassJars]), never from a dependency JAR. Powers the
          // "is this @Composable call into project code?" filter inside
          // PreviewTargetInference; computed once per scan and passed through.
          // The dependency JARs stay OUT of this set, so their classes remain on
          // the ClassGraph classpath (for multi-preview annotation resolution)
          // but aren't method-walked. See issue #1039 / #1924.
          val projectElementPaths =
            (existingClassDirs + existingProjectJars).flatMap { pathMatchKeys(it) }.toSet()
          val projectClassFqns =
            scanResult.allClasses
              .asSequence()
              .filter { ci ->
                val element = ci.classpathElementFile ?: return@filter false
                pathMatchKeys(element).any { it in projectElementPaths }
              }
              .map { it.name }
              .toSet()
          // File-level `@CatalogGroup` defaults, resolved by source file so a catalog preview that
          // is a *member* function (whose `classInfo` is its containing class, not the file facade
          // that Kotlin writes `@file:CatalogGroup` onto) still picks up the file's group. Both the
          // facade `…Kt` class and any member class in the same file resolve to the same
          // module-relative source path, so keying by that path unifies the top-level and member
          // cases. Built up-front because a member class may be method-walked before its facade.
          val catalogGroupsByFile = HashMap<String, CatalogGroupDefault>()
          for (classInfo in scanResult.allClasses) {
            if (classInfo.name !in projectClassFqns) continue
            val groupAnn = classInfo.getAnnotationInfo(CATALOG_GROUP_FQN) ?: continue
            val file = sourceFilePath(classInfo, input) ?: continue
            catalogGroupsByFile.putIfAbsent(
              file,
              CatalogGroupDefault(
                name = annStringOrNull(groupAnn, "name"),
                section = annStringOrNull(groupAnn, "section"),
              ),
            )
          }
          for (classInfo in scanResult.allClasses) {
            // Method-walk only project classes. Library JARs stay on the
            // ClassGraph classpath so `scanResult.getClassInfo` can resolve
            // multi-preview annotations (e.g. @WearPreviewDevices) declared
            // there, but iterating their methods produced no real previews
            // and spammed hundreds of "skipping @Preview" warnings for
            // synthetic Kotlin inline-class methods like
            // `TransformationState.equals-impl`. See issue #1039.
            if (classInfo.name !in projectClassFqns) continue
            scanClassCount++
            for (method in classInfo.methodInfo) {
              val annotations = method.annotationInfo ?: continue
              if (annotations.isNotEmpty()) scanMethodsWithAnnotations++
              for (ann in annotations) {
                annotationFqnCounts.merge(ann.name, 1, Int::plus)
              }
              discoverFromMethod(
                classInfo,
                method,
                annotations.toList(),
                scanResult,
                projectClassFqns,
                previews,
                input,
                warnings,
                catalogGroupsByFile,
              )
            }
            // `@ColorCatalog` / `@TypographyCatalog` / `@ShapeCatalog` design tokens: an annotated
            // `Color` / `TextStyle` / `Shape` (single token) or `ColorScheme` / `Typography` /
            // `Shapes` (whole-object) backing field. Collect the coordinates + display metadata
            // here; the values are reflected at render time. The token kind is dispatched by the
            // field's declared type (see [catalogTokenKindFor]) so a whole-object field catalogs
            // the
            // entire scheme / type scale / shape scale.
            for (field in classInfo.fieldInfo) {
              field.getAnnotationInfo(COLOR_CATALOG_FQN)?.let { ann ->
                rawColorCatalogTokens +=
                  rawCatalogToken(
                    classInfo,
                    field,
                    ann,
                    catalogTokenKindFor(field, single = CatalogTokenKind.COLOR),
                  )
              }
              field.getAnnotationInfo(TYPOGRAPHY_CATALOG_FQN)?.let { ann ->
                rawTypographyCatalogTokens +=
                  rawCatalogToken(
                    classInfo,
                    field,
                    ann,
                    catalogTokenKindFor(field, single = CatalogTokenKind.TEXT_STYLE),
                  )
              }
              field.getAnnotationInfo(SHAPE_CATALOG_FQN)?.let { ann ->
                rawShapeCatalogTokens +=
                  rawCatalogToken(
                    classInfo,
                    field,
                    ann,
                    catalogTokenKindFor(field, single = CatalogTokenKind.SHAPE),
                  )
              }
            }
            // `@ThemeCatalog` on a `PreviewWrapperProvider` class → a theme catalog sheet. The
            // provider FQN is all discovery records; the renderer resolves + invokes its `Wrap`.
            classInfo.getAnnotationInfo(THEME_CATALOG_FQN)?.let { ann ->
              rawThemeCatalogs +=
                RawThemeCatalog(
                  className = classInfo.name,
                  name = annStringOrDefault(ann, "name", classInfo.simpleName),
                  group = annStringOrDefault(ann, "group", defaultCatalogGroup(classInfo.name)),
                )
            }
          }
        }
    }

    // id already encodes the name + (device, fontScale, uiMode) variant suffix, so
    // dedup by id alone. Two identical preview variants on the same function collapse.
    val deduped = previews.distinctBy { it.id }

    // Rewrite each capture's renderOutput to a normalized, shell-safe
    // filename: drop the package prefix shared by every preview in the
    // module so `renders/ee.schimke.ha.previews.CardPreviewsKt.Foo.png`
    // lands at `renders/CardPreviewsKt.Foo.png`; sanitize spaces, parens,
    // and other awkward shell characters inherited from `@Preview(name =
    // "tile light (light)")`. Keeps `PreviewInfo.id` untouched — consumers
    // that key by id (history folders, CLI state, test names) are
    // unaffected.
    // Lottie asset previews are appended after normalization with their render outputs already
    // shell-safe, so they bypass the package-prefix stripping (they have no class/package).
    val normalized =
      retargetWearStickers(
        input.isWear,
        pinWearCanvas = input.retargetWearPreviews,
        normalizeRenderOutputs(deduped),
      ) +
        discoverLottieAssets(input) +
        discoverSvgAssets(input) +
        buildCatalogPreviews(
          rawColorCatalogTokens,
          input.catalogRenderSupported,
          idPrefix = "colorcatalog",
          noun = "colours",
        ) +
        buildCatalogPreviews(
          rawTypographyCatalogTokens,
          input.catalogRenderSupported,
          idPrefix = "typographycatalog",
          noun = "type styles",
        ) +
        buildCatalogPreviews(
          rawShapeCatalogTokens,
          input.catalogRenderSupported,
          idPrefix = "shapecatalog",
          noun = "shapes",
        ) +
        buildThemeCatalogPreviews(rawThemeCatalogs, input.catalogRenderSupported)

    // The generic per-extension reports map is empty on the standalone Gradle path — a11y
    // (today's only canned-report producer) writes its artefacts exclusively through the
    // daemon, which stamps the pointer at runtime when it has data on disk. Future
    // gradle-produced rollups would populate keys here.
    val manifest =
      PreviewManifest(
        module = input.moduleName,
        variant = input.variantName,
        previews = normalized,
        dataExtensionReports = emptyMap(),
      )

    infoMessages.add("Discovered ${normalized.size} preview(s) in module '${input.moduleName}':")
    for (preview in normalized) {
      infoMessages.add("  ${preview.className}.${preview.functionName}${describeVariant(preview)}")
    }

    // Hard-fail only when the consumer explicitly opted in via
    // `composePreview.failOnEmpty=true`. Zero previews in a single module
    // is normal — utility modules, data layers, and library projects
    // that pull the plugin in transitively legitimately have none, and
    // the multi-module aggregate (or the user's own CI gate) is the
    // right place to assert "no module produced anything". The
    // dependency-jar filter dropping the `@Preview` annotation jar (see
    // #162) is now reported as a WARN-level diagnostic via the soft
    // path below so consumers still see the cause without the build
    // breaking on it.
    val previewAnnotationsMissing = scanClassCount > 0 && reachablePreviewFqns.isEmpty()
    if (normalized.isEmpty() && input.failOnEmpty) {
      val diagnostics =
        buildEmptyDiagnostics(
          header = "composePreview: failOnEmpty diagnostics (0 previews discovered):",
          existingClassDirs = existingClassDirs,
          allClassDirs = input.classDirs,
          projectJars = existingProjectJars,
          allProjectJars = input.projectClassJars,
          filteredJars = filteredDependencyJars,
          allJarCount = input.dependencyJars.size,
          scanClassCount = scanClassCount,
          scanMethodsWithAnnotations = scanMethodsWithAnnotations,
          annotationFqnCounts = annotationFqnCounts,
          reachablePreviewFqns = reachablePreviewFqns,
        )
      val reason =
        if (previewAnnotationsMissing) {
          "the @Preview annotation class is not on the ClassGraph classpath " +
            "(dependency-jar filter dropped every jar carrying it)"
        } else {
          "with failOnEmpty=true"
        }
      return Outcome.Failure(
        reason =
          "composePreview: discovered 0 previews in module '${input.moduleName}' — " +
            "$reason. See diagnostics above.",
        diagnostics = diagnostics,
        warnings = warnings.toList(),
      )
    }

    // Soft warning: zero previews + the @Preview annotation jar got
    // filtered off the scan classpath. Multi-preview annotations
    // (@LightDarkPreviews, @WearPreviewDevices, user wrappers) can't
    // fan out without `scanResult.getClassInfo` reaching the
    // annotation class, so any previews this module *does* have are
    // invisible to discovery. Surface the diagnostics so the cause is
    // obvious, but don't fail the build — the user can opt in to a
    // hard failure with `composePreview.failOnEmpty=true`.
    if (normalized.isEmpty() && previewAnnotationsMissing) {
      warnings.add(
        "composePreview: discovered 0 previews in module '${input.moduleName}' — " +
          "the @Preview annotation class is not on the ClassGraph classpath " +
          "(dependency-jar filter dropped every jar carrying it). " +
          "Set composePreview.failOnEmpty=true to make this a hard error."
      )
      warnings.addAll(
        buildEmptyDiagnostics(
          header =
            "composePreview: 0-previews diagnostics for module '${input.moduleName}' " +
              "(soft warning — set composePreview.failOnEmpty=true to fail the build):",
          existingClassDirs = existingClassDirs,
          allClassDirs = input.classDirs,
          projectJars = existingProjectJars,
          allProjectJars = input.projectClassJars,
          filteredJars = filteredDependencyJars,
          allJarCount = input.dependencyJars.size,
          scanClassCount = scanClassCount,
          scanMethodsWithAnnotations = scanMethodsWithAnnotations,
          annotationFqnCounts = annotationFqnCounts,
          reachablePreviewFqns = reachablePreviewFqns,
        )
      )
    }

    return Outcome.Success(
      manifest = manifest,
      warnings = warnings.toList(),
      infoMessages = infoMessages.toList(),
    )
  }

  /**
   * Characters replaced with `_` when turning a resource path into a shell-safe render-file stem.
   */
  private val SANITIZE_RENDER_STEM = Regex("[^A-Za-z0-9._-]")

  /** Lenient JSON reader for Lottie structure-sniffing — tolerant of comments / trailing commas. */
  private val LOTTIE_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Scan [Input.resourceDirs] for Lottie animation assets and turn each into a [PreviewKind.LOTTIE]
   * preview — no `@Preview`, no consumer composable. A `.json` file qualifies when it parses as a
   * JSON object carrying the Lottie marker keys (`v` version + `layers`); a `.lottie` file
   * qualifies by extension (a dotLottie archive). The asset's resource-relative path is recorded on
   * [PreviewParams.assetPath] so the renderer can load it off the classpath and the bundle can pack
   * it as IR.
   *
   * Best-effort and side-effect-free: unreadable / non-Lottie files are skipped silently. Returns a
   * list deduped by preview id and ordered by relative path for stable output.
   */
  private fun discoverLottieAssets(input: Input): List<PreviewInfo> {
    if (input.resourceDirs.isEmpty()) return emptyList()
    val found = LinkedHashMap<String, PreviewInfo>()
    for (root in input.resourceDirs) {
      if (!root.isDirectory) continue
      root
        .walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .forEach { file ->
          val relPath = file.relativeTo(root).invariantSeparatorsPath
          val ext = file.extension.lowercase()
          val dims =
            when (ext) {
              "json" -> lottieDimensionsOrNull(file) ?: return@forEach
              "lottie" -> LottieDims(null, null) // dotLottie archive — accept by extension
              else -> return@forEach
            }
          // Filename-safe id: it lands verbatim in zip entry paths (`previews/<id>.png`,
          // `ir/<id>.<ext>`) and render filenames, so `:` / `/` from the resource path can't
          // survive. The `lottie__` prefix keeps it from colliding with a class-derived preview id.
          val safe = relPath.removeSuffix(".$ext").replace(SANITIZE_RENDER_STEM, "_")
          val stem = "lottie__$safe"
          val id = stem
          if (found.containsKey(id)) return@forEach
          found[id] =
            PreviewInfo(
              id = id,
              functionName = relPath,
              className = "",
              params =
                PreviewParams(
                  name = file.nameWithoutExtension,
                  kind = PreviewKind.LOTTIE,
                  assetPath = relPath,
                  widthDp = dims.width,
                  heightDp = dims.height,
                ),
              captures =
                listOf(
                  Capture(renderOutput = "${input.lottieRenderSubdir}/$stem.png"),
                  // Animated companion: the asset's intrinsic timeline encoded as a looping APNG
                  // (the renderer dispatches `_animated.png` Lottie outputs to `renderLottieApng`).
                  // APNG rather than GIF because the asset renders on a transparent background and
                  // GIF's 1-bit alpha thresholds the anti-aliased edge into a churn-prone hard
                  // boundary; APNG carries full alpha and still autoplays inline everywhere as a
                  // `.png`. Marked `optional` so a missing companion never trips
                  // `composePreviewRenderAll`'s required-render gate; the still PNG stays the
                  // baseline artefact. Cost mirrors the scroll-GIF frame-loop + encode.
                  Capture(
                    renderOutput = "${input.lottieRenderSubdir}/${stem}_animated.png",
                    optional = true,
                    cost = SCROLL_GIF_COST,
                  ),
                ),
            )
        }
    }
    return found.values.toList()
  }

  private data class LottieDims(val width: Int?, val height: Int?)

  /**
   * Scan [Input.resourceDirs] for `.svg` image assets and turn each into a [PreviewKind.SVG]
   * preview — no `@Preview`, no consumer composable, "just having the file is enough" (mirrors
   * [discoverLottieAssets]). A `.svg` qualifies by extension when its content carries an `<svg`
   * root element (the cheapest reliable fingerprint — guards against a stray file that merely ends
   * in `.svg`). The asset's resource-relative path is recorded on [PreviewParams.assetPath] so the
   * desktop renderer can load it off the classpath; the declared `viewBox` / `width` / `height`
   * seed the canvas dimensions so the still matches the artwork's intrinsic aspect ratio.
   *
   * Unlike Lottie there is no animated companion — SVG is static (SMIL/CSS animation isn't replayed
   * by `loadSvgPainter`), so each preview ships a single required still PNG.
   *
   * Best-effort and side-effect-free: unreadable / non-SVG files are skipped silently. Returns a
   * list deduped by preview id and ordered by relative path for stable output.
   */
  private fun discoverSvgAssets(input: Input): List<PreviewInfo> {
    if (input.resourceDirs.isEmpty()) return emptyList()
    val found = LinkedHashMap<String, PreviewInfo>()
    for (root in input.resourceDirs) {
      if (!root.isDirectory) continue
      root
        .walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .forEach { file ->
          if (!file.extension.equals("svg", ignoreCase = true)) return@forEach
          val relPath = file.relativeTo(root).invariantSeparatorsPath
          val dims = svgDimensionsOrNull(file) ?: return@forEach
          // Filename-safe id (see the Lottie note): lands verbatim in zip entry / render paths, so
          // `:` / `/` from the resource path can't survive. The `svg__` prefix keeps it from
          // colliding with a class-derived preview id or a `lottie__` asset id.
          val safe = relPath.removeSuffix(".${file.extension}").replace(SANITIZE_RENDER_STEM, "_")
          val stem = "svg__$safe"
          val id = stem
          if (found.containsKey(id)) return@forEach
          found[id] =
            PreviewInfo(
              id = id,
              functionName = relPath,
              className = "",
              params =
                PreviewParams(
                  name = file.nameWithoutExtension,
                  kind = PreviewKind.SVG,
                  assetPath = relPath,
                  widthDp = dims.width,
                  heightDp = dims.height,
                ),
              // Single required still — no animated companion (SVG has no replayed timeline).
              captures = listOf(Capture(renderOutput = "${input.svgRenderSubdir}/$stem.png")),
            )
        }
    }
    return found.values.toList()
  }

  private data class SvgDims(val width: Int?, val height: Int?)

  /**
   * Read [file]'s intrinsic dimensions when it is an SVG, or `null` when it is not (no `<svg` root
   * — a file that merely ends in `.svg`). Prefers an explicit `width`/`height` on the root element,
   * falling back to the `viewBox`'s width/height (the common case for icon SVGs, which declare only
   * a `viewBox`). Dimensions are rounded to whole pixels and used only to seed the render canvas'
   * aspect ratio; a value of `null` on either axis lets the renderer fall back to its default size.
   */
  private fun svgDimensionsOrNull(file: File): SvgDims? {
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    // Cheapest reliable SVG fingerprint: a `<svg` element tag. Guards against a mis-named file.
    val svgTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE).find(text) ?: return null
    val attrs = svgTag.value
    fun lengthAttr(name: String): Int? {
      // Anchor to a real attribute boundary: the name must NOT be preceded by a name char or `-`,
      // so `stroke-width` / `stroke-height` don't masquerade as the root `width`/`height`. A plain
      // `\b` word boundary matches the `-width` suffix and would size the canvas off the stroke
      // (e.g. `<svg viewBox="0 0 24 24" stroke-width="2">` → a 2dp-wide canvas instead of 24dp).
      val raw =
        Regex("(?<![\\w-])$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
          .find(attrs)
          ?.groupValues
          ?.get(1) ?: return null
      // Strip a unit suffix (px, pt, mm, %, …) — only the leading number seeds the canvas ratio.
      return Regex("[-+]?\\d*\\.?\\d+").find(raw)?.value?.toFloatOrNull()?.roundToInt()
    }
    val explicitW = lengthAttr("width")?.takeIf { it > 0 }
    val explicitH = lengthAttr("height")?.takeIf { it > 0 }
    if (explicitW != null && explicitH != null) return SvgDims(explicitW, explicitH)
    // Fall back to viewBox = "minX minY width height".
    val viewBox =
      Regex("\\bviewBox\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        .find(attrs)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.split(Regex("[\\s,]+"))
    val vbW = viewBox?.getOrNull(2)?.toFloatOrNull()?.roundToInt()?.takeIf { it > 0 }
    val vbH = viewBox?.getOrNull(3)?.toFloatOrNull()?.roundToInt()?.takeIf { it > 0 }
    return SvgDims(explicitW ?: vbW, explicitH ?: vbH)
  }

  /** Raw `@ColorCatalog` hit collected during the scan, before aggregation into sheets. */
  private data class RawCatalogToken(
    val className: String,
    val member: String,
    val name: String,
    val group: String,
    val kind: CatalogTokenKind,
  )

  /**
   * Resolves a catalog field to its [CatalogTokenKind]: a field whose declared type is a whole M3
   * theme object ([COLOR_SCHEME_TYPE] / [TYPOGRAPHY_TYPE] / [SHAPES_TYPE]) catalogs the *entire*
   * object; anything else is the [single] token kind for that annotation. A single `Color` erases
   * to `long` in bytecode, so matching the whole-object type name is a reliable discriminator. Uses
   * the type descriptor's string form (`toString()` yields the source-level FQN for class types).
   */
  private fun catalogTokenKindFor(
    field: io.github.classgraph.FieldInfo,
    single: CatalogTokenKind,
  ): CatalogTokenKind {
    val type = runCatching { field.typeSignatureOrTypeDescriptor.toString() }.getOrNull()
    return when (type) {
      COLOR_SCHEME_TYPE -> CatalogTokenKind.COLOR_SCHEME
      TYPOGRAPHY_TYPE -> CatalogTokenKind.TYPOGRAPHY
      SHAPES_TYPE -> CatalogTokenKind.SHAPES
      else -> single
    }
  }

  /**
   * Raw `@ThemeCatalog` hit: the annotated `PreviewWrapperProvider` class + its display metadata.
   */
  private data class RawThemeCatalog(val className: String, val name: String, val group: String)

  /**
   * Builds a [RawCatalogToken] from an annotated field, applying Showkase-style name/group
   * defaults.
   */
  private fun rawCatalogToken(
    classInfo: ClassInfo,
    field: io.github.classgraph.FieldInfo,
    ann: AnnotationInfo,
    kind: CatalogTokenKind,
  ): RawCatalogToken =
    RawCatalogToken(
      className = classInfo.name,
      member = field.name,
      name = annStringOrDefault(ann, "name", field.name),
      group = annStringOrDefault(ann, "group", defaultCatalogGroup(classInfo.name)),
      kind = kind,
    )

  /**
   * Reads a `String` annotation parameter, falling back to [fallback] when absent or blank — this
   * is how `@ColorCatalog.name` defaults to the property name and `.group` to the enclosing class,
   * the same defaulting Showkase applies.
   */
  private fun annStringOrDefault(ann: AnnotationInfo, param: String, fallback: String): String {
    val raw = runCatching { ann.parameterValues.getValue(param) as? String }.getOrNull()
    return raw?.takeIf { it.isNotBlank() } ?: fallback
  }

  /**
   * Default group for a token: the enclosing class simple name, with a file class's `Kt` suffix
   * dropped.
   */
  private fun defaultCatalogGroup(className: String): String {
    val simple = className.substringAfterLast('.')
    return simple.removeSuffix("Kt").ifBlank { simple }
  }

  /**
   * A file-level `@CatalogGroup` default, resolved once per source file (see the
   * `catalogGroupsByFile` pre-pass) so a member-function preview picks it up as well as a top-level
   * one.
   */
  private data class CatalogGroupDefault(val name: String?, val section: String?)

  /**
   * Design-catalog identity for a preview function from `@CatalogComponent` / `@CatalogVariant`,
   * with [fileGroup] (the file-level `@CatalogGroup`, resolved by source file so member-function
   * previews get it too — Kotlin writes `@file:CatalogGroup` onto the file facade, not the
   * containing class) supplying the group/section default. Returns `null` when the function carries
   * neither annotation — the common, non-catalog case, which leaves [PreviewInfo.catalog] absent.
   *
   * `@CatalogVariant` takes precedence if somehow both are present: a variant belongs *under*
   * another component, so it never doubles as its own top-level component entry. Resolution honours
   * the "good defaults, override with annotations" precedence — component id defaults to the
   * function name, group to the per-component argument, else the file `@CatalogGroup`, else
   * `Components`.
   */
  private fun extractCatalogEntry(
    method: MethodInfo,
    annotations: List<AnnotationInfo>,
    fileGroup: CatalogGroupDefault?,
  ): CatalogEntry? {
    annotations
      .firstOrNull { it.name == CATALOG_VARIANT_FQN }
      ?.let { variant ->
        val parent = annStringOrNull(variant, "of") ?: return null
        return CatalogEntry(
          role = CatalogRole.VARIANT,
          componentId = parent,
          caption = annStringOrNull(variant, "caption"),
          state = annStringOrNull(variant, "state"),
          props = annStringArray(variant, "props").mapNotNull(::parseCatalogProp),
        )
      }
    val component = annotations.firstOrNull { it.name == CATALOG_COMPONENT_FQN } ?: return null
    return CatalogEntry(
      role = CatalogRole.COMPONENT,
      componentId = annStringOrDefault(component, "id", method.name),
      group =
        annStringOrNull(component, "group") ?: fileGroup?.name ?: DEFAULT_CATALOG_COMPONENT_GROUP,
      section = fileGroup?.section,
      caption = annStringOrNull(component, "caption"),
      reference = annStringOrNull(component, "reference"),
    )
  }

  /** Reads a `String` annotation parameter, returning `null` when absent or blank. */
  private fun annStringOrNull(ann: AnnotationInfo, param: String): String? {
    val raw = runCatching { ann.parameterValues.getValue(param) as? String }.getOrNull()
    return raw?.takeIf { it.isNotBlank() }
  }

  /** Reads a `String[]` annotation parameter (ClassGraph yields an `Object[]`) as a list. */
  private fun annStringArray(ann: AnnotationInfo, param: String): List<String> {
    val raw = runCatching { ann.parameterValues.getValue(param) }.getOrNull() ?: return emptyList()
    return when (raw) {
      is Array<*> -> raw.filterIsInstance<String>()
      is Iterable<*> -> raw.filterIsInstance<String>()
      else -> emptyList()
    }
  }

  /** Splits a `@CatalogVariant.props` `"key=value"` pair; `null` when malformed (no key). */
  private fun parseCatalogProp(raw: String): CatalogVariantProp? {
    val idx = raw.indexOf('=')
    if (idx <= 0) return null
    val key = raw.substring(0, idx).trim()
    val value = raw.substring(idx + 1).trim()
    return if (key.isEmpty()) null else CatalogVariantProp(key, value)
  }

  /**
   * Aggregates the collected `@ColorCatalog` / `@TypographyCatalog` / `@ShapeCatalog` tokens into
   * synthetic [PreviewKind.CATALOG] sheets: one per `group`, plus a module-wide "All <noun>" sheet
   * when there is more than one group (a single group would just duplicate itself). [idPrefix]
   * namespaces the render-output filename (`colorcatalog` / `typographycatalog` / `shapecatalog`)
   * and [noun] labels the sheet ("colours" / "type styles" / "shapes"). Each token carries its own
   * [RawCatalogToken.kind] (single token vs whole-object) so the renderer picks the right layout
   * and a whole-object token expands into its scheme / type-scale / shape roles. Appended after
   * [normalizeRenderOutputs] with render outputs already shell-safe, like the Lottie assets.
   */
  private fun buildCatalogPreviews(
    tokens: List<RawCatalogToken>,
    renderSupported: Boolean,
    idPrefix: String,
    noun: String,
  ): List<PreviewInfo> {
    if (tokens.isEmpty()) return emptyList()
    val byGroup = LinkedHashMap<String, MutableList<RawCatalogToken>>()
    for (t in tokens) byGroup.getOrPut(t.group) { mutableListOf() }.add(t)

    val entries = mutableListOf<PreviewInfo>()
    for ((group, groupTokens) in byGroup) {
      entries +=
        catalogPreview(
          id = "${idPrefix}__${group.replace(SANITIZE_RENDER_STEM, "_")}",
          displayName = "$group $noun",
          tokens = groupTokens,
          renderSupported = renderSupported,
        )
    }
    if (byGroup.size > 1) {
      entries +=
        catalogPreview(
          id = "${idPrefix}__all",
          displayName = "All $noun",
          tokens = tokens,
          renderSupported = renderSupported,
        )
    }
    return entries
  }

  private fun catalogPreview(
    id: String,
    displayName: String,
    tokens: List<RawCatalogToken>,
    renderSupported: Boolean,
  ): PreviewInfo =
    PreviewInfo(
      id = id,
      functionName = displayName,
      className = tokens.first().className,
      params =
        PreviewParams(
          name = displayName,
          kind = PreviewKind.CATALOG,
          catalogTokens =
            tokens.map {
              CatalogToken(
                className = it.className,
                member = it.member,
                label = it.name,
                tokenKind = it.kind,
              )
            },
        ),
      // The capture is `optional` exactly when the backend can't render catalog sheets. On Android
      // ([renderSupported] = true) it's required, so a missing PNG is flagged as a regression by
      // the
      // gate. On desktop ([renderSupported] = false) it's optional, so every consumer that reads
      // `Capture.optional` — the render gate, VS Code's consistency check, its render UI — treats
      // the
      // (deliberately skipped, #2135) sheet as expected-absent rather than drift. One flag, all
      // consumers.
      captures = listOf(Capture(renderOutput = "renders/$id.png", optional = !renderSupported)),
    )

  /**
   * Aggregates the collected `@ThemeCatalog` providers into synthetic [PreviewKind.THEME_CATALOG]
   * sheets — one per provider, keyed `themecatalog__<name>`. Because each provider is its own sheet
   * (not aggregated like the token catalogs), the id must be unique per provider: two providers
   * that share a display `name` (e.g. `"Light"` in different groups/packages) would otherwise
   * derive the same id and `renders/<id>.png` and clobber each other, so a collision falls back to
   * appending the provider's (unique) FQN. The provider FQN travels on
   * [PreviewParams.wrapperClassName]; the renderer resolves it and composes its `Wrap(content)`
   * around a canned specimen. `optional` exactly when the backend can't render (desktop), like the
   * token catalogs.
   */
  private fun buildThemeCatalogPreviews(
    themes: List<RawThemeCatalog>,
    renderSupported: Boolean,
  ): List<PreviewInfo> {
    fun baseId(t: RawThemeCatalog) = "themecatalog__${t.name.replace(SANITIZE_RENDER_STEM, "_")}"
    val baseCounts = themes.groupingBy { baseId(it) }.eachCount()
    return themes.map { theme ->
      val base = baseId(theme)
      // Clean `themecatalog__<name>` when the name is unique; disambiguate a shared name with the
      // provider FQN (guaranteed unique) so the two sheets get distinct render outputs.
      val id =
        if (baseCounts.getValue(base) > 1) {
          "${base}__${theme.className.replace(SANITIZE_RENDER_STEM, "_")}"
        } else {
          base
        }
      PreviewInfo(
        id = id,
        functionName = "${theme.name} theme",
        className = theme.className,
        params =
          PreviewParams(
            // Clean theme name (no " theme" suffix): the renderer keys the per-theme token sidecar
            // (#2179) by this. The display label lives on `functionName` above.
            name = theme.name,
            group = theme.group.ifEmpty { null },
            kind = PreviewKind.THEME_CATALOG,
            wrapperClassName = theme.className,
          ),
        captures = listOf(Capture(renderOutput = "renders/$id.png", optional = !renderSupported)),
      )
    }
  }

  /**
   * Parse [file] as a Lottie document, returning its declared canvas dimensions when it carries the
   * Lottie marker keys, or `null` when the file is not a Lottie JSON (an ordinary config / data
   * `.json`, or unparseable). The `v`+`layers` pair is the cheapest reliable Lottie fingerprint —
   * every Bodymovin/Lottie export has a schema version string and a layers array.
   */
  private fun lottieDimensionsOrNull(file: File): LottieDims? {
    val obj =
      runCatching { LOTTIE_JSON.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        ?: return null
    val looksLikeLottie = obj.containsKey("v") && obj.containsKey("layers")
    if (!looksLikeLottie) return null
    fun dim(key: String) = (obj[key] as? JsonPrimitive)?.floatOrNull?.toInt()?.takeIf { it > 0 }
    return LottieDims(width = dim("w"), height = dim("h"))
  }

  private fun buildEmptyDiagnostics(
    header: String,
    existingClassDirs: List<File>,
    allClassDirs: List<File>,
    projectJars: List<File>,
    allProjectJars: List<File>,
    filteredJars: List<File>,
    allJarCount: Int,
    scanClassCount: Int,
    scanMethodsWithAnnotations: Int,
    annotationFqnCounts: Map<String, Int>,
    reachablePreviewFqns: List<String>,
  ): List<String> {
    val out = mutableListOf<String>()
    out.add(header)
    out.add("  classDirs (${allClassDirs.size} declared, ${existingClassDirs.size} existing):")
    for (dir in allClassDirs) {
      val exists = dir.exists()
      val isDir = dir.isDirectory
      val classCount =
        if (exists && isDir) {
          dir.walkTopDown().count { it.extension == "class" }
        } else 0
      out.add("    - $dir")
      out.add("      exists=$exists isDir=$isDir classFiles=$classCount")
    }
    // Project-own class jars (AGP scoped PROJECT CLASSES) — the built-in-Kotlin
    // rescue path. Listed separately from dependencyJars because these ARE
    // method-walked. See issue #1924.
    if (allProjectJars.isNotEmpty()) {
      out.add("  projectClassJars (${allProjectJars.size} declared, ${projectJars.size} existing):")
      for (jar in allProjectJars) {
        out.add("    - $jar")
        out.add("      exists=${jar.exists()} isFile=${jar.isFile}")
      }
    }
    out.add(
      "  dependencyJars: $allJarCount total, ${filteredJars.size} match " +
        "(preview|tooling|compose|annotation)"
    )
    for (jar in filteredJars.take(DIAG_JAR_SAMPLE)) {
      out.add("    - ${jar.name}")
    }
    if (filteredJars.size > DIAG_JAR_SAMPLE) {
      out.add("    … and ${filteredJars.size - DIAG_JAR_SAMPLE} more")
    }
    out.add(
      "  ClassGraph scan: $scanClassCount classes, " +
        "$scanMethodsWithAnnotations methods with any annotation"
    )
    if (scanClassCount > 0) {
      if (reachablePreviewFqns.isEmpty()) {
        // Most common #162-shaped failure: the consumer's preview
        // annotations live in AAR-extracted `<library>/jars/classes.jar`
        // files, whose `file.name` is just `classes.jar`. The
        // dep-jar filter used to match on file name only and
        // dropped every such jar, so no multi-preview annotation
        // could be resolved.
        out.add(
          "  known @Preview annotation classes NOT reachable on " +
            "ClassGraph classpath — multi-preview resolution is disabled."
        )
        out.add("    expected at least one of (by FQN):")
        for (fqn in PREVIEW_FQNS) out.add("      - $fqn")
      } else {
        out.add("  reachable @Preview annotation classes on ClassGraph classpath:")
        for (fqn in reachablePreviewFqns) out.add("      - $fqn")
      }
    }
    val previewAnnotationsSeen = PREVIEW_FQNS.filter { annotationFqnCounts.containsKey(it) }
    if (previewAnnotationsSeen.isNotEmpty()) {
      // If this path triggers we have a real bug: @Preview is on the
      // classpath, it's on some method, but discovery still emitted
      // nothing. Make it impossible to miss in the log.
      out.add(
        "  known @Preview FQNs WERE seen on scanned methods " +
          "(discovery dropped them — please report):"
      )
      for (fqn in previewAnnotationsSeen) {
        out.add("    - $fqn (${annotationFqnCounts[fqn]})")
      }
    } else if (scanClassCount > 0) {
      out.add("  no known @Preview FQN seen on any scanned method.")
      out.add("    expected one of:")
      for (fqn in PREVIEW_FQNS) out.add("      - $fqn")
      val topAnnotations =
        annotationFqnCounts.entries.sortedByDescending { it.value }.take(DIAG_ANNOTATION_SAMPLE)
      if (topAnnotations.isNotEmpty()) {
        out.add("    top annotation FQNs actually observed:")
        for ((fqn, count) in topAnnotations) {
          out.add("      - $fqn ($count)")
        }
      }
    } else {
      out.add("  ClassGraph scanned 0 classes — check the classDirs listing above.")
    }
    return out
  }

  /**
   * Rewrite each capture's `renderOutput` (and each `dataProduct.output`) to a shorter, shell-safe
   * filename. Two passes shape the result:
   *
   * 1. **Sanitisation per dotted segment.** Within a segment (the chunks between `.`s of the
   *    preview id) any run of non-alphanumeric characters collapses to a single `_`. So `Devices -
   *    Large Round` becomes `Devices_Large_Round`, `tile light (light)` becomes `tile_light_light`,
   *    etc. Dots stay as segment separators.
   * 2. **Shortest unique suffix per preview.** Each preview takes the rightmost segments needed to
   *    disambiguate it from every other preview in the module — the function-name-plus-variant
   *    segment alone for unique names, prepending the class only when another preview shares the
   *    same function name, prepending package parts only when classes collide too. So
   *    `com.example.PreviewsKt.ActivityListPreview_Devices - Large Round` becomes
   *    `ActivityListPreview_Devices_Large_Round` in most modules.
   *
   * `preview.id` itself stays untouched — it's the stable identity consumers key by (history
   * folders, CLI state, JUnit test names). Only the on-disk filename benefits from the prettier
   * form.
   *
   * If sanitisation forces a true collision (two distinct ids whose fully-sanitised forms are
   * byte-identical — e.g. `Foo_bar` vs `Foo-bar` after collapsing), a `_<idx>` suffix
   * disambiguates. The renders directory has to stay collision-free even when input names couldn't.
   */
  private fun normalizeRenderOutputs(previews: List<PreviewInfo>): List<PreviewInfo> {
    if (previews.isEmpty()) return previews
    val resolvedStems = resolveRenderStems(previews)
    return previews.mapIndexed { i, preview ->
      val newStem = resolvedStems[i]
      val rewritten =
        preview.captures.map { c ->
          c.copy(renderOutput = rewriteRenderStem(c.renderOutput, preview.id, newStem))
        }
      val rewrittenProducts =
        preview.dataProducts.map { p ->
          p.copy(output = rewriteRenderStem(p.output, preview.id, newStem))
        }
      preview.copy(captures = rewritten, dataProducts = rewrittenProducts)
    }
  }

  /**
   * Pick one shell-safe stem per preview. Each stem is the shortest sanitised suffix that uniquely
   * identifies its preview against the others — see [normalizeRenderOutputs] for the algorithm and
   * rationale. Exposed `internal` so the unit tests can assert "no spaces ever", "no `class.`
   * prefix when the function name is unique", and the collision-disambiguator paths directly
   * without a full discovery pipeline.
   */
  internal fun resolveRenderStems(previews: List<PreviewInfo>): List<String> {
    if (previews.isEmpty()) return emptyList()
    val sanitisedSegmentsByPreview = previews.map { sanitiseSegments(it) }
    val stems = sanitisedSegmentsByPreview.mapIndexed { i, mySegs ->
      shortestUniqueSuffix(i, mySegs, sanitisedSegmentsByPreview)
    }
    return disambiguateExactCollisions(stems)
  }

  /**
   * Returns the joined stem (segments joined with `.`) made from the rightmost segments of [mySegs]
   * that aren't matched, at the same depth, by any other preview's sanitised segments.
   *
   * If no proper suffix is unique (two distinct ids whose sanitised segment lists are
   * byte-identical — the only way every depth matches), return JUST the last segment. The user
   * wants short on-disk names; the resulting duplicate is then disambiguated with a `_<idx>` suffix
   * by [disambiguateExactCollisions] rather than padded with the full package path.
   */
  private fun shortestUniqueSuffix(
    myIndex: Int,
    mySegs: List<String>,
    allSegs: List<List<String>>,
  ): String {
    if (mySegs.isEmpty()) return ""
    for (depth in 1..mySegs.size) {
      val mySuffix = mySegs.takeLast(depth)
      val collides =
        allSegs.withIndex().any { (otherIndex, otherSegs) ->
          otherIndex != myIndex && otherSegs.takeLast(depth) == mySuffix
        }
      if (!collides) return mySuffix.joinToString(".")
    }
    return mySegs.last()
  }

  /**
   * Splits a preview id into dot-separated segments and sanitises each one. Sanitisation collapses
   * every run of non-alphanumeric characters within a segment to a single `_` and trims `_`/`-`
   * from the segment edges; the inter-segment `.` is preserved as the segment join.
   *
   * Only the *structural* part of the id — the `className.functionName` FQN — is split on `.`. The
   * trailing variant suffix (from `@Preview(name = ...)` / `group`) is folded into the
   * function-name segment first, because a name like `"Font scale 1.5x"` carries dots that are NOT
   * structural id separators: splitting the whole id would inject a spurious trailing segment ("1"
   * | "5x") and the shortest-unique-suffix walk could collapse the entire stem down to it
   * (`5x.png`). Keeping the id itself lossless preserves manifest dedup (`distinctBy { it.id }`);
   * any stem collision two distinct ids still produce is handled by [disambiguateExactCollisions].
   *
   * Empty segments (e.g. from a leading or trailing `.`, or from a segment that was all-punctuation
   * pre-sanitisation) are dropped so they don't introduce `..` in the resulting stem.
   */
  private fun sanitiseSegments(preview: PreviewInfo): List<String> {
    val fqn = "${preview.className}.${preview.functionName}"
    // Fall back to splitting the whole id when it isn't the expected `fqn + suffix` shape (e.g.
    // synthetically-constructed ids) so behaviour is unchanged for those.
    val suffix = if (preview.id.startsWith(fqn)) preview.id.substring(fqn.length) else null
    val segments = (if (suffix != null) fqn else preview.id).split('.').toMutableList()
    if (suffix != null && suffix.isNotEmpty() && segments.isNotEmpty()) {
      segments[segments.lastIndex] = segments.last() + suffix
    }
    return segments.map(::sanitiseSegment).filter { it.isNotEmpty() }
  }

  /**
   * Collapse every run of non-alphanumeric characters to a single `_`, then trim `_` and `-` from
   * the edges. Designed for one dotted segment of a preview id; dots inside [segment] would be
   * misinterpreted as segment boundaries, so callers split first.
   */
  private fun sanitiseSegment(segment: String): String =
    segment.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_', '-')

  /**
   * Last-ditch tiebreaker when two distinct preview ids sanitise to exactly the same stem (e.g.
   * `Foo_bar` and `Foo-bar` both become `Foo_bar`). Appends `_<idx>` to the second-and-later
   * occurrences in the manifest order; the first occurrence keeps its clean form so the common case
   * still gets the pretty filename.
   */
  private fun disambiguateExactCollisions(stems: List<String>): List<String> {
    if (stems.toSet().size == stems.size && stems.none { it.isEmpty() }) return stems
    val counts = mutableMapOf<String, Int>()
    return stems.mapIndexed { i, raw ->
      val base = if (raw.isEmpty()) "preview" else raw
      val seen = counts.getOrDefault(base, 0)
      counts[base] = seen + 1
      if (seen == 0 && raw.isNotEmpty()) base else "${base}_${i}"
    }
  }

  /** `renders/<oldStem><tail>.<ext>` → `renders/<newStem><tail>.<ext>`. */
  private fun rewriteRenderStem(renderOutput: String, oldStem: String, newStem: String): String {
    if (renderOutput.isEmpty() || oldStem == newStem) return renderOutput
    val dir = renderOutput.substringBeforeLast('/', missingDelimiterValue = "")
    val leaf = renderOutput.substringAfterLast('/')
    if (!leaf.startsWith(oldStem)) return renderOutput
    val rewritten = newStem + leaf.removePrefix(oldStem)
    return if (dir.isEmpty()) rewritten else "$dir/$rewritten"
  }

  // Renders the distinguishing bits of a preview variant for the discovery log
  // so sibling expansions (e.g. @WearPreviewFontScales × 6) aren't visually
  // identical. Format mirrors the VSCode tooltip: `name` / `device` /
  // `WxHdp` / `font Nx` / `uiMode=N` / `locale` / `group`.
  private fun describeVariant(preview: PreviewInfo): String {
    val p = preview.params
    val parts = mutableListOf<String>()
    p.name?.let(parts::add)
    p.device?.let(parts::add)
    val w = p.widthDp
    val h = p.heightDp
    if (w != null && h != null) parts.add("${w}x${h}dp")
    if (p.fontScale != 1.0f) parts.add("font ${p.fontScale}x")
    if (p.uiMode != 0) parts.add("uiMode=${p.uiMode}")
    p.locale?.let(parts::add)
    p.group?.let { parts.add("group=$it") }
    // Summarise capture-level dimensions (time, scroll) on one line so
    // the log remains a single bullet per preview even for fan-outs.
    val timings = preview.captures.mapNotNull { it.advanceTimeMillis }
    if (timings.isNotEmpty()) {
      parts.add("${preview.captures.size} captures @ ${timings.joinToString(",") { "${it}ms" }}")
    }
    val scrollModes = preview.captures.mapNotNull { it.scroll?.mode }.distinct()
    if (scrollModes.isNotEmpty()) {
      parts.add("scroll=" + scrollModes.joinToString(",") { it.name.lowercase() })
    }
    val anim = preview.captures.firstNotNullOfOrNull { it.animation }
    if (anim != null) {
      val curveSuffix = if (anim.showCurves) "+curves" else ""
      parts.add("animated=${anim.durationMs}ms@${anim.frameIntervalMs}ms$curveSuffix")
    }
    return if (parts.isEmpty()) "" else "  [" + parts.joinToString(" · ") + "]"
  }

  private fun discoverFromMethod(
    classInfo: ClassInfo,
    method: MethodInfo,
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
    previews: MutableList<PreviewInfo>,
    input: Input,
    warnings: MutableList<String>,
    catalogGroupsByFile: Map<String, CatalogGroupDefault>,
  ) {
    // Resolve the method's preview annotations up-front so we can bail
    // before any per-method work (and before the "skipping @Preview"
    // warning) when the method isn't actually a preview. The caller
    // routes every annotated method through here, so without this guard
    // an unrelated annotation (e.g. `@JvmStatic` on a synthetic Kotlin
    // inline-class method) would trigger the unsupported-parameters
    // warning despite carrying no @Preview at all. See issue #1039.
    val directPreviews = collectDirectPreviews(annotations)
    val resolvedMultiPreviews: List<AnnotationInfo> =
      if (directPreviews.isNotEmpty()) {
        emptyList()
      } else {
        annotations.flatMap { resolveMultiPreview(it, scanResult, mutableSetOf()) }
      }
    // Issue #2613: a preview annotated only with a multi-preview annotation whose class is off the
    // discovery classpath (e.g. wear tooling wired into `screenshotTest`, so
    // `@WearPreviewLargeRound`
    // resolves there but not in `main`) resolves to nothing and vanishes silently. For the
    // well-known AndroidX / Wear annotations we expand them from a built-in spec table so they
    // still
    // render; for any others we can't recognise, warn so the silent drop is at least visible.
    // Scoped to the `directPreviews`-empty branch — that's where `resolveMultiPreview` ran and
    // could
    // have dropped an unreachable annotation (including the mixed case where a sibling resolved).
    val builtInSpecs: List<BuiltInPreviewSpec> =
      if (directPreviews.isEmpty()) annotations.flatMap { builtInExpansionFor(it, scanResult) }
      else emptyList()
    if (directPreviews.isEmpty()) {
      for (fqn in unexpandablePreviewAnnotationNames(annotations, scanResult)) {
        if (fqn in BUILT_IN_MULTIPREVIEW_EXPANSIONS) continue // expanded from the table below
        val simple = fqn.substringAfterLast('.')
        warnings.add(
          "composePreview: '${classInfo.name}.${method.name}' carries @$simple ($fqn) but " +
            "discovery could not expand it into any @Preview — the annotation class is not on the " +
            "discovery classpath for source set '${input.variantName}'. This usually means the " +
            "tooling artifact that defines @$simple is wired into a test-only source set (e.g. " +
            "screenshotTestImplementation) rather than the main/implementation classpath, so the " +
            "preview is silently missing until it is resolvable there. See issue #2613."
        )
      }
    }
    if (directPreviews.isEmpty() && resolvedMultiPreviews.isEmpty() && builtInSpecs.isEmpty())
      return

    // @PreviewWrapper and @ScrollingPreview are both non-repeatable and apply
    // to every @Preview on the function (including expansions from
    // multi-preview meta-annotations). `@ScrollingPreview.modes` maps TOP/END
    // to normal captures and LONG/GIF to data products — see [buildOutputPlan].
    // `@AnimatedPreview` is single-shot (one GIF per function) so it doesn't
    // fan out, but follows the same "one annotation per function, applies to
    // every preview expansion" policy.
    val wrapperFqn = extractWrapperFqn(method, scanResult)
    val scrollSpecs = extractScrollSpecs(annotations)
    val animationSpec = extractAnimationSpec(annotations)
    val focusSpecs = extractFocusSpecs(annotations)
    val focusGifSpec = extractFocusGifSpec(annotations)
    val ambientSpec = extractAmbientSpec(annotations)
    val gestureHintSpec = extractGestureHintSpec(annotations)
    val launcherWidgetSpec = extractLauncherWidgetSpec(annotations)
    val launcherWidgetResizeSpec = extractLauncherWidgetResizeSpec(annotations)
    // `@OverrideVariant` (repeatable) — each spec yields one extra synthetic preview per @Preview
    // expansion below, rendered with its `previewOverride*` seeds applied. Applies to every
    // expansion, the same "one annotation, applies to every preview" policy as the capture specs.
    val overrideVariantSpecs = extractOverrideVariantSpecs(annotations)
    // @RoboComposePreviewOptions, similarly, applies to the function as a
    // whole — each timing fans out into its own manifest entry, orthogonal
    // to any multi-preview expansion.
    val timings = extractRoboTimings(annotations)
    // @PreviewParameter lives on a method PARAMETER, not the method itself,
    // so it's sourced from `parameterInfo` rather than the method
    // annotation list. Extracted once per function and applied to every
    // multi-preview expansion — the provider is the same no matter which
    // @Preview drove the fan-out.
    val previewParameter = extractPreviewParameter(method)
    val isTilePreview = isAnyTilePreviewAnnotation(annotations, scanResult)
    val isNotificationPreview = isAnyNotificationPreviewAnnotation(annotations, scanResult)
    val isGlanceAppWidgetPreview = isAnyGlanceAppWidgetPreviewAnnotation(annotations, scanResult)
    val isXrSubspacePreview = isAnyXrSubspacePreviewAnnotation(annotations, scanResult)
    // XR subspace previews are reflected + composed parameterless by the `:renderer-xr` task — it
    // has no @PreviewParameter argument-injection path (and a parameterized subspace layout is
    // nonsensical). Reject any @XrSubspacePreview that declares a user parameter (whether
    // @PreviewParameter or plain) up front, so a parameterized one is skipped here rather than
    // emitted as an XR_SUBSPACE entry that fails at render time.
    if (isXrSubspacePreview && userPreviewParameters(method).isNotEmpty()) {
      warnings.add(
        "composePreview: skipping @XrSubspacePreview '${classInfo.name}.${method.name}' — " +
          "XR subspace previews must be parameterless (@PreviewParameter / arguments aren't " +
          "supported by the XR renderer)."
      )
      return
    }
    val hasUnsupportedParameters =
      !isTilePreview &&
        !isNotificationPreview &&
        !isGlanceAppWidgetPreview &&
        !isXrSubspacePreview &&
        hasUnsupportedPreviewParameters(method, previewParameter)
    if (hasUnsupportedParameters) {
      warnings.add(
        "composePreview: skipping @Preview '${classInfo.name}.${method.name}' — " +
          "method has parameter(s) without @PreviewParameter provider wiring. " +
          "Only parameterless previews or @PreviewParameter-injected previews are supported."
      )
      return
    }

    // Target inference is identical across every @Preview expansion on a single function — the
    // bytecode and signals don't change between (e.g.) the Light and Dark variants of a
    // `@LightAndDark` multi-preview. Wrap in `lazy` so a multi-preview function with N expansions
    // walks the bytecode once instead of N times; tile previews skip the inference entirely
    // (handled in `makePreview`) and the lazy never forces.
    val previewSourceFile = sourceFilePath(classInfo, input)
    val inferredTargets = lazy {
      PreviewTargetInference.infer(
        previewClassInfo = classInfo,
        previewMethod = method,
        scanResult = scanResult,
        projectClassFqns = projectClassFqns,
        previewSourceFile = previewSourceFile,
        resolveSourceFile = { ownerFqn ->
          scanResult.getClassInfo(ownerFqn)?.let { sourceFilePath(it, input) }
        },
        variantName = input.variantName,
        hasPreviewParameter = previewParameter != null,
      )
    }

    // Design-catalog identity (`@CatalogComponent` / `@CatalogVariant`) applies to the function as
    // a
    // whole — every `@Preview` expansion of one function shares the same component id / variant
    // tag — so it's resolved once here and stamped onto each entry this method contributes below.
    val catalogEntry =
      extractCatalogEntry(method, annotations, catalogGroupsByFile[previewSourceFile])
    val firstNewPreviewIndex = previews.size
    fun tagWithCatalog() {
      if (catalogEntry == null) return
      for (i in firstNewPreviewIndex until previews.size) {
        previews[i] = previews[i].copy(catalog = catalogEntry)
      }
    }

    if (directPreviews.isNotEmpty()) {
      for (ann in directPreviews) {
        val base =
          makePreview(
            classInfo,
            method,
            ann,
            wrapperFqn,
            scrollSpecs,
            animationSpec,
            focusSpecs,
            focusGifSpec,
            ambientSpec,
            gestureHintSpec,
            launcherWidgetSpec,
            launcherWidgetResizeSpec,
            timings,
            previewParameter,
            previewSourceFile,
            inferredTargets,
          )
        previews.add(base)
        for (spec in overrideVariantSpecs) previews.add(overrideVariantPreview(base, spec))
      }
      tagWithCatalog()
      return
    }

    for (resolvedAnn in resolvedMultiPreviews) {
      val base =
        makePreview(
          classInfo,
          method,
          resolvedAnn,
          wrapperFqn,
          scrollSpecs,
          animationSpec,
          focusSpecs,
          focusGifSpec,
          ambientSpec,
          gestureHintSpec,
          launcherWidgetSpec,
          launcherWidgetResizeSpec,
          timings,
          previewParameter,
          previewSourceFile,
          inferredTargets,
        )
      previews.add(base)
      for (spec in overrideVariantSpecs) previews.add(overrideVariantPreview(base, spec))
    }

    // Built-in expansion of known off-classpath multi-preview annotations (issue #2613). Each
    // synthesised spec runs through the same [buildPreviewInfo] tail as a real `@Preview`, so it
    // fans out the function's `@ScrollingPreview` / `@AnimatedPreview` / … captures and infers
    // targets identically.
    for (spec in builtInSpecs) {
      val base =
        buildPreviewInfo(
          classInfo,
          method,
          spec.toParams(wrapperFqn, previewParameter),
          scrollSpecs,
          animationSpec,
          focusSpecs,
          focusGifSpec,
          ambientSpec,
          gestureHintSpec,
          launcherWidgetSpec,
          launcherWidgetResizeSpec,
          timings,
          previewSourceFile,
          inferredTargets,
        )
      previews.add(base)
      for (variant in overrideVariantSpecs) previews.add(overrideVariantPreview(base, variant))
    }
    tagWithCatalog()
  }

  /**
   * Derives a synthetic override-variant preview from a rendered [base]: same function, a
   * `_VARIANT_<name>`-suffixed id + render outputs, the [spec]'s seeds carried on
   * [PreviewInfo.overrides] for the renderer to apply, and no data products (a state variant
   * doesn't re-emit the heavy scroll/animation products). The unchanged `functionName` is what lets
   * the design-catalog fold merge the variant image back under its primary sticker.
   */
  private fun overrideVariantPreview(base: PreviewInfo, spec: OverrideVariantSpec): PreviewInfo {
    val tag = "_VARIANT_${spec.name}"
    return base.copy(
      id = base.id + tag,
      overrides = spec,
      captures =
        base.captures.map { it.copy(renderOutput = insertRenderTag(it.renderOutput, tag)) },
      dataProducts = emptyList(),
    )
  }

  /** Inserts [tag] just before the file extension of a `renders/<stem>.<ext>` output path. */
  private fun insertRenderTag(renderOutput: String, tag: String): String {
    if (renderOutput.isEmpty()) return renderOutput
    val dot = renderOutput.lastIndexOf('.')
    val slash = renderOutput.lastIndexOf('/')
    return if (dot > slash) renderOutput.substring(0, dot) + tag + renderOutput.substring(dot)
    else renderOutput + tag
  }

  /**
   * Reads `@OverrideVariant` annotations (repeatable — direct instances or the synthetic
   * `.Container` holder Kotlin generates for the repeated case) into one [OverrideVariantSpec]
   * each. Each per-type array entry is `"key=value"` / `"key#index=value"`; the array it lives in
   * fixes its [OverrideSeedKind]. A variant that names no parseable seed is dropped (it would
   * render identically to the base).
   */
  private fun extractOverrideVariantSpecs(
    annotations: List<AnnotationInfo>
  ): List<OverrideVariantSpec> {
    val infos = mutableListOf<AnnotationInfo>()
    for (ann in annotations) {
      when (ann.name) {
        OVERRIDE_VARIANT_FQN -> infos.add(ann)
        OVERRIDE_VARIANT_CONTAINER_FQN -> {
          when (val value = ann.parameterValues.getValue("value")) {
            is Array<*> -> value.filterIsInstance<AnnotationInfo>().forEach { infos.add(it) }
            is AnnotationInfo -> infos.add(value)
            else -> {
              val len = runCatching { java.lang.reflect.Array.getLength(value) }.getOrNull() ?: 0
              for (i in 0 until len) {
                (java.lang.reflect.Array.get(value, i) as? AnnotationInfo)?.let { infos.add(it) }
              }
            }
          }
        }
      }
    }
    return infos.mapNotNull { info ->
      val pv = info.parameterValues
      val name =
        (pv.getValue("name") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val seeds =
        readOverrideSeeds(pv.getValue("booleans"), OverrideSeedKind.BOOLEAN) +
          readOverrideSeeds(pv.getValue("strings"), OverrideSeedKind.STRING) +
          readOverrideSeeds(pv.getValue("ints"), OverrideSeedKind.INT) +
          readOverrideSeeds(pv.getValue("floats"), OverrideSeedKind.FLOAT) +
          readOverrideSeeds(pv.getValue("colors"), OverrideSeedKind.COLOR)
      if (seeds.isEmpty()) null else OverrideVariantSpec(name = name, seeds = seeds)
    }
  }

  /** Parses `"key=value"` / `"key#index=value"` string-array entries into typed [OverrideSeed]s. */
  private fun readOverrideSeeds(raw: Any?, kind: OverrideSeedKind): List<OverrideSeed> =
    readStringArray(raw).mapNotNull { entry ->
      val eq = entry.indexOf('=')
      if (eq <= 0) return@mapNotNull null
      val lhs = entry.substring(0, eq).trim()
      val value = entry.substring(eq + 1)
      val hash = lhs.indexOf('#')
      val key = (if (hash < 0) lhs else lhs.substring(0, hash)).trim()
      val index = if (hash < 0) null else lhs.substring(hash + 1).trim().toIntOrNull()
      if (key.isEmpty()) null else OverrideSeed(key = key, index = index, kind = kind, raw = value)
    }

  /** ClassGraph surfaces an `Array<String>` param as a String[]; normalise to a `List<String>`. */
  private fun readStringArray(raw: Any?): List<String> =
    when (raw) {
      null -> emptyList()
      is Array<*> -> raw.filterIsInstance<String>()
      is String -> listOf(raw)
      else -> {
        val len = runCatching { java.lang.reflect.Array.getLength(raw) }.getOrNull() ?: 0
        (0 until len).mapNotNull { java.lang.reflect.Array.get(raw, it) as? String }
      }
    }

  /**
   * Tile previews ([TILE_PREVIEW_FQN]) take a single `(context: Context)` argument supplied by the
   * renderer at run time; the @PreviewParameter contract that gates Compose previews doesn't apply
   * to them. Walk direct annotations + multi-preview meta-annotations so a tile preview reached
   * through a multi-preview alias (e.g. `@MultiRoundTilesPreviews`) is exempted too.
   */
  private fun isAnyTilePreviewAnnotation(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
  ): Boolean {
    if (collectDirectPreviews(annotations).any { it.name == TILE_PREVIEW_FQN }) return true
    for (ann in annotations) {
      val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
      if (resolved.any { it.name == TILE_PREVIEW_FQN }) return true
    }
    return false
  }

  /**
   * Notification previews (`NOTIFICATION_PREVIEW_FQN`) take a single `(Context)` argument supplied
   * by the renderer at run time — same shape as a `(Context)` tile preview, so
   * the @PreviewParameter contract that gates Compose previews doesn't apply. Walk direct
   * annotations + multi-preview meta-annotations so a notification preview reached through a future
   * multi-preview alias is exempted too.
   */
  private fun isAnyNotificationPreviewAnnotation(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
  ): Boolean {
    if (collectDirectPreviews(annotations).any { it.name == NOTIFICATION_PREVIEW_FQN }) return true
    for (ann in annotations) {
      val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
      if (resolved.any { it.name == NOTIFICATION_PREVIEW_FQN }) return true
    }
    return false
  }

  /**
   * Glance preview functions (`GLANCE_APPWIDGET_PREVIEW_FQN`) are `@Composable @GlanceComposable ()
   * -> Unit` bodies — their JVM signature ends with the compiler-added `Composer, Int` pair the
   * standard composable-parameter check would flag as "unsupported parameter(s)". Treat them the
   * same way as tile / notification previews so the check is skipped: the renderer reflects the
   * function and invokes it via a synthetic `GlanceAppWidget.providePreview(...)` instead.
   */
  private fun isAnyGlanceAppWidgetPreviewAnnotation(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
  ): Boolean {
    if (collectDirectPreviews(annotations).any { it.name == GLANCE_APPWIDGET_PREVIEW_FQN }) {
      return true
    }
    for (ann in annotations) {
      val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
      if (resolved.any { it.name == GLANCE_APPWIDGET_PREVIEW_FQN }) return true
    }
    return false
  }

  /**
   * XR subspace previews (`XR_SUBSPACE_PREVIEW_FQN`) are `@Composable` functions whose JVM
   * signature carries the compiler-added `Composer, Int` pair the standard composable-parameter
   * check would flag. Treat them the same as tile / notification / glance so the check is skipped:
   * they're rendered by the separate `:renderer-xr` task, not the Android image renderer. Walk
   * direct annotations + multi-preview meta-annotations so an XR preview reached through an alias
   * is exempted too.
   */
  private fun isAnyXrSubspacePreviewAnnotation(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
  ): Boolean {
    if (collectDirectPreviews(annotations).any { it.name == XR_SUBSPACE_PREVIEW_FQN }) return true
    for (ann in annotations) {
      val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
      if (resolved.any { it.name == XR_SUBSPACE_PREVIEW_FQN }) return true
    }
    return false
  }

  private fun hasUnsupportedPreviewParameters(
    method: MethodInfo,
    previewParameter: Pair<String, Int>?,
  ): Boolean {
    val userParameters = userPreviewParameters(method)
    if (userParameters.isEmpty()) return false
    // Current renderer contract: preview methods are either parameterless,
    // or take exactly one value sourced by @PreviewParameter.
    return userParameters.size != 1 || previewParameter == null
  }

  private fun userPreviewParameters(method: MethodInfo): List<MethodParameterInfo> {
    val params = method.parameterInfo?.toList() ?: return emptyList()
    val composerIndex = params.indexOfLast { it.typeDescriptorName() == COMPOSER_FQN }
    if (composerIndex == -1) return params
    val compilerMaskParams = params.drop(composerIndex + 1)
    if (compilerMaskParams.all { it.typeDescriptorName() == "int" }) {
      return params.take(composerIndex)
    }
    return params
  }

  private fun MethodParameterInfo.typeDescriptorName(): String =
    getTypeDescriptor().toString().removePrefix("class ")

  /**
   * Scans [method]'s parameters for `@PreviewParameter`. Returns the provider FQN + `limit` of the
   * FIRST parameter that carries the annotation; `null` when none do. Supporting a single parameter
   * mirrors the current upstream (Studio/Layoutlib) semantic — multi-param preview functions
   * require explicit wiring in tooling code, which our renderer doesn't expose.
   *
   * ClassGraph surfaces parameter annotations on `MethodParameterInfo.annotationInfo`. The `value`
   * field on `@PreviewParameter` carries the provider KClass, which comes back as an
   * [AnnotationClassRef] — we pull its FQN without triggering classloading (matches how
   * [extractWrapperFqn] handles `@PreviewWrapper`).
   */
  private fun extractPreviewParameter(method: MethodInfo): Pair<String, Int>? {
    val params = method.parameterInfo ?: return null
    for (param in params) {
      val anns = param.annotationInfo ?: continue
      val ann = anns.firstOrNull { it.name == PREVIEW_PARAMETER_FQN } ?: continue
      val provider =
        when (val value = ann.parameterValues.getValue("provider")) {
          is AnnotationClassRef -> value.name
          is String -> value
          else -> null
        } ?: continue
      val limit = (ann.parameterValues.getValue("limit") as? Int)?.coerceAtLeast(0) ?: Int.MAX_VALUE
      return provider to limit
    }
    return null
  }

  // Tile previews don't go through `mainClock` and can't scroll (the
  // renderer inflates a View via `TileRenderer` and has no Compose
  // animation clock / scrollable), so both dimensional annotations are
  // no-ops for tiles.
  private data class PreviewOutputPlan(
    val captures: List<Capture>,
    val dataProducts: List<PreviewDataProduct>,
  )

  private fun buildOutputPlan(
    kind: PreviewKind,
    previewId: String,
    scrolls: List<ScrollCapture>,
    animation: AnimationCapture?,
    focuses: List<FocusCapture>,
    focusGif: FocusGifCapture?,
    ambient: AmbientCapture?,
    gestureHint: GestureHintCapture?,
    launcherWidget: LauncherWidgetCapture?,
    launcherWidgetResize: LauncherWidgetResizeSpec?,
    timings: List<Long>,
  ): PreviewOutputPlan {
    val isTile = kind == PreviewKind.TILE
    // Notification previews aren't composable either — no `mainClock`, no scrollables, no focus
    // owner. Treat them the same as tiles for every dimensional fan-out so the single-capture
    // path runs unmodified.
    val isNotification = kind == PreviewKind.NOTIFICATION
    // Glance preview functions are technically `@Composable`, but they're a closed Glance
    // composition driven by `composeForPreview(...)` rather than the standard Compose machinery.
    // Treat them the same way as tile / notification for fan-out gating — no scroll / animation
    // / focus drive, no `mainClock` tick, the renderer handles the whole materialise + inflate
    // in one shot.
    val isGlanceAppWidget = kind == PreviewKind.GLANCE_APPWIDGET
    // XR subspace previews aren't captured to a single image and have no `mainClock` / scrollable /
    // focus owner here — the `:renderer-xr` task drives the whole recover-and-write in one shot.
    // Gate them out of every dimensional fan-out the same way as tile / notification / glance.
    val isXrSubspace = kind == PreviewKind.XR_SUBSPACE
    // XR subspace previews don't render a PNG through the Robolectric path — the opt-in
    // `composePreviewRenderXr` task writes a `scene.json` (+ one `<panelId>.png` texture per panel)
    // into `renders/<sanitizedId>/`, and the optional `composePreviewCompositeXr` task bakes a
    // single `composite.png` still from that scene via the native `xr-composite` tool. Emit ONE
    // optional capture pointing at that composite so it shows up in the preview listing when
    // present, but is NOT required by `composePreviewRenderAll`'s missing-render gate — the
    // composite is best-effort (it's absent when the binary / display / software GL isn't
    // available, or when a consumer declares `@XrSubspacePreview` but leaves `enableXrPreviews`
    // off). The subdir uses the SAME sanitisation as `XrSubspaceRenderTest.sanitize`
    // (`[^A-Za-z0-9._-]` → `_`, keeping dots) so the path matches the render subdir on disk.
    // `normalizeRenderOutputs`/`rewriteRenderStem` only rewrite the leaf when it starts with the
    // preview's stem; the leaf here is the literal `composite.png`, so the per-preview subdir path
    // stays stable. No data products.
    if (isXrSubspace) {
      val sanitizedId = previewId.replace(Regex("[^A-Za-z0-9._-]"), "_")
      return PreviewOutputPlan(
        captures =
          listOf(Capture(renderOutput = "renders/$sanitizedId/composite.png", optional = true)),
        dataProducts = emptyList(),
      )
    }
    val nonComposable = isTile || isNotification || isGlanceAppWidget || isXrSubspace
    val effectiveTimings = if (nonComposable) emptyList() else timings
    val effectiveScrolls = if (nonComposable) emptyList() else scrolls
    // Tile / notification previews don't go through `mainClock` — there's no animation surface to
    // drive.
    val effectiveAnimation = if (nonComposable) null else animation
    // `@FocusedPreview` only applies to Compose previews (the focus owner is a Compose construct).
    // `gif = true` swaps the per-step PNG fan-out for a single GIF capture, so skip the per-step
    // PNG path entirely when a GIF spec is set.
    val effectiveFocusGif = if (nonComposable) null else focusGif
    val effectiveFocuses = if (nonComposable || effectiveFocusGif != null) emptyList() else focuses
    // `@AmbientPreview` is Wear-Compose-only — it drives `LocalAmbientModeManager`. Non-composable
    // previews render outside the Compose composition where the local lives, so the override is a
    // no-op there.
    val effectiveAmbient = if (nonComposable) null else ambient
    // `@GestureHintPreview` force-shows the Wear one-handed-gesture indicator, which lives in the
    // Compose composition — same reasoning as ambient: a no-op for non-composable previews.
    val effectiveGestureHint = if (nonComposable) null else gestureHint
    // `@LauncherWidgetPreview` wraps the composition in a sized Box — same reasoning as ambient:
    // non-composable previews have no Compose layout pass to wrap. The override is also dropped
    // for tile / notification renders.
    val effectiveLauncherWidget = if (nonComposable) null else launcherWidget
    val effectiveLauncherWidgetResize = if (nonComposable) null else launcherWidgetResize

    // @AnimatedPreview and @FocusedPreview(gif = true) both produce a `.gif` output for the
    // function. When one is paired with anything else on the same function — scroll/time
    // fan-out, or each other, or a `@LauncherWidgetResize` PNG fan-out — they need
    // disambiguating suffixes so neither silently overwrites the other. Plain filename only
    // when a single GIF mode owns the function with no scroll/time/resize siblings.
    val gifSharesFn =
      effectiveScrolls.isNotEmpty() ||
        effectiveTimings.isNotEmpty() ||
        effectiveLauncherWidgetResize != null ||
        (effectiveAnimation != null && effectiveFocusGif != null)

    // `@LauncherWidgetResize` owns the static capture list when present — it walks N whole-cell
    // stops between source and target sizes and emits one PNG per stop. Coexisting with the
    // standard scroll / time / focus fan-out doesn't make sense (a resize walk is its own
    // dimensional axis), so the resize captures fully replace the regular capture grid.
    // focusGif / animation GIFs still fan out independently — the resize annotation isn't meant
    // to combine with those either, but if a consumer stacks them the GIFs come out alongside
    // the resize PNGs with their existing suffixes (computed below using the shared
    // `gifSharesFn` flag).
    if (effectiveLauncherWidgetResize != null) {
      val stops =
        launcherWidgetResizeStops(
          from = effectiveLauncherWidgetResize.from,
          to = effectiveLauncherWidgetResize.to,
          order = effectiveLauncherWidgetResize.resizeOrder,
        )
      val resizeCaptures = stops.map { (w, h) ->
        Capture(
          launcherWidget =
            LauncherWidgetCapture(
              width = w,
              height = h,
              cellSizeDp = effectiveLauncherWidgetResize.cellSizeDp,
              cellSpacingDp = effectiveLauncherWidgetResize.cellSpacingDp,
              resizeOrder = effectiveLauncherWidgetResize.resizeOrder,
              frameDelayMs = effectiveLauncherWidgetResize.frameDelayMs,
              launcherMode = effectiveLauncherWidgetResize.launcherMode,
            ),
          ambient = effectiveAmbient,
          gestureHint = effectiveGestureHint,
          renderOutput = "renders/${previewId}_RESIZE_${w}x${h}.png",
          cost = STATIC_COST,
        )
      }
      val focusGifCaptures: List<Capture> =
        if (effectiveFocusGif == null) emptyList()
        else {
          val suffix = if (gifSharesFn) "_focus_gif" else ""
          listOf(
            Capture(
              focusGif = effectiveFocusGif,
              ambient = effectiveAmbient,
              gestureHint = effectiveGestureHint,
              launcherWidget = effectiveLauncherWidget,
              renderOutput = "renders/${previewId}${suffix}.gif",
              cost = FOCUS_GIF_COST,
            )
          )
        }
      val animationCaptures: List<Capture> =
        if (effectiveAnimation == null) emptyList()
        else {
          val suffix = if (gifSharesFn) "_anim" else ""
          listOf(
            Capture(
              animation = effectiveAnimation,
              renderOutput = "renders/${previewId}${suffix}.gif",
              cost = ANIMATION_COST,
            )
          )
        }
      return PreviewOutputPlan(
        captures = resizeCaptures + focusGifCaptures + animationCaptures,
        dataProducts = emptyList(),
      )
    }

    // @FocusedPreview(gif = true): one GIF capture per annotated function, dimension-flat —
    // doesn't cross with scrolls / timings / focus fan-out. Mirrors @AnimatedPreview's
    // "single-output annotation" pattern.
    val focusGifCaptures: List<Capture> =
      if (effectiveFocusGif == null) emptyList()
      else {
        val suffix = if (gifSharesFn) "_focus_gif" else ""
        listOf(
          Capture(
            focusGif = effectiveFocusGif,
            ambient = effectiveAmbient,
            gestureHint = effectiveGestureHint,
            launcherWidget = effectiveLauncherWidget,
            renderOutput = "renders/${previewId}${suffix}.gif",
            cost = FOCUS_GIF_COST,
          )
        )
      }

    // @AnimatedPreview produces its own dedicated capture, alongside any
    // scroll / time fan-out. The GIF gets a distinguishing `_anim` suffix
    // when other captures share the function (the multi-mode scroll
    // pattern, or a peer `@FocusedPreview(gif = true)` GIF), and the plain
    // filename otherwise.
    val animationCaptures: List<Capture> =
      if (effectiveAnimation == null) emptyList()
      else {
        val suffix = if (gifSharesFn) "_anim" else ""
        listOf(
          Capture(
            animation = effectiveAnimation,
            renderOutput = "renders/${previewId}${suffix}.gif",
            cost = ANIMATION_COST,
          )
        )
      }

    // Single-mode scroll keeps the plain filename so migrations from the
    // old single-valued `mode = …` annotation land on identical paths.
    // Multi-mode adds `_SCROLL_<mode>` to disambiguate siblings, same
    // pattern as `_TIME_<ms>ms` for the time dimension.
    val captureScrolls = effectiveScrolls.filterNot {
      it.mode == ScrollMode.LONG || it.mode == ScrollMode.GIF
    }
    val productScrolls = effectiveScrolls.filter {
      it.mode == ScrollMode.LONG || it.mode == ScrollMode.GIF
    }

    val scrollRows: List<Pair<ScrollCapture?, String>> =
      when {
        captureScrolls.isEmpty() -> listOf(null to "")
        captureScrolls.size == 1 -> listOf(captureScrolls[0] to "")
        else -> captureScrolls.map { it to "_SCROLL_${it.mode.name.lowercase()}" }
      }
    val timeRows: List<Pair<Long?, String>> =
      if (effectiveTimings.isEmpty()) listOf(null to "")
      else effectiveTimings.map { ms -> ms to "_TIME_${ms}ms" }
    // `@FocusedPreview` fans out one capture per index (indexed mode) or
    // per direction step (traversal mode). Single-capture annotations
    // keep the plain filename (matches the @ScrollingPreview single-mode
    // pattern). Empty → one (null, "") row, same shape as scroll/time
    // when their annotations are absent.
    val focusRows: List<Pair<FocusCapture?, String>> =
      when {
        effectiveFocuses.isEmpty() -> listOf(null to "")
        effectiveFocuses.size == 1 -> listOf(effectiveFocuses[0] to "")
        else -> effectiveFocuses.map { it to "_FOCUS_${focusSuffixOf(it)}" }
      }

    // When ONLY @AnimatedPreview (or @FocusedPreview(gif = true)) is on the function, the
    // scroll/time/focus cross-product would still emit one (null, null, null) row — i.e. a
    // static PNG capture. Suppress that to keep single-output annotations clean. The same
    // applies to @ScrollingPreview with only data-product modes (LONG/GIF): the data product
    // IS the rendered output (the tall stitched PNG / scrolling GIF), so a sibling static
    // `renders/<id>.png` would just be the unscrolled initial frame — misleading, and the
    // exact regression issue #1524 reported.
    val emitStaticCross =
      captureScrolls.isNotEmpty() ||
        effectiveTimings.isNotEmpty() ||
        effectiveFocuses.isNotEmpty() ||
        (effectiveAnimation == null && effectiveFocusGif == null && productScrolls.isEmpty())

    val scrollTimeCaptures: List<Capture> =
      if (!emitStaticCross) emptyList()
      else {
        scrollRows.flatMap { (scroll, scrollSuffix) ->
          timeRows.flatMap { (ms, timeSuffix) ->
            focusRows.map { (focus, focusSuffix) ->
              val ext = "png"
              // Cost is normalised to a static @Preview = 1.0. The mode
              // ladder (TOP < END) reflects how much extra
              // work each scroll variant adds on top of the baseline
              // compose pass. `advanceTimeMillis` alone is still one
              // pass at a specific virtual time, so it doesn't bump the
              // per-capture cost — the wall-time of a multi-timing
              // fan-out is in the *count*, which lives in the captures
              // list itself. Focus drive is similar: one moveFocus call
              // per stop, fixed-time work, no extra cost bucket.
              val captureCost =
                when (scroll?.mode) {
                  null -> STATIC_COST
                  ScrollMode.TOP -> SCROLL_TOP_COST
                  ScrollMode.END -> SCROLL_END_COST
                  ScrollMode.LONG -> SCROLL_LONG_COST
                  ScrollMode.GIF -> SCROLL_GIF_COST
                }
              Capture(
                advanceTimeMillis = ms,
                scroll = scroll,
                focus = focus,
                ambient = effectiveAmbient,
                gestureHint = effectiveGestureHint,
                launcherWidget = effectiveLauncherWidget,
                renderOutput =
                  "renders/${previewId}${scrollSuffix}${timeSuffix}${focusSuffix}.${ext}",
                cost = captureCost,
              )
            }
          }
        }
      }

    val dataProducts = productScrolls.flatMap { scroll ->
      val productSuffix =
        if (productScrolls.size == 1 && captureScrolls.isEmpty()) ""
        else "_SCROLL_${scroll.mode.name.lowercase()}"
      val ext = if (scroll.mode == ScrollMode.GIF) "gif" else "png"
      val cost = if (scroll.mode == ScrollMode.GIF) SCROLL_GIF_COST else SCROLL_LONG_COST
      val kind =
        when (scroll.mode) {
          ScrollMode.LONG -> "render/scroll/long"
          ScrollMode.GIF -> "render/scroll/gif"
          else -> error("non-product scroll mode ${scroll.mode}")
        }
      val displayName =
        when (scroll.mode) {
          ScrollMode.LONG -> "Long scroll"
          ScrollMode.GIF -> "Scroll GIF"
          else -> error("non-product scroll mode ${scroll.mode}")
        }
      val effectId =
        when (scroll.mode) {
          ScrollMode.LONG -> "long"
          ScrollMode.GIF -> "gif"
          else -> error("non-product scroll mode ${scroll.mode}")
        }
      val extensionId =
        when (scroll.mode) {
          ScrollMode.LONG -> "scroll-long"
          ScrollMode.GIF -> "scroll-gif"
          else -> error("non-product scroll mode ${scroll.mode}")
        }
      val facets =
        when (scroll.mode) {
          ScrollMode.LONG -> listOf(PreviewDataProductFacet.ARTIFACT, PreviewDataProductFacet.IMAGE)
          ScrollMode.GIF ->
            listOf(PreviewDataProductFacet.ARTIFACT, PreviewDataProductFacet.ANIMATION)
          else -> error("non-product scroll mode ${scroll.mode}")
        }
      val mediaTypes =
        when (scroll.mode) {
          ScrollMode.LONG -> listOf("image/png")
          ScrollMode.GIF -> listOf("image/gif")
          else -> error("non-product scroll mode ${scroll.mode}")
        }
      timeRows.map { (ms, timeSuffix) ->
        PreviewDataProduct(
          kind = kind,
          extensionId = extensionId,
          effectId = effectId,
          usageMode = PreviewExtensionUsageMode.SUGGESTED_EXTRA_PREVIEW,
          suggestedBy = SCROLLING_PREVIEW_FQN,
          displayName = displayName,
          facets = facets,
          mediaTypes = mediaTypes,
          sampling =
            if (scroll.mode == ScrollMode.GIF) PreviewDataProductSampling.EACH_FRAME
            else PreviewDataProductSampling.AGGREGATE,
          advanceTimeMillis = ms,
          scroll = scroll,
          output =
            "data/${kind.replace('/', '-')}/${previewId}${productSuffix}${timeSuffix}.${ext}",
          cost = cost,
        )
      }
    }

    return PreviewOutputPlan(
      captures = scrollTimeCaptures + animationCaptures + focusGifCaptures,
      dataProducts = dataProducts,
    )
  }

  // Reads `@RoboComposePreviewOptions(manualClockOptions = [...])` on the
  // preview function and returns the `advanceTimeMillis` of each entry.
  // Empty list if the annotation is absent OR present with no entries — the
  // latter is equivalent to "default" per Roborazzi's own scanner-support
  // behaviour. ClassGraph surfaces `manualClockOptions` as an
  // Object[] of `AnnotationInfo` because the field type is `Array<ManualClockOptions>`.
  private fun extractRoboTimings(annotations: List<AnnotationInfo>): List<Long> {
    val ann =
      annotations.firstOrNull { it.name == ROBO_COMPOSE_PREVIEW_OPTIONS_FQN } ?: return emptyList()
    val raw = ann.parameterValues.getValue("manualClockOptions") ?: return emptyList()
    val items =
      when (raw) {
        is Array<*> -> raw.filterIsInstance<AnnotationInfo>()
        is AnnotationInfo -> listOf(raw)
        else -> {
          // Some ClassGraph versions hand back a typed primitive array or
          // Kotlin wrapper — fall back to reflective iteration.
          val len = runCatching { java.lang.reflect.Array.getLength(raw) }.getOrNull() ?: 0
          (0 until len).mapNotNull { java.lang.reflect.Array.get(raw, it) as? AnnotationInfo }
        }
      }
    return items.mapNotNull { it.parameterValues.getValue("advanceTimeMillis") as? Long }
  }

  /**
   * Resolves the `PreviewWrapperProvider` FQN for [method]'s previews.
   *
   * Must work off the method's **direct** annotations, not `method.annotationInfo` — ClassGraph
   * flattens the whole meta-annotation closure into that list, so a function tagged with both a
   * direct `@PreviewWrapperClass` and a multi-preview annotation that *also* hoists one would show
   * two indistinguishable `@PreviewWrapperClass` entries and the direct-wins precedence would be
   * decided by list order. `directOnly()` restores the distinction: a wrapper written directly on
   * the function wins; otherwise it's inherited from a multi-preview annotation that hoists one.
   */
  private fun extractWrapperFqn(method: MethodInfo, scanResult: ScanResult): String? {
    val directAnnotations = method.annotationInfo?.directOnly()?.toList() ?: emptyList()
    // A wrapper declared directly on the function (androidx `@PreviewWrapper` or our
    // `@PreviewWrapperClass`) wins over any inherited from a multi-preview meta-annotation.
    directWrapperFqn(directAnnotations)?.let {
      return it
    }
    // Otherwise inherit from a multi-preview annotation that carries the wrapper. androidx's
    // `@PreviewWrapper` is `@Target(FUNCTION)`-only so it can never legally sit on an annotation
    // class, but our `@PreviewWrapperClass` can — hoisting the wrapper onto the multi-preview
    // saves repeating it on every tagged function.
    val visited = mutableSetOf<String>()
    for (ann in directAnnotations) {
      wrapperFromMetaAnnotation(ann, scanResult, visited)?.let {
        return it
      }
    }
    return null
  }

  /**
   * Reads a wrapper FQN from a direct annotation list — androidx `@PreviewWrapper(wrapper = …)`
   * first (its `KClass` surfaces as an [AnnotationClassRef]), then our
   * `@PreviewWrapperClass(wrapperClassName = …)` (a plain String). Returns `null` if neither is
   * present.
   */
  private fun directWrapperFqn(annotations: List<AnnotationInfo>): String? {
    annotations
      .firstOrNull { it.name == PREVIEW_WRAPPER_FQN }
      ?.let { ann ->
        // The `wrapper: KClass<out PreviewWrapperProvider>` parameter surfaces as an
        // AnnotationClassRef — pull the FQN without triggering classloading.
        return when (val value = ann.parameterValues.getValue("wrapper")) {
          is AnnotationClassRef -> value.name
          is String -> value
          else -> null
        }
      }
    annotations
      .firstOrNull { it.name == PREVIEW_WRAPPER_CLASS_FQN }
      ?.let { ann ->
        return ann.parameterValues.getValue("wrapperClassName") as? String
      }
    return null
  }

  /**
   * Recursively searches a multi-preview meta-annotation (and its own meta-annotations) for a
   * hoisted wrapper declaration, mirroring [resolveMultiPreview]'s traversal + cycle guard. Skips
   * `@Preview` itself and its repeatable container — a wrapper only ever rides on a custom
   * multi-preview annotation.
   */
  private fun wrapperFromMetaAnnotation(
    ann: AnnotationInfo,
    scanResult: ScanResult,
    visited: MutableSet<String>,
  ): String? {
    if (ann.name in visited) return null
    if (isDirectPreview(ann) || isPreviewContainer(ann)) return null
    visited.add(ann.name)
    val annClassInfo = scanResult.getClassInfo(ann.name) ?: return null
    val metaAnns = annClassInfo.annotationInfo.toList()
    directWrapperFqn(metaAnns)?.let {
      return it
    }
    for (metaAnn in metaAnns) {
      wrapperFromMetaAnnotation(metaAnn, scanResult, visited)?.let {
        return it
      }
    }
    return null
  }

  /**
   * Reads `@AnimatedPreview(durationMs, frameIntervalMs, showCurves)` off the function annotation
   * list. Single-shot — at most one animation capture per function, so we return a nullable spec
   * rather than a list. Negative / zero numeric fields fall back to the annotation defaults.
   */
  private fun extractAnimationSpec(annotations: List<AnnotationInfo>): AnimationCapture? {
    val ann = annotations.firstOrNull { it.name == ANIMATED_PREVIEW_FQN } ?: return null
    val pv = ann.parameterValues
    // `durationMs = 0` is the auto-detect sentinel; let the renderer ask
    // PreviewAnimationClock for the real duration. A positive value
    // overrides; negatives clamp to the sentinel.
    val durationMs = (pv.getValue("durationMs") as? Int)?.coerceAtLeast(0) ?: 0
    val frameIntervalMs = (pv.getValue("frameIntervalMs") as? Int)?.takeIf { it > 0 } ?: 33
    val showCurves = (pv.getValue("showCurves") as? Boolean) ?: true
    return AnimationCapture(
      durationMs = durationMs,
      frameIntervalMs = frameIntervalMs,
      showCurves = showCurves,
    )
  }

  /**
   * Filename suffix for a single [FocusCapture]. Traversal mode emits `step<n>_<direction>` so
   * repeated directions (e.g. `Next, Next, Previous`) get unique paths; indexed mode emits the tab
   * index. Empty when neither field is set (defensive — the discovery extractor doesn't emit such
   * captures).
   */
  private fun focusSuffixOf(focus: FocusCapture): String {
    val direction = focus.direction
    val step = focus.step
    return when {
      direction != null && step != null -> "step${step}_${direction.name}"
      focus.tabIndex != null -> focus.tabIndex.toString()
      else -> ""
    }
  }

  /**
   * Reads `@FocusedPreview(indices, traverse, overlay)` off the function annotation list. Returns
   * one [FocusCapture] per capture requested — traversal mode (one per direction step) when
   * `traverse` is non-empty, otherwise indexed mode (one per non-negative tab index, sorted
   * ascending and de-duplicated). The boolean `overlay` flag is stamped onto every returned
   * capture. Empty inputs collapse to no captures (the annotation falls back to the cross-product's
   * null row).
   */
  /**
   * Reads `@AmbientPreview(state, burnInProtectionRequired, deviceHasLowBitAmbient)` off the
   * function annotation list. Returns a single [AmbientCapture] when present, `null` otherwise.
   * Mirrors `extractFocusSpecs` but single-shot — the annotation maps to one preview variant per
   * function (the consumer authors a separate `@AmbientPreview` `@Preview` function for each state
   * they want to render).
   */
  private fun extractAmbientSpec(annotations: List<AnnotationInfo>): AmbientCapture? {
    val ann = annotations.firstOrNull { it.name == AMBIENT_PREVIEW_FQN } ?: return null
    val pv = ann.parameterValues
    val stateName =
      (pv.getValue("state") as? AnnotationEnumValue)?.valueName ?: AmbientCaptureState.Ambient.name
    val state =
      runCatching { AmbientCaptureState.valueOf(stateName) }
        .getOrDefault(AmbientCaptureState.Ambient)
    val burnIn = (pv.getValue("burnInProtectionRequired") as? Boolean) ?: false
    val lowBit = (pv.getValue("deviceHasLowBitAmbient") as? Boolean) ?: false
    return AmbientCapture(
      state = state,
      burnInProtectionRequired = burnIn,
      deviceHasLowBitAmbient = lowBit,
    )
  }

  /**
   * Reads a `@GestureHintPreview` off [annotations] into a [GestureHintCapture], or `null` when the
   * annotation is absent. Like [extractAmbientSpec] this is a single-shot per function — the
   * consumer pairs a bare `@Preview` (hint off) with a `@GestureHintPreview` `@Preview` (hint on)
   * over the same screen.
   */
  private fun extractGestureHintSpec(annotations: List<AnnotationInfo>): GestureHintCapture? {
    val ann = annotations.firstOrNull { it.name == GESTURE_HINT_PREVIEW_FQN } ?: return null
    val showHints = (ann.parameterValues.getValue("showHints") as? Boolean) ?: true
    return GestureHintCapture(showHints = showHints)
  }

  /**
   * Walking-state for a `@LauncherWidgetResize` annotation: the source / target cell counts plus
   * the shared cell-grid knobs every stop on the walk inherits. The cell-bound clamp from
   * `@LauncherWidgetPreview` is intentionally absent — `@LauncherWidgetResize` is point-to-point
   * (from explicitly given), not slider-style.
   */
  private data class LauncherWidgetResizeSpec(
    val from: Pair<Int, Int>,
    val to: Pair<Int, Int>,
    val cellSizeDp: Int?,
    val cellSpacingDp: Int?,
    val resizeOrder: LauncherWidgetCaptureResizeOrder,
    val frameDelayMs: Int,
    val launcherMode: Boolean,
  )

  /**
   * Whole-cell stops on the walk between `from` and `to` under [order]. Algorithm copy of
   * `:data-launcher-widget-connector`'s `launcherWidgetStops(...)` — the gradle plugin can't depend
   * on the connector at discovery time, so the algorithm is duplicated here. Keep in sync with the
   * connector if the underlying behaviour ever changes.
   */
  private fun launcherWidgetResizeStops(
    from: Pair<Int, Int>,
    to: Pair<Int, Int>,
    order: LauncherWidgetCaptureResizeOrder,
  ): List<Pair<Int, Int>> {
    if (from == to) return listOf(from)
    return when (order) {
      LauncherWidgetCaptureResizeOrder.Diagonal -> {
        val dw = to.first - from.first
        val dh = to.second - from.second
        val n = maxOf(kotlin.math.abs(dw), kotlin.math.abs(dh))
        (0..n).map { i ->
          val w = from.first + Math.round(dw.toDouble() * i / n).toInt()
          val h = from.second + Math.round(dh.toDouble() * i / n).toInt()
          w to h
        }
      }
      LauncherWidgetCaptureResizeOrder.WidthFirst -> {
        val stops = mutableListOf(from)
        walkAxis(from.first, to.first) { w -> stops.add(w to from.second) }
        walkAxis(from.second, to.second) { h -> stops.add(to.first to h) }
        stops
      }
      LauncherWidgetCaptureResizeOrder.HeightFirst -> {
        val stops = mutableListOf(from)
        walkAxis(from.second, to.second) { h -> stops.add(from.first to h) }
        walkAxis(from.first, to.first) { w -> stops.add(w to to.second) }
        stops
      }
    }
  }

  private inline fun walkAxis(from: Int, to: Int, emit: (Int) -> Unit) {
    if (from == to) return
    val step = if (to > from) 1 else -1
    var v = from
    while (v != to) {
      v += step
      emit(v)
    }
  }

  /**
   * Reads `@LauncherWidgetResize(fromWidth, fromHeight, toWidth, toHeight, ...)` off the function
   * annotation list. Returns a single [LauncherWidgetResizeSpec] when present, `null` otherwise.
   */
  private fun extractLauncherWidgetResizeSpec(
    annotations: List<AnnotationInfo>
  ): LauncherWidgetResizeSpec? {
    val ann = annotations.firstOrNull { it.name == LAUNCHER_WIDGET_RESIZE_FQN } ?: return null
    val pv = ann.parameterValues
    val fromWidth = (pv.getValue("fromWidth") as? Int) ?: return null
    val fromHeight = (pv.getValue("fromHeight") as? Int) ?: return null
    val toWidth = (pv.getValue("toWidth") as? Int) ?: return null
    val toHeight = (pv.getValue("toHeight") as? Int) ?: return null
    fun optionalInt(name: String): Int? = (pv.getValue(name) as? Int)?.takeIf { it >= 0 }
    val orderName =
      (pv.getValue("resizeOrder") as? AnnotationEnumValue)?.valueName
        ?: LauncherWidgetCaptureResizeOrder.WidthFirst.name
    val order =
      runCatching { LauncherWidgetCaptureResizeOrder.valueOf(orderName) }
        .getOrDefault(LauncherWidgetCaptureResizeOrder.WidthFirst)
    val frameDelay = (pv.getValue("frameDelayMs") as? Int)?.coerceAtLeast(0) ?: 600
    val launcherMode = (pv.getValue("launcherMode") as? Boolean) ?: false
    return LauncherWidgetResizeSpec(
      from = fromWidth to fromHeight,
      to = toWidth to toHeight,
      cellSizeDp = optionalInt("cellSizeDp"),
      cellSpacingDp = optionalInt("cellSpacingDp"),
      resizeOrder = order,
      frameDelayMs = frameDelay,
      launcherMode = launcherMode,
    )
  }

  /**
   * Reads `@LauncherWidgetPreview(width, height, cellSizeDp, cellSpacingDp, minWidth, …)` off the
   * function annotation list. Returns a single [LauncherWidgetCapture] when present, `null`
   * otherwise. Mirrors `extractAmbientSpec` — single-shot per function, applied to every preview
   * variant. Optional `Int` parameters use `-1` as the "not set" sentinel (annotation parameters
   * can't be nullable in Kotlin); we map that back to `null` so the renderer / connector apply
   * their own defaults rather than treating `-1` as a literal.
   */
  private fun extractLauncherWidgetSpec(annotations: List<AnnotationInfo>): LauncherWidgetCapture? {
    val ann = annotations.firstOrNull { it.name == LAUNCHER_WIDGET_PREVIEW_FQN } ?: return null
    val pv = ann.parameterValues
    val width = (pv.getValue("width") as? Int) ?: return null
    val height = (pv.getValue("height") as? Int) ?: return null
    fun optionalInt(name: String): Int? = (pv.getValue(name) as? Int)?.takeIf { it >= 0 }
    val orderName =
      (pv.getValue("resizeOrder") as? AnnotationEnumValue)?.valueName
        ?: LauncherWidgetCaptureResizeOrder.WidthFirst.name
    val order =
      runCatching { LauncherWidgetCaptureResizeOrder.valueOf(orderName) }
        .getOrDefault(LauncherWidgetCaptureResizeOrder.WidthFirst)
    val launcherMode = (pv.getValue("launcherMode") as? Boolean) ?: false
    return LauncherWidgetCapture(
      width = width,
      height = height,
      cellSizeDp = optionalInt("cellSizeDp"),
      cellSpacingDp = optionalInt("cellSpacingDp"),
      minWidth = optionalInt("minWidth"),
      minHeight = optionalInt("minHeight"),
      maxWidth = optionalInt("maxWidth"),
      maxHeight = optionalInt("maxHeight"),
      resizeOrder = order,
      launcherMode = launcherMode,
    )
  }

  private fun extractFocusSpecs(annotations: List<AnnotationInfo>): List<FocusCapture> {
    val ann = annotations.firstOrNull { it.name == FOCUSED_PREVIEW_FQN } ?: return emptyList()
    return readFocusSteps(ann)
  }

  /**
   * Returns a single [FocusGifCapture] when the function carries `@FocusedPreview(gif = true)` and
   * the captured step list has at least one entry. `null` otherwise — single-step annotations
   * collapse to plain captures and never produce a GIF (a one-frame GIF wouldn't animate anything).
   */
  private fun extractFocusGifSpec(annotations: List<AnnotationInfo>): FocusGifCapture? {
    val ann = annotations.firstOrNull { it.name == FOCUSED_PREVIEW_FQN } ?: return null
    val gif = (ann.parameterValues.getValue("gif") as? Boolean) ?: false
    if (!gif) return null
    val steps = readFocusSteps(ann)
    if (steps.size < 2) return null
    return FocusGifCapture(steps = steps)
  }

  private fun readFocusSteps(ann: AnnotationInfo): List<FocusCapture> {
    val pv = ann.parameterValues
    val overlay = (pv.getValue("overlay") as? Boolean) ?: false
    val enterPlacesFocus = (pv.getValue("enterPlacesFocus") as? Boolean) ?: false
    val pressed = (pv.getValue("pressed") as? Boolean) ?: false
    val directions = readEnumArray(pv.getValue("traverse")) { FocusDirection.valueOf(it) }
    if (directions.isNotEmpty()) {
      // 1-based `step` lets the overlay label and the filename suffix
      // disambiguate repeated directions (e.g. `Next, Next, Previous`). `pressed` is
      // indexed-mode only — traversal-mode walks across focusables without a "settle and press"
      // point, so it's intentionally not carried here.
      return directions.mapIndexed { i, dir ->
        FocusCapture(direction = dir, step = i + 1, overlay = overlay)
      }
    }
    val raw = pv.getValue("indices")
    val indices: IntArray =
      when (raw) {
        is IntArray -> raw
        is Array<*> -> raw.filterIsInstance<Int>().toIntArray()
        else -> intArrayOf()
      }
    return indices
      .filter { it >= 0 }
      .distinct()
      .sorted()
      .map {
        FocusCapture(
          tabIndex = it,
          overlay = overlay,
          enterPlacesFocus = enterPlacesFocus,
          pressed = pressed,
        )
      }
  }

  private fun extractScrollSpecs(annotations: List<AnnotationInfo>): List<ScrollCapture> {
    val ann = annotations.firstOrNull { it.name == SCROLLING_PREVIEW_FQN } ?: return emptyList()
    val pv = ann.parameterValues
    // ClassGraph surfaces the `modes: Array<ScrollMode>` field as an
    // Object[] of AnnotationEnumValue; same shape as `manualClockOptions`
    // above. Enum constants are compared by `.valueName` so we never
    // force-load the annotation's classes.
    val rawModes = pv.getValue("modes")
    val modes = readEnumArray(rawModes) { ScrollMode.valueOf(it) }
    if (modes.isEmpty()) return emptyList()
    val axis =
      (pv.getValue("axis") as? AnnotationEnumValue)?.valueName?.let {
        runCatching { ScrollAxis.valueOf(it) }.getOrNull()
      } ?: ScrollAxis.VERTICAL
    val maxScrollPx = (pv.getValue("maxScrollPx") as? Int)?.coerceAtLeast(0) ?: 0
    val reduceMotion = (pv.getValue("reduceMotion") as? Boolean) ?: true
    // `frameIntervalMs` only meaningful for GIF mode; we still read it
    // unconditionally and carry it into every ScrollCapture so the
    // manifest shape stays uniform. `0` (or negative, coerced) signals
    // "use the renderer's default" — matching the annotation-side
    // DEFAULT_GIF_FRAME_INTERVAL_MS without duplicating the literal here.
    val frameIntervalMs = (pv.getValue("frameIntervalMs") as? Int)?.coerceAtLeast(0) ?: 0
    // Result fields (atEnd, reachedPx) default to "not reported" — the
    // renderer would fill them in post-capture; discovery knows only the
    // intent. De-dup to guard against `modes = [END, END]` producing
    // colliding paths. Sort by enum ordinal (TOP→END→LONG→GIF) so the
    // renderer captures the initial frame before driving the scroller —
    // otherwise `modes = [END, TOP]` would produce a "TOP" PNG at the
    // scrolled-end position.
    return modes
      .distinct()
      .sortedBy { it.ordinal }
      .map { mode ->
        ScrollCapture(
          mode = mode,
          axis = axis,
          maxScrollPx = maxScrollPx,
          reduceMotion = reduceMotion,
          frameIntervalMs = frameIntervalMs,
        )
      }
  }

  // Reads an annotation's Array<EnumT> parameter and maps each entry by
  // `.valueName` through [parse]. ClassGraph can hand this back as a plain
  // array, a single AnnotationEnumValue (single-entry arrays), or a typed
  // array we need to walk reflectively — same cases as
  // [extractRoboTimings].
  private fun <T> readEnumArray(raw: Any?, parse: (String) -> T): List<T> {
    if (raw == null) return emptyList()
    val items =
      when (raw) {
        is Array<*> -> raw.filterIsInstance<AnnotationEnumValue>()
        is AnnotationEnumValue -> listOf(raw)
        else -> {
          val len = runCatching { java.lang.reflect.Array.getLength(raw) }.getOrNull() ?: 0
          (0 until len).mapNotNull { java.lang.reflect.Array.get(raw, it) as? AnnotationEnumValue }
        }
      }
    return items.mapNotNull { runCatching { parse(it.valueName) }.getOrNull() }
  }

  private fun isDirectPreview(ann: AnnotationInfo): Boolean = ann.name in PREVIEW_FQNS

  private fun isPreviewContainer(ann: AnnotationInfo): Boolean = ann.name in CONTAINER_FQNS

  private fun collectDirectPreviews(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
    val result = mutableListOf<AnnotationInfo>()
    for (ann in annotations) {
      when {
        isDirectPreview(ann) -> result.add(ann)
        isPreviewContainer(ann) -> {
          val value = ann.parameterValues.getValue("value")
          when (value) {
            is Array<*> -> value.filterIsInstance<AnnotationInfo>().forEach { result.add(it) }
            is AnnotationInfo -> result.add(value)
            else -> {
              val len = java.lang.reflect.Array.getLength(value)
              for (i in 0 until len) {
                val elem = java.lang.reflect.Array.get(value, i)
                if (elem is AnnotationInfo) result.add(elem)
              }
            }
          }
        }
      }
    }
    return result
  }

  // Preview-adjacent annotations we own that legitimately never expand into a `@Preview` — they
  // modify, wrap, or parameterise a preview rather than declare one. Their simple names all contain
  // `Preview`, so the unexpandable-annotation heuristic below (which matches on
  // `contains("Preview")`
  // — the wear multi-preview annotations put `Preview` mid-name, e.g. `WearPreviewLargeRound`) must
  // exclude them explicitly or it would warn on every `@ScrollingPreview` / `@PreviewParameter`.
  // The
  // direct-preview FQNs — plain / desktop / tile / notification / glance / XR `@Preview` — are
  // excluded separately by [isDirectPreview].
  private val NON_EXPANDING_PREVIEW_FQNS =
    setOf(
      SCROLLING_PREVIEW_FQN,
      ANIMATED_PREVIEW_FQN,
      FOCUSED_PREVIEW_FQN,
      AMBIENT_PREVIEW_FQN,
      GESTURE_HINT_PREVIEW_FQN,
      LAUNCHER_WIDGET_PREVIEW_FQN,
      LAUNCHER_WIDGET_RESIZE_FQN,
      OVERRIDE_VARIANT_FQN,
      OVERRIDE_VARIANT_CONTAINER_FQN,
      PREVIEW_PARAMETER_FQN,
      PREVIEW_WRAPPER_FQN,
      PREVIEW_WRAPPER_CLASS_FQN,
      ROBO_COMPOSE_PREVIEW_OPTIONS_FQN,
    )

  /**
   * FQNs among [annotations] that look like preview-family annotations — a multi-preview
   * meta-annotation whose simple name contains `Preview` — whose annotation class is NOT on the
   * discovery classpath, so [resolveMultiPreview] can't reach the `@Preview`(s) inside them and the
   * preview is dropped with no diagnostic. Issue #2613: `@WearPreviewLargeRound` in an app's `main`
   * source set, whose wear tooling artifact was wired only into `screenshotTest`, vanished this
   * way.
   *
   * Keyed on the annotation class being **absent from the scan** (`getClassInfo == null`). The scan
   * doesn't `enableExternalClasses()`, so ClassGraph returns null — never a placeholder — for a
   * class it only saw referenced, which is precisely the off-classpath case. This is what
   * distinguishes a genuinely-dropped preview from a *reachable* annotation that merely happens to
   * contain `Preview` in its name and isn't a multi-preview (a project's own `@PreviewOnly`
   * marker): the latter is scanned, so `getClassInfo` is non-null and it is not flagged — no
   * misleading "classpath" WARN on healthy modules (Codex review, PR #2631). `isExternalClass` is
   * folded in defensively in case external-class scanning is ever enabled. The
   * capture/wrapper/parameter annotations we own are excluded via [NON_EXPANDING_PREVIEW_FQNS], and
   * direct `@Preview` / `Preview.Container` are handled elsewhere.
   */
  private fun unexpandablePreviewAnnotationNames(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
  ): List<String> =
    annotations
      .asSequence()
      .filterNot { isDirectPreview(it) || isPreviewContainer(it) }
      .filterNot { it.name in NON_EXPANDING_PREVIEW_FQNS }
      .filter { ann ->
        ann.name.substringAfterLast('.').contains("Preview") &&
          scanResult.getClassInfo(ann.name).let { it == null || it.isExternalClass }
      }
      .map { it.name }
      .distinct()
      .toList()

  /**
   * A single `@Preview` expansion of a well-known AndroidX / Wear multi-preview annotation, used as
   * a built-in fallback when the annotation class is off the discovery classpath (issue #2613).
   * Only the fields these annotations actually vary are modelled.
   */
  private data class BuiltInPreviewSpec(
    val name: String? = null,
    val group: String? = null,
    val device: String? = null,
    val fontScale: Float = 1.0f,
    val uiMode: Int = 0,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0L,
  )

  // Every wear `@Preview` sets showBackground / showSystemUi / backgroundColor=0xff000000 and
  // labels
  // the variant with `group`, never `name` (verbatim from
  // androidx.wear.compose:compose-ui-tooling).
  private fun wearSpec(device: String, group: String, fontScale: Float = 1.0f) =
    BuiltInPreviewSpec(
      group = group,
      device = device,
      fontScale = fontScale,
      showSystemUi = true,
      showBackground = true,
      backgroundColor = 0xff000000L,
    )

  /**
   * Stable, documented `@Preview` expansions of the well-known AndroidX / Wear multi-preview
   * annotations, transcribed verbatim from the AndroidX sources (wear:
   * `androidx.wear.compose:compose-ui-tooling`; compose: `androidx.compose.ui:ui-tooling-preview`
   * `MultiPreviews.kt`). Consulted only when the annotation class is off the discovery classpath —
   * see [builtInExpansionFor] — so a preview annotated only with e.g. `@WearPreviewLargeRound` in a
   * `main` source set (its wear tooling wired into `screenshotTest`) still renders instead of
   * vanishing. `@PreviewDynamicColors` is intentionally absent: its only axis is `wallpaper=`,
   * which this pipeline doesn't model, so its four variants would render identically — the
   * off-classpath WARN is more honest than four duplicate PNGs.
   */
  private val BUILT_IN_MULTIPREVIEW_EXPANSIONS: Map<String, List<BuiltInPreviewSpec>> =
    mapOf(
      "androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound" to
        listOf(wearSpec("id:wearos_large_round", "Devices - Large Round")),
      "androidx.wear.compose.ui.tooling.preview.WearPreviewSmallRound" to
        listOf(wearSpec("id:wearos_small_round", "Devices - Small Round")),
      "androidx.wear.compose.ui.tooling.preview.WearPreviewSquare" to
        listOf(wearSpec("id:wearos_square", "Devices - Small Square")),
      "androidx.wear.compose.ui.tooling.preview.WearPreviewDevices" to
        listOf(
          wearSpec("id:wearos_large_round", "Devices - Large Round"),
          wearSpec("id:wearos_small_round", "Devices - Small Round"),
        ),
      "androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales" to
        listOf(
          wearSpec("id:wearos_small_round", "Fonts - Small", 0.94f),
          wearSpec("id:wearos_small_round", "Fonts - Normal", 1.0f),
          wearSpec("id:wearos_small_round", "Fonts - Medium", 1.06f),
          wearSpec("id:wearos_small_round", "Fonts - Large", 1.12f),
          wearSpec("id:wearos_small_round", "Fonts - Larger", 1.18f),
          wearSpec("id:wearos_small_round", "Fonts - Largest", 1.24f),
        ),
      "androidx.compose.ui.tooling.preview.PreviewLightDark" to
        listOf(
          BuiltInPreviewSpec(name = "Light"),
          // uiMode = UI_MODE_NIGHT_YES (0x20) or UI_MODE_TYPE_NORMAL (0x01) = 0x21
          BuiltInPreviewSpec(name = "Dark", uiMode = 0x21),
        ),
      "androidx.compose.ui.tooling.preview.PreviewFontScale" to
        listOf(
          BuiltInPreviewSpec(name = "85%", fontScale = 0.85f),
          BuiltInPreviewSpec(name = "100%", fontScale = 1.0f),
          BuiltInPreviewSpec(name = "115%", fontScale = 1.15f),
          BuiltInPreviewSpec(name = "130%", fontScale = 1.3f),
          BuiltInPreviewSpec(name = "150%", fontScale = 1.5f),
          BuiltInPreviewSpec(name = "180%", fontScale = 1.8f),
          BuiltInPreviewSpec(name = "200%", fontScale = 2.0f),
        ),
      "androidx.compose.ui.tooling.preview.PreviewScreenSizes" to
        listOf(
          BuiltInPreviewSpec(
            name = "Phone",
            device = "spec:width=411dp,height=891dp",
            showSystemUi = true,
          ),
          BuiltInPreviewSpec(
            name = "Phone - Landscape",
            device = "spec:width=411dp,height=891dp,orientation=landscape,dpi=420",
            showSystemUi = true,
          ),
          BuiltInPreviewSpec(
            name = "Unfolded Foldable",
            device = "spec:width=673dp,height=841dp",
            showSystemUi = true,
          ),
          BuiltInPreviewSpec(
            name = "Tablet",
            device = "spec:width=1280dp,height=800dp,dpi=240,orientation=portrait",
            showSystemUi = true,
          ),
          BuiltInPreviewSpec(
            name = "Tablet - Landscape",
            device = "spec:width=1280dp,height=800dp,dpi=240",
            showSystemUi = true,
          ),
          BuiltInPreviewSpec(
            name = "Desktop",
            device = "spec:width=1920dp,height=1080dp,dpi=160",
            showSystemUi = true,
          ),
        ),
    )

  /**
   * The built-in [BuiltInPreviewSpec] expansion for [ann], but ONLY when its annotation class is
   * off the discovery classpath (`getClassInfo == null`; `isExternalClass` folded in defensively).
   * An on-classpath copy is resolved from its real `@Preview` definitions by [resolveMultiPreview]
   * instead, so a project that shadows the annotation keeps its own definition and we never
   * double-expand.
   */
  private fun builtInExpansionFor(
    ann: AnnotationInfo,
    scanResult: ScanResult,
  ): List<BuiltInPreviewSpec> {
    if (isDirectPreview(ann) || isPreviewContainer(ann)) return emptyList()
    val specs = BUILT_IN_MULTIPREVIEW_EXPANSIONS[ann.name] ?: return emptyList()
    val ci = scanResult.getClassInfo(ann.name)
    return if (ci == null || ci.isExternalClass) specs else emptyList()
  }

  /**
   * Builds [PreviewParams] from a built-in spec, resolving the device to concrete dims/density the
   * same way [extractPreviewParams] does for a real `@Preview`, and threading the function-level
   * `@PreviewWrapper` / `@PreviewParameter` bindings through.
   */
  private fun BuiltInPreviewSpec.toParams(
    wrapperClassName: String?,
    previewParameter: Pair<String, Int>?,
  ): PreviewParams {
    val effectiveWidth: Int?
    val effectiveHeight: Int?
    val effectiveDensity: Float?
    if (device != null || showSystemUi) {
      val dims = DeviceDimensions.resolve(device, null, null)
      effectiveWidth = dims.widthDp
      effectiveHeight = dims.heightDp
      effectiveDensity = dims.density
    } else {
      effectiveWidth = null
      effectiveHeight = null
      effectiveDensity = DeviceDimensions.DEFAULT_DENSITY
    }
    return PreviewParams(
      name = name,
      device = device,
      widthDp = effectiveWidth,
      heightDp = effectiveHeight,
      density = effectiveDensity,
      fontScale = fontScale,
      showSystemUi = showSystemUi,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      uiMode = uiMode,
      group = group,
      wrapperClassName = wrapperClassName,
      previewParameterProviderClassName = previewParameter?.first,
      previewParameterLimit = previewParameter?.second ?: Int.MAX_VALUE,
      kind = PreviewKind.COMPOSE,
    )
  }

  private fun resolveMultiPreview(
    ann: AnnotationInfo,
    scanResult: ScanResult,
    visited: MutableSet<String>,
  ): List<AnnotationInfo> {
    if (ann.name in visited) return emptyList()
    if (isDirectPreview(ann) || isPreviewContainer(ann)) return emptyList()
    visited.add(ann.name)

    val annClassInfo = scanResult.getClassInfo(ann.name) ?: return emptyList()
    val directPreviews = collectDirectPreviews(annClassInfo.annotationInfo.toList())
    if (directPreviews.isNotEmpty()) return directPreviews

    val result = mutableListOf<AnnotationInfo>()
    for (metaAnn in annClassInfo.annotationInfo) {
      result.addAll(resolveMultiPreview(metaAnn, scanResult, visited))
    }
    return result
  }

  private fun makePreview(
    classInfo: ClassInfo,
    method: MethodInfo,
    ann: AnnotationInfo,
    wrapperClassName: String?,
    scrolls: List<ScrollCapture>,
    animation: AnimationCapture?,
    focuses: List<FocusCapture>,
    focusGif: FocusGifCapture?,
    ambient: AmbientCapture?,
    gestureHint: GestureHintCapture?,
    launcherWidget: LauncherWidgetCapture?,
    launcherWidgetResize: LauncherWidgetResizeSpec?,
    timings: List<Long>,
    previewParameter: Pair<String, Int>?,
    previewSourceFile: String?,
    inferredTargets: Lazy<List<PreviewTarget>>,
  ): PreviewInfo {
    val params = extractPreviewParams(ann, wrapperClassName, previewParameter)
    return buildPreviewInfo(
      classInfo,
      method,
      params,
      scrolls,
      animation,
      focuses,
      focusGif,
      ambient,
      gestureHint,
      launcherWidget,
      launcherWidgetResize,
      timings,
      previewSourceFile,
      inferredTargets,
    )
  }

  /**
   * Assembles a [PreviewInfo] from already-resolved [params] — the shared tail of [makePreview]
   * (which sources [params] from a real `@Preview` [AnnotationInfo]) and the built-in multi-preview
   * expansion (which builds [params] from a [BuiltInPreviewSpec] table when the annotation class is
   * off the discovery classpath — issue #2613). Keeping the id/suffix/output-plan/target assembly
   * in one place means a synthesised preview fans out captures (scroll/animation/focus/…) and
   * infers targets identically to a real one.
   */
  private fun buildPreviewInfo(
    classInfo: ClassInfo,
    method: MethodInfo,
    params: PreviewParams,
    scrolls: List<ScrollCapture>,
    animation: AnimationCapture?,
    focuses: List<FocusCapture>,
    focusGif: FocusGifCapture?,
    ambient: AmbientCapture?,
    gestureHint: GestureHintCapture?,
    launcherWidget: LauncherWidgetCapture?,
    launcherWidgetResize: LauncherWidgetResizeSpec?,
    timings: List<Long>,
    previewSourceFile: String?,
    inferredTargets: Lazy<List<PreviewTarget>>,
  ): PreviewInfo {
    val fqn = "${classInfo.name}.${method.name}"
    val id = fqn + buildVariantSuffix(params)
    val outputPlan =
      buildOutputPlan(
        params.kind,
        id,
        scrolls,
        animation,
        focuses,
        focusGif,
        ambient,
        gestureHint,
        launcherWidget,
        launcherWidgetResize,
        timings,
      )
    // Tile / notification previews don't go through @Composable invocations — they return a
    // `TilePreviewData` / `Notification` and the renderer reflects them directly. Skipping the
    // lazy means the bytecode walk never runs for these methods.
    val targets =
      if (
        params.kind == PreviewKind.TILE ||
          params.kind == PreviewKind.NOTIFICATION ||
          params.kind == PreviewKind.GLANCE_APPWIDGET ||
          params.kind == PreviewKind.XR_SUBSPACE
      )
        emptyList()
      else inferredTargets.value
    return PreviewInfo(
      id = id,
      functionName = method.name,
      className = classInfo.name,
      sourceFile = previewSourceFile,
      params = params,
      captures = outputPlan.captures,
      dataProducts = outputPlan.dataProducts,
      targets = targets,
    )
  }

  // Module-relative source path, e.g. "src/main/kotlin/com/example/samplewear/Previews.kt".
  // Fall back to the old package-qualified path when source files were not wired into the task.
  private fun sourceFilePath(classInfo: ClassInfo, input: Input): String? {
    val packageQualified = packageQualifiedSourcePath(classInfo) ?: return null
    val source =
      input.sourceFiles.firstOrNull { file ->
        file.isFile && file.invariantSeparatorsPath.endsWith(packageQualified)
      }
    return source?.let { it.toRelativeStringSafe(input.projectDirectory) } ?: packageQualified
  }

  private fun File.toRelativeStringSafe(root: File): String {
    return try {
      relativeTo(root).path.replace(File.separatorChar, '/')
    } catch (_: IllegalArgumentException) {
      absolutePath.replace(File.separatorChar, '/')
    }
  }

  // Package-qualified source path, e.g. "com/example/samplewear/Previews.kt".
  // The bytecode SourceFile attribute is just the basename, which collides
  // when two files with the same name live in different packages within one
  // module. Prefixing with the package path makes the value unique and lets
  // the VSCode extension / CLI resolve a preview back to the exact file.
  private fun packageQualifiedSourcePath(classInfo: ClassInfo): String? {
    val simpleName = classInfo.sourceFile ?: return null
    val pkg = classInfo.packageName.orEmpty()
    return if (pkg.isEmpty()) simpleName else "${pkg.replace('.', '/')}/$simpleName"
  }

  // Disambiguates multi-preview expansions (e.g. @WearPreviewDevices → large_round
  // + small_round) when the inner @Preview has no explicit `name`. Without this
  // every variant collides on the same id / PNG path.
  //
  // Prefer `group` — Horologist's @WearPreview* annotations set a distinct, human
  // readable group per variant (e.g. "Fonts - Large"), so it captures exactly what
  // varies. Fall back to device + fontScale + uiMode only if neither name nor
  // group is present.
  private fun buildVariantSuffix(params: PreviewParams): String {
    val name = params.name
    if (!name.isNullOrBlank()) return "_${sanitizeForPath(name)}"
    val group = params.group
    if (!group.isNullOrBlank()) return "_${sanitizeForPath(group)}"
    val parts = mutableListOf<String>()
    params.device?.substringAfterLast(":")?.takeIf { it.isNotBlank() }?.let(parts::add)
    if (params.fontScale != 1.0f) parts.add("fs${params.fontScale}")
    if (params.uiMode != 0) parts.add("ui${params.uiMode}")
    return if (parts.isEmpty()) "" else "_" + parts.joinToString("_")
  }

  // Strip characters that would break file paths or IDs. Spaces are left alone
  // (they already appear in existing `_Red Box.png`-style outputs). Dots are
  // deliberately left intact here so the `id` stays lossless — two variants whose
  // names differ only by `.` vs `_` must keep distinct ids (the manifest dedups by
  // id). The render-stem derivation handles name-dots separately; see
  // `sanitiseSegments`.
  private fun sanitizeForPath(s: String): String = s.replace(Regex("""[/\\:*?"<>|]"""), "_")

  /**
   * Retarget a Wear module's device-less, wrap-content component previews from Studio's phone
   * default device to the Wear default. A frame-less `@Preview(showBackground = false)` declares no
   * `device`, so [extractPreviewParams] leaves it wrap-content at
   * [DeviceDimensions.DEFAULT_DENSITY] (2.625x — Studio's xxhdpi phone default), which renders a
   * Wear sticker on a ~400dp phone canvas (the fill-width components size like a phone, and the
   * export's dp→px scale is off by 2.625/2.0). On a Wear module ([Input.isWear]) such previews are
   * pinned to the Wear width + density ([DeviceDimensions.DEFAULT_WEAR], 227dp @ 2.0x). Previews
   * that pin their own `device` / `widthDp` / `heightDp` (e.g. the `id:wearos_*_round` breakpoints,
   * or fixed-size specimens) are left untouched, and the preview id — which never encodes a device
   * for a device-less preview — is unchanged, so `catalog.spec.json` references and delivery
   * filenames stay stable. A no-op off Wear.
   *
   * [pinWearCanvas] (from the `retargetWearPreviews` extension flag, [Input.retargetWearPreviews])
   * selects between two Wear behaviours for those device-less previews; it's a no-op off Wear:
   * - `true` (default): pin the full 227dp watch canvas + Wear density — the sticker behaviour
   *   above.
   * - `false`: leave `widthDp`/`heightDp` null so the preview stays wrap-content and the renderer
   *   crops each PNG to its intrinsic bounds, while still swapping in the Wear density (2.0x) so a
   *   Wear widget/tile asset exported at fixed size scales to watch-density px rather than the
   *   inherited 2.625x phone default (#2670).
   *
   * **Auto-detected Wear widgets always crop, regardless of [pinWearCanvas].** A glance-wear widget
   * preview — one whose `@PreviewParameter` provider comes from `androidx.glance.wear.*` (the
   * `Squircle`/`RectangularAllWidgetPreviewParams` providers that feed `WearWidgetParams`) — is
   * exported as a fixed-size drawable asset and must never occupy the watch-face canvas. Those are
   * cropped at Wear density even under the default `pinWearCanvas = true`, so no per-module config
   * is needed for the common widget case; the flag remains the override for the broader "crop every
   * device-less preview" case and for non-glance widget param types (#2670). This is per-preview,
   * so one module can mix fill-width catalog components (pinned) with widgets (cropped).
   */
  internal fun retargetWearStickers(
    isWear: Boolean,
    pinWearCanvas: Boolean = true,
    previews: List<PreviewInfo>,
  ): List<PreviewInfo> {
    if (!isWear) return previews
    val wear = DeviceDimensions.DEFAULT_WEAR
    return previews.map { info ->
      val p = info.params
      if (
        p.kind == PreviewKind.COMPOSE && p.device == null && p.widthDp == null && p.heightDp == null
      ) {
        // Glance-wear widgets crop even when the flag would otherwise pin the canvas — a widget
        // sticker on a 227dp watch face is never what you want.
        val pinCanvas = pinWearCanvas && !isWearWidgetPreview(p)
        if (pinCanvas) {
          // Pin the wear canvas (square 227dp) + density so fill-width components (Card) size to
          // the wear screen and dp→px matches the render. A fixed square surface — rather than
          // wrap-height — keeps the render and the exported figma-svg in one shared geometry, which
          // is what makes the layered export align to the render (the alternative, wrap-height,
          // drifts the vertical crop between the two and scores markedly worse).
          info.copy(
            params =
              p.copy(widthDp = wear.widthDp, heightDp = wear.heightDp, density = wear.density)
          )
        } else {
          // Opted out of the canvas pin (`retargetWearPreviews = false`): leave
          // `widthDp`/`heightDp`
          // null so the preview stays wrap-content and the renderer crops each PNG to its intrinsic
          // layout bounds — needed for Wear widget/tile assets exported at fixed size (#2670).
          // Still
          // apply the Wear density (2.0x) rather than the inherited phone default (2.625x), so the
          // cropped dp bounds scale to the correct watch-density px, not an oversized phone-scale
          // export.
          info.copy(params = p.copy(density = wear.density))
        }
      } else {
        info
      }
    }
  }

  /**
   * Package prefixes of `@PreviewParameter` providers that mark a preview as a **Wear widget** —
   * glance-wear's `SquircleAllWidgetPreviewParams` / `RectangularAllWidgetPreviewParams` and any
   * other provider under `androidx.glance.wear.*`, all of which feed `WearWidgetParams`. Matched by
   * FQN prefix so the alpha package layout (`androidx.glance.wear.tooling.preview.*`) is covered
   * without pinning an exact class. A widget so detected always crops to its intrinsic bounds
   * ([retargetWearStickers]) rather than occupying the watch-face canvas.
   */
  private val WEAR_WIDGET_PARAM_PROVIDER_PREFIXES = listOf("androidx.glance.wear.")

  /**
   * True when [params] is a glance-wear widget preview — a device-less `@PreviewParameter` preview
   * whose provider comes from [WEAR_WIDGET_PARAM_PROVIDER_PREFIXES]. Such widgets are exported as
   * fixed-size drawable assets and must crop to their bounds regardless of the
   * `retargetWearPreviews` flag (#2670).
   */
  private fun isWearWidgetPreview(params: PreviewParams): Boolean {
    val provider = params.previewParameterProviderClassName ?: return false
    return WEAR_WIDGET_PARAM_PROVIDER_PREFIXES.any { provider.startsWith(it) }
  }

  private fun extractPreviewParams(
    ann: AnnotationInfo,
    wrapperClassName: String?,
    previewParameter: Pair<String, Int>?,
  ): PreviewParams {
    // `@NotificationPreview` has no parameters, so the rest of this function — which
    // dereferences `device` / `widthDp` / `fontScale` / etc. from `ann.parameterValues` — would
    // throw `NoSuchElementException`. Return a minimal params object up-front.
    //
    // Pin `widthDp` to the sandbox width (400dp) rather than leaving it null: without it the
    // router falls back to its 320dp square default and the AOSP notification shade inflates to
    // its ~320dp intrinsic width, producing the cramped ~320×320 PNG from #1249. 400dp matches
    // the canvas the `@Preview` + `NotificationContent` gallery path renders at (its
    // `DEFAULT_NOTIFICATION_WIDTH_DP`), so FQN-discovered notifications share the wider shade
    // footprint. Height stays on the renderer default.
    if (ann.name == NOTIFICATION_PREVIEW_FQN) {
      return PreviewParams(
        kind = PreviewKind.NOTIFICATION,
        widthDp = DeviceDimensions.SANDBOX_WIDTH_DP,
      )
    }
    // Glance's own `androidx.glance.preview.Preview(widthDp, heightDp)`. The annotation's params
    // started life as `()` in 1.0.x, gained `widthDp` / `heightDp` in 1.1.0-rc01. Read both
    // optimistically; missing entries fall through to the renderer's default sandbox size.
    if (ann.name == GLANCE_APPWIDGET_PREVIEW_FQN) {
      val pv = ann.parameterValues
      val widthDp = (pv.getValue("widthDp") as? Int)?.takeIf { it > 0 }
      val heightDp = (pv.getValue("heightDp") as? Int)?.takeIf { it > 0 }
      return PreviewParams(
        kind = PreviewKind.GLANCE_APPWIDGET,
        widthDp = widthDp,
        heightDp = heightDp,
      )
    }
    // XR subspace previews carry no device / dimension annotation params — the layout comes from
    // the composed `Subspace`. Emit minimal params; the `:renderer-xr` task does the rest.
    if (ann.name == XR_SUBSPACE_PREVIEW_FQN) {
      return PreviewParams(kind = PreviewKind.XR_SUBSPACE)
    }
    val pv = ann.parameterValues
    val kind = if (ann.name == TILE_PREVIEW_FQN) PreviewKind.TILE else PreviewKind.COMPOSE
    val device = (pv.getValue("device") as? String)?.ifBlank { null }
    val rawWidth = (pv.getValue("widthDp") as? Int)?.takeIf { it > 0 }
    val rawHeight = (pv.getValue("heightDp") as? Int)?.takeIf { it > 0 }
    val showSystemUi = (pv.getValue("showSystemUi") as? Boolean) ?: false
    // AS-parity sizing: when the user picked a device or asked for the
    // system UI frame, resolve up-front so downstream consumers (renderers,
    // VS Code extension, CLI) see the effective widthDp/heightDp and the
    // device's density. When no frame was requested, keep the raw user
    // values — nulls on either axis signal "wrap to intrinsic" to the
    // renderers, matching how Android Studio's preview pane sizes
    // component previews.
    val effectiveWidth: Int?
    val effectiveHeight: Int?
    val effectiveDensity: Float?
    if (device != null || showSystemUi) {
      val dims = DeviceDimensions.resolve(device, rawWidth, rawHeight)
      effectiveWidth = dims.widthDp
      effectiveHeight = dims.heightDp
      effectiveDensity = dims.density
    } else {
      effectiveWidth = rawWidth
      effectiveHeight = rawHeight
      // Pin Android Studio's default preview density (xxhdpi-ish, 420dpi
      // → 2.625x). Without this the Robolectric renderer defaults to
      // mdpi (1.0x), which is fine at the PNG level but fuzzy in the VS
      // Code tile grid: tiles have a `max-width: 180px`, so a 100-dp
      // composable that produced a 100-px PNG under mdpi gets upscaled
      // and looks blurry next to device-based previews rendered at
      // their native densities. Pinning here keeps wrap-content
      // previews at the same pixel density as both the Desktop
      // renderer and Studio's own preview pane.
      effectiveDensity = DeviceDimensions.DEFAULT_DENSITY
    }
    return PreviewParams(
      name = (pv.getValue("name") as? String)?.ifBlank { null },
      device = device,
      widthDp = effectiveWidth,
      heightDp = effectiveHeight,
      density = effectiveDensity,
      fontScale = (pv.getValue("fontScale") as? Float)?.takeIf { it > 0 } ?: 1.0f,
      showSystemUi = showSystemUi,
      showBackground = (pv.getValue("showBackground") as? Boolean) ?: false,
      backgroundColor = (pv.getValue("backgroundColor") as? Long) ?: 0L,
      uiMode = (pv.getValue("uiMode") as? Int)?.takeIf { it > 0 } ?: 0,
      locale = (pv.getValue("locale") as? String)?.ifBlank { null },
      group = (pv.getValue("group") as? String)?.ifBlank { null },
      // @PreviewWrapper targets composables. Tile previews aren't composable,
      // so even if the annotation happened to be present on the function,
      // the wrapper's `Wrap(content)` would never wrap the tile View.
      wrapperClassName = if (kind == PreviewKind.TILE) null else wrapperClassName,
      // @PreviewParameter targets composables too. Tile preview functions
      // return `TilePreviewData`; the renderer reflects them directly and
      // has no code path for injecting a provider value.
      previewParameterProviderClassName =
        if (kind == PreviewKind.TILE) null else previewParameter?.first,
      previewParameterLimit = previewParameter?.second ?: Int.MAX_VALUE,
      kind = kind,
      // @ScrollingPreview is applied by `makePreview` via `.copy(scroll = …)` so
      // the timings fan-out and scroll spec live side-by-side in one place.
    )
  }
}
