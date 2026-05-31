package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes `ee/schimke/composeai/renderer/robolectric.properties` into a generated resources
 * directory added to the `composePreviewRender` test classpath.
 *
 * Robolectric reads package-level `robolectric.properties` from the classpath and merges its fields
 * into each test's effective config. `RobolectricRenderTestBase` deliberately carries NO `@Config`
 * or `@GraphicsMode` annotation so this file is the sole source of truth for those settings — see
 * that class's KDoc for the #142 motivation.
 *
 * Fields written unconditionally:
 * - `sdk=N` — the Android SDK level Robolectric targets. Resolved from the inputs below in priority
 *   order; see [resolveSdk] for the chain and the clamp-vs-fail behaviour around Robolectric's
 *   supported range (issue #1248).
 * - `graphicsMode=NATIVE` — routes Compose capture through HardwareRenderer, the only path that
 *   replays RenderNodes correctly for `roborazzi`'s `captureRoboImage`.
 * - `shadows=…ShadowFontsContractCompat` — globally registers the GoogleFont shadow so
 *   `Font(GoogleFont(...), provider)` renders without the consumer having to add `@Config(shadows =
 *   [...])`.
 *
 * Fields that depend on [useConsumerApplication]:
 * - Default ([useConsumerApplication] = false): the file pins
 *   `application=android.app.Application`, so Robolectric creates a plain Application and SKIPS the
 *   consumer's `onCreate()`. Consumer-side init that depends on platform features Robolectric
 *   doesn't emulate (BridgingManager on non-Wear sandboxes, Firebase, WorkManager, Play Services)
 *   no longer runs during preview rendering.
 * - Opt-out ([useConsumerApplication] = true): the file is written without `application=`, so
 *   Robolectric falls back to the manifest- declared Application class. Intended for preview setups
 *   that genuinely require their custom Application (e.g. Hilt's generated testing application).
 */
@CacheableTask
abstract class GenerateRobolectricPropertiesTask : DefaultTask() {
  @get:Input abstract val useConsumerApplication: Property<Boolean>

  /**
   * Explicit `composePreview.sdkVersion = N` override. When present, used verbatim — validated
   * strictly against [MIN_SUPPORTED_SDK]..[MAX_SUPPORTED_SDK] (the consumer asked for a specific
   * level, so an out-of-range value is a configuration error worth failing fast on).
   */
  @get:Input @get:Optional abstract val sdkOverride: Property<Int>

  /**
   * Consumer's `android.compileSdk`, captured by [AndroidPreviewSupport] in `finalizeDsl`. Used
   * when [sdkOverride] is absent. Auto-detected values above [MAX_SUPPORTED_SDK] are CLAMPED to
   * [MAX_SUPPORTED_SDK] with a build warning rather than failing the task — a consumer on
   * `compileSdk = 37` (e.g. for a transitive minCompileSdk requirement) should still get the
   * best-effort render at the highest SDK Robolectric supports, not a build break.
   */
  @get:Input @get:Optional abstract val consumerCompileSdk: Property<Int>

  /**
   * Static fallback when neither [sdkOverride] nor [consumerCompileSdk] is set. Should equal
   * [DEFAULT_SDK] in normal wiring; only reachable when AGP didn't supply a `compileSdk` and the
   * user didn't override (unit-test setups).
   */
  @get:Input abstract val defaultSdk: Property<Int>

  /**
   * Overrides [MAX_SUPPORTED_SDK] at task execution time. Production consumers leave this unset and
   * get the constant — `36` for Robolectric 4.16.1. The SDK compatibility matrix's snapshot probe
   * cells set this to lift the ceiling alongside forcing a Robolectric snapshot that actually ships
   * the higher API; without that pairing, lifting the ceiling silently turns runtime sandbox
   * failures into "passed validation" then a worse runtime error.
   *
   * Don't expose on the public `composePreview` extension — this is a matrix-internal escape hatch,
   * not a knob production builds should reach for. See `docs/SDK_COMPATIBILITY.md` and the
   * `composeai.matrix.maxSupportedSdk` property in `:samples:sdk-matrix`.
   */
  @get:Input @get:Optional abstract val maxSupportedSdkOverride: Property<Int>

  /**
   * Major version of the JVM that will run the `composePreviewRender` test (where Robolectric
   * actually bootstraps the sandbox). Wired by [AndroidPreviewSupport] from that Test task's
   * resolved `javaLauncher` — the consumer's test toolchain, the SDK matrix's
   * `composeai.matrix.jvmToolchain`, or the Gradle build JVM when none is set. Left unset only by
   * unit tests that drive [resolveSdk] directly, in which case it defaults to the running JVM.
   *
   * Robolectric 4.16.1 refuses to bootstrap an SDK [SDK_REQUIRING_JAVA_21] (Baklava) sandbox unless
   * the test JVM is JDK [MIN_JAVA_FOR_SDK_36]+ — `DefaultSdkProvider.verifySupportedSdk` throws an
   * opaque `UnsupportedOperationException`, which fails *every* preview render rather than surfacing
   * a clear message. When the build runs on an older JDK we lower the effective ceiling one level
   * (to [MAX_SUPPORTED_SDK_BELOW_JAVA_21]) so a consumer on `compileSdk = 36` still renders at 35
   * instead of producing zero PNGs. The daemon path pins the same level for the same reason — see
   * `RobolectricHost.ANDROID_SDK`.
   */
  @get:Input @get:Optional abstract val buildJavaMajor: Property<Int>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val sdkLevel = resolveSdk()
    val dir = outputDir.get().asFile.resolve("ee/schimke/composeai/renderer")
    dir.deleteRecursively()
    dir.mkdirs()
    val file = dir.resolve("robolectric.properties")
    // `shadows=` registers our GoogleFont shadow globally for every test
    // in this package. See [ShadowFontsContractCompat].
    val shadowsLine = "shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat"
    // `sdk=` and `graphicsMode=` live here (not on `@Config`/`@GraphicsMode`
    // on `RobolectricRenderTestBase`) to avoid JUnit's `AnnotationParser`
    // resolving `@Config.application()`'s `android.app.Application` default
    // during test-class discovery — that resolution fails under some
    // JVM/classloader combinations and produces `ClassNotFoundException:
    // android.app.Application`. See issue #142.
    val sdkLine = "sdk=$sdkLevel"
    val graphicsLine = "graphicsMode=NATIVE"
    val body =
      if (useConsumerApplication.get()) {
        """
            |# Generated by compose-ai-tools.
            |# useConsumerApplication=true — Robolectric falls back to the manifest-declared Application.
            |$sdkLine
            |$graphicsLine
            |$shadowsLine
            |"""
          .trimMargin()
      } else {
        """
            |# Generated by compose-ai-tools.
            |# Override the consumer's Application to a Robolectric-safe stub so
            |# preview rendering skips app-lifecycle side effects (DI, BridgingManager,
            |# Firebase, WorkManager, …). Set composePreview.useConsumerApplication = true
            |# to restore the manifest-declared Application.
            |application=android.app.Application
            |$sdkLine
            |$graphicsLine
            |$shadowsLine
            |"""
          .trimMargin()
      }
    file.writeText(body)
  }

  /**
   * Priority chain: [sdkOverride] (strict) → [consumerCompileSdk] (clamp to range with warning) →
   * [defaultSdk]. Internal so the test can drive it directly.
   */
  internal fun resolveSdk(): Int {
    val javaMajor = buildJavaMajor.orNull ?: Runtime.version().feature()
    // Robolectric's max SDK for *this build* — the static/overridden jar ceiling, then lowered when
    // the test JVM is too old to bootstrap the top level (see [buildJavaMajor]).
    val jarCeiling = maxSupportedSdkOverride.orNull ?: MAX_SUPPORTED_SDK
    val ceiling =
      if (javaMajor < MIN_JAVA_FOR_SDK_36) minOf(jarCeiling, MAX_SUPPORTED_SDK_BELOW_JAVA_21)
      else jarCeiling
    if (sdkOverride.isPresent) {
      val v = sdkOverride.get()
      if (v !in MIN_SUPPORTED_SDK..ceiling) {
        throw GradleException(
          "compose-preview: composePreview.sdkVersion = $v is outside the supported range " +
            "($MIN_SUPPORTED_SDK..$ceiling for this Robolectric build${jdkCeilingSuffix(javaMajor, ceiling, jarCeiling)}). " +
            "Pick a value inside that range or remove the override to let the plugin auto-detect " +
            "from android.compileSdk."
        )
      }
      return v
    }
    if (consumerCompileSdk.isPresent) {
      val raw = consumerCompileSdk.get()
      if (raw < MIN_SUPPORTED_SDK) {
        throw GradleException(
          "compose-preview: consumer compileSdk = $raw is below Robolectric's floor " +
            "($MIN_SUPPORTED_SDK). Raise android.compileSdk or set composePreview.sdkVersion = N " +
            "to pin a supported level explicitly."
        )
      }
      if (raw > ceiling) {
        val jdkClamped = ceiling < jarCeiling && raw <= jarCeiling
        logger.warn(
          if (jdkClamped) {
            "compose-preview: consumer compileSdk = $raw needs JDK $MIN_JAVA_FOR_SDK_36+ to render " +
              "(Robolectric refuses an SDK > $MAX_SUPPORTED_SDK_BELOW_JAVA_21 sandbox on JDK " +
              "$javaMajor); clamping the Robolectric sandbox to $ceiling. Run the render on JDK " +
              "$MIN_JAVA_FOR_SDK_36+ to render at $raw, or set composePreview.sdkVersion = N to " +
              "silence this warning."
          } else {
            "compose-preview: consumer compileSdk = $raw exceeds Robolectric's max supported SDK " +
              "($ceiling for this Robolectric build); clamping the Robolectric sandbox " +
              "to $ceiling. Rendering may still fail if your APK's <uses-sdk> requires a " +
              "newer framework. Set composePreview.sdkVersion = N to silence this warning."
          }
        )
        return ceiling
      }
      return raw
    }
    return defaultSdk.get()
  }

  /**
   * Tail clause for the strict-override error message, naming the JDK gate when the effective
   * ceiling was lowered below the jar ceiling because the build JVM is older than
   * [MIN_JAVA_FOR_SDK_36]. Empty otherwise so the JDK-21+ path reads unchanged.
   */
  private fun jdkCeilingSuffix(javaMajor: Int, ceiling: Int, jarCeiling: Int): String =
    if (ceiling < jarCeiling)
      "; SDK > $ceiling needs JDK $MIN_JAVA_FOR_SDK_36+, this build is on JDK $javaMajor"
    else ""

  companion object {
    /**
     * Floor of Robolectric's supported `sdk=` range. Robolectric 4.16.x ships
     * `android-all-instrumented` jars for API 21 (LOLLIPOP) and above; setting `sdk=` below this
     * fails sandbox bootstrap with a missing-jar error rather than the nicer message this task
     * emits.
     */
    internal const val MIN_SUPPORTED_SDK: Int = 21

    /**
     * Ceiling of the bundled Robolectric's supported `sdk=` range. Pinned to API 36 because
     * `gradle/libs.versions.toml` pins `robolectric = "4.16.1"`, whose `android-all-instrumented`
     * jars top out at API 36 (Baklava). Auto-detected `compileSdk` values above this clamp here so
     * consumers don't trip a runtime sandbox failure (`IllegalArgumentException: API level N is not
     * available`) — they get a build warning and a best-effort render at API 36 instead.
     *
     * Bump in lockstep with `libs.robolectric` in `gradle/libs.versions.toml`. The SDK matrix's
     * snapshot probe cells (see `docs/SDK_COMPATIBILITY.md`) lift this via the
     * [maxSupportedSdkOverride] escape hatch when paired with a Robolectric snapshot that actually
     * ships the higher API; production consumers shouldn't reach for that knob.
     *
     * This is the *jar* ceiling. Robolectric additionally refuses to bootstrap an SDK 36 sandbox
     * unless the test JVM is JDK 21+ (`DefaultSdkProvider.verifySupportedSdk` throws a bare
     * `UnsupportedOperationException`, not the clear message you'd hope for). On older JDKs the
     * effective ceiling drops to [MAX_SUPPORTED_SDK_BELOW_JAVA_21] — see [buildJavaMajor] and
     * [resolveSdk] — so a `compileSdk = 36` consumer on JDK 17 renders at 35 instead of failing
     * every preview.
     */
    internal const val MAX_SUPPORTED_SDK: Int = 36

    /**
     * First Android SDK level Robolectric gates behind the test JVM's Java version. Robolectric
     * 4.16.1 refuses to bootstrap a sandbox for [SDK_REQUIRING_JAVA_21] (API 36, Baklava) unless the
     * JVM is JDK [MIN_JAVA_FOR_SDK_36]+ — `DefaultSdkProvider.verifySupportedSdk` throws a bare
     * `UnsupportedOperationException`, failing every preview render instead of surfacing a clear
     * message.
     */
    internal const val SDK_REQUIRING_JAVA_21: Int = 36

    /** JDK major version Robolectric requires before it will bootstrap an [SDK_REQUIRING_JAVA_21] sandbox. */
    internal const val MIN_JAVA_FOR_SDK_36: Int = 21

    /**
     * Effective Robolectric SDK ceiling when the render JVM is older than [MIN_JAVA_FOR_SDK_36]. One
     * level below [SDK_REQUIRING_JAVA_21] so a consumer on `compileSdk = 36` still renders (at 35)
     * under JDK 17 rather than failing every preview. Mirrors `RobolectricHost.ANDROID_SDK`.
     */
    internal const val MAX_SUPPORTED_SDK_BELOW_JAVA_21: Int = 35

    /**
     * Default Robolectric SDK when neither the consumer's `android.compileSdk` nor
     * `composePreview.sdkVersion` is set. Matches the minimum we expect any AGP consumer to be on;
     * AGP itself raises a build error if `compileSdk` is unset, so in practice this default is only
     * reached by unit tests that drive the task directly without an `android { … }` block.
     */
    internal const val DEFAULT_SDK: Int = 35
  }
}
