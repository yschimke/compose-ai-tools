package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.CaptureResult
import ee.schimke.composeai.previewdata.PreviewDataProduct
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewParams
import ee.schimke.composeai.previewdata.PreviewResult
import ee.schimke.composeai.previewdriver.GradleTaskDisposition
import ee.schimke.composeai.previewdriver.GradleTaskOutcome
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A preview that produced no PNG must be reported from the renderer's `<render>.png.error.json`
 * sidecar when there is one, not with the generic build-wiring paragraph. Covers
 * [diagnoseMissingRenders] (who owns the preview, what it did, which sidecars it could have
 * written) and [formatMissingRenderReport] (the wording).
 */
class MissingRenderReportTest {

  private lateinit var workspace: File
  private lateinit var moduleDir: File

  @BeforeTest
  fun setUp() {
    workspace = createTempDirectory("missing-render-report").toFile()
    moduleDir = workspace.resolve("app").apply { mkdirs() }
  }

  @AfterTest
  fun tearDown() {
    workspace.deleteRecursively()
  }

  // The outer throwable is a reflective InvocationTargetException with only tooling frames; the
  // cause chain names the real problem (an on-device-only Wear class) and passes through the
  // consumer's source.
  private val wearStackTrace =
    """
    java.lang.reflect.InvocationTargetException
    	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
    	at ee.schimke.composeai.renderer.KeyboardDataProduct.AroundComposable${'$'}lambda${'$'}2(KeyboardDataProduct.kt:148)
    Caused by: java.lang.NoClassDefFoundError: com/google/wear/services/ambient/AmbientComponentState
    	at com.example.wear.ambient.AmbientAwareActivity.rememberAmbientState(AmbientAwareActivity.kt:76)
    	at com.example.wear.WearAppKt.WearApp(WearApp.kt:31)
    	at androidx.compose.runtime.ComposerImpl.doCompose(Composer.kt:3300)
    """
      .trimIndent()

  private fun sidecarJson(stackTrace: String = wearStackTrace) =
    """
    {
      "schema": "compose-preview-error/v1",
      "exception": "java.lang.reflect.InvocationTargetException",
      "message": "",
      "topAppFrame": {
        "file": "KeyboardDataProduct.kt",
        "line": 148,
        "function": "AroundComposable${'$'}lambda${'$'}2"
      },
      "stackTrace": ${escapeJson(stackTrace)}
    }
    """
      .trimIndent()

  private fun escapeJson(text: String): String =
    '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'

  private val previewId = "com.example.wear.WearAppKt.WearAppPreview_Devices - Large Round"

  private fun manifests(renderOutput: String = "renders/WearAppPreview.png") =
    listOf(
      PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
        PreviewManifest(
          module = ":app",
          variant = "debug",
          previews =
            listOf(
              PreviewInfo(
                id = previewId,
                functionName = "WearAppPreview",
                className = "com.example.wear.WearAppKt",
                captures = listOf(Capture(renderOutput = renderOutput)),
              )
            ),
        )
    )

  private fun missingResult(id: String = previewId) =
    PreviewResult(
      id = id,
      module = ":app",
      functionName = "WearAppPreview",
      className = "com.example.wear.WearAppKt",
      captures = listOf(CaptureResult(pngPath = null)),
    )

  private fun writeSidecar(relative: String, json: String = sidecarJson()) {
    val file = moduleDir.resolve("build/compose-previews/$relative$RENDER_ERROR_SIDECAR_SUFFIX")
    file.parentFile.mkdirs()
    file.writeText(json)
  }

  @Test
  fun `an error sidecar beside the expected output replaces the NO-SOURCE guess`() {
    writeSidecar("renders/WearAppPreview.png")

    val entries = diagnoseMissingRenders(listOf(missingResult()), manifests())
    val message = formatMissingRenderReport(entries, total = 35)

    // The render task ran (it wrote a sidecar), so the build-wiring guess must not appear.
    assertFalse(message.contains("NO-SOURCE"), message)
    assertFalse(message.contains("testClassesDirs"), message)
    assertContains(message, "1 of 35 preview(s)")
    // The real cause from the `Caused by:` chain, not the reflective wrapper.
    assertContains(message, "NoClassDefFoundError")
    assertContains(message, "com/google/wear/services/ambient/AmbientComponentState")
    assertContains(message, "chain: InvocationTargetException → NoClassDefFoundError")
    // And the consumer's own frame rather than the data-product frame `topAppFrame` recorded.
    assertContains(message, "AmbientAwareActivity.kt:76")
    assertFalse(message.contains("KeyboardDataProduct.kt"), message)
    assertContains(message, "rendered and then threw")
  }

  @Test
  fun `no sidecar keeps the historical NO-SOURCE guidance`() {
    // Nothing written to disk: the render task really was skipped.
    val entries = diagnoseMissingRenders(listOf(missingResult()), manifests())
    assertNull(entries.single().sidecars.firstOrNull()?.sidecar)

    val message = formatMissingRenderReport(entries, total = 35)

    assertContains(message, "NO-SOURCE")
    assertContains(message, "testClassesDirs")
    assertContains(message, previewId)
    assertFalse(message.contains("rendered and then threw"), message)
  }

