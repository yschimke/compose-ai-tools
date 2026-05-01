package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import java.io.File
import org.jetbrains.skia.EncodedImageFormat

/**
 * Compose-Desktop render body for the preview daemon — the per-preview inner loop that turns a
 * resolved class+method reference into a PNG on disk.
 *
 * **Duplicated from
 * [`renderer-desktop`'s `DesktopRendererMain`](../../../../../../../renderer-desktop/src/main/kotlin/ee/schimke/composeai/renderer/DesktopRendererMain.kt).**
 * Per
 * [DESIGN.md § 7](../../../../../../docs/daemon/DESIGN.md#7-sharing-strategy--what-crosses-the-boundary)
 * the v1 render body lives in two places — the standalone renderer (existing CLI / Gradle path) and
 * the daemon — so the daemon doesn't depend on the renderer's `main()`-only entry point. v2's
 * reconciliation extracts the body into a shared helper. Until then any change to the render body
 * landed here also has to land in `:renderer-desktop`'s `renderPreview` (and vice versa); the bench
 * + CI pixel-diff (D2.2 / harness S1) will catch divergence.
 *
 * **What's duplicated, what isn't.** This is the "small composable, no PreviewParameter, no
 * wrapper" subset — the daemon's v1 surface only renders single previews from existing
 * `previews.json` discovery. The fan-out / `@PreviewParameter` / `@PreviewWrapper` paths from
 * `DesktopRendererMain` stay behind the standalone renderer for now; B-desktop.1.7+ revisits if the
 * harness needs them.
 *
 * **Threading contract.** Called from [DesktopHost]'s single render thread. [ImageComposeScene] is
 * instantiated *per render* (not held warm across renders) — see
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#9-sandbox-lifecycle--no-mid-render-cancellation).
 * The "warm runtime" the daemon amortises here is the JVM + JIT + Skiko native bundle, not the
 * scene itself. Holding `ImageComposeScene` across renders would require restoring its content tree
 * between previews; the per-render construction cost is dominated by Skiko's `Surface` allocation,
 * which is cheap once the JVM has been through it once.
 *
 * **No-mid-render-cancellation invariant** (DESIGN § 9). [ImageComposeScene.close] is invoked from
 * a `try/finally` so the underlying Skia `Surface` always releases, even when the render body
 * throws. This is the desktop equivalent of Android's `bitmap.recycle()` discipline; without the
 * `finally`, a thrown render leaks one Skia `Surface` per submission until the JVM exits.
 */
