package ee.schimke.composeai.cli

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
 * Issue #3741: a preview that produced no PNG used to be reported with one fixed paragraph blaming
 * the build wiring ("`composePreviewRender` reported NO-SOURCE — the renderer test class wasn't on
 * testClassesDirs"), even when the renderer had already written the precise cause to
 * `<render>.png.error.json` beside the would-be output.
 *
 * These cover the seam that decides between the two: [diagnoseMissingRenders] resolves who owns the
 * preview, what that owner did, and which sidecars it could have written;
 * [formatMissingRenderReport] turns those facts into the message. They are the behavioural
 * specification the #3796 restructuring had to preserve — every one of them predates it.
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

  // The failure reported in issue #3741, verbatim in shape: the renderer invokes the preview
  // reflectively, so the outermost throwable is a useless InvocationTargetException whose only
  // stack frames belong to this project's own data-product plumbing, while the cause chain names
  // the real problem (a Wear Services class that only exists on-device) and passes through the
  // consumer's own source file.
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

    // The whole point of the issue: the render task demonstrably ran (it wrote a sidecar), so the
    // build-wiring guess must not be printed at all.
    assertFalse(message.contains("NO-SOURCE"), message)
    assertFalse(message.contains("testClassesDirs"), message)
    assertContains(message, "1 of 35 preview(s)")
    // The real cause, from the `Caused by:` chain — not the reflective wrapper the sidecar's own
    // `exception` field names.
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
    // A preview whose only declared output is a data product (no capture renderOutput) still gets
    // its sidecar found — the renderer writes it beside whichever artefact it was producing.
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
    // `com.example.wear.ambient` is a sibling package of the preview's own, reached by walking the
    // package prefix outwards — and it sits deeper in the cause chain than `WearAppKt.WearApp`.
    assertEquals("AmbientAwareActivity.kt", frame.file)
    assertEquals(76, frame.line)
    assertEquals("rememberAmbientState", frame.function)

    // A preview class that shares nothing with any frame leaves the sidecar's own topAppFrame as
    // the fallback (the caller does that; here the chooser simply declines).
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
    // Two captures of one preview, failing differently — the later frame advances the clock into a
    // coroutine that isn't ready. Collapsing to the first sidecar would report the 500ms exception
    // against both coordinates and hide this entirely.
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
    // Each exception is tied to the output that produced it, so a reader can tell which coordinate
    // died which way.
    assertContains(message, "renders/Wear_500ms.png — threw IllegalStateException")
    assertContains(message, "renders/Wear_1000ms.png — threw NullPointerException")
  }

  @Test
  fun `one throwable across several outputs still reports one line`() {
    // The common case: one broken composable fails every output with the same exception. Reporting
    // it once per output would be noise, so identical sidecars collapse — and stay unlabelled.
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
    // `composePreviewRender` was skipped this time (NO-SOURCE — the wiring bug the historical
    // guidance was written for), so the `.error.json` on disk is last run's. Claiming "rendered and
    // then threw — the build wiring is fine" would state the exact opposite of what happened.
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
    // ...and the wiring guidance the sidecar had suppressed comes back, because that is now the
    // live hypothesis.
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
   * A `kind=LOTTIE` preview: Android renders it from `composePreviewRenderLottie` into its own
   * `lottie-renders/` dir, never from the Robolectric task.
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
    // Identity is manifest-derivable, so it is always known; behaviour is only knowable from the
    // build, so it carries provenance. Splitting them is what makes "did not run" unwriteable
    // without an observed disposition.
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

    // Another module's skip says nothing about this one, and no evidence stays Unobserved rather
    // than being guessed either way — `ownerRan` is null, not false.
    assertEquals(Evidence.Unobserved, runOf("COMPOSE", skipped, module = ":other").ownerRun)
    assertEquals(Evidence.Unobserved, runOf("COMPOSE", emptyMap()).ownerRun)
    assertNull(runOf("COMPOSE", emptyMap()).ownerRan)
  }

  @Test
  fun `Lottie and SVG are owned by their own renderer task, not Robolectric`() {
    // Android renders `kind=LOTTIE` / `kind=SVG` from separate tasks folded into
    // `composePreviewRenderAll` — Robolectric can inflate neither. A NO-SOURCE
    // `composePreviewRender` therefore says nothing about them: their sidecars are this run's.
    // The owning task travels with the verdict: the report has to name the task that really
    // skipped, not the one that happens to have a `testClassesDirs` remedy.
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
    // ...while an ordinary Compose preview in the same module *is* stale — the task that owns it
    // was the one that didn't run.
    assertEquals(false, runOf("COMPOSE", robolectricSkipped).ownerRan)

    // And the converse: the Lottie task skipped while Robolectric ran.
    val lottieSkipped =
      outcomes(
        "composePreviewRender" to GradleTaskDisposition.SUCCESS,
        "composePreviewRenderLottie" to GradleTaskDisposition.SKIPPED,
      )
    assertEquals(false, runOf("LOTTIE", lottieSkipped).ownerRan)
    assertEquals(true, runOf("COMPOSE", lottieSkipped).ownerRan)

    // The desktop backend has no kind tasks at all — `composePreviewRender` renders every kind
    // there, so it is the owner and the answer must not degrade to Unobserved.
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
    // The converse scenario of the test above, carried all the way to the message: the Lottie task
    // skipped, so the sidecar beside a Lottie preview is stale — but blaming `composePreviewRender`
    // and its `testClassesDirs` would be a precise, checkable, false statement. That task ran fine,
    // it is not what renders Lottie, and being a `RenderPreviewsTask` it has no testClassesDirs and
    // never reports NO-SOURCE at all.
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
    // ...and never the Robolectric one, which would be a wrong-task diagnosis here. `NO-SOURCE` is
    // not even a state this task can report.
    assertFalse(message.contains("testClassesDirs"), message)
    assertFalse(message.contains("NO-SOURCE"), message)
    assertFalse(message.contains("`composePreviewRender` did not run"), message)
  }

  @Test
  fun `a Lottie failure is not labelled stale when Robolectric was NO-SOURCE`() {
    // End to end through the report: the Android Lottie renderer wrote this sidecar seconds ago.
    // Calling it an "earlier run" and pointing at testClassesDirs is the original bug inverted.
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
    // Every Android module registers its own `composePreviewRenderLottie`, so a multi-module render
    // has several tasks with one name and independently different outcomes. Grouping on the name
    // alone merged them: one paragraph, the combined count, an unqualified task to go and inspect,
    // and "in this module" said of two modules at once.
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
    // `:app`'s Lottie task skipped and left last run's sidecar behind; `:feature`'s ran and simply
    // produced nothing.
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
    // The preview used to render to `renders/<id>.png` and now declares a fanout output. Nothing
    // deletes the old sidecar — `cleanStaleRenders` walks `png`/`gif` only — so probing the default
    // stem alongside the declared one reports a years-old exception as if it happened just now.
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
    // A capture that declares no `renderOutput` is a supported manifest shape, and the Android
    // renderer resolves it to `renders/<id>.png` — `capture.renderOutput.substringAfterLast('/')
    // .ifEmpty { "<id>.png" }` — which is also the anchor its outer per-preview catch writes the
    // sidecar to. With a data product declared alongside it, dropping the default stem would leave
    // a render that died before producing the product with no sidecar found at all, and the CLI
    // back on the NO-SOURCE wiring guess it is this whole file's job not to guess.
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
    // The Android renderer anchors the preview-level sidecar on the *first* capture only
    // (`captures.firstOrNull()`), deleting and rewriting it there; its two per-job writes need a
    // `.gif` extension or a data-product path, so neither can land on the default stem. A blank
    // second capture therefore cannot produce a fresh `renders/<id>.png` sidecar — anything found
    // there is an older manifest's leftover, and quoting it as this run's finding is the stale-file
    // trap through a narrower gap.
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
    // The fallback's actual job: a manifest predating `renderOutput`, or a preview globbed away
    // entirely, still renders to `renders/<id>.png` and its sidecar must be found there.
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

    // ...and for a preview the manifest doesn't describe at all — `manifests()` knows only
    // `previewId`, so this one falls back to its own default stem.
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
    // The gap #3793 recorded and #3796 closes: a parameterised preview renders one output per
    // provider value and writes the sidecar beside *that* — `renders/Wear_Alice.png.error.json` —
    // which neither the declared template output nor the default stem ever pointed at, so a
    // per-value failure was invisible to the CLI and came out as the NO-SOURCE wiring guess.
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
                // A sibling that owns `renders/Wear_Bob.png` outright — the fan-out glob must not
                // adopt another preview's declared output.
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

  @Test
  fun `the single-preview report render --output prints carries the sidecar`() {
    // `render --output` exits as soon as the one matched preview has no PNG. It goes through the
    // same report as every other missing render, so the exception the renderer already wrote down
    // reaches the user instead of a bare "Render produced no PNG".
    writeSidecar("renders/WearAppPreview.png")

    val message =
      missingRenderReport(missing = listOf(missingResult()), manifests = manifests(), total = 1)

    assertContains(message, previewId)
    assertContains(message, "NoClassDefFoundError")
    assertContains(message, "AmbientAwareActivity.kt:76")
  }

  // A `use {}` body that threw and then failed to close: `printStackTrace()` prints the suppressed
  // throwable's own `Caused by:` indented under it, *after* the primary chain's deepest cause.
  // Verbatim JDK output shape (`Throwable.printEnclosedStackTrace`).
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

    // The deepest *primary* section is the IOException's, not the suppressed close failure's —
    // whose frames sit in `com.example.wear.io` and would otherwise win by being printed last.
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