  @Test
  fun `a mixed run reports both causes and never blames the wiring for the thrower`() {
    writeSidecar("renders/WearAppPreview.png")
    // The second preview isn't in the manifest and has no sidecar anywhere — the skip case.
    val skipped = missingResult(id = "com.example.wear.WearAppKt.OtherPreview")
    val entries = diagnoseMissingRenders(listOf(missingResult(), skipped), manifests())
    assertNotNull(entries.first().sidecars.firstOrNull())
    assertTrue(entries.last().sidecars.isEmpty())

    val message = formatMissingRenderReport(entries, total = 35)

    assertContains(message, "NoClassDefFoundError")
    // The skip-class entry keeps its own guidance, scoped to the previews it applies to.
    assertContains(message, "No sidecar from this run for 1 preview(s)")
    assertContains(message, "NO-SOURCE")
    assertContains(message, "1 preview(s) rendered and then threw")
  }

  @Test
  fun `the sidecar is found beside a data product output too`() {
    // A preview whose only declared output is a data product still gets its sidecar found.
    val manifests =
      listOf(
        PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
          PreviewManifest(
            module = ":app",
            variant = "debug",
            previews =
              listOf(
                PreviewInfo(
                  id = previewId,
                  functionName = "WearAppPreview",
                  className = "com.example.wear.WearAppKt",
                  captures = listOf(Capture(renderOutput = "")),
                  dataProducts =
                    listOf(PreviewDataProduct(kind = "gif", output = "data/anim/Wear.gif")),
                )
              ),
          )
      )
    writeSidecar("data/anim/Wear.gif")

    val entries = diagnoseMissingRenders(listOf(missingResult()), manifests)

    assertNotNull(entries.single().sidecars.firstOrNull()?.sidecar)
  }

  @Test
  fun `an unreadable or foreign sidecar is treated as absent`() {
    val png = moduleDir.resolve("build/compose-previews/renders/WearAppPreview.png")
    png.parentFile.mkdirs()

    assertNull(readRenderErrorSidecar(png), "no sidecar on disk")

    writeSidecar("renders/WearAppPreview.png", json = "{ not json at all ")
    assertNull(readRenderErrorSidecar(png), "unparseable sidecar")

    writeSidecar(
      "renders/WearAppPreview.png",
      json = """{"schema":"some-other-tool/v9","exception":"Boom","stackTrace":""}""",
    )
    assertNull(readRenderErrorSidecar(png), "foreign schema")

    writeSidecar("renders/WearAppPreview.png")
    assertEquals(
      "java.lang.reflect.InvocationTargetException",
      readRenderErrorSidecar(png)?.exception,
    )
  }

  @Test
  fun `the cause chain is read outermost-first with the root last`() {
    val chain = causeChainOf(wearStackTrace)
    assertEquals(1, chain.size)
    assertEquals("java.lang.NoClassDefFoundError", chain.single().exception)
    assertEquals("com/google/wear/services/ambient/AmbientComponentState", chain.single().message)

    val nested =
      wearStackTrace +
        "\nCaused by: java.lang.ClassNotFoundException: com.google.wear.services.ambient." +
        "AmbientComponentState\n\tat java.base/jdk.internal.loader.ClassLoaders" +
        "${'$'}AppClassLoader.loadClass(ClassLoaders.java:641)"
    assertEquals("java.lang.ClassNotFoundException", rootCauseOf(nested)?.exception)
    assertEquals(2, causeChainOf(nested).size)
    // No `Caused by:` at all — the outermost throwable is the whole story.
    assertNull(rootCauseOf("java.lang.IllegalStateException: boom\n\tat A.b(A.kt:1)"))
  }

  @Test
  fun `the preferred frame is the deepest one in the preview's own package`() {
    val frame = preferredAppFrame(wearStackTrace, "com.example.wear.WearAppKt")
    assertNotNull(frame)
    // `com.example.wear.ambient` is a sibling package found by walking prefixes outward, deeper in
    // the cause chain than `WearAppKt.WearApp`.
    assertEquals("AmbientAwareActivity.kt", frame.file)
    assertEquals(76, frame.line)
    assertEquals("rememberAmbientState", frame.function)

    // No shared package: the chooser declines and the caller falls back to `topAppFrame`.
    assertNull(preferredAppFrame(wearStackTrace, "zz.unrelated.PreviewsKt"))
    assertNull(preferredAppFrame(wearStackTrace, "NoPackagePreviews"))
  }

  @Test
  fun `frame lines with no source location are skipped`() {
    val trace =
      """
      java.lang.RuntimeException: boom
      	at com.example.wear.Gen.invoke(Unknown Source)
      	at com.example.wear.Real.render(Real.kt:12)
      """
        .trimIndent()
    val frame = preferredAppFrame(trace, "com.example.wear.WearAppKt")
    assertEquals("Real.kt", frame?.file)
    assertEquals(12, frame?.line)
  }

  @Test
  fun `a native-load diagnosis is surfaced alongside the exception`() {
    val json =
      """
      {
        "schema": "compose-preview-error/v1",
        "exception": "java.lang.ExceptionInInitializerError",
        "message": "",
        "diagnosis": "skiko's native library could not be loaded (libGL.so.1 missing)",
        "stackTrace": "java.lang.ExceptionInInitializerError\n\tat org.jetbrains.skia.Surface.<clinit>(Surface.kt:1)"
      }
      """
        .trimIndent()
    writeSidecar("renders/WearAppPreview.png", json)

    val message =
      formatMissingRenderReport(diagnoseMissingRenders(listOf(missingResult()), manifests()), 35)

    assertContains(message, "ExceptionInInitializerError")
    assertContains(message, "libGL.so.1 missing")
  }