class RenderEngine(
  /**
   * Directory under which PNG files are written. Defaults to the `composeai.render.outputDir`
   * system property (mirrors the Android side's contract); falls back to
   * `${user.dir}/.compose-preview-history/daemon-renders/` so unit tests don't need to set the
   * property.
   */
  private val outputDir: File =
    File(
      System.getProperty(OUTPUT_DIR_PROP)
        ?: "${System.getProperty("user.dir")}/.compose-preview-history/daemon-renders"
    )
) {

  /**
   * Renders one preview to a PNG on disk and returns a [RenderResult] populated with the absolute
   * `pngPath` and a `metrics` map containing `tookMs` (wall-clock of the render body, excluding
   * queue wait).
   *
   * @param spec what to render — class FQN, method name, sandbox dimensions.
   * @param requestId opaque id forwarded to the [RenderResult] so [DesktopHost]'s queue can demux.
   * @param classLoader classloader used to resolve [spec]'s class. B2.0 — see
   *   [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md). Pass the disposable child
   *   loader from [UserClassLoaderHolder.currentChildLoader] so a recompiled `Foo.kt`'s fresh
   *   bytecode is read on the next render. Defaults to the engine's own classloader for
   *   backward-compatible callers (unit tests that pre-load fixtures into the host classloader).
   */
  fun render(
    spec: RenderSpec,
    requestId: Long,
    classLoader: ClassLoader =
      RenderEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
    /**
     * B2.3 — per-host measurement context. The host owns its own [SandboxLifecycleStats] (start
     * time + render counter); the engine simply reads from it at metrics-population time and bumps
     * the counter once per render. Defaults to a fresh per-call instance for unit tests that drive
     * the engine directly without a host wrapper — the resulting metrics are still populated, just
     * with a sandbox-age that resets on every test render.
     */
    sandboxStats: SandboxLifecycleStats = SandboxLifecycleStats(),
  ): RenderResult {
    outputDir.mkdirs()
    val outputFile = File(outputDir, "${spec.outputBaseName}.png")
    val startNs = System.nanoTime()

    val clazz = Class.forName(spec.className, true, classLoader)
    val composableMethod: ComposableMethod = clazz.getDeclaredComposableMethod(spec.functionName)

    // Self-diagnostic — surfaces in the VS Code extension's output channel as `[daemon stderr] …`.
    // Pairs with `[classloader] swap requested` / `allocate child loader` lines from
    // [UserClassLoaderHolder]. If `classFile` doesn't advance across saves the daemon is
    // re-rendering against bytecode that wasn't actually recompiled.
    val fingerprint =
      UserClassLoaderHolder.classFileFingerprint(classLoader, spec.className)
        ?: "fingerprint unavailable (class not on a file: URL)"
    System.err.println(
      "compose-ai-daemon: [render] ${spec.className}#${spec.functionName} " +
        "loaderId=${System.identityHashCode(classLoader).toString(16)} classFile=$fingerprint"
    )

    // Install the child classloader as the context classloader for the duration of the render
    // dispatch. Compose's reflection paths (notably PreviewParameter providers — see
    // CLASSLOADER.md § Risks 2) consult the context classloader; without this install they would
    // miss user classes that aren't on the parent's classpath. Restored in `finally` so the host
    // render-thread invariants outside the render are unaffected.
    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader

    val scene =
      ImageComposeScene(
        width = spec.widthPx,
        height = spec.heightPx,
        density = Density(spec.density),
      )
    try {
      scene.setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
          val bgColor =
            when {
              spec.backgroundColor != 0L -> Color(spec.backgroundColor.toInt())
              spec.showBackground -> Color.White
              else -> Color.Transparent
            }
          Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
            // Trampoline through a @Composable so the reflective invocation lands inside the
            // running composition. Mirrors `:renderer-desktop`'s InvokeComposable.
            InvokeComposable(composableMethod)
          }
        }
      }

      // Render two frames so any LaunchedEffect / animations have a tick to settle. Same reasoning
      // as `:renderer-desktop`'s renderPreview.
      scene.render()
      val image = scene.render()

      val pngData =
        image.encodeToData(EncodedImageFormat.PNG)
          ?: error("Failed to encode image to PNG for ${spec.className}.${spec.functionName}")

      outputFile.parentFile?.mkdirs()
      outputFile.writeBytes(pngData.bytes)
    } finally {
      // DESIGN § 9: always close the scene, even on render-body exceptions. Without this, a thrown
      // render leaks one Skia `Surface` per submission until JVM exit. The desktop counterpart of
      // Android's bitmap.recycle() discipline.
      try {
        scene.close()
      } finally {
        // Restore the pre-render context classloader regardless of how the body completed.
        Thread.currentThread().contextClassLoader = previousContext
      }
    }

    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    val metrics = SandboxMeasurement.collect(sandboxStats, tookMs = tookMs)
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(classLoader),
      classLoaderName = classLoader.javaClass.name,
      pngPath = outputFile.absolutePath,
      metrics = metrics,
    )
  }

  companion object {
    /**
     * System property carrying the absolute path of the renders directory. Same name the Android
     * side uses; the gradle plugin's daemon launch descriptor sets it once at JVM start.
     */
    const val OUTPUT_DIR_PROP: String = "composeai.render.outputDir"
  }
}

/**
 * What [RenderEngine.render] needs to produce a single PNG. Decoupled from the protocol's
 * `RenderRequest` so the engine has no dependency on the JSON-RPC envelope shapes.
 *
 * For v1 the daemon's [DesktopHost] parses this out of [RenderRequest.Render.payload] using a
 * trivial `key=value;key=value` format (see [parseFromPayload]); a typed `previewId` field on
 * `RenderRequest` is a documented follow-up that requires widening the renderer-agnostic surface in
 * `:daemon:core`.
 */
