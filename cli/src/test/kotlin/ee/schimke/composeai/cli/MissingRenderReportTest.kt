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
 * These cover the seam that decides between the two: [collectMissingRenders] reads the sidecar off
 * disk, [formatMissingRenderReport] turns "expected output + sidecar (or none)" into the message.
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

    val entries = collectMissingRenders(listOf(missingResult()), manifests())
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
    val entries = collectMissingRenders(listOf(missingResult()), manifests())
    assertNull(entries.single().sidecar)

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
    val entries = collectMissingRenders(listOf(missingResult(), skipped), manifests())
    assertNotNull(entries.first().sidecar)
    assertNull(entries.last().sidecar)

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

    val entries = collectMissingRenders(listOf(missingResult()), manifests)

    assertNotNull(entries.single().sidecar)
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
      formatMissingRenderReport(collectMissingRenders(listOf(missingResult()), manifests()), 35)

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

    val entries = collectMissingRenders(listOf(fanoutResult()), fanoutManifests())
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
      formatMissingRenderReport(collectMissingRenders(listOf(fanoutResult()), fanoutManifests()), 2)

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
      collectMissingRenders(listOf(missingResult()), manifests()) { RenderTaskEvidence.DID_NOT_RUN }
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
      collectMissingRenders(listOf(missingResult()), manifests()) { RenderTaskEvidence.RAN }
    val message = formatMissingRenderReport(entries, total = 35)

    assertContains(message, "rendered and then threw")
    assertFalse(message.contains("earlier run"), message)
    assertFalse(message.contains("NO-SOURCE"), message)
  }

  @Test
  fun `render-task evidence comes from the module's own composePreviewRender outcome`() {
    val skipped =
      mapOf(
        ":app:composePreviewRender" to
          GradleTaskOutcome(":app:composePreviewRender", GradleTaskDisposition.SKIPPED)
      )
    assertEquals(RenderTaskEvidence.DID_NOT_RUN, renderTaskEvidenceOf(":app", skipped))
    // The gradle path is carried with and without its leading colon depending on the caller.
    assertEquals(RenderTaskEvidence.DID_NOT_RUN, renderTaskEvidenceOf("app", skipped))

    for (disposition in
      listOf(
        GradleTaskDisposition.SUCCESS,
        GradleTaskDisposition.UP_TO_DATE,
        GradleTaskDisposition.FROM_CACHE,
        GradleTaskDisposition.FAILED,
      )) {
      val outcomes =
        mapOf(
          ":app:composePreviewRender" to GradleTaskOutcome(":app:composePreviewRender", disposition)
        )
      assertEquals(RenderTaskEvidence.RAN, renderTaskEvidenceOf(":app", outcomes), "$disposition")
    }

    // Another module's skip says nothing about this one, and no evidence stays "unknown" rather
    // than being guessed either way.
    assertEquals(RenderTaskEvidence.UNKNOWN, renderTaskEvidenceOf(":other", skipped))
    assertEquals(RenderTaskEvidence.UNKNOWN, renderTaskEvidenceOf(":app", emptyMap()))
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
        listOf(MissingRender(id = "A", module = ":app", coords = "default")),
        total = 1,
        prefix = "missing-renders policy=warn — ",
      )
    assertTrue(message.startsWith("missing-renders policy=warn — "), message)
  }
}
