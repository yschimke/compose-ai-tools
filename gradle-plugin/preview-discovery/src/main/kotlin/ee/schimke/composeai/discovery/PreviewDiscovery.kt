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
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/**
 * Pure-JVM library that scans compiled Kotlin classes for `@Preview` functions and produces a
 * [PreviewManifest] (`compose-previews/v1`). The Gradle plugin's `DiscoverPreviewsTask` is one
 * adapter; non-Gradle build systems (Bazel, Amper) call it directly.
 *
 * Logger-agnostic: diagnostics are returned as strings for each adapter to route. The caller
 * serializes [Outcome.Success.manifest] to `previews.json`.
 */
object PreviewDiscovery {

  /**
   * Longest readable prefix of a render stem, before its `-<digest>` and structural suffixes, so
   * the full filename stays well inside the 255-byte `NAME_MAX`. The digest keeps truncated names
   * unique.
   */
  internal const val MAX_READABLE_STEM: Int = 80

  /**
   * Hex chars of `sha256(preview.id)` appended to every render stem; 32 bits makes collisions among
   * same-named previews vanishingly unlikely.
   */
  internal const val RENDER_STEM_DIGEST_CHARS: Int = 8

  /** Digest width used by the tie backstop. Full sha256 hex — cannot collide for distinct ids. */
  internal const val FULL_DIGEST_CHARS: Int = 64

  /** Inputs the scan needs from the calling build system. All paths are absolute. */
  data class Input(
    /** Directories of compiled `.class` files belonging to the consumer module. */
    val classDirs: List<File>,
    /**
     * Dependency JARs to merge onto the scan classpath; the scanner filters them to a
     * preview-relevant subset.
     */
    val dependencyJars: List<File>,
    /**
     * Maven coordinate of each [dependencyJars] entry, keyed by absolute path (Gradle's
     * `ComponentIdentifier.displayName`).
     *
     * Needed because an AAR's transformed jar path
     * (`transforms/<hash>/transformed/<module>/jars/classes.jar`) loses the group, so path matching
     * dropped component libraries like `material3` from the scan. Entries without a coordinate fall
     * back to path matching.
     */
    val dependencyJarCoordinates: Map<String, String> = emptyMap(),
    /** Source files used to attach module-relative `sourceFile` paths to each [PreviewInfo]. */
    val sourceFiles: List<File>,
    /**
     * Source files of the compilation in [activeClassDirs]. [sourceFiles] may span every source set
     * for source mapping, but inactive ones must not trigger the empty-compile integrity guard.
     * `null` falls back to [sourceFiles]; an empty list is authoritative.
     */
    val activeSourceFiles: List<File>? = null,
    /**
     * Logical module name for [PreviewManifest.module]: the Gradle project path or Bazel target
     * label.
     */
    val moduleName: String,
    /** Build variant ("debug" / "release" / "desktop"). Surfaced via [PreviewManifest.variant]. */
    val variantName: String,
    /** Module root — `PreviewInfo.sourceFile` is rendered relative to this. */
    val projectDirectory: File,
    /** When `true` and zero previews are found, returns [Outcome.Failure] with diagnostics. */
    val failOnEmpty: Boolean,
    /**
     * Absolute processed-resource roots (e.g. `build/resources/main`) scanned for Lottie assets:
     * each Lottie-shaped `.json` or `.lottie` becomes a [PreviewKind.LOTTIE] preview. Empty skips
     * the scan.
     */
    val resourceDirs: List<File> = emptyList(),
    /**
     * Subdirectory (under `compose-previews`) for Lottie `renderOutput`s. Defaults to `"renders"`
     * on desktop; Android uses a disjoint dir so the JVM Lottie task and Robolectric task don't
     * share outputs, which would disable build caching.
     */
    val lottieRenderSubdir: String = "renders",
    /**
     * Subdirectory for [PreviewKind.SVG] `renderOutput`s; same rationale as [lottieRenderSubdir].
     */
    val svgRenderSubdir: String = "renders",
    /**
     * The module's own classes packaged as JARs (AGP's scoped `PROJECT` `CLASSES` artifact).
     * Method-walked like [classDirs] and not subject to the dependency filter. Needed because AGP 9
     * built-in Kotlin never writes the legacy `kotlin-classes/<variant>` directory (#1924).
     */
    val projectClassJars: List<File> = emptyList(),
    /**
     * Class directories from the active compilation. [classDirs] may include compatibility
     * fallbacks with stale classes, which must not satisfy the empty-compile check. Empty falls
     * back to [classDirs].
     */
    val activeClassDirs: List<File> = emptyList(),
    /**
     * Whether the backend can draw `@ColorCatalog` sheets (`false` on desktop, #2135). When
     * `false`, `CATALOG` captures are emitted `optional`, so every consumer reading
     * `Capture.optional` treats the missing PNG as expected.
     */
    val catalogRenderSupported: Boolean = true,
    /**
     * Whether this is a Wear OS module (manifest declares `android.hardware.type.watch`). When
     * `true`, device-less wrap-content previews are retargeted from the phone default
     * ([DeviceDimensions.DEFAULT]) to [DeviceDimensions.DEFAULT_WEAR]. Previews pinning their own
     * size are untouched.
     */
    val isWear: Boolean = false,
    /**
     * Whether the Wear retarget (see [isWear]) applies at all. `false` keeps device-less previews
     * wrap-content and cropped, for widget/tile previews exported as fixed-size assets (#2670).
     * From `retargetWearPreviews`.
     */
    val retargetWearPreviews: Boolean = true,
    /**
     * Extra component-library owners beyond the built-ins: packages ending in `.` or exact JVM
     * owner classes. From `componentLibraryPrefixes`; see
     * [PreviewTargetInference.isComponentLibraryOwner].
     */
    val componentLibraryPrefixes: List<String> = emptyList(),
    /**
     * The variant's merged `AndroidManifest.xml` (Android only). Its activities become
     * [PreviewManifest.activities] plus one [PreviewKind.ACTIVITY] preview each, and its launcher
     * is the default tour start. `null` skips app-level discovery.
     */
    val mergedManifest: File? = null,
    /**
     * Tour scripts (`compose-previews/tours/<name>.json`), each a [PreviewKind.APP_TOUR] preview.
     * Only honoured with [mergedManifest], since tours need the Android backend.
     */
    val tourSpecFiles: List<File> = emptyList(),
    /**
     * Whether the backend honours `@AnimatedPreview(format = Apng)`. The Gradle plugin passes
     * `true` for both backends (Android since daemon 3.13.0). `false` (default for non-Gradle
     * callers) records and names the output as GIF. See [resolveAnimationFormat].
     */
    val animatedPreviewApngSupported: Boolean = false,
  )

  /** Outcome of a [discover] call. */
  sealed class Outcome {
    /**
     * Discovery completed. [warnings] are per-method warnings for WARN-level logging;
     * [infoMessages] are the lifecycle summary lines.
     */
    data class Success(
      val manifest: PreviewManifest,
      val warnings: List<String>,
      val infoMessages: List<String>,
    ) : Outcome()

    /**
     * Hard failure: zero previews with `failOnEmpty=true` (an unreachable `@Preview` annotation is
     * only a soft warning on [Success]). [reason] is the exception message; [diagnostics] the
     * multi-line scan dump; [warnings] the per-method skip reasons, often the real cause of zero
     * previews.
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
      CMP_PREVIEW_FQN,
      TILE_PREVIEW_FQN,
      NOTIFICATION_PREVIEW_FQN,
      GLANCE_APPWIDGET_PREVIEW_FQN,
      XR_SUBSPACE_PREVIEW_FQN,
    )
  private val CONTAINER_FQNS =
    setOf(
      "androidx.compose.ui.tooling.preview.Preview\$Container",
      "androidx.compose.ui.tooling.preview.Preview.Container",
      // Tiles @Preview is @Repeatable, so stacked tile previews arrive via the synthesised
      // `Preview.Container`.
      "androidx.wear.tiles.tooling.preview.Preview\$Container",
      "androidx.wear.tiles.tooling.preview.Preview.Container",
      // CMP's @Preview is @Repeatable too, with its Container in its own package.
      "org.jetbrains.compose.ui.tooling.preview.Preview\$Container",
      "org.jetbrains.compose.ui.tooling.preview.Preview.Container",
    )
  // ui-tooling-preview 1.11.0+. FQN-matched, so apps without the class simply never see it.
  private const val PREVIEW_WRAPPER_FQN = "androidx.compose.ui.tooling.preview.PreviewWrapper"
  // Project-side companion to @PreviewWrapper that also targets ANNOTATION_CLASS, so a
  // multi-preview meta-annotation can declare the wrapper once (androidx's is FUNCTION-only).
  // Carries the provider FQN as a String.
  private const val PREVIEW_WRAPPER_CLASS_FQN = "ee.schimke.composeai.preview.PreviewWrapperClass"
  // Our own annotations are FQN-matched and never loaded, so projects without `preview-annotations`
  // are unaffected.
  private const val SCROLLING_PREVIEW_FQN = "ee.schimke.composeai.preview.ScrollingPreview"
  private const val ANIMATED_PREVIEW_FQN = "ee.schimke.composeai.preview.AnimatedPreview"
  private const val INTERACTION_PREVIEW_FQN = "ee.schimke.composeai.preview.InteractionPreview"
  // `InteractionGesture` entry names, matched off `AnnotationEnumValue` without loading the enum.
  private const val INTERACTION_GESTURE_TAP = "Tap"
  private const val INTERACTION_GESTURE_PRESS_AND_HOLD = "PressAndHold"
  private const val FOCUSED_PREVIEW_FQN = "ee.schimke.composeai.preview.FocusedPreview"
  private const val AMBIENT_PREVIEW_FQN = "ee.schimke.composeai.preview.AmbientPreview"
  private const val GLIMMER_ENVIRONMENT_PREVIEW_FQN =
    "ee.schimke.composeai.preview.GlimmerEnvironmentPreview"
  private const val GLIMMER_ENVIRONMENT_PREVIEW_CONTAINER_FQN =
    "ee.schimke.composeai.preview.GlimmerEnvironmentPreview.Container"
  // Per-edge dp margin the renderer adds outside the composable so shadows / focus rings aren't
  // cropped. See `CaptureGutter.kt`.
  private const val CAPTURE_GUTTER_FQN = "ee.schimke.composeai.preview.CaptureGutter"
  // Mirrors `MAX_CAPTURE_GUTTER_DP` in the annotation artifact (never loaded here).
  private const val MAX_CAPTURE_GUTTER_DP = 64
  // `@CaptureGutter`'s per-edge "take `all`" sentinel (`INHERIT_GUTTER`).
  private const val INHERIT_GUTTER = -1
  private const val SETTLED_PREVIEW_FQN = "ee.schimke.composeai.preview.SettledPreview"
  private const val GESTURE_HINT_PREVIEW_FQN = "ee.schimke.composeai.preview.GestureHintPreview"
  // Seeds Robolectric's grant set before `setContent` rather than wrapping the composition, because
  // permissions are read on the first composition. See `PermissionPreview.kt`.
  internal const val PERMISSION_PREVIEW_FQN = "ee.schimke.composeai.preview.PermissionPreview"
  // Marks a preview whose subject is a theme — never re-render it under a themeProvider override.
  private const val FIXED_THEME_FQN = "ee.schimke.composeai.preview.FixedTheme"
  // Lets visual-only specimens opt out of a11y auditing.
  private const val PREVIEW_HELPER_FQN = "ee.schimke.composeai.preview.PreviewHelper"
  private const val LAUNCHER_WIDGET_PREVIEW_FQN =
    "ee.schimke.composeai.preview.LauncherWidgetPreview"
  // Repeatable: one extra synthetic preview per variant with `previewOverride*` values seeded.
  // `.Container` is Kotlin's repeated-annotation holder.
  private const val OVERRIDE_VARIANT_FQN = "ee.schimke.composeai.preview.OverrideVariant"
  private const val OVERRIDE_VARIANT_CONTAINER_FQN =
    "ee.schimke.composeai.preview.OverrideVariant.Container"
  // Repeatable: each declares one dimension and discovery expands the cross product into seeded
  // variants. Unlike stacked `@OverrideVariant`s (which union), axes multiply.
  private const val PREVIEW_AXIS_FQN = "ee.schimke.composeai.preview.PreviewAxis"
  private const val PREVIEW_AXIS_CONTAINER_FQN =
    "ee.schimke.composeai.preview.PreviewAxis.Container"

  /**
   * Leaves for the meta-annotation walk: their own meta-annotations are only `@Retention` /
   * `@Target`, so descending into them only costs a scan.
   */
  /** Mirrors `PreviewAxis.MAX_CELLS_WARN` / `MAX_CELLS`; see that annotation's KDoc. */
  private const val PREVIEW_AXIS_MAX_CELLS_WARN = 64L
  private const val PREVIEW_AXIS_MAX_CELLS = 512L

  private val HOISTABLE_LEAF_FQNS =
    setOf(
      OVERRIDE_VARIANT_FQN,
      OVERRIDE_VARIANT_CONTAINER_FQN,
      PREVIEW_AXIS_FQN,
      PREVIEW_AXIS_CONTAINER_FQN,
    )
  // Two FQNs: CMP's `components-ui-tooling-preview` ships its own
  // `org.jetbrains.compose.ui.tooling.preview` classes rather than the androidx names. See
  // [CMP_PREVIEW_FQN].
  private val PREVIEW_PARAMETER_FQNS =
    setOf(
      "androidx.compose.ui.tooling.preview.PreviewParameter",
      "org.jetbrains.compose.ui.tooling.preview.PreviewParameter",
    )
  private const val COMPOSER_FQN = "androidx.compose.runtime.Composer"

  internal const val TILE_PREVIEW_FQN = "androidx.wear.tiles.tooling.preview.Preview"

  // Compose Multiplatform's own @Preview (from `compose.components.uiToolingPreview`), distinct
  // from the relocated artifact that ships the androidx FQN. Same shape as androidx's (BINARY,
  // @Repeatable, same attributes), so nothing downstream needs to know which it came from. Without
  // it CMP projects using this artifact discover zero previews.
  internal const val CMP_PREVIEW_FQN = "org.jetbrains.compose.ui.tooling.preview.Preview"

  // Notification previews: `(Context) -> Notification`. See `NotificationPreview.kt`.
  internal const val NOTIFICATION_PREVIEW_FQN = "ee.schimke.composeai.preview.NotificationPreview"

  // Glance's preview annotation (`glance-preview`). The function is a `@GlanceComposable` body
  // invoked from a synthetic `GlanceAppWidget.providePreview(...)`.
  internal const val GLANCE_APPWIDGET_PREVIEW_FQN = "androidx.glance.preview.Preview"

  // XR subspace previews: a `@Composable` wrapping `Subspace { … }`, rendered by a separate
  // `:renderer-xr` task to `scene.json` rather than a PNG.
  internal const val XR_SUBSPACE_PREVIEW_FQN = "ee.schimke.composeai.preview.XrSubspacePreview"

  // Emits one capture per whole-cell stop between source and target sizes. Stops are computed here
  // by `launcherWidgetResizeStops(...)`, a copy of the connector's `launcherWidgetStops(...)` since
  // the plugin can't depend on it.
  internal const val LAUNCHER_WIDGET_RESIZE_FQN =
    "ee.schimke.composeai.preview.LauncherWidgetResize"

  // On a `Color` property's backing field (BINARY, `@Target(FIELD)`), so unlike Showkase's
  // SOURCE-retained `@ShowkaseColor` it survives into bytecode.
  internal const val COLOR_CATALOG_FQN = "ee.schimke.composeai.preview.ColorCatalog"

  // Type-scale sibling of `@ColorCatalog`, on a `TextStyle` backing field.
  internal const val TYPOGRAPHY_CATALOG_FQN = "ee.schimke.composeai.preview.TypographyCatalog"

  // Shape sibling, on a `Shape` or `Shapes` backing field.
  internal const val SHAPE_CATALOG_FQN = "ee.schimke.composeai.preview.ShapeCatalog"

  // On a `PreviewWrapperProvider` class (`@Target(CLASS)`) rather than a field.
  internal const val THEME_CATALOG_FQN = "ee.schimke.composeai.preview.ThemeCatalog"

  // Wear sibling; a separate annotation because the specimen reads a different `MaterialTheme`.
  internal const val WEAR_THEME_CATALOG_FQN = "ee.schimke.composeai.preview.WearThemeCatalog"

  // Design-catalog annotations: `@CatalogComponent` / `@CatalogVariant` on the `@Preview` function,
  // `@CatalogGroup` on the file (its `…Kt` facade). See [extractCatalogEntry].
  internal const val CATALOG_COMPONENT_FQN = "ee.schimke.composeai.preview.CatalogComponent"
  internal const val CATALOG_VARIANT_FQN = "ee.schimke.composeai.preview.CatalogVariant"
  internal const val CATALOG_GROUP_FQN = "ee.schimke.composeai.preview.CatalogGroup"

  // Per-component UI builder policy on the `@Preview` function; independent of the catalog
  // inventory. See [extractBuilderEntry].
  internal const val BUILDER_COMPONENT_FQN = "ee.schimke.composeai.preview.BuilderComponent"