data class RenderSpec(
  /** Fully-qualified name of the class containing the @Preview function. */
  val className: String,
  /** Method name of the @Preview function (parameterless overload). */
  val functionName: String,
  val widthPx: Int = 320,
  val heightPx: Int = 320,
  val density: Float = 2.0f,
  val showBackground: Boolean = true,
  val backgroundColor: Long = 0L,
  /**
   * Raw `@Preview(device = …)` string when known. The desktop render path is currently
   * shape-agnostic (no circular crop — that's an Android/Robolectric-only mechanism), but the field
   * is carried so the wire format stays identical to `:daemon:android`'s `RenderSpec` and a single
   * payload can drive both backends.
   */
  val device: String? = null,
  /** Stem used for the output PNG filename (e.g. "preview-A" → "<outputDir>/preview-A.png"). */
  val outputBaseName: String = "${className.substringAfterLast('.')}-$functionName",
  /**
   * BCP-47 locale tag override. Carried on the wire for parity with `:daemon:android`'s
   * `RenderSpec`; Compose Desktop has no Android-style resource qualifier system so this is a no-op
   * on the desktop render path today. A future change can route it into `LocalConfiguration` /
   * `androidx.compose.ui.text.intl.Locale.current` if a desktop consumer needs it.
   */
  val localeTag: String? = null,
  /**
   * Font scale multiplier override. No-op on desktop today (would route through `LocalDensity`'s
   * `fontScale` field); carried for wire parity with `:daemon:android`.
   */
  val fontScale: Float? = null,
  /** Light/dark mode override. No-op on desktop today; carried for wire parity. */
  val uiMode: SpecUiMode? = null,
  /** Portrait/landscape override. Desktop only honours derived size; carried for wire parity. */
  val orientation: SpecOrientation? = null,
) {

  enum class SpecUiMode {
    LIGHT,
    DARK,
  }

  enum class SpecOrientation {
    PORTRAIT,
    LANDSCAPE,
  }

  companion object {

    /**
     * Parses [RenderRequest.Render.payload] — a `;`-delimited `key=value` string — into a
     * [RenderSpec]. Recognised keys: `className`, `functionName`, `widthPx`, `heightPx`, `density`,
     * `showBackground`, `backgroundColor`, `device`, `outputBaseName`, `localeTag`, `fontScale`,
     * `uiMode` (`light`/`dark`), `orientation` (`portrait`/`landscape`). `className` and
     * `functionName` are required; everything else falls back to the defaults on this data class.
     *
     * Keeping this stringly-typed for v1 is deliberate (per the task brief). When `RenderRequest`
     * grows a typed `previewId: String?` field, [DesktopHost] will look the spec up in
     * `previews.json` rather than parsing it out of the payload — at which point this helper goes
     * away.
     */
    fun parseFromPayload(payload: String): RenderSpec {
      val map = mutableMapOf<String, String>()
      for (entry in payload.split(';')) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        val eq = trimmed.indexOf('=')
        if (eq <= 0) continue
        map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
      }
      val className =
        map["className"] ?: error("RenderSpec.parseFromPayload: missing 'className' in '$payload'")
      val functionName =
        map["functionName"]
          ?: error("RenderSpec.parseFromPayload: missing 'functionName' in '$payload'")
      val defaults = RenderSpec(className = className, functionName = functionName)
      return RenderSpec(
        className = className,
        functionName = functionName,
        widthPx = map["widthPx"]?.toIntOrNull() ?: defaults.widthPx,
        heightPx = map["heightPx"]?.toIntOrNull() ?: defaults.heightPx,
        density = map["density"]?.toFloatOrNull() ?: defaults.density,
        showBackground = map["showBackground"]?.toBoolean() ?: defaults.showBackground,
        backgroundColor = map["backgroundColor"]?.toLongOrNull() ?: defaults.backgroundColor,
        device = map["device"]?.takeIf { it.isNotBlank() } ?: defaults.device,
        outputBaseName = map["outputBaseName"] ?: defaults.outputBaseName,
        localeTag = map["localeTag"]?.takeIf { it.isNotBlank() },
        fontScale = map["fontScale"]?.toFloatOrNull(),
        uiMode =
          when (map["uiMode"]?.lowercase()) {
            "light" -> SpecUiMode.LIGHT
            "dark" -> SpecUiMode.DARK
            else -> null
          },
        orientation =
          when (map["orientation"]?.lowercase()) {
            "portrait" -> SpecOrientation.PORTRAIT
            "landscape" -> SpecOrientation.LANDSCAPE
            else -> null
          },
      )
    }
  }
}

/**
 * Tiny @Composable trampoline that invokes [composableMethod] reflectively against the current
 * composer. Mirrors `:renderer-desktop`'s private `InvokeComposable`; kept private+top-level so the
 * compose-compiler plugin recognises it as a composable function.
 */
@androidx.compose.runtime.Composable
private fun InvokeComposable(composableMethod: ComposableMethod) {
  composableMethod.invoke(currentComposer, null)
}