  // ---- Follow-ups to #3779's review (issue #3741) ----

  private fun simpleSidecarJson(exception: String, message: String, stackTrace: String) =
    """
    {
      "schema": "compose-preview-error/v1",
      "exception": ${escapeJson(exception)},
      "message": ${escapeJson(message)},
      "stackTrace": ${escapeJson(stackTrace)}
    }
    """
      .trimIndent()

  /** A two-capture time fan-out: `500ms` and `1000ms`, each with its own would-be output. */
  private fun fanoutManifests() =
    listOf(
      PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
        PreviewManifest(
          module = ":app",
          variant = "debug",
          previews =
            listOf(
              PreviewInfo(
                id = previewId,
                functionName = "WearAppPreview",
                className = "com.example.wear.WearAppKt",
                captures =
                  listOf(
                    Capture(advanceTimeMillis = 500, renderOutput = "renders/Wear_500ms.png"),
                    Capture(advanceTimeMillis = 1000, renderOutput = "renders/Wear_1000ms.png"),
                  ),
              )
            ),
        )
    )

  private fun fanoutResult() =
    PreviewResult(
      id = previewId,
      module = ":app",
      functionName = "WearAppPreview",
      className = "com.example.wear.WearAppKt",
      captures =
        listOf(
          CaptureResult(advanceTimeMillis = 500, pngPath = null),
          CaptureResult(advanceTimeMillis = 1000, pngPath = null),
        ),
    )

  @Test
  fun `each missing capture keeps its own sidecar`() {
    // Two captures failing differently; collapsing to one sidecar would misattribute the 500ms
    // exception.
    writeSidecar(
      "renders/Wear_500ms.png",
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "no theme provided",
        "java.lang.IllegalStateException: no theme provided\n" +
          "\tat com.example.wear.WearAppKt.WearApp(WearApp.kt:31)",
      ),
    )
    writeSidecar(
      "renders/Wear_1000ms.png",
      simpleSidecarJson(
        "java.lang.NullPointerException",
        "animation target was null",
        "java.lang.NullPointerException: animation target was null\n" +
          "\tat com.example.wear.WearAppKt.WearAnim(WearApp.kt:57)",
      ),
    )

    val entries = diagnoseMissingRenders(listOf(fanoutResult()), fanoutManifests())
    assertEquals(2, entries.single().sidecars.size)

    val message = formatMissingRenderReport(entries, total = 2)