  // Fallback group for a `@CatalogComponent` with no `group` argument and no file `@CatalogGroup`.
  private const val DEFAULT_CATALOG_COMPONENT_GROUP = "Components"

  // Whole-object catalog field types: annotating one catalogs the entire scheme / type scale /
  // shape scale. Dispatched by declared type, since a single `Color` erases to `long`. See
  // [catalogTokenKindFor].
  private const val COLOR_SCHEME_TYPE = "androidx.compose.material3.ColorScheme"
  private const val TYPOGRAPHY_TYPE = "androidx.compose.material3.Typography"
  private const val SHAPES_TYPE = "androidx.compose.material3.Shapes"
  private const val REMOTE_COLOR_SCHEME_TYPE =
    "androidx.wear.compose.remote.material3.RemoteColorScheme"
  private const val REMOTE_TYPOGRAPHY_TYPE =
    "androidx.wear.compose.remote.material3.RemoteTypography"
  private const val REMOTE_SHAPES_TYPE = "androidx.wear.compose.remote.material3.RemoteShapes"

  // Caps the failOnEmpty diagnostic samples so the log stays readable.
  private const val DIAG_JAR_SAMPLE = 15
  private const val DIAG_ANNOTATION_SAMPLE = 20

  // Roborazzi's per-preview clock control: fans out one entry per
  // `ManualClockOptions.advanceTimeMillis` value (suffix `_TIME_<ms>ms`). Read by descriptor, never
  // loaded.
  private const val ROBO_COMPOSE_PREVIEW_OPTIONS_FQN =
    "com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions"

  /**
   * Absolute and canonical forms of [file], for matching a class's classpath element against the
   * project's own outputs. ClassGraph reports canonical (symlink-resolved) paths while Gradle
   * passes them verbatim, so on symlinked build trees a plain comparison matched nothing and
   * discovery found 0 previews (#1924). `canonicalPath` can throw, so it's best-effort.
   */
  private fun pathMatchKeys(file: File): Set<String> = buildSet {
    add(file.absolutePath)
    runCatching { add(file.canonicalPath) }
  }

  /**
   * Tokens marking a dependency whose classes the scan needs (Compose, tooling/preview annotations,
   * multi-preview annotation artifacts). Everything else stays off the classpath, keeping the scan
   * proportional to the previews.
   */
  private val PREVIEW_RELEVANT_TOKENS =
    listOf("preview", "tooling", "compose", "remote-material", "glimmer", "annotation")

  /** Whether [subject] — a coordinate, or a path standing in for one — names such a dependency. */
  internal fun isPreviewRelevant(subject: String): Boolean {
    val lowered = subject.lowercase()
    return PREVIEW_RELEVANT_TOKENS.any { it in lowered }
  }

  fun discover(input: Input): Outcome {
    val warnings = mutableListOf<String>()
    val infoMessages = mutableListOf<String>()

    val existingClassDirs = input.classDirs.filter { it.exists() && it.isDirectory }
    // The module's own classes as jars: walked as project classes and not filtered (AGP 9 built-in
    // Kotlin, #1924).
    val existingProjectJars =
      input.projectClassJars.filter {
        it.exists() && it.isFile && it.name.lowercase().endsWith(".jar")
      }
    // Prefer the Maven coordinate, else match the whole path (not just the file name — transformed
    // AARs are all `classes.jar`, #162). See [Input.dependencyJarCoordinates].
    val filteredDependencyJars =
      input.dependencyJars.filter { file ->
        file.exists() &&
          file.name.lowercase().endsWith(".jar") &&
          isPreviewRelevant(input.dependencyJarCoordinates[file.absolutePath] ?: file.absolutePath)
      }
    // Project jars before dependency jars, so a class present in both is attributed to the project
    // and walked.
    val classpath = existingClassDirs + existingProjectJars + filteredDependencyJars

    // A restored cache entry can be empty (FROM-CACHE, no classes), and asset discovery could then
    // hide the broken compilation (#3600). Only sources that actually declare @Preview establish
    // the invariant, avoiding false positives for source-only constructs and asset-only modules;
    // test sources are excluded. Only the active compilation output is checked, since fallback dirs
    // may hold stale classes.
    val integritySourceFiles = input.activeSourceFiles ?: input.sourceFiles
    val previewSourceFiles = integritySourceFiles.filter {
      it.isFile && !it.isTestSourceSetFile() && it.declaresPreviewAnnotation()
    }
    val integrityClassDirs = input.activeClassDirs.ifEmpty { input.classDirs }
    val classDirCounts = integrityClassDirs.associateWith(::classFileCount)
    val projectJarCounts = input.projectClassJars.associateWith(::classFileCount)
    val declaredProjectOutputs =
      integrityClassDirs.isNotEmpty() || input.projectClassJars.isNotEmpty()
    val projectClassFileCount = classDirCounts.values.sum() + projectJarCounts.values.sum()
    val emptyCompiledOutputs =
      previewSourceFiles.isNotEmpty() && declaredProjectOutputs && projectClassFileCount == 0
    if (emptyCompiledOutputs) {
      warnings.add(
        buildString {
          append(
            "composePreview: module '${input.moduleName}' has ${previewSourceFiles.size} " +
              "source file(s) declaring @Preview "
          )
          append("but its active class outputs contain 0 .class files. ")
          append("A compile task may have restored an empty build-cache entry; rerun with ")
          append("--no-build-cache --rerun-tasks. Resolved project class outputs:")
          classDirCounts.forEach { (file, count) ->
            append("\n  classDir: $file (exists=${file.exists()}, classFiles=$count)")
          }
          projectJarCounts.forEach { (file, count) ->
            append("\n  projectClassJar: $file (exists=${file.exists()}, classFiles=$count)")
          }
        }
      )
    }

    val previews = mutableListOf<PreviewInfo>()
    // Diagnostics: whether ClassGraph saw any classes, and which annotation FQNs — separates
    // "classpath is wrong" from "FQN doesn't match".
    var scanClassCount = 0
    var scanMethodsWithAnnotations = 0
    val annotationFqnCounts = LinkedHashMap<String, Int>()
    // Known @Preview FQNs reachable on the scan classpath. Empty means multi-preview annotations
    // can't resolve, almost always a misconfigured dep-jar classpath.
    var reachablePreviewFqns: List<String> = emptyList()

    // Catalog tokens collected during the scan, aggregated into [PreviewKind.CATALOG] sheets
    // afterwards.
    val rawColorCatalogTokens = mutableListOf<RawCatalogToken>()
    val rawTypographyCatalogTokens = mutableListOf<RawCatalogToken>()
    val rawShapeCatalogTokens = mutableListOf<RawCatalogToken>()
    // `@ThemeCatalog`-annotated `PreviewWrapperProvider` classes → one theme catalog sheet each.
    val rawThemeCatalogs = mutableListOf<RawThemeCatalog>()
    // Methods that produced previews, for [PreviewThemeShadowing] after the walk.
    val previewMethods = mutableListOf<Pair<ClassInfo, MethodInfo>>()

    if (classpath.isNotEmpty()) {
      ClassGraph()
        .enableMethodInfo()
        // Field info for catalog annotations on backing fields; `ignoreFieldVisibility()` because a
        // top-level `val`'s backing field is private static.
        .enableFieldInfo()
        .ignoreFieldVisibility()
        .enableAnnotationInfo()
        .ignoreMethodVisibility()
        .overrideClasspath(classpath.map { it.absolutePath })
        .ignoreParentClassLoaders()
        .scan()
        .use { scanResult ->
          reachablePreviewFqns = PREVIEW_FQNS.filter { scanResult.getClassInfo(it) != null }
          // FQNs of classes from the project's own outputs, never dependency JARs. Dependency
          // classes stay on the classpath for multi-preview resolution but aren't method-walked
          // (#1039 / #1924).
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
          // File-level `@CatalogGroup` defaults keyed by source path, so member-class previews
          // (whose `classInfo` isn't the `…Kt` facade) still get their file's group. Built up-front
          // because a member class may be walked before its facade.
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
            // Method-walk only project classes; library methods produced spurious "skipping
            // @Preview" warnings (#1039).
            if (classInfo.name !in projectClassFqns) continue
            scanClassCount++
            for (method in classInfo.methodInfo) {
              val annotations = method.annotationInfo ?: continue
              if (annotations.isNotEmpty()) scanMethodsWithAnnotations++
              for (ann in annotations) {
                annotationFqnCounts.merge(ann.name, 1, Int::plus)
              }
              val previewCountBefore = previews.size
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
              // Record methods that yielded previews so theme shadowing needn't re-derive "is this
              // a preview?".
              if (previews.size > previewCountBefore) {
                previewMethods += classInfo to method
              }
            }
            // Catalog design tokens on backing fields: collect coordinates and display metadata;
            // values are reflected at render time. Kind is dispatched by declared type
            // ([catalogTokenKindFor]).
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
            // `@ThemeCatalog` / `@WearThemeCatalog` on a `PreviewWrapperProvider` → a theme sheet;
            // the renderer invokes its `Wrap`. A provider carrying both is recorded once per
            // platform.
            for ((fqn, wear) in
              listOf(THEME_CATALOG_FQN to false, WEAR_THEME_CATALOG_FQN to true)) {
              classInfo.getAnnotationInfo(fqn)?.let { ann ->
                rawThemeCatalogs +=
                  RawThemeCatalog(
                    className = classInfo.name,
                    name = annStringOrDefault(ann, "name", classInfo.simpleName),
                    group = annStringOrDefault(ann, "group", defaultCatalogGroup(classInfo.name)),
                    wear = wear,
                  )
              }
            }
          }

          // A preview installing its own theme shadows the module's theme providers. Only reported
          // when the module declares themes; otherwise every app preview would be flagged.
          if (rawThemeCatalogs.isNotEmpty()) {
            PreviewThemeShadowing.warningOrNull(
                findings =
                  PreviewThemeShadowing.detect(previewMethods, scanResult, projectClassFqns),
                themeCount = rawThemeCatalogs.size,
              )
              ?.let { warnings.add(it) }
          }
        }
    }

    // The id encodes the name + variant suffix, so dedup by id alone.
    val deduped = previews.distinctBy { it.id }