    assertContains(message, "IllegalStateException")
    assertContains(message, "no theme provided")
    assertContains(message, "NullPointerException")
    assertContains(message, "animation target was null")
    // Each exception is tied to the output that produced it.
    assertContains(message, "renders/Wear_500ms.png — threw IllegalStateException")
    assertContains(message, "renders/Wear_1000ms.png — threw NullPointerException")
  }

  @Test
  fun `one throwable across several outputs still reports one line`() {
    // One broken composable fails every output identically, so identical sidecars collapse
    // unlabelled.
    val json =
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "no theme provided",
        "java.lang.IllegalStateException: no theme provided\n" +
          "\tat com.example.wear.WearAppKt.WearApp(WearApp.kt:31)",
      )
    writeSidecar("renders/Wear_500ms.png", json)
    writeSidecar("renders/Wear_1000ms.png", json)

    val message =
      formatMissingRenderReport(
        diagnoseMissingRenders(listOf(fanoutResult()), fanoutManifests()),
        2,
      )

    assertEquals(1, message.lines().count { it.contains("threw IllegalStateException") }, message)
    assertFalse(message.contains("renders/Wear_500ms.png —"), message)
  }

  @Test
  fun `a sidecar left by an earlier run is never reported as this run's finding`() {
    // `composePreviewRender` was skipped (NO-SOURCE), so the sidecar is last run's; "rendered and
    // then threw" would be false.
    writeSidecar("renders/WearAppPreview.png")

    val entries =
      diagnoseMissingRenders(
        listOf(missingResult()),
        manifests(),
        outcomes("composePreviewRender" to GradleTaskDisposition.SKIPPED),
      )
    val message = formatMissingRenderReport(entries, total = 35)

    assertFalse(message.contains("rendered and then threw"), message)
    assertFalse(message.contains("the build wiring is fine"), message)
    assertContains(message, "did not run in this invocation")
    assertContains(message, "earlier run — threw NoClassDefFoundError")
    // …and the wiring guidance returns as the live hypothesis.
    assertContains(message, "NO-SOURCE")
    assertContains(message, "testClassesDirs")
  }

  @Test
  fun `a sidecar from a render that did run keeps the thrown-here wording`() {
    writeSidecar("renders/WearAppPreview.png")

    val entries =
      diagnoseMissingRenders(
        listOf(missingResult()),
        manifests(),
        outcomes("composePreviewRender" to GradleTaskDisposition.SUCCESS),
      )
    val message = formatMissingRenderReport(entries, total = 35)

    assertContains(message, "rendered and then threw")
    assertContains(message, "the build wiring is fine")
    assertFalse(message.contains("earlier run"), message)
    assertFalse(message.contains("NO-SOURCE"), message)
  }

  /**
   * A `kind=LOTTIE` preview: Android renders it from `composePreviewRenderLottie` into
   * `lottie-renders/`, never from the Robolectric task.
   */
  private fun lottieManifests() =
    listOf(
      PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
        PreviewManifest(
          module = ":app",
          variant = "debug",
          previews =
            listOf(
              PreviewInfo(
                id = previewId,
                functionName = "WearAppPreview",
                className = "com.example.wear.WearAppKt",
                params = PreviewParams(kind = "LOTTIE"),
                captures = listOf(Capture(renderOutput = "lottie-renders/Wear.png")),
              )
            ),
        )
    )

  private fun outcomes(vararg entries: Pair<String, GradleTaskDisposition>) =
    entries.associate { (task, disposition) ->
      ":app:$task" to GradleTaskOutcome(":app:$task", disposition)
    }

  @Test
  fun `the owning task and what it did are resolved separately`() {
    // Identity comes from the manifest; behaviour only from the build, so it carries provenance and
    // "did not run" requires an observed disposition.
    val skipped = outcomes("composePreviewRender" to GradleTaskDisposition.SKIPPED)
    val mainTask =
      RendererTask("composePreviewRender", ":app:composePreviewRender", RendererTaskKind.MAIN)
    assertEquals(mainTask, ownerTaskFor(":app", "COMPOSE", skipped))
    // The gradle path is carried with and without its leading colon depending on the caller.
    assertEquals(mainTask, ownerTaskFor("app", "COMPOSE", skipped))
    assertEquals(false, runOf("COMPOSE", skipped).ownerRan)

    for (disposition in
      listOf(
        GradleTaskDisposition.SUCCESS,
        GradleTaskDisposition.UP_TO_DATE,
        GradleTaskDisposition.FROM_CACHE,
        GradleTaskDisposition.FAILED,
      )) {
      assertEquals(
        true,
        runOf("COMPOSE", outcomes("composePreviewRender" to disposition)).ownerRan,
        "$disposition",
      )
    }

    // Another module's skip says nothing about this one: Unobserved, `ownerRan` null.
    assertEquals(Evidence.Unobserved, runOf("COMPOSE", skipped, module = ":other").ownerRun)
    assertEquals(Evidence.Unobserved, runOf("COMPOSE", emptyMap()).ownerRun)
    assertNull(runOf("COMPOSE", emptyMap()).ownerRan)
  }

  @Test
  fun `Lottie and SVG are owned by their own renderer task, not Robolectric`() {
    // Android renders `kind=LOTTIE` / `kind=SVG` from separate tasks, so a NO-SOURCE
    // `composePreviewRender` says nothing about them; the verdict names the task that actually owns
    // them.
    val robolectricSkipped =
      outcomes(
        "composePreviewRender" to GradleTaskDisposition.SKIPPED,
        "composePreviewRenderLottie" to GradleTaskDisposition.SUCCESS,
        "composePreviewRenderSvg" to GradleTaskDisposition.SUCCESS,
      )
    assertEquals(
      RendererTask(
        "composePreviewRenderLottie",
        ":app:composePreviewRenderLottie",
        RendererTaskKind.KIND_SPECIFIC,
        "LOTTIE",
      ),
      ownerTaskFor(":app", "LOTTIE", robolectricSkipped),
    )
    assertEquals(
      RendererTask(
        "composePreviewRenderSvg",
        ":app:composePreviewRenderSvg",
        RendererTaskKind.KIND_SPECIFIC,
        "SVG",
      ),
      ownerTaskFor(":app", "SVG", robolectricSkipped),
    )
    assertEquals(true, runOf("LOTTIE", robolectricSkipped).ownerRan)
    assertEquals(true, runOf("SVG", robolectricSkipped).ownerRan)
    // …while an ordinary Compose preview in the same module is stale.
    assertEquals(false, runOf("COMPOSE", robolectricSkipped).ownerRan)

    // And the converse: the Lottie task skipped while Robolectric ran.
    val lottieSkipped =
      outcomes(
        "composePreviewRender" to GradleTaskDisposition.SUCCESS,
        "composePreviewRenderLottie" to GradleTaskDisposition.SKIPPED,
      )
    assertEquals(false, runOf("LOTTIE", lottieSkipped).ownerRan)
    assertEquals(true, runOf("COMPOSE", lottieSkipped).ownerRan)

    // On desktop `composePreviewRender` renders every kind, so it is the owner.
    val desktop = outcomes("composePreviewRender" to GradleTaskDisposition.SUCCESS)
    assertEquals(
      RendererTask("composePreviewRender", ":app:composePreviewRender", RendererTaskKind.MAIN),
      ownerTaskFor(":app", "LOTTIE", desktop),
    )
    assertEquals(true, runOf("LOTTIE", desktop).ownerRan)
    assertEquals(
      false,
      runOf("SVG", outcomes("composePreviewRender" to GradleTaskDisposition.SKIPPED)).ownerRan,
    )
  }

  /** The diagnosis of a bare preview of [kind] in [module], for owner / run assertions. */
  private fun runOf(
    kind: String,
    taskOutcomes: Map<String, GradleTaskOutcome>,
    module: String = ":app",
  ): PreviewDiagnosis =
    diagnose(
      result = missingResult().copy(module = module, params = PreviewParams(kind = kind)),
      module = null,
      preview = null,
      taskOutcomes = taskOutcomes,
    )

  @Test
  fun `the skipped-task diagnosis names the task that actually skipped`() {
    // The Lottie task skipped, so the Lottie sidecar is stale — but blaming `composePreviewRender`
    // and `testClassesDirs` would be wrong: it ran, doesn't render Lottie, and never reports
    // NO-SOURCE.
    val lottieManifests = lottieManifests()
    writeSidecar("lottie-renders/Wear.png")

    val message =
      missingRenderReport(
        missing = listOf(missingResult().copy(params = PreviewParams(kind = "LOTTIE"))),
        manifests = lottieManifests,
        total = 1,
        taskOutcomes =
          outcomes(
            "composePreviewRender" to GradleTaskDisposition.SUCCESS,
            "composePreviewRenderLottie" to GradleTaskDisposition.SKIPPED,
          ),
      )

    // Qualified with the module: one task *name* is many tasks in a multi-module render.
    assertContains(message, "`:app:composePreviewRenderLottie` did not run in this invocation")
    assertContains(message, "earlier run — threw NoClassDefFoundError")
    // The remedy is the one that fits the task that skipped...
    assertContains(message, "composePreview { enabled = false }")
    assertContains(message, "kind=LOTTIE")
    // …and never the Robolectric remedy.
    assertFalse(message.contains("testClassesDirs"), message)
    assertFalse(message.contains("NO-SOURCE"), message)
    assertFalse(message.contains("`composePreviewRender` did not run"), message)
  }

  @Test
  fun `a Lottie failure is not labelled stale when Robolectric was NO-SOURCE`() {
    // The Lottie renderer wrote this sidecar this run; calling it an earlier run's would be wrong.
    writeSidecar("lottie-renders/Wear.png")
    val lottieResult = missingResult().copy(params = PreviewParams(kind = "LOTTIE"))

    val message =
      missingRenderReport(
        missing = listOf(lottieResult),
        manifests = lottieManifests(),
        total = 1,
        taskOutcomes =
          outcomes(
            "composePreviewRender" to GradleTaskDisposition.SKIPPED,
            "composePreviewRenderLottie" to GradleTaskDisposition.SUCCESS,
          ),
      )

    assertContains(message, "rendered and then threw")
    assertFalse(message.contains("earlier run"), message)
    assertFalse(message.contains("testClassesDirs"), message)
  }

  @Test
  fun `renderer guidance is per module, not per task name`() {
    // Every Android module has its own `composePreviewRenderLottie`; grouping by name alone merged
    // modules with different outcomes.
    val otherDir = workspace.resolve("feature").apply { mkdirs() }
    val otherId = "com.example.wear.FeatureKt.FeaturePreview"
    val manifests =
      lottieManifests() +
        (PreviewModule(gradlePath = ":feature", projectDir = otherDir) to
          PreviewManifest(
            module = ":feature",
            variant = "debug",
            previews =
              listOf(
                PreviewInfo(
                  id = otherId,
                  functionName = "FeaturePreview",
                  className = "com.example.wear.FeatureKt",
                  params = PreviewParams(kind = "LOTTIE"),
                  captures = listOf(Capture(renderOutput = "lottie-renders/Feature.png")),
                )
              ),
          ))
    // `:app`'s Lottie task skipped (stale sidecar); `:feature`'s ran and produced nothing.
    writeSidecar("lottie-renders/Wear.png")
    val taskOutcomes =
      mapOf(
        ":app:composePreviewRenderLottie" to
          GradleTaskOutcome(":app:composePreviewRenderLottie", GradleTaskDisposition.SKIPPED),
        ":feature:composePreviewRenderLottie" to
          GradleTaskOutcome(":feature:composePreviewRenderLottie", GradleTaskDisposition.SUCCESS),
      )

    val message =
      missingRenderReport(
        missing =
          listOf(
            missingResult().copy(params = PreviewParams(kind = "LOTTIE")),
            missingResult(id = otherId)
              .copy(module = ":feature", params = PreviewParams(kind = "LOTTIE")),
          ),
        manifests = manifests,
        total = 2,
        taskOutcomes = taskOutcomes,
      )

    // The stale sidecar belongs to `:app`'s task alone — `:feature`'s ran.
    assertContains(message, "`:app:composePreviewRenderLottie` did not run in this invocation")
    assertFalse(message.contains("`:feature:composePreviewRenderLottie` did not run"), message)
    // Two paragraphs, each naming its own module and counting only its own previews.
    assertContains(
      message,
      "`:app:composePreviewRenderLottie` renders every `kind=LOTTIE` preview in :app (1 here)",
    )
    assertContains(
      message,
      "`:feature:composePreviewRenderLottie` renders every `kind=LOTTIE` preview in :feature (1 here)",
    )
    assertFalse(message.contains("(2 here)"), message)
    assertFalse(message.contains("in this module"), message)
  }

  @Test
  fun `the legacy default stem is only probed when the manifest declares no output`() {
    // The preview moved from `renders/<id>.png` to a fan-out output; the old sidecar is never
    // cleaned (`cleanStaleRenders` only walks `png`/`gif`), so the default stem must not be probed
    // alongside.
    writeSidecar(
      "renders/Wear_500ms.png",
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "no theme provided",
        "java.lang.IllegalStateException: no theme provided\n" +
          "\tat com.example.wear.WearAppKt.WearApp(WearApp.kt:31)",
      ),
    )
    writeSidecar(
      "renders/$previewId.png",
      simpleSidecarJson(
        "java.lang.NoSuchMethodError",
        "long gone",
        "java.lang.NoSuchMethodError: long gone\n" +
          "\tat com.example.wear.WearAppKt.Removed(WearApp.kt:9)",
      ),
    )
    val declared =
      listOf(
        PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
          PreviewManifest(
            module = ":app",
            variant = "debug",
            previews =
              listOf(
                PreviewInfo(
                  id = previewId,
                  functionName = "WearAppPreview",
                  className = "com.example.wear.WearAppKt",
                  captures = listOf(Capture(renderOutput = "renders/Wear_500ms.png")),
                )
              ),
          )
      )

    val entries = diagnoseMissingRenders(listOf(missingResult()), declared)
    assertEquals(listOf("renders/Wear_500ms.png"), entries.single().sidecars.map { it.output })

    val message = formatMissingRenderReport(entries, total = 1)
    assertContains(message, "IllegalStateException")
    assertFalse(message.contains("NoSuchMethodError"), message)
    assertFalse(message.contains("long gone"), message)
  }

  @Test
  fun `a blank capture output keeps the default stem as a candidate`() {
    // A capture with no `renderOutput` resolves to `renders/<id>.png` on Android, which also
    // anchors the per-preview sidecar; with a data product declared too, the default stem must
    // still be probed.
    val blankCaptureWithProduct =
      listOf(
        PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
          PreviewManifest(
            module = ":app",
            variant = "debug",
            previews =
              listOf(
                PreviewInfo(
                  id = previewId,
                  functionName = "WearAppPreview",
                  className = "com.example.wear.WearAppKt",
                  captures = listOf(Capture(renderOutput = "")),
                  dataProducts =
                    listOf(PreviewDataProduct(kind = "gif", output = "data/anim/Wear.gif")),
                )
              ),
          )
      )
    writeSidecar("renders/$previewId.png")

    val entries = diagnoseMissingRenders(listOf(missingResult()), blankCaptureWithProduct)

    assertEquals(listOf("renders/$previewId.png"), entries.single().sidecars.map { it.output })
    val message = formatMissingRenderReport(entries, total = 1)
    assertContains(message, "NoClassDefFoundError")
    assertFalse(message.contains("NO-SOURCE"), message)
  }

  @Test
  fun `a blank capture after a declared one does not reopen the default stem`() {
    // Android anchors the preview-level sidecar on the first capture only, so a blank second
    // capture can't produce a fresh `renders/<id>.png` sidecar; anything there is stale.
    val declaredThenBlank =
      listOf(
        PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
          PreviewManifest(
            module = ":app",
            variant = "debug",
            previews =
              listOf(
                PreviewInfo(
                  id = previewId,
                  functionName = "WearAppPreview",
                  className = "com.example.wear.WearAppKt",
                  captures = listOf(Capture(renderOutput = "renders/Wear_500ms.png"), Capture()),
                )
              ),
          )
      )
    writeSidecar(
      "renders/Wear_500ms.png",
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "no theme provided",
        "java.lang.IllegalStateException: no theme provided\n" +
          "\tat com.example.wear.WearAppKt.WearApp(WearApp.kt:31)",
      ),
    )
    writeSidecar(
      "renders/$previewId.png",
      simpleSidecarJson(
        "java.lang.NoSuchMethodError",
        "long gone",
        "java.lang.NoSuchMethodError: long gone\n" +
          "\tat com.example.wear.WearAppKt.Removed(WearApp.kt:9)",
      ),
    )

    val entries = diagnoseMissingRenders(listOf(missingResult()), declaredThenBlank)

    assertEquals(listOf("renders/Wear_500ms.png"), entries.single().sidecars.map { it.output })
    val message = formatMissingRenderReport(entries, total = 1)
    assertContains(message, "IllegalStateException")
    assertFalse(message.contains("NoSuchMethodError"), message)
  }

  @Test
  fun `the default stem still answers for a preview the manifest does not describe`() {
    // A manifest predating `renderOutput` still renders to `renders/<id>.png`; find its sidecar
    // there.
    writeSidecar("renders/$previewId.png")

    val bare =
      listOf(
        PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
          PreviewManifest(
            module = ":app",
            variant = "debug",
            previews =
              listOf(
                PreviewInfo(
                  id = previewId,
                  functionName = "WearAppPreview",
                  className = "com.example.wear.WearAppKt",
                  captures = listOf(Capture(renderOutput = "")),
                )
              ),
          )
      )

    assertNotNull(
      diagnoseMissingRenders(listOf(missingResult()), bare).single().sidecars.firstOrNull()?.sidecar
    )

    // …including for a preview the manifest doesn't describe.
    val unknownId = "com.example.wear.WearAppKt.OtherPreview"
    writeSidecar("renders/$unknownId.png")
    assertNotNull(
      diagnoseMissingRenders(listOf(missingResult(id = unknownId)), manifests())
        .single()
        .sidecars
        .firstOrNull()
    )
  }

  @Test
  fun `a PreviewParameter fan-out's per-value sidecars are found`() {
    // A parameterised preview writes one sidecar per provider value
    // (`renders/Wear_Alice.png.error.json`), which neither the template nor the default stem points
    // at.
    val parameterised =
      listOf(
        PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
          PreviewManifest(
            module = ":app",
            variant = "debug",
            previews =
              listOf(
                PreviewInfo(
                  id = previewId,
                  functionName = "WearAppPreview",
                  className = "com.example.wear.WearAppKt",
                  params =
                    PreviewParams(
                      previewParameterProviderClassName = "com.example.wear.NamesProvider"
                    ),
                  captures = listOf(Capture(renderOutput = "renders/Wear.png")),
                ),
                // A sibling that owns `renders/Wear_Bob.png`; the fan-out glob must not adopt it.
                PreviewInfo(
                  id = "com.example.wear.WearAppKt.WearBob",
                  functionName = "WearBob",
                  className = "com.example.wear.WearAppKt",
                  captures = listOf(Capture(renderOutput = "renders/Wear_Bob.png")),
                ),
              ),
          )
      )
    writeSidecar(
      "renders/Wear_Alice.png",
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "Alice has no avatar",
        "java.lang.IllegalStateException: Alice has no avatar\n" +
          "\tat com.example.wear.WearAppKt.WearApp(WearApp.kt:31)",
      ),
    )
    writeSidecar(
      "renders/Wear_Bob.png",
      simpleSidecarJson(
        "java.lang.NoSuchMethodError",
        "the sibling's own failure",
        "java.lang.NoSuchMethodError: the sibling's own failure\n" +
          "\tat com.example.wear.WearAppKt.WearBob(WearApp.kt:44)",
      ),
    )

    val entry = diagnoseMissingRenders(listOf(missingResult()), parameterised).single()

    assertEquals(listOf("renders/Wear_Alice.png"), entry.sidecars.map { it.output })
    val message = formatMissingRenderReport(listOf(entry), total = 1)
    assertContains(message, "Alice has no avatar")
    assertFalse(message.contains("the sibling's own failure"), message)
    assertFalse(message.contains("NO-SOURCE"), message)
  }

  /** A parameterised preview whose template is [template], plus any [siblings] in the manifest. */
  private fun parameterisedManifests(
    template: String,
    vararg siblings: Pair<String, String>,
    dataProducts: List<PreviewDataProduct> = emptyList(),
  ) =
    listOf(
      PreviewModule(gradlePath = ":app", projectDir = moduleDir) to
        PreviewManifest(
          module = ":app",
          variant = "debug",
          previews =
            listOf(
              PreviewInfo(
                id = previewId,
                functionName = "WearAppPreview",
                className = "com.example.wear.WearAppKt",
                params =
                  PreviewParams(previewParameterProviderClassName = "com.example.wear.Names"),
                captures = listOf(Capture(renderOutput = template)),
                dataProducts = dataProducts,
              )
            ) +
              siblings.map { (id, output) ->
                PreviewInfo(
                  id = id,
                  functionName = id.substringAfterLast('.'),
                  className = "com.example.wear.WearAppKt",
                  params =
                    PreviewParams(previewParameterProviderClassName = "com.example.wear.Names"),
                  captures = listOf(Capture(renderOutput = output)),
                )
              },
        )
    )

  @Test
  fun `a more specific sibling template owns its own fan-out rows`() {
    // `Foo_Dark_Alice.png` matches `Foo_` but is `Foo_Dark`'s row; the shared
    // `PreviewResultBuilder` rule resolves it.
    writeSidecar(
      "renders/Wear_Alice.png",
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "Alice has no avatar",
        "java.lang.IllegalStateException: Alice has no avatar\n\tat com.example.wear.W.f(W.kt:1)",
      ),
    )
    writeSidecar(
      "renders/Wear_Dark_Alice.png",
      simpleSidecarJson(
        "java.lang.NoSuchMethodError",
        "the dark sibling's row",
        "java.lang.NoSuchMethodError: the dark sibling's row\n\tat com.example.wear.W.g(W.kt:2)",
      ),
    )

    val entry =
      diagnoseMissingRenders(
          listOf(missingResult()),
          parameterisedManifests(
            "renders/Wear.png",
            "com.example.wear.WearAppKt.WearDark" to "renders/Wear_Dark.png",
          ),
        )
        .single()

    assertEquals(listOf("renders/Wear_Alice.png"), entry.sidecars.map { it.output })
    val message = formatMissingRenderReport(listOf(entry), total = 1)
    assertContains(message, "Alice has no avatar")
    assertFalse(message.contains("the dark sibling's row"), message)
  }

  @Test
  fun `fan-out sidecars are found for data products and for a blank capture`() {
    // The renderer suffixes any output path it is handed, including data products and blank
    // captures resolved to `renders/<id>.png`; all must be enumerated.
    writeSidecar(
      "renders/$previewId" + "_Alice.png",
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "blank-capture row failed",
        "java.lang.IllegalStateException: blank-capture row failed\n\tat com.example.W.f(W.kt:1)",
      ),
    )
    writeSidecar(
      "data/render-scroll-long/Wear_Bob.png",
      simpleSidecarJson(
        "java.lang.NullPointerException",
        "scroll product row failed",
        "java.lang.NullPointerException: scroll product row failed\n\tat com.example.W.g(W.kt:2)",
      ),
    )

    val entry =
      diagnoseMissingRenders(
          listOf(missingResult()),
          parameterisedManifests(
            "",
            dataProducts =
              listOf(
                PreviewDataProduct(
                  kind = "render-scroll-long",
                  output = "data/render-scroll-long/Wear.png",
                )
              ),
          ),
        )
        .single()

    assertEquals(
      listOf("data/render-scroll-long/Wear_Bob.png", "renders/$previewId" + "_Alice.png"),
      entry.sidecars.map { it.output }.sorted(),
    )
    val message = formatMissingRenderReport(listOf(entry), total = 1)
    assertContains(message, "blank-capture row failed")
    assertContains(message, "scroll product row failed")
    assertFalse(message.contains("NO-SOURCE"), message)
  }

  @Test
  fun `a scanned parameter row is reported but never dated to this run`() {
    // Stale fan-out `.error.json`s are never cleaned up, so a scanned row is reported undated even
    // when the renderer ran.
    writeSidecar(
      "renders/Wear_Alice.png",
      simpleSidecarJson(
        "java.lang.IllegalStateException",
        "Alice has no avatar",
        "java.lang.IllegalStateException: Alice has no avatar\n\tat com.example.wear.W.f(W.kt:1)",
      ),
    )

    val entry =
      diagnoseMissingRenders(
          listOf(missingResult()),
          parameterisedManifests("renders/Wear.png"),
          outcomes("composePreviewRender" to GradleTaskDisposition.SUCCESS),
        )
        .single()

    assertEquals(OutputDiscovery.SCANNED, entry.sidecars.single().discovery)
    assertEquals(SidecarDating.UNDATED, entry.dating(entry.sidecars.single()))
    assertEquals(false, entry.threwThisRun)

    val message = formatMissingRenderReport(listOf(entry), total = 1)
    assertContains(message, "Alice has no avatar")
    assertContains(message, "undated parameter row")
    assertContains(message, "this run may not have attempted that row")
    assertFalse(message.contains("the build wiring is fine"), message)
  }

  @Test
  fun `a declared output's sidecar is still dated to this run`() {
    // Declared outputs were targeted by the renderer, so the scanned-row caution doesn't apply.
    writeSidecar("renders/WearAppPreview.png")

    val entry =
      diagnoseMissingRenders(
          listOf(missingResult()),
          manifests(),
          outcomes("composePreviewRender" to GradleTaskDisposition.SUCCESS),
        )
        .single()

    assertEquals(OutputDiscovery.DECLARED, entry.sidecars.single().discovery)
    assertEquals(SidecarDating.THIS_RUN, entry.dating(entry.sidecars.single()))
    val message = formatMissingRenderReport(listOf(entry), total = 1)
    assertContains(message, "the build wiring is fine")
    assertFalse(message.contains("undated parameter row"), message)
  }

  @Test
  fun `the single-preview report render --output prints carries the sidecar`() {
    // `render --output` goes through the same report, so the renderer's exception reaches the user.
    writeSidecar("renders/WearAppPreview.png")

    val message =
      missingRenderReport(missing = listOf(missingResult()), manifests = manifests(), total = 1)

    assertContains(message, previewId)
    assertContains(message, "NoClassDefFoundError")
    assertContains(message, "AmbientAwareActivity.kt:76")
  }

  // A `use {}` body that threw and then failed to close: the suppressed throwable's own `Caused
  // by:` is printed indented after the primary chain (`Throwable.printEnclosedStackTrace` shape).
  private val suppressedStackTrace =
    """
    java.lang.reflect.InvocationTargetException
    	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
    Caused by: java.lang.IllegalStateException: body failed
    	at com.example.wear.WearAppKt.WearApp(WearApp.kt:31)
    Caused by: java.io.IOException: disk gone
    	at com.example.wear.ambient.AmbientAwareActivity.rememberAmbientState(AmbientAwareActivity.kt:76)
    	Suppressed: java.lang.RuntimeException: close failed
    		at com.example.wear.io.Closer.close(Closer.kt:12)
    	Caused by: java.net.SocketException: connection reset by peer
    		at com.example.wear.io.Socket.reset(Socket.kt:99)
    """
      .trimIndent()

  @Test
  fun `a suppressed exception's own cause is not mistaken for the root cause`() {
    val chain = causeChainOf(suppressedStackTrace)

    assertEquals(
      listOf("java.lang.IllegalStateException", "java.io.IOException"),
      chain.map { it.exception },
      "the suppressed branch's `Caused by:` must not join the primary chain",
    )
    assertEquals("java.io.IOException", rootCauseOf(suppressedStackTrace)?.exception)
    assertEquals("disk gone", rootCauseOf(suppressedStackTrace)?.message)
  }

  @Test
  fun `the preferred frame never comes from a suppressed branch`() {
    val frame = preferredAppFrame(suppressedStackTrace, "com.example.wear.WearAppKt")

    // The deepest primary section is the IOException's, not the suppressed close failure printed
    // last.
    assertEquals("AmbientAwareActivity.kt", frame?.file)
    assertEquals(76, frame?.line)
  }

  @Test
  fun `a suppressed exception with no cause of its own is still skipped for frames`() {
    val trace =
      """
      java.lang.IllegalStateException: body failed
      	at com.example.wear.WearAppKt.WearApp(WearApp.kt:31)
      	Suppressed: java.lang.RuntimeException: close failed
      		at com.example.wear.io.Closer.close(Closer.kt:12)
      """
        .trimIndent()

    assertTrue(causeChainOf(trace).isEmpty(), "a suppressed caption is not a `Caused by:`")
    assertEquals("WearApp.kt", preferredAppFrame(trace, "com.example.wear.WearAppKt")?.file)
  }

  @Test
  fun `the policy prefix survives`() {
    val message =
      formatMissingRenderReport(
        listOf(PreviewDiagnosis(id = "A", module = ":app", coords = "default")),
        total = 1,
        prefix = "missing-renders policy=warn — ",
      )
    assertTrue(message.startsWith("missing-renders policy=warn — "), message)
  }
}