    // Normalize each `renderOutput` to a shell-safe filename with the module-wide package prefix
    // stripped. `PreviewInfo.id` is untouched. Asset previews are appended afterwards, already
    // shell-safe.
    val normalized =
      retargetGlimmerStickers(
        isGlimmerModule(input),
        retargetWearStickers(
          input.isWear,
          pinWearCanvas = input.retargetWearPreviews,
          normalizeRenderOutputs(deduped),
        ),
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

    // App-level discovery (Android only, gated on a merged manifest): activities and tour specs.
    // See [AppTourDiscovery].
    val manifestActivities =
      input.mergedManifest?.let { AppTourDiscovery.parseManifestActivities(it) } ?: emptyList()
    val appPreviews =
      if (input.mergedManifest == null) {
        emptyList()
      } else {
        AppTourDiscovery.buildActivityPreviews(manifestActivities, input.isWear) +
          AppTourDiscovery.buildTourPreviews(
            input.tourSpecFiles,
            launcherActivity = manifestActivities.firstOrNull { it.launcher },
            isWear = input.isWear,
            warnings = warnings,
          )
      }

    val allPreviews = enforceOutputUniqueness(normalized + appPreviews)

    // Empty on the standalone Gradle path: a11y reports are written by the daemon, which stamps the
    // pointer at runtime.
    val manifest =
      PreviewManifest(
        module = input.moduleName,
        variant = input.variantName,
        previews = allPreviews,
        dataExtensionReports = emptyMap(),
        activities = manifestActivities,
      )

    infoMessages.add("Discovered ${allPreviews.size} preview(s) in module '${input.moduleName}':")
    for (preview in allPreviews) {
      infoMessages.add("  ${preview.className}.${preview.functionName}${describeVariant(preview)}")
    }

    // Hard-fail only with `failOnEmpty=true`; zero previews in one module is normal. A missing
    // `@Preview` annotation jar is reported as a soft warning below.
    val previewAnnotationsMissing = scanClassCount > 0 && reachablePreviewFqns.isEmpty()
    val codePreviewCount = allPreviews.count { it.params.kind !in ASSET_PREVIEW_KINDS }
    // Source declares @Preview but compiled outputs are empty: a broken/cancelled compilation, not
    // "zero previews". Writing an assets-only manifest would hide it (#4364). Asset-only modules
    // stay valid.
    if (emptyCompiledOutputs || (normalized.isEmpty() && input.failOnEmpty)) {
      val failureSummary =
        if (emptyCompiledOutputs) {
          "empty compiled outputs ($codePreviewCount code previews; ${allPreviews.size} total)"
        } else {
          "0 code previews discovered; ${allPreviews.size} total including assets"
        }
      val diagnostics =
        buildEmptyDiagnostics(
          header = "composePreview: discovery failure diagnostics ($failureSummary):",
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
          if (emptyCompiledOutputs) {
            "compiled outputs are empty; rerun with --no-build-cache --rerun-tasks"
          } else {
            "with failOnEmpty=true"
          }
        }
      return Outcome.Failure(
        reason =
          if (emptyCompiledOutputs) {
            "composePreview: $failureSummary in module '${input.moduleName}' — $reason. " +
              "See diagnostics above."
          } else {
            "composePreview: discovered 0 previews in module '${input.moduleName}' " +
              "($failureSummary) — $reason. See diagnostics above."
          },
        diagnostics = diagnostics,
        warnings = warnings.toList(),
      )
    }

    // Soft warning when zero previews coincide with the @Preview annotation jar missing from the
    // scan classpath (multi-preview annotations can't fan out), or with sources that declare
    // @Preview — something between source and scan lost them, and otherwise the first symptom is an
    // unrelated bundle failure later. Doesn't fail the build; `failOnEmpty=true` does.
    val sourcePreviewsVanished = normalized.isEmpty() && previewSourceFiles.isNotEmpty()
    if (normalized.isEmpty() && (previewAnnotationsMissing || sourcePreviewsVanished)) {
      warnings.add(
        if (previewAnnotationsMissing) {
          "composePreview: discovered 0 previews in module '${input.moduleName}' — " +
            "the @Preview annotation class is not on the ClassGraph classpath " +
            "(dependency-jar filter dropped every jar carrying it). " +
            "Set composePreview.failOnEmpty=true to make this a hard error."
        } else {
          "composePreview: discovered 0 previews in module '${input.moduleName}', but " +
            "${previewSourceFiles.size} of its source file(s) declare @Preview " +
            "(${previewSourceFiles.joinToString(", ") { it.name }}) — the compiled classes the " +
            "scan reached do not carry them. Diagnostics below; set " +
            "composePreview.failOnEmpty=true to make this a hard error."
        }
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
   * Turns each Lottie asset in [Input.resourceDirs] into a [PreviewKind.LOTTIE] preview: a `.json`
   * with the `v` + `layers` marker keys, or any `.lottie` archive. Unreadable / non-Lottie files
   * are skipped; output is deduped by id and sorted by path.
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
          // Filename-safe id: it lands in zip entries and render filenames. The `lottie__` prefix
          // avoids colliding with class-derived ids.
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
                  // Animated companion as APNG (full alpha; GIF's 1-bit alpha makes edges churn).
                  // `optional` so a missing companion never trips the required-render gate.
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
   * Turns each `.svg` (with an `<svg` root) in [Input.resourceDirs] into a [PreviewKind.SVG]
   * preview, seeding the canvas from its declared size. Static only (`loadSvgPainter` doesn't
   * replay SMIL/CSS animation), so one required still. Unreadable / non-SVG files are skipped;
   * output is deduped and sorted.
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
          // Filename-safe id, as for Lottie; the `svg__` prefix avoids collisions.
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
   * [file]'s intrinsic dimensions, or `null` if it has no `<svg` root. Prefers explicit
   * `width`/`height`, else the `viewBox`. Rounded to whole pixels; a null axis falls back to the
   * renderer default.
   */
  private fun svgDimensionsOrNull(file: File): SvgDims? {
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    // Cheapest reliable SVG fingerprint: a `<svg` element tag. Guards against a mis-named file.
    val svgTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE).find(text) ?: return null
    val attrs = svgTag.value
    fun lengthAttr(name: String): Int? {
      // Require a real attribute boundary: `\b` would match `stroke-width` and size the canvas off
      // the stroke.
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
   * A field typed as a whole M3 theme object catalogs the entire object; anything else is the
   * [single] token kind. Reliable because a single `Color` erases to `long`.
   */
  private fun catalogTokenKindFor(
    field: io.github.classgraph.FieldInfo,
    single: CatalogTokenKind,
  ): CatalogTokenKind {
    val type = runCatching { field.typeSignatureOrTypeDescriptor.toString() }.getOrNull()
    return when (type) {
      COLOR_SCHEME_TYPE,
      REMOTE_COLOR_SCHEME_TYPE -> CatalogTokenKind.COLOR_SCHEME
      TYPOGRAPHY_TYPE,
      REMOTE_TYPOGRAPHY_TYPE -> CatalogTokenKind.TYPOGRAPHY
      SHAPES_TYPE,
      REMOTE_SHAPES_TYPE -> CatalogTokenKind.SHAPES
      else -> single
    }
  }

  /**
   * Raw `@ThemeCatalog` / `@WearThemeCatalog` hit: the annotated `PreviewWrapperProvider` class +
   * its display metadata, plus which platform's specimen to render it with.
   */
  private data class RawThemeCatalog(
    val className: String,
    val name: String,
    val group: String,
    /** True for `@WearThemeCatalog` — selects the Wear specimen and the `wearthemecatalog__` id. */
    val wear: Boolean = false,
  )

  /** Builds a [RawCatalogToken] with Showkase-style name/group defaults. */
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
   * A `String` annotation parameter, or [fallback] when absent or blank (Showkase-style
   * defaulting).
   */
  private fun annStringOrDefault(ann: AnnotationInfo, param: String, fallback: String): String {
    val raw = runCatching { ann.parameterValues.getValue(param) as? String }.getOrNull()
    return raw?.takeIf { it.isNotBlank() } ?: fallback
  }

  /** Enclosing class simple name, minus a file class's `Kt` suffix. */
  private fun defaultCatalogGroup(className: String): String {
    val simple = className.substringAfterLast('.')
    return simple.removeSuffix("Kt").ifBlank { simple }
  }

  /**
   * A file-level `@CatalogGroup` default, resolved per source file so member-function previews get
   * it too.
   */
  private data class CatalogGroupDefault(val name: String?, val section: String?)

  /**
   * Catalog identity from `@CatalogComponent` / `@CatalogVariant`, or `null`. [fileGroup] supplies
   * the group/section default. `@CatalogVariant` wins if both are present. Component id defaults to
   * the function name; group to the argument, else the file group, else `Components`.
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
          kitAxis = annStringOrNull(variant, "kitAxis"),
          kitValue = annStringOrNull(variant, "kitValue"),
          // A variant is compared in its own right, so it keeps its own parallel and reference.
          reference = annStringOrNull(variant, "reference"),
          referenceSet = annStringOrNull(variant, "referenceSet"),
          noReference = annStringOrNull(variant, "noReference"),
          referenceContentsOnly = annBoolean(variant, "referenceContentsOnly", default = true),
          parallel = annStringOrNull(variant, "parallel"),
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
      referenceSet = annStringOrNull(component, "referenceSet"),
      noReference = annStringOrNull(component, "noReference"),
      referenceContentsOnly = annBoolean(component, "referenceContentsOnly", default = true),
      parallel = annStringOrNull(component, "parallel"),
      kitAxis = annStringOrNull(component, "kitAxis"),
      motionPreview = annStringOrNull(component, "motionPreview"),
      perBreakpoint = annBoolean(component, "perBreakpoint"),
      breakpointKit = annStringArray(component, "breakpointKit"),
      // Read verbatim like `breakpointKit`: the export is the only parser. Older annotations
      // without the attribute yield an empty list.
      related = annStringArray(component, "related"),
    )
  }

  /**
   * UI-builder policy from `@BuilderComponent`, or `null`.
   *
   * Nothing is defaulted: a blank argument records `null` so the generator can report what the
   * catalog didn't say. An all-default annotation is still recorded, since writing it means someone
   * considered the policy.
   */
  private fun extractBuilderEntry(annotations: List<AnnotationInfo>): BuilderPolicy? {
    val builder = annotations.firstOrNull { it.name == BUILDER_COMPONENT_FQN } ?: return null
    val malformed = mutableListOf<String>()
    return BuilderPolicy.Builder()
      .also { b ->
        b.id = annStringOrNull(builder, "id")
        b.component = annStringOrNull(builder, "component")
        b.group = annStringOrNull(builder, "group")
        b.displayName = annStringOrNull(builder, "displayName")
        b.canvas = annStringOrNull(builder, "canvas")
        b.stateCallbacks = builderPairs(builder, "stateCallbacks", malformed)
        b.starter = builderPairs(builder, "starter", malformed)
        b.slots = builderPairs(builder, "slots", malformed)
        b.traits = annStringArray(builder, "traits").map { it.trim() }.filter { it.isNotEmpty() }
        b.variantProperty = annStringOrNull(builder, "variantProperty")
        b.variants = builderPairs(builder, "variants", malformed)
        b.nativeOnly = annBoolean(builder, "nativeOnly")
        b.exclude = annStringOrNull(builder, "exclude")
        b.malformed = malformed
      }
      .build()
  }

  /**
   * A `key=value` array parameter as [BuilderPair]s, split on the first `=`. Malformed entries are
   * dropped into [into] verbatim so the generator can report them rather than fail the build.
   */
  private fun builderPairs(
    ann: AnnotationInfo,
    param: String,
    into: MutableList<String>,
  ): List<BuilderPair> =
    annStringArray(ann, param).mapNotNull { entry ->
      val separator = entry.indexOf('=')
      val key = if (separator <= 0) "" else entry.substring(0, separator).trim()
      if (key.isEmpty()) {
        into += "$param: $entry"
        return@mapNotNull null
      }
      BuilderPair.Builder(key = key, value = entry.substring(separator + 1).trim()).build()
    }

  /** Reads a `String` annotation parameter, returning `null` when absent or blank. */
  private fun annStringOrNull(ann: AnnotationInfo, param: String): String? {
    val raw = runCatching { ann.parameterValues.getValue(param) as? String }.getOrNull()
    return raw?.takeIf { it.isNotBlank() }
  }

  /** Reads a `Boolean` annotation parameter, using [default] when absent or not a boolean. */
  private fun annBoolean(ann: AnnotationInfo, param: String, default: Boolean = false): Boolean =
    runCatching { ann.parameterValues.getValue(param) as? Boolean }.getOrNull() ?: default

  /** Reads a `String[]` annotation parameter (ClassGraph yields an `Object[]`) as a list. */
  private fun annStringArray(ann: AnnotationInfo, param: String): List<String> {
    val raw = runCatching { ann.parameterValues.getValue(param) }.getOrNull() ?: return emptyList()
    return stringArrayValue(raw)
  }

  /**
   * Strings of an already-read array parameter value; shared with the `@OverrideVariant` walk so
   * both treat ClassGraph's empty-array shape the same.
   */
  private fun stringArrayValue(raw: Any?): List<String> =
    when (raw) {
      is Array<*> -> raw.filterIsInstance<String>()
      is Iterable<*> -> raw.filterIsInstance<String>()
      else -> emptyList()
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
   * Aggregates catalog tokens into [PreviewKind.CATALOG] sheets: one per `group`, plus an "All
   * <noun>" sheet when there are several groups. [idPrefix] namespaces the filename; [noun] labels
   * the sheet. Appended after [normalizeRenderOutputs] with already shell-safe outputs.
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
          device = CATALOG_SHEET_DEVICE,
          widthDp = CATALOG_SHEET.widthDp,
          heightDp = CATALOG_SHEET.heightDp,
          density = CATALOG_SHEET.density,
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
      // `optional` exactly when the backend can't render catalog sheets (desktop, #2135), so every
      // consumer of `Capture.optional` treats the absent PNG as expected.
      captures = listOf(Capture(renderOutput = "renders/$id.png", optional = !renderSupported)),
    )

  /**
   * One [PreviewKind.THEME_CATALOG] sheet per `@ThemeCatalog` provider, keyed
   * `themecatalog__<name>`; a shared display name falls back to the provider FQN so outputs don't
   * clobber. The renderer composes the provider's `Wrap` around a canned specimen. `optional` on
   * desktop, like the token catalogs.
   */
  /**
   * Canvas for synthetic token/theme sheets: 900x760dp at density 1, so the PNG is 900x760px. The
   * 400x800dp sandbox couldn't fit a theme sheet; [CatalogSpecimenSheet] packs rows into landscape
   * blocks (colour columns, full-width type scale, a single shape row). Density 1 because the sheet
   * is a document, not a device capture.
   */
  internal const val CATALOG_SHEET_DEVICE: String = "spec:width=900dp,height=760dp,dpi=160"

  /** [CATALOG_SHEET_DEVICE] resolved once — 900x760dp at density 1. */
  internal val CATALOG_SHEET: DeviceDimensions.DeviceSpec =
    DeviceDimensions.resolve(CATALOG_SHEET_DEVICE, null, null)

  private fun buildThemeCatalogPreviews(
    themes: List<RawThemeCatalog>,
    renderSupported: Boolean,
  ): List<PreviewInfo> {
    // Platform-scoped prefix so Wear and mobile themes with the same name get distinct ids.
    fun baseId(t: RawThemeCatalog) =
      (if (t.wear) "wearthemecatalog__" else "themecatalog__") +
        t.name.replace(SANITIZE_RENDER_STEM, "_")
    val baseCounts = themes.groupingBy { baseId(it) }.eachCount()
    return themes.map { theme ->
      val base = baseId(theme)
      // Use the FQN only to disambiguate a shared name.
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
            // Bare theme name: the renderer keys the per-theme token sidecar by it.
            name = theme.name,
            group = theme.group.ifEmpty { null },
            kind = if (theme.wear) PreviewKind.WEAR_THEME_CATALOG else PreviewKind.THEME_CATALOG,
            wrapperClassName = theme.className,
            // Resolved to concrete dp + density here: `device` alone is only honoured on the
            // `@Preview` annotation path.
            device = CATALOG_SHEET_DEVICE,
            widthDp = CATALOG_SHEET.widthDp,
            heightDp = CATALOG_SHEET.heightDp,
            density = CATALOG_SHEET.density,
          ),
        captures = listOf(Capture(renderOutput = "renders/$id.png", optional = !renderSupported)),
        // A theme sheet's subject is its own theme, so it's fixed by construction.
        fixedTheme = true,
      )
    }
  }

  /** [file]'s canvas dimensions if it's a Lottie document (has `v` + `layers`), else `null`. */
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
    // Project-own class jars, listed separately because they are method-walked (#1924).
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
        // Most common #162 failure: preview annotations in transformed AARs named just
        // `classes.jar`.
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
      // @Preview is on the classpath and on some method, yet nothing was emitted: a real bug, so
      //   make it loud.
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

  private val ASSET_PREVIEW_KINDS = setOf(PreviewKind.LOTTIE, PreviewKind.SVG)

  // Source-level signal for the empty-compile guard. Anchored at line start to skip comment
  // examples; qualified prefixes allowed. Supported annotations conventionally end in
  // Preview/Previews, and a module-local multi-preview annotation itself contains @Preview.
  private val PREVIEW_SOURCE_ANNOTATION =
    Regex(
      """(?m)^[\t ]*@(?!file:)(?:[A-Za-z_][A-Za-z0-9_.]*\.)?(?!(?:PreviewParameter|PreviewParameterProvider)\b)(?:[A-Za-z_][A-Za-z0-9_]*)?Preview[A-Za-z0-9_]*(?=[\t (\r\n])"""
    )

  private fun File.declaresPreviewAnnotation(): Boolean = runCatching {
    PREVIEW_SOURCE_ANNOTATION.containsMatchIn(readText().kotlinCodeOnly())
  }
    .getOrDefault(false)

  /**
   * Blanks comments and string literals (keeping line breaks) so the integrity guard sees only
   * code. Kotlin block comments nest, so a regex isn't enough.
   */
  private fun String.kotlinCodeOnly(): String {
    val out = StringBuilder(length)
    var i = 0
    var blockDepth = 0
    var lineComment = false
    var quote: Char? = null
    var tripleQuoted = false
    var escaped = false
    fun blank(c: Char) {
      out.append(if (c == '\n' || c == '\r') c else ' ')
    }
    while (i < length) {
      val c = this[i]
      val next = getOrNull(i + 1)
      if (lineComment) {
        blank(c)
        if (c == '\n' || c == '\r') lineComment = false
        i++
        continue
      }
      if (blockDepth > 0) {
        when {
          c == '/' && next == '*' -> {
            blank(c)
            blank(next)
            blockDepth++
            i += 2
          }
          c == '*' && next == '/' -> {
            blank(c)
            blank(next)
            blockDepth--
            i += 2
          }
          else -> {
            blank(c)
            i++
          }
        }
        continue
      }
      if (quote != null) {
        if (tripleQuoted && startsWith("\"\"\"", i)) {
          repeat(3) { blank(this[i + it]) }
          i += 3
          quote = null
          tripleQuoted = false
        } else {
          blank(c)
          if (!tripleQuoted) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true else if (c == quote) quote = null
          }
          i++
        }
        continue
      }
      when {
        c == '/' && next == '/' -> {
          blank(c)
          blank(next)
          lineComment = true
          i += 2
        }
        c == '/' && next == '*' -> {
          blank(c)
          blank(next)
          blockDepth = 1
          i += 2
        }
        startsWith("\"\"\"", i) -> {
          repeat(3) { blank(this[i + it]) }
          quote = '\"'
          tripleQuoted = true
          i += 3
        }
        c == '\"' || c == '\'' -> {
          blank(c)
          quote = c
          escaped = false
          i++
        }
        else -> {
          out.append(c)
          i++
        }
      }
    }
    return out.toString()
  }

  private fun File.isTestSourceSetFile(): Boolean {
    val marker = "/src/"
    val normalized = absolutePath.replace(File.separatorChar, '/')
    val sourceSet =
      normalized.substringAfter(marker, missingDelimiterValue = "").substringBefore('/')
    return sourceSet.contains("test", ignoreCase = true)
  }

  private fun classFileCount(file: File): Int =
    when {
      file.isDirectory -> file.walkTopDown().count { it.isFile && it.extension == "class" }
      file.isFile && file.extension.equals("jar", ignoreCase = true) ->
        runCatching {
            ZipFile(file).use { zip ->
              zip.entries().asSequence().count { it.name.endsWith(".class") }
            }
          }
          .getOrDefault(0)
      else -> 0
    }

  /**
   * Rewrites each `renderOutput` (and `dataProduct.output`) to a shell-safe `<readable>-<digest>`:
   * 1. `<readable>` — the id's last dotted segment with non-alphanumeric runs collapsed to `_`,
   *    capped at [MAX_READABLE_STEM].
   * 2. `-<digest>` — [RENDER_STEM_DIGEST_CHARS] hex of `sha256(preview.id)`. `-` can't occur in
   *    `<readable>`.
   *
   * `preview.id` is untouched. The digest is unconditional so a stem depends only on its own id,
   * which makes filenames stable under unrelated changes, collision-free across ids that sanitise
   * identically, distinct on case-insensitive filesystems, separated from structural suffixes like
   * `_animated`, and never a Windows reserved name. [disambiguateDigestTies] backstops
   * truncated-digest ties.
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
   * Final guarantee that no two previews write the same path (case-folded, for APFS/NTFS).
   *
   * Asset, catalog, activity and tour previews are appended after [normalizeRenderOutputs] with
   * literal stems and can collide with annotation previews; validating the assembled list covers
   * any future source too. Every entry in a colliding group is re-stemmed with its own id digest,
   * so the result doesn't depend on manifest order; non-colliding entries are untouched.
   *
   * Doesn't cover `@PreviewParameter` fan-out names (`<stem>_<label>`), which need provider values
   * discovery can't enumerate.
   */
  internal fun enforceOutputUniqueness(previews: List<PreviewInfo>): List<PreviewInfo> {
    if (previews.size < 2) return previews
    val contested = contestedIndices(previews)
    if (contested.isEmpty()) return previews

    // Retagging can land on a path an untouched preview owns, so re-check; if still contested, redo
    // from the original list at full digest width over the widened set (never appending a digest
    // twice).
    val short = retagOutputs(previews, contested, RENDER_STEM_DIGEST_CHARS)
    val stillContested = contestedIndices(short)
    if (stillContested.isEmpty()) return short
    return retagOutputs(previews, contested + stillContested, FULL_DIGEST_CHARS)
  }

  /**
   * Indices of previews with an output path another preview also claims (case-folded). Previews
   * with no outputs are ignored.
   */
  private fun contestedIndices(previews: List<PreviewInfo>): Set<Int> {
    val pathsPerPreview = previews.map { outputPaths(it).map(String::lowercase).distinct() }
    val counts = mutableMapOf<String, Int>()
    for (paths in pathsPerPreview) {
      for (path in paths) counts[path] = counts.getOrDefault(path, 0) + 1
    }
    val duplicated = counts.filterValues { it > 1 }.keys
    if (duplicated.isEmpty()) return emptySet()
    return pathsPerPreview
      .withIndex()
      .filter { (_, paths) -> paths.any { it in duplicated } }
      .map { it.index }
      .toSet()
  }

  private fun outputPaths(preview: PreviewInfo): List<String> =
    (preview.captures.map { it.renderOutput } + preview.dataProducts.map { it.output }).filter {
      it.isNotEmpty()
    }

  /** Re-stem every output of the previews at [indices] with a digest of that preview's own id. */
  private fun retagOutputs(
    previews: List<PreviewInfo>,
    indices: Set<Int>,
    digestChars: Int,
  ): List<PreviewInfo> = previews.mapIndexed { i, preview ->
    if (i !in indices) preview
    else {
      val suffix = "-${idDigest(preview.id, digestChars)}"
      preview.copy(
        captures =
          preview.captures.map { it.copy(renderOutput = tagLeaf(it.renderOutput, suffix)) },
        dataProducts = preview.dataProducts.map { it.copy(output = tagLeaf(it.output, suffix)) },
      )
    }
  }

  /**
   * `dir/stem.ext` → `dir/stem<suffix>.ext`, splitting on the first dot so multi-dot sidecars
   * (`.raw.png`) keep their suffix.
   */
  private fun tagLeaf(path: String, suffix: String): String {
    if (path.isEmpty()) return path
    val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
    val leaf = path.substringAfterLast('/')
    val dot = leaf.indexOf('.')
    val tagged =
      if (dot < 0) leaf + suffix else leaf.substring(0, dot) + suffix + leaf.substring(dot)
    return if (dir.isEmpty()) tagged else "$dir/$tagged"
  }

  /** One shell-safe stem per preview; see [normalizeRenderOutputs]. `internal` for tests. */
  internal fun resolveRenderStems(
    previews: List<PreviewInfo>,
    // Narrowed only by tests, so a tie is reachable.
    digestChars: Int = RENDER_STEM_DIGEST_CHARS,
  ): List<String> {
    if (previews.isEmpty()) return emptyList()
    return disambiguateDigestTies(previews.map { renderStem(it, digestChars) }, previews)
  }

  /** The stem for one preview, from that preview alone; see [normalizeRenderOutputs]. */
  internal fun renderStem(
    preview: PreviewInfo,
    digestChars: Int = RENDER_STEM_DIGEST_CHARS,
  ): String {
    val readable =
      sanitiseSegments(preview)
        .lastOrNull()
        .orEmpty()
        // Truncate before trimming: a cut can land mid-run and leave a trailing `_`.
        .take(MAX_READABLE_STEM)
        .trim('_', '-')
        .ifEmpty { "preview" }
    return "$readable-${idDigest(preview.id, digestChars)}"
  }

  /** Lowercase hex of `sha256(id)`, truncated to [chars]. */
  private fun idDigest(id: String, chars: Int): String =
    MessageDigest.getInstance("SHA-256")
      .digest(id.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(chars)

  /**
   * Backstop for two ids sharing readable part and truncated digest: only the tied previews get a
   * full-length digest, keeping the result a pure function of the id.
   */
  private fun disambiguateDigestTies(
    stems: List<String>,
    previews: List<PreviewInfo>,
  ): List<String> {
    // Case-folded, since case-only differences are one file on APFS/NTFS; the stems keep their
    // casing.
    val tied = stems.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys
    if (tied.isEmpty()) return stems
    return stems.mapIndexed { i, stem ->
      if (stem.lowercase() !in tied) stem
      else stem.substringBeforeLast('-') + "-" + idDigest(previews[i].id, FULL_DIGEST_CHARS)
    }
  }

  /**
   * Splits a preview id into sanitised dot segments.
   *
   * Only the `className.functionName` FQN is split; the `@Preview(name = …)` suffix is folded into
   * the last segment first, since names like `"Font scale 1.5x"` contain non-structural dots that
   * would otherwise collapse the stem to `5x`. Empty segments are dropped.
   */
  private fun sanitiseSegments(preview: PreviewInfo): List<String> {
    val fqn = "${preview.className}.${preview.functionName}"
    // Ids not shaped `fqn + suffix` (e.g. synthetic) are split whole.
    val suffix = if (preview.id.startsWith(fqn)) preview.id.substring(fqn.length) else null
    val segments = (if (suffix != null) fqn else preview.id).split('.').toMutableList()
    if (suffix != null && suffix.isNotEmpty() && segments.isNotEmpty()) {
      segments[segments.lastIndex] = segments.last() + suffix
    }
    return segments.map(::sanitiseSegment).filter { it.isNotEmpty() }
  }

  /**
   * Collapses non-alphanumeric runs to `_` and trims `_`/`-` from the edges. One segment only;
   * callers split on dots first.
   */
  private fun sanitiseSegment(segment: String): String =
    segment.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_', '-')

  /** `renders/<oldStem><tail>.<ext>` → `renders/<newStem><tail>.<ext>`. */
  private fun rewriteRenderStem(renderOutput: String, oldStem: String, newStem: String): String {
    if (renderOutput.isEmpty() || oldStem == newStem) return renderOutput
    val dir = renderOutput.substringBeforeLast('/', missingDelimiterValue = "")
    val leaf = renderOutput.substringAfterLast('/')
    if (!leaf.startsWith(oldStem)) return renderOutput
    val rewritten = newStem + leaf.removePrefix(oldStem)
    return if (dir.isEmpty()) rewritten else "$dir/$rewritten"
  }

  // Distinguishing bits of a variant for the discovery log, so fan-out siblings aren't identical
  // lines. Mirrors the VS Code tooltip format.
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
    // Capture-level dimensions on one line, keeping one bullet per preview.
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
    // Resolve preview annotations first and bail on non-previews, so unrelated annotations on
    // synthetic methods don't trigger the unsupported-parameters warning (#1039).
    val directPreviews = collectDirectPreviews(annotations)
    val resolvedMultiPreviews: List<AnnotationInfo> =
      if (directPreviews.isNotEmpty()) {
        emptyList()
      } else {
        annotations.flatMap { resolveMultiPreview(it, scanResult, mutableSetOf()) }
      }
    // A multi-preview annotation off the discovery classpath resolves to nothing (#2613). Known
    // AndroidX / Wear annotations are expanded from a built-in table; unknown ones get a warning
    // instead of a silent drop.
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

    // These function-level annotations apply to every @Preview expansion. `@ScrollingPreview.modes`
    // maps TOP/END to captures and LONG/GIF to data products (see [buildOutputPlan]);
    // `@AnimatedPreview` is one capture per function.
    val wrapperFqn = extractWrapperFqn(method, scanResult)
    val scrollSpecs = extractScrollSpecs(annotations)
    val animationSpec =
      extractAnimationSpec(annotations)?.let {
        resolveAnimationFormat(
          it,
          input.animatedPreviewApngSupported,
          "${classInfo.name}.${method.name}",
          warnings,
        )
      }
    val interactionSpec = extractInteractionSpec(annotations)
    val focusSpecs = extractFocusSpecs(annotations)
    val focusGifSpec = extractFocusGifSpec(annotations)
    val ambientSpec = extractAmbientSpec(annotations)
    val glimmerEnvironmentSpecs = extractGlimmerEnvironmentSpecs(annotations)
    // `@SettledPreview` plus a motion capture is fine: both renderers give the settled still its
    // own composition, so each product owns its timeline (#4244).
    val rawSettleSpec = extractSettleSpec(annotations)
    // Clamp an exact settle below the focus setup ([SettleCapture.FOCUS_SETUP_FRAMES_MS]): desktop
    // always spends those frames before any drive, so otherwise the two backends would capture
    // different instants (#4247). Warn, since such a value can't show a focused component.
    val settleSpec =
      if (
        rawSettleSpec != null &&
          rawSettleSpec.afterMs in 1 until FOCUS_SETUP_FRAMES_MS &&
          focusSpecs.isNotEmpty()
      ) {
        warnings.add(
          "@SettledPreview(afterMs = ${rawSettleSpec.afterMs}) on " +
            "'${classInfo.name}.${method.name}' is raised to ${FOCUS_SETUP_FRAMES_MS}ms: " +
            "@FocusedPreview on the same function spends its first ${FOCUS_SETUP_FRAMES_MS}ms " +
            "laying out the tree the focus walk searches, so nothing focusable exists before " +
            "then and the two backends would otherwise capture different instants."
        )
        rawSettleSpec.copy(afterMs = FOCUS_SETUP_FRAMES_MS)
      } else {
        rawSettleSpec
      }
    val gestureHintSpec = extractGestureHintSpec(annotations)
    val permissionSpec =
      extractPermissionSpec(annotations, "${classInfo.name}.${method.name}", warnings)
    val launcherWidgetSpec = extractLauncherWidgetSpec(annotations)
    val launcherWidgetResizeSpec = extractLauncherWidgetResizeSpec(annotations)
    // `@OverrideVariant` and `@PreviewAxis` each yield one synthetic preview per spec per
    // expansion. Axes expand a cross product with typed props; the two are unioned, axes first so
    // generated cells win name collisions (`mergeVariantSpecs` warns either way).
    val owner = "${classInfo.name}.${method.name}"
    val axisSpecs =
      expandAxes(extractPreviewAxes(annotations, scanResult, owner, warnings), owner, warnings)
    val overrideVariantSpecs =
      mergeVariantSpecs(
        axisSpecs,
        extractOverrideVariantSpecs(annotations, scanResult, owner, warnings),
        owner,
        warnings,
      )
    // Each timing fans out into its own entry, orthogonal to multi-preview expansion.
    val timings = extractRoboTimings(annotations)
    // @PreviewParameter is on a method parameter; one provider applies to every expansion.
    val previewParameter = extractPreviewParameter(method)
    val isTilePreview = isAnyTilePreviewAnnotation(annotations, scanResult)
    val isNotificationPreview = isAnyNotificationPreviewAnnotation(annotations, scanResult)
    val isGlanceAppWidgetPreview = isAnyGlanceAppWidgetPreviewAnnotation(annotations, scanResult)
    val isXrSubspacePreview = isAnyXrSubspacePreviewAnnotation(annotations, scanResult)
    // `:renderer-xr` composes subspace previews parameterless, so reject parameterized ones here
    // rather than fail at render time.
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
        hasUnsupportedPreviewParameters(classInfo, method, previewParameter)
    if (hasUnsupportedParameters) {
      warnings.add(
        "composePreview: skipping @Preview '${classInfo.name}.${method.name}' — " +
          "method has parameter(s) that are neither @PreviewParameter-injected nor fully " +
          "defaulted. Supported shapes: no parameters, exactly one @PreviewParameter value, " +
          "or a default for every parameter."
      )
      return
    }

    // Target inference is identical across expansions; `lazy` walks the bytecode once (tile
    // previews never force it).
    val previewSourceFile = sourceFilePath(classInfo, input)
    // Library components this preview demonstrates (see `PreviewInfo.componentTargets`); lazy for
    // the same reason.
    val renderedCalls = lazy {
      PreviewTargetInference.renderedCalls(classInfo, method, scanResult, projectClassFqns)
    }
    val inferredComponentTargets = lazy {
      PreviewTargetInference.inferComponents(
        renderedCalls.value,
        scanResult,
        input.componentLibraryPrefixes,
      )
    }
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

    // Catalog identity, builder policy and capture gutter are function-level, shared by every
    // expansion.
    val catalogEntry =
      extractCatalogEntry(method, annotations, catalogGroupsByFile[previewSourceFile])
    val builderEntry = extractBuilderEntry(annotations)
    val captureGutter = extractCaptureGutter(annotations)
    // `@CaptureGutter` plus `@ScrollingPreview` is unsupported: scroll captures have no component
    // edge for a gutter to sit on, and honouring both diverges per lane (#4467). Skip the function
    // with an actionable message. Keyed on the annotation's presence, so `modes = []` is caught
    // too.
    val declaresScrollingPreview = annotations.any { it.name == SCROLLING_PREVIEW_FQN }
    if (captureGutter != null && declaresScrollingPreview) {
      warnings.add(
        "composePreview: skipping '${classInfo.name}.${method.name}' — @CaptureGutter cannot be " +
          "combined with @ScrollingPreview. A scroll capture has no component edge for a gutter " +
          "to sit on (see @CaptureGutter's kdoc and RENDER_LANE_PARITY.md). Remove one annotation."
      )
      return
    }
    val firstNewPreviewIndex = previews.size
    fun tagFunctionLevel() {
      for (i in firstNewPreviewIndex until previews.size) {
        val preview = previews[i]
        val widget = widgetOf(preview, renderedCalls)
        if (catalogEntry == null && captureGutter == null && builderEntry == null && widget == null)
          continue
        previews[i] =
          preview.copy(
            catalog = catalogEntry ?: preview.catalog,
            builder = builderEntry ?: preview.builder,
            widget = widget ?: preview.widget,
            params =
              if (captureGutter == null) preview.params
              else preview.params.copy(captureGutter = captureGutter),
          )
      }
    }

    if (directPreviews.isNotEmpty()) {
      for (ann in directPreviews) {
        val base =
          makePreview(
            classInfo,
            method,
            ann,
            scanResult,
            wrapperFqn,
            scrollSpecs,
            animationSpec,
            interactionSpec,
            focusSpecs,
            focusGifSpec,
            ambientSpec,
            glimmerEnvironmentSpecs,
            settleSpec,
            gestureHintSpec,
            permissionSpec,
            launcherWidgetSpec,
            launcherWidgetResizeSpec,
            timings,
            previewParameter,
            previewSourceFile,
            inferredTargets,
            inferredComponentTargets,
          )
        previews.add(base)
        for (spec in overrideVariantSpecs) previews.add(overrideVariantPreview(base, spec))
      }
      tagFunctionLevel()
      return
    }

    for (resolvedAnn in resolvedMultiPreviews) {
      val base =
        makePreview(
          classInfo,
          method,
          resolvedAnn,
          scanResult,
          wrapperFqn,
          scrollSpecs,
          animationSpec,
          interactionSpec,
          focusSpecs,
          focusGifSpec,
          ambientSpec,
          glimmerEnvironmentSpecs,
          settleSpec,
          gestureHintSpec,
          permissionSpec,
          launcherWidgetSpec,
          launcherWidgetResizeSpec,
          timings,
          previewParameter,
          previewSourceFile,
          inferredTargets,
          inferredComponentTargets,
        )
      previews.add(base)
      for (spec in overrideVariantSpecs) previews.add(overrideVariantPreview(base, spec))
    }

    // Built-in expansion of known off-classpath multi-preview annotations (#2613), through the same
    // [buildPreviewInfo] tail as a real `@Preview`.
    for (spec in builtInSpecs) {
      val base =
        buildPreviewInfo(
          classInfo,
          method,
          spec.toParams(wrapperFqn, previewParameter),
          scanResult,
          scrollSpecs,
          animationSpec,
          interactionSpec,
          focusSpecs,
          focusGifSpec,
          ambientSpec,
          glimmerEnvironmentSpecs,
          settleSpec,
          gestureHintSpec,
          permissionSpec,
          launcherWidgetSpec,
          launcherWidgetResizeSpec,
          timings,
          previewSourceFile,
          inferredTargets,
          inferredComponentTargets,
        )
      previews.add(base)
      for (variant in overrideVariantSpecs) previews.add(overrideVariantPreview(base, variant))
    }
    tagFunctionLevel()
  }

  /**
   * A synthetic override-variant preview from [base]: `_VARIANT_<name>` id and outputs, [spec]'s
   * seeds on [PreviewInfo.overrides], any harness interaction on its capture, and no data products.
   * The unchanged `functionName` lets the catalog fold it under its primary sticker.
   */
  private fun overrideVariantPreview(base: PreviewInfo, spec: OverrideVariantSpec): PreviewInfo {
    val tag = "_VARIANT_${spec.name}"
    val variantCaptures =
      base.captures
        // `@InteractionPreview` doesn't fan out across variants: the recording would be an
        // expensive duplicate. Drop the whole capture so the variant doesn't write an undriven
        // still under an interaction filename.
        .filter { it.interaction == null }
        .let { captures ->
          if (spec.interaction != OverrideVariantInteraction.Dragged) captures
          else
            captures
              .map { capture ->
                if (capture.animation == null && capture.focusGif == null) capture
                else
                  capture.copy(
                    animation = null,
                    focusGif = null,
                    renderOutput = replaceRenderExtension(capture.renderOutput, "png"),
                    cost = STATIC_COST,
                  )
              }
              // LONG/GIF-only scrolling previews have no primary capture, but a Dragged variant
              // still needs one still.
              .ifEmpty { listOf(Capture(renderOutput = "renders/${base.id}.png")) }
              // A held drag mutates remembered state and Android reuses one composition per
              // preview, so applying it across several captures would be cumulative. Keep the first
              // still.
              .take(1)
        }
    return base.copy(
      id = base.id + tag,
      overrides = spec,
      captures =
        variantCaptures.map { capture ->
          val tagged = capture.copy(renderOutput = insertRenderTag(capture.renderOutput, tag))
          when (spec.interaction) {
            OverrideVariantInteraction.Focused ->
              tagged.copy(
                focus = FocusCapture(tabIndex = spec.interactionIndex),
                focusGif = null,
                hover = null,
              )
            OverrideVariantInteraction.Pressed ->
              tagged.copy(
                focus = FocusCapture(tabIndex = spec.interactionIndex, pressed = true),
                focusGif = null,
                hover = null,
              )
            OverrideVariantInteraction.Hovered ->
              tagged.copy(
                focus = null,
                focusGif = null,
                hover = HoverCapture(targetIndex = spec.interactionIndex),
              )
            OverrideVariantInteraction.Dragged ->
              tagged.copy(
                focus = null,
                focusGif = null,
                hover = null,
                drag = DragCapture(targetIndex = spec.interactionIndex),
              )
            null -> tagged
          }
        },
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

  private fun replaceRenderExtension(renderOutput: String, extension: String): String {
    val dot = renderOutput.lastIndexOf('.')
    val slash = renderOutput.lastIndexOf('/')
    return if (dot > slash) renderOutput.substring(0, dot + 1) + extension
    else "$renderOutput.$extension"
  }

  /**
   * Reads `@OverrideVariant`s (direct or via the `.Container`) into one [OverrideVariantSpec] each.
   * Array entries are `"key=value"` / `"key#index=value"`; the array fixes the [OverrideSeedKind].
   * A variant with neither a parseable seed nor an interaction is dropped.
   *
   * Variants may be hoisted onto an annotation class. ClassGraph flattens meta-annotations into
   * [annotations], and [overrideVariantsFromMetaAnnotation] walks them explicitly too so we don't
   * depend on that; both feed the same de-duplication.
   *
   * **Names are de-duplicated, first wins, with a warning**: a name is the output path, so two
   * variants sharing one would silently overwrite each other — easy once overlapping matrices are
   * hoisted.
   */
  private fun extractOverrideVariantSpecs(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
    owner: String,
    warnings: MutableList<String>,
  ): List<OverrideVariantSpec> {
    val infos = mutableListOf<AnnotationInfo>()
    val visited = mutableSetOf<String>()
    for (ann in annotations) {
      collectOverrideVariants(ann, infos)
      overrideVariantsFromMetaAnnotation(ann, scanResult, visited, infos)
    }
    val specs = LinkedHashMap<String, OverrideVariantSpec>()
    val collisions = LinkedHashSet<String>()
    for (info in infos) {
      val pv = info.parameterValues
      val name = (pv.getValue("name") as? String)?.takeIf { it.isNotBlank() } ?: continue
      val seeds =
        readOverrideSeeds(pv.getValue("booleans"), OverrideSeedKind.BOOLEAN) +
          readOverrideSeeds(pv.getValue("strings"), OverrideSeedKind.STRING) +
          readOverrideSeeds(pv.getValue("ints"), OverrideSeedKind.INT) +
          readOverrideSeeds(pv.getValue("floats"), OverrideSeedKind.FLOAT) +
          readOverrideSeeds(pv.getValue("colors"), OverrideSeedKind.COLOR)
      val interaction =
        (pv.getValue("interaction") as? AnnotationEnumValue)
          ?.valueName
          ?.takeUnless { it == "None" }
          ?.let { runCatching { OverrideVariantInteraction.valueOf(it) }.getOrNull() }
      if (seeds.isEmpty() && interaction == null) continue
      val interactionIndex = ((pv.getValue("interactionIndex") as? Int) ?: 0).coerceAtLeast(0)
      val kitAxis = (pv.getValue("kitAxis") as? String)?.takeIf { it.isNotBlank() }
      val kitValue = (pv.getValue("kitValue") as? String)?.takeIf { it.isNotBlank() }
      // `"Axis=Value"`, split on the first `=`. `runCatching` because `getValue` throws for a
      // parameter absent from an older annotations jar, and discovery must keep working against
      // one.
      val kitProps =
        stringArrayValue(runCatching { pv.getValue("kitProps") }.getOrNull())
          .mapNotNull(::parseCatalogProp)
      // Both `kitProps` and `kitAxis`/`kitValue`: keep the plural (the only form for multi-knob
      // cells) and report the singular as ignored.
      if (kitProps.isNotEmpty() && (kitAxis != null || kitValue != null)) {
        warnings.add(
          "composePreview: '$owner' variant '$name' declares both kitProps and kitAxis/kitValue — " +
            "keeping kitProps and ignoring the singular pair. Declare one or the other."
        )
      }
      // `runCatching` for older annotations jars, as for `kitProps`.
      val secondary = (runCatching { pv.getValue("secondary") }.getOrNull() as? Boolean) ?: false
      val noReference =
        (runCatching { pv.getValue("noReference") }.getOrNull() as? String)?.takeIf {
          it.isNotBlank()
        }
      val spec =
        OverrideVariantSpec(
          name = name,
          seeds = seeds,
          interaction = interaction,
          interactionIndex = interactionIndex,
          kitAxis = kitAxis.takeIf { kitProps.isEmpty() },
          kitValue = kitValue.takeIf { kitProps.isEmpty() },
          kitProps = kitProps,
          noReference = noReference,
          secondary = secondary,
        )
      val existing = specs.putIfAbsent(name, spec)
      // The same annotation reached twice (flattened and via the meta walk) yields an identical
      // spec; only conflicts warn.
      if (existing != null && existing != spec) collisions.add(name)
    }
    for (name in collisions) {
      warnings.add(
        "composePreview: '$owner' carries more than one @OverrideVariant named '$name' with " +
          "different seeds — most likely two hoisted variant annotations that overlap on a cell. " +
          "Variant names are the `_VARIANT_$name` render output's identity, so the second would " +
          "overwrite the first; keeping the first and ignoring the rest. Rename the cell, or drop " +
          "the overlapping annotation."
      )
    }
    return specs.values.toList()
  }

  /** Adds [ann] to [into] when it is an `@OverrideVariant`, unwrapping the repeatable container. */
  private fun collectOverrideVariants(ann: AnnotationInfo, into: MutableList<AnnotationInfo>) {
    when (ann.name) {
      OVERRIDE_VARIANT_FQN -> into.add(ann)
      OVERRIDE_VARIANT_CONTAINER_FQN -> {
        when (val value = ann.parameterValues.getValue("value")) {
          is Array<*> -> value.filterIsInstance<AnnotationInfo>().forEach { into.add(it) }
          is AnnotationInfo -> into.add(value)
          else -> {
            val len = runCatching { java.lang.reflect.Array.getLength(value) }.getOrNull() ?: 0
            for (i in 0 until len) {
              (java.lang.reflect.Array.get(value, i) as? AnnotationInfo)?.let { into.add(it) }
            }
          }
        }
      }
    }
  }

  /**
   * Collects `@OverrideVariant`s hoisted onto annotation classes, recursively with a cycle guard,
   * skipping `@Preview` itself. Contributes nothing for annotation classes off the discovery
   * classpath.
   */
  private fun overrideVariantsFromMetaAnnotation(
    ann: AnnotationInfo,
    scanResult: ScanResult,
    visited: MutableSet<String>,
    into: MutableList<AnnotationInfo>,
  ) = walkMetaAnnotations(ann, scanResult, visited) { collectOverrideVariants(it, into) }

  /**
   * Shared meta-annotation traversal for `@OverrideVariant` and `@PreviewAxis`, so they agree on
   * what "hoisted" reaches. [visited] is shared per call site, so diamonds are visited once.
   */
  private fun walkMetaAnnotations(
    ann: AnnotationInfo,
    scanResult: ScanResult,
    visited: MutableSet<String>,
    collect: (AnnotationInfo) -> Unit,
  ) {
    if (ann.name in visited) return
    if (isDirectPreview(ann) || isPreviewContainer(ann)) return
    // Hoistable annotations are leaves; descending into them wastes a scan.
    if (ann.name in HOISTABLE_LEAF_FQNS) return
    visited.add(ann.name)
    val annClassInfo = scanResult.getClassInfo(ann.name) ?: return
    for (metaAnn in annClassInfo.annotationInfo.toList()) {
      collect(metaAnn)
      walkMetaAnnotations(metaAnn, scanResult, visited, collect)
    }
  }

  /** One resolved `@PreviewAxis`: its knob, its values (with name slugs), and its default. */
  private data class AxisSpec(
    val key: String,
    val values: List<Pair<String, String>>, // value → name slug
    val default: String,
    val kind: OverrideSeedKind,
    val namesEveryValue: Boolean,
    val order: Int,
  )

  /**
   * Reads every `@PreviewAxis` (direct or hoisted) into an [AxisSpec]. Malformed axes are dropped
   * with a warning rather than failing the build.
   */
  private fun extractPreviewAxes(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
    owner: String,
    warnings: MutableList<String>,
  ): List<AxisSpec> {
    val infos = mutableListOf<AnnotationInfo>()
    val visited = mutableSetOf<String>()
    for (ann in annotations) {
      collectPreviewAxes(ann, infos)
      walkMetaAnnotations(ann, scanResult, visited) { collectPreviewAxes(it, infos) }
    }
    val byKey = LinkedHashMap<String, AxisSpec>()
    for (info in infos) {
      val pv = info.parameterValues
      val key = (pv.getValue("key") as? String)?.takeIf { it.isNotBlank() } ?: continue
      val values = readStringArray(pv.getValue("values")).distinct()
      if (values.size < 2) {
        warnings.add(
          "composePreview: '$owner' declares @PreviewAxis(key = \"$key\") with " +
            "${values.size} distinct value(s) — an axis with fewer than two multiplies nothing. " +
            "Ignoring it."
        )
        continue
      }
      val slugsRaw = readStringArray(pv.getValue("slugs"))
      val slugs =
        when {
          slugsRaw.isEmpty() -> values
          slugsRaw.size == values.size -> slugsRaw
          else -> {
            warnings.add(
              "composePreview: '$owner' declares @PreviewAxis(key = \"$key\") with " +
                "${slugsRaw.size} slugs for ${values.size} values — positional, so a mismatch " +
                "would misname cells. Using the values as their own slugs."
            )
            values
          }
        }
      val declaredDefault = (pv.getValue("default") as? String).orEmpty()
      val default =
        when {
          declaredDefault.isEmpty() -> values.first()
          declaredDefault in values -> declaredDefault
          else -> {
            warnings.add(
              "composePreview: '$owner' declares @PreviewAxis(key = \"$key\", default = " +
                "\"$declaredDefault\") but that is not one of its values — every cell would then " +
                "count as non-default and one would duplicate the base render. Using " +
                "\"${values.first()}\"."
            )
            values.first()
          }
        }
      val kind = axisSeedKind(pv.getValue("kind"))
      // `OverrideSeed.toValueOrNull` silently drops unparseable seeds, so such a cell would render
      // the base while publishing props claiming otherwise. Reject it.
      val unparseable = values.filterNot { parseableAs(it, kind) }
      if (unparseable.isNotEmpty()) {
        warnings.add(
          "composePreview: '$owner' declares @PreviewAxis(key = \"$key\", kind = $kind) with " +
            "value(s) ${unparseable.joinToString(", ") { "\"$it\"" }} that are not valid $kind " +
            "literals — the renderer would drop the seed and bake a duplicate of the base render " +
            "while the catalog claimed it was that cell. Ignoring the axis."
        )
        continue
      }
      val spec =
        AxisSpec(
          key = key,
          values = values.zip(slugs),
          default = default,
          kind = kind,
          namesEveryValue = pv.getValue("namesEveryValue") as? Boolean ?: false,
          order = (pv.getValue("order") as? Int) ?: 0,
        )
      // Two axes on one key are a contradiction. A hoisted axis is reached twice by design, so only
      // conflicting duplicates warn.
      val existing = byKey.putIfAbsent(key, spec)
      if (existing != null && existing != spec) {
        warnings.add(
          "composePreview: '$owner' declares @PreviewAxis(key = \"$key\") more than once, with " +
            "different values — crossing a key with itself produces contradictory cells. Keeping " +
            "the first."
        )
      }
    }
    // Stable sort on the declared order: the compiler's emit order for repeated annotations doesn't
    // match source order and would rename every cell. See `PreviewAxis.order`.
    return byKey.values.sortedBy { it.order }
  }

  /**
   * Whether [value] parses for [kind] using the same predicates as `OverrideSeed.toValueOrNull`, so
   * an axis is rejected exactly when its seed would be dropped. `COLOR` isn't reachable from
   * `@PreviewAxis`, so it isn't validated.
   */
  private fun parseableAs(value: String, kind: OverrideSeedKind): Boolean {
    val text = value.trim()
    return when (kind) {
      OverrideSeedKind.STRING -> true
      OverrideSeedKind.BOOLEAN -> text.toBooleanStrictOrNull() != null
      OverrideSeedKind.INT -> text.toIntOrNull() != null
      OverrideSeedKind.FLOAT -> text.toFloatOrNull() != null
      OverrideSeedKind.COLOR -> true
    }
  }

  /** Adds [ann] to [into] when it is a `@PreviewAxis`, unwrapping the repeatable container. */
  private fun collectPreviewAxes(ann: AnnotationInfo, into: MutableList<AnnotationInfo>) {
    when (ann.name) {
      PREVIEW_AXIS_FQN -> into.add(ann)
      PREVIEW_AXIS_CONTAINER_FQN -> {
        when (val value = ann.parameterValues.getValue("value")) {
          is Array<*> -> value.filterIsInstance<AnnotationInfo>().forEach { into.add(it) }
          is AnnotationInfo -> into.add(value)
          else -> {
            val len = runCatching { java.lang.reflect.Array.getLength(value) }.getOrNull() ?: 0
            for (i in 0 until len) {
              (java.lang.reflect.Array.get(value, i) as? AnnotationInfo)?.let { into.add(it) }
            }
          }
        }
      }
    }
  }

  /**
   * `PreviewAxisKind` → [OverrideSeedKind], read off the `AnnotationEnumValue` name without loading
   * the class.
   */
  private fun axisSeedKind(raw: Any?): OverrideSeedKind {
    val name =
      when (raw) {
        is AnnotationEnumValue -> raw.valueName
        is String -> raw
        else -> null
      }
    return when (name) {
      "BOOLEAN" -> OverrideSeedKind.BOOLEAN
      "INT" -> OverrideSeedKind.INT
      "FLOAT" -> OverrideSeedKind.FLOAT
      else -> OverrideSeedKind.STRING
    }
  }

  /**
   * Expands [axes] into one [OverrideVariantSpec] per cross-product cell, axis-major (first axis
   * slowest). A cell:
   * * **seeds** only its non-default values;
   * * is **named** by its non-default slugs joined by `-`, plus any [AxisSpec.namesEveryValue]
   *   axis;
   * * publishes its **full** assignment as props, defaults included, for kit pairing.
   *
   * The all-defaults cell (no seeds, though possibly named) is skipped, since it would duplicate
   * the base render.
   */
  private fun expandAxes(
    axes: List<AxisSpec>,
    owner: String,
    warnings: MutableList<String>,
  ): List<OverrideVariantSpec> {
    if (axes.isEmpty()) return emptyList()
    val total = axes.fold(1L) { acc, axis -> acc * axis.values.size }
    if (total > PREVIEW_AXIS_MAX_CELLS) {
      warnings.add(
        "composePreview: '$owner' declares @PreviewAxis axes whose cross product is $total cells " +
          "(${axes.joinToString(" x ") { "${it.key}(${it.values.size})" }}), over the " +
          "$PREVIEW_AXIS_MAX_CELLS cap — that is a render bill, not a matrix. Ignoring the axes; " +
          "split the component or drop an axis."
      )
      return emptyList()
    }
    if (total > PREVIEW_AXIS_MAX_CELLS_WARN) {
      warnings.add(
        "composePreview: '$owner' expands to $total @PreviewAxis cells " +
          "(${axes.joinToString(" x ") { "${it.key}(${it.values.size})" }}), each rendered for " +
          "every @Preview on the function. Intentional?"
      )
    }

    var combinations: List<List<Pair<String, String>>> = listOf(emptyList())
    for (axis in axes) {
      combinations = combinations.flatMap { prefix -> axis.values.map { prefix + it } }
    }
    return combinations
      .mapNotNull { combination ->
        val slugs = mutableListOf<String>()
        val seeds = mutableListOf<OverrideSeed>()
        val props = mutableListOf<CatalogVariantProp>()
        for ((axis, valueAndSlug) in axes.zip(combination)) {
          val (value, slug) = valueAndSlug
          val isDefault = value == axis.default
          if (axis.namesEveryValue || !isDefault) slugs.add(slug)
          props.add(CatalogVariantProp(key = axis.key, value = value))
          if (!isDefault) {
            seeds.add(OverrideSeed(key = axis.key, index = null, kind = axis.kind, raw = value))
          }
        }
        if (seeds.isEmpty()) null
        else
          OverrideVariantSpec(name = slugs.joinToString("-"), seeds = seeds, props = props.toList())
      }
      .let { cells -> dedupeCellNames(cells, owner, warnings) }
  }

  /**
   * Drops cells whose generated name collides with an earlier one, warning about each. Axes sharing
   * value names can collide without any authoring mistake. Dropped rather than renamed, since
   * inventing a suffix would mint an undeclared address.
   */
  private fun dedupeCellNames(
    cells: List<OverrideVariantSpec>,
    owner: String,
    warnings: MutableList<String>,
  ): List<OverrideVariantSpec> {
    val byName = LinkedHashMap<String, OverrideVariantSpec>()
    val collided = LinkedHashSet<String>()
    for (cell in cells) {
      if (byName.putIfAbsent(cell.name, cell) != null) collided.add(cell.name)
    }
    if (collided.isNotEmpty()) {
      warnings.add(
        "composePreview: '$owner' expands @PreviewAxis to cells that collide on the name(s) " +
          "${collided.joinToString(", ")} — a name is a render output path, so only the first of " +
          "each is kept. Two axes sharing a value name, or repeated `slugs`, produce this; give " +
          "the values distinct slugs, or set `namesEveryValue` on one axis so its cells stay " +
          "distinguishable."
      )
    }
    return byName.values.toList()
  }

  /**
   * Unions axis cells with hand-written variants; first wins on a name collision (names are output
   * paths). Axis cells go first, so a hand-written variant shadowing one is the reported mistake.
   */
  private fun mergeVariantSpecs(
    axisCells: List<OverrideVariantSpec>,
    handWritten: List<OverrideVariantSpec>,
    owner: String,
    warnings: MutableList<String>,
  ): List<OverrideVariantSpec> {
    if (axisCells.isEmpty()) return handWritten
    if (handWritten.isEmpty()) return axisCells
    val merged = LinkedHashMap<String, OverrideVariantSpec>()
    axisCells.forEach { merged[it.name] = it }
    val shadowed = handWritten.filter { it.name in merged }.map { it.name }
    handWritten.forEach { merged.putIfAbsent(it.name, it) }
    if (shadowed.isNotEmpty()) {
      warnings.add(
        "composePreview: '$owner' has @OverrideVariant(s) named ${shadowed.joinToString(", ")} " +
          "that a @PreviewAxis cell already produces — one render output per name, so the " +
          "hand-written one is ignored. Rename it, or drop it if the axes already cover the cell."
      )
    }
    return merged.values.toList()
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
   * Tile previews take a renderer-supplied `(Context)`, so the @PreviewParameter contract doesn't
   * apply. Walks meta-annotations so aliases like `@MultiRoundTilesPreviews` are exempt too.
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
   * Notification previews take a renderer-supplied `(Context)`, like tiles; exempt from the
   * @PreviewParameter check, including via meta-annotations.
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
   * Glance previews' JVM signature carries the `Composer, Int` pair the parameter check would flag;
   * the renderer invokes them via `GlanceAppWidget.providePreview(...)`, so skip the check.
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
   * XR subspace previews are rendered by `:renderer-xr`, so skip the parameter check, including via
   * meta-annotations.
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
    classInfo: ClassInfo,
    method: MethodInfo,
    previewParameter: Pair<String, Int>?,
  ): Boolean {
    val userParameters = userPreviewParameters(method)
    if (userParameters.isEmpty()) return false
    // Renderer contract: no parameters, exactly one @PreviewParameter value, or a default for every
    // parameter.
    if (previewParameter != null) return userParameters.size != 1
    // All-defaults is also Studio's contract and common for production composables annotated in
    // place (`modifier: Modifier = Modifier`). The renderer invokes them with no args via the
    // `$default` bridge.
    return !allParametersHaveDefaults(classInfo, method, userParameters.size)
  }

  /**
   * True when the first [userParameterCount] value parameters all declare defaults, per
   * `@kotlin.Metadata`. Bytecode can't tell: one `$default` bridge exists if *any* parameter has a
   * default, and an all-bits mask would pass `null`/`0` for the rest. Unreadable metadata yields
   * `false`.
   */
  internal fun allParametersHaveDefaults(
    classInfo: ClassInfo,
    method: MethodInfo,
    userParameterCount: Int,
  ): Boolean {
    val parameters = ComposableSignature.parametersOf(classInfo, method)
    // Metadata unreadable, or it disagrees with the JVM signature about how many parameters the
    // author wrote — don't guess.
    if (parameters.isEmpty() || parameters.size != userParameterCount) return false
    return parameters.all { it.hasDefault }
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
   * Provider FQN + `limit` of the first `@PreviewParameter` parameter, or `null`. Single-parameter
   * only, like Studio. The provider `KClass` is read as an [AnnotationClassRef] without
   * classloading.
   */
  private fun extractPreviewParameter(method: MethodInfo): Pair<String, Int>? {
    val params = method.parameterInfo ?: return null
    for (param in params) {
      val anns = param.annotationInfo ?: continue
      val ann = anns.firstOrNull { it.name in PREVIEW_PARAMETER_FQNS } ?: continue
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

  // Tile previews have no `mainClock` or scrollables, so dimensional annotations are no-ops for
  // them.
  internal data class PreviewOutputPlan(
    val captures: List<Capture>,
    val dataProducts: List<PreviewDataProduct>,
  )

  private fun glimmerEnvironmentOutput(
    output: String,
    environment: GlimmerEnvironmentCapture,
  ): String {
    val dot = output.lastIndexOf('.')
    val suffix = "_GLIMMER_${environment.name.lowercase()}"
    return if (dot < 0) "$output$suffix"
    else output.substring(0, dot) + suffix + output.substring(dot)
  }

  internal fun buildOutputPlan(
    kind: PreviewKind,
    previewId: String,
    scrolls: List<ScrollCapture>,
    animation: AnimationCapture?,
    interaction: InteractionCapture?,
    focuses: List<FocusCapture>,
    focusGif: FocusGifCapture?,
    ambient: AmbientCapture?,
    glimmerEnvironments: List<GlimmerEnvironmentCapture>,
    settle: SettleCapture?,
    gestureHint: GestureHintCapture?,
    permissions: PermissionsCapture?,
    launcherWidget: LauncherWidgetCapture?,
    launcherWidgetResize: LauncherWidgetResizeSpec?,
    timings: List<Long>,
  ): PreviewOutputPlan {
    val environments: List<GlimmerEnvironmentCapture?> = glimmerEnvironments.ifEmpty {
      listOf(null)
    }
    val plans = environments.map { environment ->
      buildOutputPlanForEnvironment(
        kind,
        previewId,
        scrolls,
        animation,
        interaction,
        focuses,
        focusGif,
        ambient,
        environment,
        settle,
        gestureHint,
        permissions,
        launcherWidget,
        launcherWidgetResize,
        timings,
      )
    }
    if (plans.size == 1) return plans.single()
    return PreviewOutputPlan(
      captures =
        plans.zip(glimmerEnvironments).flatMap { (plan, environment) ->
          plan.captures.map { capture ->
            capture.copy(renderOutput = glimmerEnvironmentOutput(capture.renderOutput, environment))
          }
        },
      dataProducts = plans.first().dataProducts,
    )
  }

  private fun buildOutputPlanForEnvironment(
    kind: PreviewKind,
    previewId: String,
    scrolls: List<ScrollCapture>,
    animation: AnimationCapture?,
    interaction: InteractionCapture?,
    focuses: List<FocusCapture>,
    focusGif: FocusGifCapture?,
    ambient: AmbientCapture?,
    glimmerEnvironment: GlimmerEnvironmentCapture?,
    settle: SettleCapture?,
    gestureHint: GestureHintCapture?,
    permissions: PermissionsCapture?,
    launcherWidget: LauncherWidgetCapture?,
    launcherWidgetResize: LauncherWidgetResizeSpec?,
    timings: List<Long>,
  ): PreviewOutputPlan {
    val isTile = kind == PreviewKind.TILE
    // Notifications aren't composable either; same treatment as tiles.
    val isNotification = kind == PreviewKind.NOTIFICATION
    // Glance previews are a closed composition materialised in one shot; same treatment.
    val isGlanceAppWidget = kind == PreviewKind.GLANCE_APPWIDGET
    // XR subspace previews are driven by `:renderer-xr` in one shot; same treatment.
    val isXrSubspace = kind == PreviewKind.XR_SUBSPACE
    // XR subspace previews emit one optional capture pointing at
    // `renders/<sanitizedId>/composite.png`, baked best-effort by `composePreviewCompositeXr` from
    // the `scene.json` the XR task writes. Sanitisation matches `XrSubspaceRenderTest.sanitize` so
    // the path agrees on disk; the literal leaf isn't rewritten by `normalizeRenderOutputs`. No
    // data products.
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
    // Non-composable previews have no clock to drive; nor pointer pipeline, focus owner or
    // composition locals for the overrides below.
    val effectiveAnimation = if (nonComposable) null else animation
    val effectiveInteraction = if (nonComposable) null else interaction
    // `gif = true` replaces the per-step PNG fan-out.
    val effectiveFocusGif = if (nonComposable) null else focusGif
    val effectiveFocuses = if (nonComposable || effectiveFocusGif != null) emptyList() else focuses
    val effectiveAmbient = if (nonComposable) null else ambient
    val effectiveGlimmerEnvironment = if (nonComposable) null else glimmerEnvironment
    // A motion capture on the same function is fine: the settled still gets its own composition
    // (#4244).
    val effectiveSettle = if (nonComposable) null else settle
    val effectiveGestureHint = if (nonComposable) null else gestureHint
    val effectivePermissions = if (nonComposable) null else permissions
    val effectiveLauncherWidget = if (nonComposable) null else launcherWidget
    val effectiveLauncherWidgetResize = if (nonComposable) null else launcherWidgetResize

    // @AnimatedPreview and @FocusedPreview(gif = true) each produce one motion output. When sharing
    //   the function with anything else (scroll/time fan-out, each other, a resize fan-out) they
    //   take suffixes; otherwise the plain filename. Decided by capture kinds, not extensions, so a
    //   format change only changes the extension. [separateMotionOutputs] is the backstop.
    val motionSharesFn =
      effectiveScrolls.isNotEmpty() ||
        effectiveTimings.isNotEmpty() ||
        effectiveLauncherWidgetResize != null ||
        (effectiveAnimation != null && effectiveFocusGif != null)

    // Suffix the interaction whenever another motion kind could claim the same `<id>.<ext>`, keyed
    // on kinds present.
    val interactionSharesFn =
      motionSharesFn || effectiveAnimation != null || effectiveFocusGif != null

    // One interaction capture per function, not crossed with other fan-outs; shared by the resize
    // branch and the ordinary path.
    val interactionCaptures: List<Capture> =
      if (effectiveInteraction == null) emptyList()
      else {
        val suffix = if (interactionSharesFn) "_interaction" else ""
        listOf(
          Capture(
            interaction = effectiveInteraction,
            // The renderer finds the permissions extension on the first capture with non-null
            // `permissions`, so a sole motion capture must carry it.
            permissions = effectivePermissions,
            glimmerEnvironment = effectiveGlimmerEnvironment,
            renderOutput = motionRenderOutput(previewId, suffix, effectiveInteraction.format),
            cost = INTERACTION_COST,
          )
        )
      }

    // `@LauncherWidgetResize` replaces the static capture grid with one PNG per whole-cell stop.
    // Motion GIFs still fan out alongside with their usual suffixes.
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
          permissions = effectivePermissions,
          glimmerEnvironment = effectiveGlimmerEnvironment,
          settle = effectiveSettle,
          renderOutput = "renders/${previewId}_RESIZE_${w}x${h}.png",
          // A settle walks its window frame by frame; see `settleCaptureCost`.
          cost = effectiveSettle?.let { settleCaptureCost(it.windowMs) } ?: STATIC_COST,
        )
      }
      val focusGifCaptures: List<Capture> =
        if (effectiveFocusGif == null) emptyList()
        else {
          val suffix = if (motionSharesFn) "_focus_gif" else ""
          listOf(
            Capture(
              focusGif = effectiveFocusGif,
              ambient = effectiveAmbient,
              gestureHint = effectiveGestureHint,
              permissions = effectivePermissions,
              glimmerEnvironment = effectiveGlimmerEnvironment,
              launcherWidget = effectiveLauncherWidget,
              renderOutput = motionRenderOutput(previewId, suffix, MotionFormat.GIF),
              cost = FOCUS_GIF_COST,
            )
          )
        }
      val animationCaptures: List<Capture> =
        if (effectiveAnimation == null) emptyList()
        else {
          val suffix = if (motionSharesFn) "_anim" else ""
          listOf(
            Capture(
              animation = effectiveAnimation,
              // Sole capture must carry `permissions` (see the interaction branch).
              permissions = effectivePermissions,
              glimmerEnvironment = effectiveGlimmerEnvironment,
              renderOutput = motionRenderOutput(previewId, suffix, effectiveAnimation.format),
              cost = ANIMATION_COST,
            )
          )
        }
      return PreviewOutputPlan(
        captures =
          separateMotionOutputs(
            resizeCaptures + focusGifCaptures + animationCaptures + interactionCaptures
          ),
        dataProducts = emptyList(),
      )
    }

    // One focus GIF per function, not crossed with other fan-outs.
    val focusGifCaptures: List<Capture> =
      if (effectiveFocusGif == null) emptyList()
      else {
        val suffix = if (motionSharesFn) "_focus_gif" else ""
        listOf(
          Capture(
            focusGif = effectiveFocusGif,
            ambient = effectiveAmbient,
            gestureHint = effectiveGestureHint,
            permissions = effectivePermissions,
            glimmerEnvironment = effectiveGlimmerEnvironment,
            launcherWidget = effectiveLauncherWidget,
            renderOutput = motionRenderOutput(previewId, suffix, MotionFormat.GIF),
            cost = FOCUS_GIF_COST,
          )
        )
      }

    // Its own capture alongside any scroll/time fan-out; `_anim`-suffixed when sharing the
    // function.
    val animationCaptures: List<Capture> =
      if (effectiveAnimation == null) emptyList()
      else {
        val suffix = if (motionSharesFn) "_anim" else ""
        listOf(
          Capture(
            animation = effectiveAnimation,
            // Sole capture must carry `permissions`.
            permissions = effectivePermissions,
            glimmerEnvironment = effectiveGlimmerEnvironment,
            renderOutput = motionRenderOutput(previewId, suffix, effectiveAnimation.format),
            cost = ANIMATION_COST,
          )
        )
      }

    // Single-mode scroll keeps the plain filename; multi-mode adds `_SCROLL_<mode>`.
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
    // One focus capture per index or traversal step; a single capture keeps the plain filename.
    //
    // Multi-capture fan-outs also keep the undriven row, so the component still has a resting
    // picture for catalogs and parity. Only there, because a single-capture annotation already owns
    // `renders/<id>.png`.
    val focusRows: List<Pair<FocusCapture?, String>> =
      when {
        effectiveFocuses.isEmpty() -> listOf(null to "")
        effectiveFocuses.size == 1 -> listOf(effectiveFocuses[0] to "")
        else ->
          listOf<Pair<FocusCapture?, String>>(null to "") +
            effectiveFocuses.map { it to "_FOCUS_${focusSuffixOf(it)}" }
      }

    // Suppress the static row when only a motion annotation, or only data-product scroll modes
    // (LONG/GIF), own the function — a static PNG would just be an unscrolled frame (#1524).
    //
    // `@SettledPreview` overrides the suppression: it requests a settled still, and both ship with
    // separate compositions (#4244).
    val emitStaticCross =
      captureScrolls.isNotEmpty() ||
        effectiveTimings.isNotEmpty() ||
        effectiveFocuses.isNotEmpty() ||
        effectiveSettle != null ||
        (effectiveAnimation == null && effectiveFocusGif == null && productScrolls.isEmpty())

    val scrollTimeCaptures: List<Capture> =
      if (!emitStaticCross) emptyList()
      else {
        scrollRows.flatMap { (scroll, scrollSuffix) ->
          timeRows.flatMap { (ms, timeSuffix) ->
            focusRows.map { (focus, focusSuffix) ->
              val ext = "png"
              // Cost is per capture; a timing fan-out's cost lives in the capture count, and focus
              // drive adds no bucket.
              val settleForRow = if (scroll == null && ms == null) effectiveSettle else null
              val captureCost =
                when (scroll?.mode) {
                  null -> settleForRow?.let { settleCaptureCost(it.windowMs) } ?: STATIC_COST
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
                permissions = effectivePermissions,
                glimmerEnvironment = effectiveGlimmerEnvironment,
                // Settle only the plain still: scroll drives settle themselves and timings are
                // exact snapshots.
                settle = settleForRow,
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
      captures =
        separateMotionOutputs(
          scrollTimeCaptures + animationCaptures + focusGifCaptures + interactionCaptures
        ),
      dataProducts = dataProducts,
    )
  }

  // `advanceTimeMillis` of each `@RoboComposePreviewOptions(manualClockOptions)` entry; empty when
  // absent or empty (Roborazzi's "default").
  private fun extractRoboTimings(annotations: List<AnnotationInfo>): List<Long> {
    val ann =
      annotations.firstOrNull { it.name == ROBO_COMPOSE_PREVIEW_OPTIONS_FQN } ?: return emptyList()
    val raw = ann.parameterValues.getValue("manualClockOptions") ?: return emptyList()
    val items =
      when (raw) {
        is Array<*> -> raw.filterIsInstance<AnnotationInfo>()
        is AnnotationInfo -> listOf(raw)
        else -> {
          // Some ClassGraph versions return a primitive or wrapper array.
          val len = runCatching { java.lang.reflect.Array.getLength(raw) }.getOrNull() ?: 0
          (0 until len).mapNotNull { java.lang.reflect.Array.get(raw, it) as? AnnotationInfo }
        }
      }
    return items.mapNotNull { it.parameterValues.getValue("advanceTimeMillis") as? Long }
  }

  /**
   * Resolves the `PreviewWrapperProvider` FQN for [method]. Uses `directOnly()` because
   * `method.annotationInfo` flattens meta-annotations, which would make direct-vs-hoisted
   * precedence depend on list order. A direct wrapper wins; otherwise one hoisted on a
   * multi-preview annotation.
   */
  private fun extractWrapperFqn(method: MethodInfo, scanResult: ScanResult): String? {
    val directAnnotations = method.annotationInfo?.directOnly()?.toList() ?: emptyList()
    directWrapperFqn(directAnnotations)?.let {
      return it
    }
    // Only our `@PreviewWrapperClass` can be hoisted; androidx's `@PreviewWrapper` is
    // FUNCTION-only.
    val visited = mutableSetOf<String>()
    for (ann in directAnnotations) {
      wrapperFromMetaAnnotation(ann, scanResult, visited)?.let {
        return it
      }
    }
    return null
  }

  /**
   * Wrapper FQN from direct annotations: androidx `@PreviewWrapper` (an [AnnotationClassRef])
   * first, then `@PreviewWrapperClass` (a String).
   */
  private fun directWrapperFqn(annotations: List<AnnotationInfo>): String? {
    annotations
      .firstOrNull { it.name == PREVIEW_WRAPPER_FQN }
      ?.let { ann ->
        // Read the FQN without classloading.
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
   * Searches a multi-preview annotation (recursively, cycle-guarded) for a hoisted wrapper,
   * skipping `@Preview` itself.
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
   * Reads `@AnimatedPreview` (at most one per function). Non-positive numeric fields fall back to
   * defaults.
   */
  private fun extractAnimationSpec(annotations: List<AnnotationInfo>): AnimationCapture? {
    val ann = annotations.firstOrNull { it.name == ANIMATED_PREVIEW_FQN } ?: return null
    val pv = ann.parameterValues
    // `0` is the auto-detect sentinel; negatives clamp to it.
    val durationMs = (pv.getValue("durationMs") as? Int)?.coerceAtLeast(0) ?: 0
    val frameIntervalMs = (pv.getValue("frameIntervalMs") as? Int)?.takeIf { it > 0 } ?: 33
    val showCurves = (pv.getValue("showCurves") as? Boolean) ?: true
    val formatName = (pv.getValue("format") as? AnnotationEnumValue)?.valueName
    return AnimationCapture(
      durationMs = durationMs,
      frameIntervalMs = frameIntervalMs,
      showCurves = showCurves,
      // GIF default here (unlike `@InteractionPreview`'s APNG) because consumers already reference
      // `renders/<id>.gif`.
      format = motionFormatOf(formatName, default = MotionFormat.GIF),
      caption = (pv.getValue("caption") as? String).orEmpty(),
    )
  }

  /**
   * Reads `@InteractionPreview` (one script per function — one recording). Returns `null` when
   * absent or when no targets remain: a script that dispatches nothing would be an animation
   * masquerading as an interaction.
   */
  private fun extractInteractionSpec(annotations: List<AnnotationInfo>): InteractionCapture? {
    val ann = annotations.firstOrNull { it.name == INTERACTION_PREVIEW_FQN } ?: return null
    val pv = ann.parameterValues
    val gestureName =
      (pv.getValue("gesture") as? AnnotationEnumValue)?.valueName ?: INTERACTION_GESTURE_TAP
    val gesture =
      when (gestureName) {
        INTERACTION_GESTURE_PRESS_AND_HOLD -> InteractionGesture.PRESS_AND_HOLD
        else -> InteractionGesture.TAP
      }
    // Drop negative indices (an all-negative list declines above). Order and repeats are meaningful
    // (`[0, 0, 0]` is a triple tap), so no sorting or de-dup.
    val targets =
      when (val raw = pv.getValue("targets")) {
        is IntArray -> raw.toList()
        is Array<*> -> raw.filterIsInstance<Int>()
        else -> emptyList()
      }.filter { it >= 0 }
    if (targets.isEmpty()) return null
    val formatName = (pv.getValue("format") as? AnnotationEnumValue)?.valueName
    return InteractionCapture(
      gesture = gesture,
      targets = targets,
      caption = (pv.getValue("caption") as? String).orEmpty(),
      holdMs = (pv.getValue("holdMs") as? Int)?.takeIf { it > 0 } ?: 600,
      gapMs = (pv.getValue("gapMs") as? Int)?.takeIf { it > 0 } ?: 700,
      leadInMs = (pv.getValue("leadInMs") as? Int)?.coerceAtLeast(0) ?: 250,
      frameIntervalMs = (pv.getValue("frameIntervalMs") as? Int)?.takeIf { it > 0 } ?: 16,
      format = motionFormatOf(formatName, default = MotionFormat.APNG),
    )
  }

  /**
   * Annotation enum name → [MotionFormat], falling back to [default] so a newer annotation artifact
   * can't break discovery.
   */
  private fun motionFormatOf(name: String?, default: MotionFormat): MotionFormat =
    when (name?.uppercase()) {
      "APNG" -> MotionFormat.APNG
      "GIF" -> MotionFormat.GIF
      else -> default
    }

  /**
   * The container an `@AnimatedPreview` is actually written in on this backend, so the manifest's
   * extension matches the bytes. The Gradle plugin passes `apngSupported = true` for both backends,
   * making this a no-op; only GIF-only callers (the CLI default, for Android renderers before
   * daemon 3.13.0) downgrade APNG to GIF.
   */
  internal fun resolveAnimationFormat(
    animation: AnimationCapture,
    apngSupported: Boolean,
    owner: String,
    warnings: MutableList<String>,
  ): AnimationCapture {
    if (animation.format != MotionFormat.APNG || apngSupported) return animation
    warnings.add(
      "composePreview: '$owner' asks for @AnimatedPreview(format = Apng), but this module's " +
        "render backend is declared GIF-only (--animated-preview-apng-supported false) — the " +
        "capture is written as GIF to a `.gif` output. The desktop renderer and the Android " +
        "renderer from compose-preview-daemon 3.13.0 honour the format."
    )
    return animation.copy(format = MotionFormat.GIF)
  }

  /** Which motion product a capture is — the key motion naming decisions are made on. */
  internal enum class MotionKind(
    /** Suffix that keeps this kind apart from another output on the same function. */
    val suffix: String
  ) {
    ANIMATION("_anim"),
    INTERACTION("_interaction"),
    FOCUS_GIF("_focus_gif"),
  }

  /**
   * The motion kind of [capture], read from its fields rather than its extension (an APNG may be
   * written as `.png`).
   */
  internal fun motionKindOf(capture: Capture): MotionKind? =
    when {
      capture.animation != null -> MotionKind.ANIMATION
      capture.interaction != null -> MotionKind.INTERACTION
      capture.focusGif != null -> MotionKind.FOCUS_GIF
      else -> null
    }

  /** `renders/<previewId><suffix>.<ext>`, using the format's own extension. */
  internal fun motionRenderOutput(previewId: String, suffix: String, format: MotionFormat): String =
    "renders/$previewId$suffix.${format.extension}"

  /**
   * Ensures no motion capture shares an output path with another capture in the plan, deciding by
   * kind. If a motion format ever used a still's extension, the motion capture takes its kind's
   * suffix (case-folded comparison). Non-colliding outputs are untouched.
   */
  internal fun separateMotionOutputs(captures: List<Capture>): List<Capture> {
    val claimed = mutableSetOf<String>()
    // Stills claim paths first; the motion capture moves aside.
    captures.filter { motionKindOf(it) == null }.forEach { claimed += it.renderOutput.lowercase() }
    return captures.map { capture ->
      val kind = motionKindOf(capture) ?: return@map capture
      var output = capture.renderOutput
      if (output.lowercase() in claimed) {
        output = insertRenderTag(output, kind.suffix)
      }
      claimed += output.lowercase()
      if (output == capture.renderOutput) capture else capture.copy(renderOutput = output)
    }
  }

  /**
   * Filename suffix for a [FocusCapture]: `step<n>_<direction>` in traversal mode (unique across
   * repeated directions), the tab index in indexed mode.
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
   * Reads `@FocusedPreview(indices, traverse, overlay)`: one [FocusCapture] per traversal step when
   * `traverse` is set, else per non-negative index (sorted, de-duplicated). Empty inputs yield no
   * captures.
   */
  /** Reads `@AmbientPreview` into a single [AmbientCapture], or `null`. One state per function. */
  private fun extractAmbientSpec(annotations: List<AnnotationInfo>): AmbientCapture? {
    val ann = annotations.firstOrNull { it.name == AMBIENT_PREVIEW_FQN } ?: return null
    val pv = ann.parameterValues
    val stateName =
      (pv.getValue("state") as? AnnotationEnumValue)?.valueName ?: AmbientCaptureState.Ambient.name
    val state = runCatching {
      AmbientCaptureState.valueOf(stateName)
    }
      .getOrDefault(AmbientCaptureState.Ambient)
    val burnIn = (pv.getValue("burnInProtectionRequired") as? Boolean) ?: false
    val lowBit = (pv.getValue("deviceHasLowBitAmbient") as? Boolean) ?: false
    return AmbientCapture(
      state = state,
      burnInProtectionRequired = burnIn,
      deviceHasLowBitAmbient = lowBit,
    )
  }

  /** Reads repeatable `@GlimmerEnvironmentPreview(environment)` post-capture metadata. */
  private fun extractGlimmerEnvironmentSpecs(
    annotations: List<AnnotationInfo>
  ): List<GlimmerEnvironmentCapture> {
    val infos = mutableListOf<AnnotationInfo>()
    for (ann in annotations) {
      when (ann.name) {
        GLIMMER_ENVIRONMENT_PREVIEW_FQN -> infos += ann
        GLIMMER_ENVIRONMENT_PREVIEW_CONTAINER_FQN ->
          when (val value = ann.parameterValues.getValue("value")) {
            is Array<*> -> infos += value.filterIsInstance<AnnotationInfo>()
            is AnnotationInfo -> infos += value
            else -> {
              val length = runCatching { java.lang.reflect.Array.getLength(value) }.getOrNull() ?: 0
              for (index in 0 until length) {
                (java.lang.reflect.Array.get(value, index) as? AnnotationInfo)?.let(infos::add)
              }
            }
          }
      }
    }
    return infos
      .mapNotNull { info ->
        val environmentName =
          (info.parameterValues.getValue("environment") as? AnnotationEnumValue)?.valueName
            ?: return@mapNotNull null
        runCatching { GlimmerEnvironmentCapture.valueOf(environmentName) }.getOrNull()
      }
      .distinct()
      // Annotation-table order isn't source order (ClassGraph may reverse repeated entries); sort
      // for stable names.
      .sortedBy { it.ordinal }
  }

  /**
   * Reads `@SettledPreview(afterMs, maxMs)`, or `null`. Clamped here — the single place the
   * manifest is written — so backends can't clamp differently: negative `afterMs` means auto;
   * `maxMs` is floored at one frame and capped at [MAX_SETTLE_MS].
   */
  private fun extractSettleSpec(annotations: List<AnnotationInfo>): SettleCapture? {
    val ann = annotations.firstOrNull { it.name == SETTLED_PREVIEW_FQN } ?: return null
    val afterMs = (ann.parameterValues.getValue("afterMs") as? Int ?: 0).coerceAtLeast(0)
    val maxMs =
      (ann.parameterValues.getValue("maxMs") as? Int ?: DEFAULT_SETTLE_MAX_MS).coerceIn(
        SETTLE_FRAME_MS,
        MAX_SETTLE_MS,
      )
    return SettleCapture(afterMs = afterMs.coerceAtMost(MAX_SETTLE_MS), maxMs = maxMs)
  }

  /**
   * Reads `@CaptureGutter` into [CaptureGutterDp], or `null` when absent or all-zero. Clamped here
   * like [extractSettleSpec], to `0..`[MAX_CAPTURE_GUTTER_DP].
   */
  private fun extractCaptureGutter(annotations: List<AnnotationInfo>): CaptureGutterDp? {
    val ann = annotations.firstOrNull { it.name == CAPTURE_GUTTER_FQN } ?: return null
    val pv = ann.parameterValues
    val all = (pv.getValue("all") as? Int) ?: 0
    fun edge(name: String): Int {
      val raw = (pv.getValue(name) as? Int) ?: INHERIT_GUTTER
      val resolved = if (raw == INHERIT_GUTTER) all else raw
      return resolved.coerceIn(0, MAX_CAPTURE_GUTTER_DP)
    }
    val gutter =
      CaptureGutterDp(
        start = edge("start"),
        top = edge("top"),
        end = edge("end"),
        bottom = edge("bottom"),
      )
    return gutter.takeUnless { it.isEmpty() }
  }

  /** Reads `@GestureHintPreview`, or `null`. One per function. */
  private fun extractGestureHintSpec(annotations: List<AnnotationInfo>): GestureHintCapture? {
    val ann = annotations.firstOrNull { it.name == GESTURE_HINT_PREVIEW_FQN } ?: return null
    val showHints = (ann.parameterValues.getValue("showHints") as? Boolean) ?: true
    return GestureHintCapture(showHints = showHints)
  }

  /**
   * Reads `@PermissionPreview`, or `null` when absent or contributing nothing usable. `null` rather
   * than an empty map, because an empty grant map would mean "deny everything", not "no override".
   */
  private fun extractPermissionSpec(
    annotations: List<AnnotationInfo>,
    owner: String,
    warnings: MutableList<String>,
  ): PermissionsCapture? {
    val ann = annotations.firstOrNull { it.name == PERMISSION_PREVIEW_FQN } ?: return null
    val entries = readStringArray(ann.parameterValues.getValue("grants"))
    val grants = parsePermissionGrants(entries, owner, warnings)
    return if (grants.isEmpty()) null else PermissionsCapture(grants = grants)
  }

  /**
   * Parses `"<permission>=<state>"` grant entries, split on the first `=`; `granted` / `denied` are
   * case-insensitive and trimmed.
   *
   * Malformed entries are dropped with a warning naming the accepted spellings, since the silent
   * symptom is a "granted" preview capturing the denied branch. Duplicates keep the first; only
   * disagreeing duplicates warn. `internal` for tests.
   */
  internal fun parsePermissionGrants(
    entries: List<String>,
    owner: String,
    warnings: MutableList<String>,
  ): Map<String, PermissionGrantCaptureState> {
    val grants = LinkedHashMap<String, PermissionGrantCaptureState>()
    for (entry in entries) {
      val separator = entry.indexOf('=')
      val permission = if (separator < 0) "" else entry.substring(0, separator).trim()
      val rawState = if (separator < 0) "" else entry.substring(separator + 1).trim()
      val state =
        when (rawState.lowercase()) {
          "granted" -> PermissionGrantCaptureState.GRANTED
          "denied" -> PermissionGrantCaptureState.DENIED
          else -> null
        }
      if (permission.isEmpty() || state == null) {
        warnings.add(
          "composePreview: '$owner' declares @PermissionPreview with the entry \"$entry\", which " +
            "is not a \"<permission>=<state>\" pair with a state of granted or denied " +
            "(case-insensitive) — e.g. \"android.permission.CAMERA=granted\". Ignoring it; " +
            "the preview will capture whatever branch the un-overridden permission check returns."
        )
        continue
      }
      val existing = grants.putIfAbsent(permission, state)
      if (existing != null && existing != state) {
        warnings.add(
          "composePreview: '$owner' declares @PermissionPreview with conflicting states for " +
            "\"$permission\" ($existing then $state) — a permission has one grant state per " +
            "capture. Keeping $existing."
        )
      }
    }
    return grants
  }

  /**
   * Source/target cell counts plus shared grid knobs for a `@LauncherWidgetResize` walk.
   * Point-to-point, so no cell-bound clamp.
   */
  internal data class LauncherWidgetResizeSpec(
    val from: Pair<Int, Int>,
    val to: Pair<Int, Int>,
    val cellSizeDp: Int?,
    val cellSpacingDp: Int?,
    val resizeOrder: LauncherWidgetCaptureResizeOrder,
    val frameDelayMs: Int,
    val launcherMode: Boolean,
  )

  /**
   * Whole-cell stops from `from` to `to` under [order]. Copy of the connector's
   * `launcherWidgetStops(...)` (not a discovery-time dependency); keep in sync.
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

  /** Reads `@LauncherWidgetResize`, or `null`. */
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
    val order = runCatching {
      LauncherWidgetCaptureResizeOrder.valueOf(orderName)
    }
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
   * Reads `@LauncherWidgetPreview`, or `null`; applied to every expansion. Optional `Int`s use `-1`
   * as "not set" (annotation params can't be nullable), mapped back to `null` so the connector
   * applies its defaults.
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
    val order = runCatching {
      LauncherWidgetCaptureResizeOrder.valueOf(orderName)
    }
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
   * A [FocusGifCapture] for `@FocusedPreview(gif = true)` with at least one step, else `null` (a
   * one-frame GIF animates nothing).
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
      // 1-based `step` disambiguates repeated directions. `pressed` is indexed-mode only.
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
    // `modes` arrives as `AnnotationEnumValue`s; compare by `.valueName` to avoid loading classes.
    val rawModes = pv.getValue("modes")
    val modes = readEnumArray(rawModes) { ScrollMode.valueOf(it) }
    if (modes.isEmpty()) return emptyList()
    val axis =
      (pv.getValue("axis") as? AnnotationEnumValue)?.valueName?.let {
        runCatching { ScrollAxis.valueOf(it) }.getOrNull()
      } ?: ScrollAxis.VERTICAL
    val maxScrollPx = (pv.getValue("maxScrollPx") as? Int)?.coerceAtLeast(0) ?: 0
    val reduceMotion = (pv.getValue("reduceMotion") as? Boolean) ?: true
    // Carried into every [ScrollCapture] for a uniform shape; `0` means the renderer default.
    val frameIntervalMs = (pv.getValue("frameIntervalMs") as? Int)?.coerceAtLeast(0) ?: 0
    // De-dup (`[END, END]` would collide) and sort by ordinal so TOP is captured before the
    // scroller is driven.
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

  // Maps an `Array<EnumT>` parameter by `.valueName`. ClassGraph may return a plain array, a single
  // value, or a typed array (as in [extractRoboTimings]).
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

  // Our preview-adjacent annotations that modify a preview rather than declare one. Their names
  // contain `Preview`, so the unexpandable-annotation heuristic below must exclude them. Direct
  // preview FQNs are excluded via [isDirectPreview].
  private val NON_EXPANDING_PREVIEW_FQNS =
    setOf(
      SCROLLING_PREVIEW_FQN,
      ANIMATED_PREVIEW_FQN,
      FOCUSED_PREVIEW_FQN,
      AMBIENT_PREVIEW_FQN,
      GLIMMER_ENVIRONMENT_PREVIEW_FQN,
      GLIMMER_ENVIRONMENT_PREVIEW_CONTAINER_FQN,
      GESTURE_HINT_PREVIEW_FQN,
      PERMISSION_PREVIEW_FQN,
      LAUNCHER_WIDGET_PREVIEW_FQN,
      LAUNCHER_WIDGET_RESIZE_FQN,
      OVERRIDE_VARIANT_FQN,
      OVERRIDE_VARIANT_CONTAINER_FQN,
      *PREVIEW_PARAMETER_FQNS.toTypedArray(),
      PREVIEW_WRAPPER_FQN,
      PREVIEW_WRAPPER_CLASS_FQN,
      ROBO_COMPOSE_PREVIEW_OPTIONS_FQN,
    )

  /**
   * FQNs in [annotations] that look like multi-preview annotations (name contains `Preview`) but
   * whose class is off the discovery classpath, so the preview would be dropped silently (#2613).
   *
   * Keyed on `getClassInfo == null`: without `enableExternalClasses()` ClassGraph returns null only
   * for unscanned classes, so a reachable non-preview annotation like `@PreviewOnly` isn't flagged.
   * `isExternalClass` is folded in defensively.
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
   * One `@Preview` expansion of a well-known AndroidX / Wear multi-preview annotation, the fallback
   * when its class is off the classpath (#2613).
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

  // Every wear `@Preview` sets showBackground / showSystemUi / black background and labels the
  // variant with `group` (verbatim from compose-ui-tooling).
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
   * Verbatim `@Preview` expansions of the well-known AndroidX / Wear multi-preview annotations,
   * consulted only when the annotation class is off the classpath (see [builtInExpansionFor]).
   * `@PreviewDynamicColors` is absent: its only axis is `wallpaper=`, which isn't modelled, so the
   * warning beats four identical PNGs.
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
   * Built-in expansion for [ann] only when its class is off the classpath; on-classpath copies
   * resolve from their real definitions, so shadowing works and nothing double-expands.
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
   * [PreviewParams] from a built-in spec, resolved the same way [extractPreviewParams] resolves a
   * real `@Preview`.
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
    // Only to resolve enum-typed knob parameters; see `ComposableSignature.knobsOf`.
    scanResult: ScanResult,
    wrapperClassName: String?,
    scrolls: List<ScrollCapture>,
    animation: AnimationCapture?,
    interaction: InteractionCapture?,
    focuses: List<FocusCapture>,
    focusGif: FocusGifCapture?,
    ambient: AmbientCapture?,
    glimmerEnvironments: List<GlimmerEnvironmentCapture>,
    settle: SettleCapture?,
    gestureHint: GestureHintCapture?,
    permissions: PermissionsCapture?,
    launcherWidget: LauncherWidgetCapture?,
    launcherWidgetResize: LauncherWidgetResizeSpec?,
    timings: List<Long>,
    previewParameter: Pair<String, Int>?,
    previewSourceFile: String?,
    inferredTargets: Lazy<List<PreviewTarget>>,
    inferredComponentTargets: Lazy<List<PreviewTarget>>,
  ): PreviewInfo {
    val params = extractPreviewParams(ann, wrapperClassName, previewParameter)
    return buildPreviewInfo(
      classInfo,
      method,
      params,
      scanResult,
      scrolls,
      animation,
      interaction,
      focuses,
      focusGif,
      ambient,
      glimmerEnvironments,
      settle,
      gestureHint,
      permissions,
      launcherWidget,
      launcherWidgetResize,
      timings,
      previewSourceFile,
      inferredTargets,
      inferredComponentTargets,
    )
  }

  /**
   * Assembles a [PreviewInfo] from resolved [params]: the shared tail of [makePreview] and the
   * built-in expansion path, so both fan out captures and infer targets identically.
   */
  private fun buildPreviewInfo(
    classInfo: ClassInfo,
    method: MethodInfo,
    params: PreviewParams,
    scanResult: ScanResult,
    scrolls: List<ScrollCapture>,
    animation: AnimationCapture?,
    interaction: InteractionCapture?,
    focuses: List<FocusCapture>,
    focusGif: FocusGifCapture?,
    ambient: AmbientCapture?,
    glimmerEnvironments: List<GlimmerEnvironmentCapture>,
    settle: SettleCapture?,
    gestureHint: GestureHintCapture?,
    permissions: PermissionsCapture?,
    launcherWidget: LauncherWidgetCapture?,
    launcherWidgetResize: LauncherWidgetResizeSpec?,
    timings: List<Long>,
    previewSourceFile: String?,
    inferredTargets: Lazy<List<PreviewTarget>>,
    inferredComponentTargets: Lazy<List<PreviewTarget>>,
  ): PreviewInfo {
    val fqn = "${classInfo.name}.${method.name}"
    val id = fqn + buildVariantSuffix(params)
    val outputPlan =
      buildOutputPlan(
        params.kind,
        id,
        scrolls,
        animation,
        interaction,
        focuses,
        focusGif,
        ambient,
        glimmerEnvironments,
        settle,
        gestureHint,
        permissions,
        launcherWidget,
        launcherWidgetResize,
        timings,
      )
    // Tile / notification previews aren't invoked through Compose; skipping keeps the bytecode walk
    // from running.
    val skipTargetInference =
      params.kind == PreviewKind.TILE ||
        params.kind == PreviewKind.NOTIFICATION ||
        params.kind == PreviewKind.GLANCE_APPWIDGET ||
        params.kind == PreviewKind.XR_SUBSPACE
    val targets = if (skipTargetInference) emptyList() else inferredTargets.value
    return PreviewInfo(
      id = id,
      functionName = method.name,
      className = classInfo.name,
      sourceFile = previewSourceFile,
      bodyLine = bodyLineOf(method),
      params = params,
      captures = outputPlan.captures,
      dataProducts = outputPlan.dataProducts,
      targets = targets,
      // Read off the method, so every expansion (including built-in ones) shares it.
      fixedTheme = method.annotationInfo.any { it.name == FIXED_THEME_FQN },
      includeInA11y =
        method.annotationInfo
          .firstOrNull { it.name == PREVIEW_HELPER_FQN }
          ?.let { annBoolean(it, "includeInA11y", default = true) } ?: true,
      // Knobs from defaulted value parameters, read off the method so every expansion shares them.
      knobs = ComposableSignature.knobsOf(classInfo, method, scanResult),
      // Skipped for the same non-composable kinds as `targets`.
      componentTargets = if (skipTargetInference) emptyList() else inferredComponentTargets.value,
    )
  }

  /**
   * First body line from `LineNumberTable`, or null when absent (ClassGraph reports `0`). Never the
   * last line: inlined code makes `maxLineNum` unreliable. See [PreviewInfo.bodyLine].
   */
  private fun bodyLineOf(method: MethodInfo): Int? = method.minLineNum.takeIf { it > 0 }

  // Module-relative source path (e.g. `src/main/kotlin/…/Previews.kt`), falling back to the
  // package-qualified path when source files weren't wired in.
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

  // Package-qualified source path, e.g. `com/example/samplewear/Previews.kt`. The bytecode
  // `SourceFile` is just the basename, which collides across packages.
  private fun packageQualifiedSourcePath(classInfo: ClassInfo): String? {
    val simpleName = classInfo.sourceFile ?: return null
    val pkg = classInfo.packageName.orEmpty()
    return if (pkg.isEmpty()) simpleName else "${pkg.replace('.', '/')}/$simpleName"
  }

  // Disambiguates multi-preview expansions without an explicit `name`, which would otherwise
  // collide. Prefers `group` (Horologist sets a readable one per variant), then device + fontScale
  // + uiMode.
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

  // Strips path-breaking characters. Spaces and dots are kept so ids stay lossless (dedup is by
  // id); render stems handle dots separately (`sanitiseSegments`).
  private fun sanitizeForPath(s: String): String = s.replace(Regex("""[/\\:*?"<>|]"""), "_")

  /**
   * Retargets a Wear module's device-less wrap-content previews from Studio's phone default
   * (2.625x, ~400dp) to the Wear screen ([DeviceDimensions.DEFAULT_WEAR], 227dp @ 2.0x). Previews
   * pinning `device` / `widthDp` / `heightDp` and preview ids are unchanged. No-op off Wear.
   *
   * Sets [PreviewParams.wrapSandboxWidthDp] / [PreviewParams.wrapSandboxHeightDp], not `widthDp` /
   * `heightDp`: both axes stay wrapped, so fill-width components size to the watch while everything
   * still crops to its measured bounds. Pinning the axes (#2373) left small components adrift on a
   * full watch canvas.
   *
   * [pinWearCanvas] ([Input.retargetWearPreviews]):
   * - `true` (default): sandbox to the 227dp watch screen at Wear density.
   * - `false`: keep the generic 400dp sandbox and only swap in Wear density, for widget/tile assets
   *   (#2670).
   *
   * Auto-detected glance-wear widgets ([isWearWidgetPreview]) always take the `false` branch, per
   * preview.
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
      val isWidget = isWearWidgetPreview(p)
      // A widget's canvas is its `WearWidgetParams`, so a `device` (already resolved into
      // `widthDp`/`heightDp`) is dropped along with those dims. See [isWearWidgetPreview].
      val widgetCanvas = isWidget && p.device != null
      if (
        p.kind == PreviewKind.COMPOSE &&
          (widgetCanvas || (p.device == null && p.widthDp == null && p.heightDp == null))
      ) {
        // `fillMaxWidth` inside a widget means "fill the widget", not the watch.
        val sandboxToWatch = pinWearCanvas && !isWidget
        if (sandboxToWatch) {
          // Wear screen + density as the wrap sandbox; both axes stay wrapped so the renderer still
          // crops.
          info.copy(
            params =
              p.copy(
                wrapSandboxWidthDp = wear.widthDp,
                wrapSandboxHeightDp = wear.heightDp,
                density = wear.density,
              )
          )
        } else {
          // Generic sandbox, Wear density (#2670). The device is dropped only for widgets;
          // honouring it would render the widget across the whole screen.
          if (widgetCanvas) {
            info.copy(
              params =
                p.copy(device = null, widthDp = null, heightDp = null, density = wear.density)
            )
          } else {
            info.copy(params = p.copy(density = wear.density))
          }
        }
      } else {
        info
      }
    }
  }

  /**
   * Glimmer modules are detected by dependency group (glasses have no manifest feature like Wear's
   * `type.watch`). A prefix so all `androidx.xr.glimmer` artifacts match.
   */
  private const val GLIMMER_COORDINATE_PREFIX = "androidx.xr.glimmer:"

  /**
   * True when this module compiles against `androidx.xr.glimmer` — see [GLIMMER_COORDINATE_PREFIX].
   */
  internal fun isGlimmerModule(input: Input): Boolean =
    input.dependencyJarCoordinates.values.any { it.startsWith(GLIMMER_COORDINATE_PREFIX) }

  /**
   * Measures a Glimmer module's device-less previews against the AI-glasses display
   * ([DeviceDimensions.DEFAULT_GLASSES], 960x720 @ 1.0x) instead of the phone sandbox. Density 1.0
   * is a calibration, since Glimmer sizes UI in visual angle.
   *
   * Like [retargetWearStickers], this sets the wrap sandbox, not `widthDp` / `heightDp`: pinning
   * the axes leaves small components adrift in a huge frame, while the phone sandbox gives
   * fill-width components a meaningless bound. No-op off Glimmer and on previews that pin their own
   * canvas.
   */
  internal fun retargetGlimmerStickers(
    isGlimmer: Boolean,
    previews: List<PreviewInfo>,
  ): List<PreviewInfo> {
    if (!isGlimmer) return previews
    val glasses = DeviceDimensions.DEFAULT_GLASSES
    return previews.map { info ->
      val p = info.params
      if (
        p.kind == PreviewKind.COMPOSE && p.device == null && p.widthDp == null && p.heightDp == null
      ) {
        info.copy(
          params =
            p.copy(
              wrapSandboxWidthDp = glasses.widthDp,
              wrapSandboxHeightDp = glasses.heightDp,
              density = glasses.density,
            )
        )
      } else {
        // An explicit device or size is honoured as on Wear.
        info
      }
    }
  }

  /**
   * `@PreviewParameter` provider package prefixes marking a **Wear widget** (anything under
   * `androidx.glance.wear.*`, which feed `WearWidgetParams`). Prefix-matched to survive alpha
   * package moves.
   */
  private val WEAR_WIDGET_PARAM_PROVIDER_PREFIXES = listOf("androidx.glance.wear.")

  /**
   * True when [params] is a glance-wear widget preview (provider from
   * [WEAR_WIDGET_PARAM_PROVIDER_PREFIXES]), which always crops to its bounds regardless of
   * `retargetWearPreviews` (#2670). A `device` doesn't disqualify it — samples declare a 1000dp
   * Studio scratch canvas — and [retargetWearStickers] drops it.
   */
  /**
   * The widget [preview] draws, or null ([PreviewWidget]). Glance Wear: drawn through a
   * widget-preview entry point ([PreviewTargetInference.drawsWearWidget], via the deferred [calls]
   * walk) or fed a glance-wear provider ([isWearWidgetPreview]). Launcher: a Glance app-widget
   * preview or `@LauncherWidgetPreview` / `@LauncherWidgetResize`.
   */
  internal fun widgetOf(
    preview: PreviewInfo,
    calls: Lazy<List<PreviewTargetInference.Invocation>>,
  ): PreviewWidget? {
    val params = preview.params
    if (
      params.kind == PreviewKind.GLANCE_APPWIDGET ||
        preview.captures.any { it.launcherWidget != null }
    )
      return PreviewWidget(PreviewWidget.HOST_LAUNCHER)
    if (params.kind != PreviewKind.COMPOSE) return null
    if (isWearWidgetPreview(params) || PreviewTargetInference.drawsWearWidget(calls.value))
      return PreviewWidget(PreviewWidget.HOST_WEAR, PreviewWidget.PROFILE_WEAR_WIDGETS)
    return null
  }

  private fun isWearWidgetPreview(params: PreviewParams): Boolean {
    val provider = params.previewParameterProviderClassName ?: return false
    return WEAR_WIDGET_PARAM_PROVIDER_PREFIXES.any { provider.startsWith(it) }
  }

  private fun extractPreviewParams(
    ann: AnnotationInfo,
    wrapperClassName: String?,
    previewParameter: Pair<String, Int>?,
  ): PreviewParams {
    // `@NotificationPreview` has no parameters, so return minimal params before the reads below
    // throw. `widthDp` pins the 400dp sandbox; otherwise the shade inflates at its ~320dp intrinsic
    // width (#1249).
    if (ann.name == NOTIFICATION_PREVIEW_FQN) {
      return PreviewParams(
        kind = PreviewKind.NOTIFICATION,
        widthDp = DeviceDimensions.SANDBOX_WIDTH_DP,
      )
    }
    // Glance's `Preview(widthDp, heightDp)`: the params arrived in 1.1.0-rc01, so read
    // optimistically.
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
    // The layout comes from the composed `Subspace`; `:renderer-xr` does the rest.
    if (ann.name == XR_SUBSPACE_PREVIEW_FQN) {
      return PreviewParams(kind = PreviewKind.XR_SUBSPACE)
    }
    val pv = ann.parameterValues
    val kind = if (ann.name == TILE_PREVIEW_FQN) PreviewKind.TILE else PreviewKind.COMPOSE
    val device = (pv.getValue("device") as? String)?.ifBlank { null }
    val rawWidth = (pv.getValue("widthDp") as? Int)?.takeIf { it > 0 }
    val rawHeight = (pv.getValue("heightDp") as? Int)?.takeIf { it > 0 }
    val showSystemUi = (pv.getValue("showSystemUi") as? Boolean) ?: false
    // Studio parity: with a device or system UI frame, resolve dims and density up-front; otherwise
    // keep raw values, where null means wrap to intrinsic.
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
      // Pin Studio's default density (2.625x) instead of Robolectric's mdpi, so wrap-content
      // previews match device-based and desktop renders.
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
      // Tiles aren't composable, so @PreviewWrapper and @PreviewParameter don't apply.
      wrapperClassName = if (kind == PreviewKind.TILE) null else wrapperClassName,
      previewParameterProviderClassName =
        if (kind == PreviewKind.TILE) null else previewParameter?.first,
      previewParameterLimit = previewParameter?.second ?: Int.MAX_VALUE,
      kind = kind,
    )
  }
}
