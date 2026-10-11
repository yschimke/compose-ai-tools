package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MissingPreviewMessageTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun previewWithCapture(id: String, renderOutput: String) =
    PreviewInfo(
      id = id,
      functionName = id,
      className = "com.example.PreviewsKt",
      captures = listOf(Capture(renderOutput = renderOutput)),
    )

  @Test
  fun `formatMissingPreviewsMessage keeps legacy guidance when no sidecars exist`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = listOf(previewWithCapture("A", "renders/A.png")),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A"),
        sidecars = emptyMap(),
      )

    // Without sidecars the original NO-SOURCE diagnosis stays: the task really was skipped.
    assertThat(msg).contains("composePreviewRender")
    assertThat(msg).contains("NO-SOURCE")
    assertThat(msg).contains("1 of 1")
  }

  @Test
  fun `formatMissingPreviewsMessage surfaces sidecar exception details`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            previewWithCapture("A", "renders/A.png"),
            previewWithCapture("B", "renders/B.png"),
          ),
      )

    val sidecars =
      mapOf(
        "A" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.ClassNotFoundException",
            message = "com.example.PreviewsKt",
            topAppFrame =
              ComposePreviewTasks.ErrorSidecar.TopAppFrame(
                file = "Previews.kt",
                line = 42,
                function = "Greeting",
              ),
          )
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A", "B"),
        sidecars = sidecars,
      )

    // With any sidecar, the misleading NO-SOURCE sentence must not appear.
    assertThat(msg).doesNotContain("NO-SOURCE")
    assertThat(msg).doesNotContain("RobolectricRenderTest")
    // The sidecar's exception class, message and frame are shown directly.
    assertThat(msg).contains("ClassNotFoundException")
    assertThat(msg).contains("com.example.PreviewsKt")
    assertThat(msg).contains("Previews.kt:42")
    assertThat(msg).contains("A:")
    // Previews without a sidecar are still listed separately.
    assertThat(msg).contains("No sidecar")
    assertThat(msg).contains("B")
  }

  @Test
  fun `readErrorSidecarsFor parses sidecar JSON next to each missing preview's render path`() {
    val outDir = tempDir.root.resolve("compose-previews")
    val rendersDir = outDir.resolve("renders").apply { mkdirs() }
    // Schema mirrors `RenderErrorSidecar.write` without a cross-module dependency.
    rendersDir
      .resolve("A.png.error.json")
      .writeText(
        """
        {
          "schema": "compose-preview-error/v1",
          "exception": "java.lang.NoSuchMethodError",
          "message": "ComposeUiNode.setCompositeKeyHash",
          "topAppFrame": {"file": "MyPreview.kt", "line": 17, "function": "Greet"},
          "stackTrace": "..."
        }
        """
          .trimIndent()
      )
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            previewWithCapture("A", "renders/A.png"),
            previewWithCapture("B", "renders/B.png"),
          ),
      )

    val sidecars = ComposePreviewTasks.readErrorSidecarsFor(manifest, listOf("A", "B"), outDir)

    // Only previews with sidecars are in the map; callers use `id !in sidecars` to bucket skipped
    // vs threw.
    assertThat(sidecars.keys).containsExactly("A")
    val a = sidecars.getValue("A")
    assertThat(a.exception).isEqualTo("java.lang.NoSuchMethodError")
    assertThat(a.message).isEqualTo("ComposeUiNode.setCompositeKeyHash")
    assertThat(a.topAppFrame?.file).isEqualTo("MyPreview.kt")
    assertThat(a.topAppFrame?.line).isEqualTo(17)
  }

  @Test
  fun `formatMissingPreviewsMessage truncates long sidecar lists`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = (1..10).map { previewWithCapture("P$it", "renders/P$it.png") },
      )
    val sidecars =
      (1..10).associate { i ->
        "P$i" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.RuntimeException",
            message = "boom $i",
          )
      }

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = (1..10).map { "P$it" },
        sidecars = sidecars,
      )

    // Capped at 5 entries with a "(+N more)" footer.
    assertThat(msg).contains("(+5 more with sidecars)")
  }

  @Test
  fun `formatMissingPreviewsMessage collapses one root cause into a single counted line`() {
    // #3690's shape: the first preview has the real skiko load error, the rest the same cascade;
    // the real cause must lead.
    val manifest =
      PreviewManifest(
        module = "catalog",
        variant = "debug",
        previews = (1..8).map { previewWithCapture("P$it", "renders/P$it.png") },
      )
    val sidecars =
      (1..8).associate { i ->
        "P$i" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.NoClassDefFoundError",
            message = "Could not initialize class org.jetbrains.skia.Surface",
            diagnosis = "${ComposePreviewTasks.CASCADE_DIAGNOSIS_PREFIX} in this render JVM.",
          )
      }

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = (1..8).map { "P$it" },
        sidecars = sidecars,
      )

    assertThat(msg).contains("NoClassDefFoundError")
    assertThat(msg).contains("8 previews, e.g. P1, P2, P3")
    // One line covered all eight, so there is nothing left to apologise for.
    assertThat(msg).doesNotContain("more with sidecars")
  }

  @Test
  fun `formatMissingPreviewsMessage keeps distinct source frames apart`() {
    // Same exception and message, different composables: three separate bugs. Collapsing them
    // under the first one's frame would blame `A.kt:10` for failures in files it never touched.
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = listOf("A", "B").map { previewWithCapture(it, "renders/$it.png") },
      )
    val sidecars =
      mapOf(
        "A" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.IllegalStateException",
            message = "missing state",
            topAppFrame = ComposePreviewTasks.ErrorSidecar.TopAppFrame("A.kt", 10, "APreview"),
          ),
        "B" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.IllegalStateException",
            message = "missing state",
            topAppFrame = ComposePreviewTasks.ErrorSidecar.TopAppFrame("B.kt", 20, "BPreview"),
          ),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A", "B"),
        sidecars = sidecars,
      )

    assertThat(msg).contains("A.kt:10")
    assertThat(msg).contains("B.kt:20")
    // Two lines, each naming its own preview — not one line claiming "2 previews".
    assertThat(msg).doesNotContain("2 previews")
  }

  @Test
  fun `formatMissingPreviewsMessage leads with the real cause, not the cascade`() {
    val manifest =
      PreviewManifest(
        module = "catalog",
        variant = "debug",
        previews =
          listOf(
            previewWithCapture("A", "renders/A.png"),
            previewWithCapture("B", "renders/B.png"),
          ),
      )
    val rootCause =
      "skiko's native library could not be loaded because this process mixed two glibc builds"
    val sidecars =
      mapOf(
        // Deliberately cascade-first: the map's iteration order must not decide which explanation
        // the user is shown.
        "A" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.NoClassDefFoundError",
            message = "Could not initialize class org.jetbrains.skia.Surface",
            diagnosis = "${ComposePreviewTasks.CASCADE_DIAGNOSIS_PREFIX} in this render JVM.",
          ),
        "B" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.ExceptionInInitializerError",
            message = "",
            diagnosis = rootCause,
          ),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A", "B"),
        sidecars = sidecars,
      )

    assertThat(msg).contains(rootCause)
    // Before the per-preview list, which is where it was previously buried.
    assertThat(msg.indexOf(rootCause)).isLessThan(msg.indexOf("Per-preview render errors"))
  }

  /**
   * #3741's shape: the reflective invoke makes `exception` a bare `InvocationTargetException` and
   * `topAppFrame` a tooling frame; the trace has what matters.
   */
  private val reflectiveWrapperTrace =
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

  private val reflectiveWrapperSidecar =
    ComposePreviewTasks.ErrorSidecar(
      exception = "java.lang.reflect.InvocationTargetException",
      message = "",
      topAppFrame =
        ComposePreviewTasks.ErrorSidecar.TopAppFrame(
          file = "KeyboardDataProduct.kt",
          line = 148,
          function = "AroundComposable${'$'}lambda${'$'}2",
        ),
      stackTrace = reflectiveWrapperTrace,
    )

  @Test
  fun `formatMissingPreviewsMessage leads with the Caused by root, not the reflective wrapper`() {
    val manifest =
      PreviewManifest(
        module = "wear",
        variant = "debug",
        previews =
          listOf(
            previewWithCapture("WearAppPreview_Devices - Large Round", "renders/WearApp.png")
              .copy(className = "com.example.wear.WearAppKt")
          ),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("WearAppPreview_Devices - Large Round"),
        sidecars = mapOf("WearAppPreview_Devices - Large Round" to reflectiveWrapperSidecar),
      )

    // The wrapper alone ("InvocationTargetException at KeyboardDataProduct.kt:148") named neither
    // the failure nor a file the reader owns — both live in the cause chain.
    assertThat(msg).contains("NoClassDefFoundError")
    assertThat(msg).contains("com/google/wear/services/ambient/AmbientComponentState")
    assertThat(msg).contains("chain: InvocationTargetException → NoClassDefFoundError")
    assertThat(msg).contains("AmbientAwareActivity.kt:76")
    assertThat(msg).doesNotContain("KeyboardDataProduct.kt")
    assertThat(msg).doesNotContain("NO-SOURCE")
  }

  @Test
  fun `renderErrorInsight falls back to the sidecar fields without a stack trace`() {
    // Sidecars written by an older renderer carry no `stackTrace`; nothing about the message may
    // depend on it.
    val insight =
      ComposePreviewTasks.renderErrorInsight(
        ComposePreviewTasks.ErrorSidecar(
          exception = "java.lang.IllegalStateException",
          message = "missing state",
          topAppFrame = ComposePreviewTasks.ErrorSidecar.TopAppFrame("A.kt", 10, "APreview"),
        ),
        previewClassName = "com.example.PreviewsKt",
      )

    assertThat(insight.exception).isEqualTo("java.lang.IllegalStateException")
    assertThat(insight.message).isEqualTo("missing state")
    assertThat(insight.frame?.file).isEqualTo("A.kt")
    assertThat(insight.chain).isEmpty()
  }

  @Test
  fun `renderErrorInsight prefers the deepest frame in the preview's own package`() {
    val insight =
      ComposePreviewTasks.renderErrorInsight(
        reflectiveWrapperSidecar,
        previewClassName = "com.example.wear.WearAppKt",
      )

    assertThat(insight.frame?.file).isEqualTo("AmbientAwareActivity.kt")
    assertThat(insight.frame?.line).isEqualTo(76)
    assertThat(insight.frame?.function).isEqualTo("rememberAmbientState")

    // A preview from an unrelated package matches no frame, so the sidecar's own stays.
    val unrelated =
      ComposePreviewTasks.renderErrorInsight(reflectiveWrapperSidecar, "zz.other.PreviewsKt")
    assertThat(unrelated.frame?.file).isEqualTo("KeyboardDataProduct.kt")
  }

  /**
   * A `use {}` that threw and then failed to close: the suppressed throwable's `Caused by:` prints
   * after the primary chain (JDK `printEnclosedStackTrace` shape).
   */
  private val suppressedTrace =
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
  fun `a suppressed exception's own cause never becomes the root cause`() {
    // Trimming every line before matching made the indented `Caused by:` of the suppressed close
    // failure indistinguishable from the primary chain — and, printed last, it won `lastOrNull()`.
    assertThat(RenderErrorTrace.causeChain(suppressedTrace).map { it.exception })
      .containsExactly("java.lang.IllegalStateException", "java.io.IOException")
      .inOrder()
    assertThat(RenderErrorTrace.rootCause(suppressedTrace)?.exception)
      .isEqualTo("java.io.IOException")
    assertThat(RenderErrorTrace.rootCause(suppressedTrace)?.message).isEqualTo("disk gone")
  }

  @Test
  fun `the preferred frame never comes from a suppressed branch`() {
    // The primary chain's deepest cause wins, not the suppressed branch printed last.
    val frame = RenderErrorTrace.preferredAppFrame(suppressedTrace, "com.example.wear.WearAppKt")

    assertThat(frame?.file).isEqualTo("AmbientAwareActivity.kt")
    assertThat(frame?.line).isEqualTo(76)
  }

  @Test
  fun `formatMissingPreviewsMessage names the merged unit-test R jar behind a missing R class`() {
    // element-x's shape: every preview dies at class init on a missing R table; the hint keeps it
    // from reading as 321 broken previews.
    val manifest =
      PreviewManifest(
        module = "designsystem",
        variant = "debug",
        previews =
          listOf(
            previewWithCapture("A", "renders/A.png"),
            previewWithCapture("B", "renders/B.png"),
          ),
      )
    val sidecar =
      ComposePreviewTasks.ErrorSidecar(
        exception = "java.lang.NoClassDefFoundError",
        message = "androidx/customview/poolingcontainer/R\$id",
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A", "B"),
        sidecars = mapOf("A" to sidecar, "B" to sidecar),
      )

    // Reported once, in dotted form, whatever the slashed form the JVM used.
    assertThat(msg).contains("androidx.customview.poolingcontainer.R\$id")
    assertThat(msg).contains("is an AGP-generated resource table")
    assertThat(msg).contains("compile_and_runtime_r_class_jar")
    assertThat(msg).contains("test<Variant>UnitTest")
  }

  @Test
  fun `the R-jar hint stays out of an ordinary preview failure`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = listOf(previewWithCapture("A", "renders/A.png")),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A"),
        sidecars =
          mapOf(
            "A" to
              ComposePreviewTasks.ErrorSidecar(
                exception = "java.lang.IllegalStateException",
                message = "missing state",
              )
          ),
      )

    assertThat(msg).doesNotContain("compile_and_runtime_r_class_jar")
  }

  @Test
  fun `missingAgpRClass only fires on a class-loading failure over an R table`() {
    fun insight(exception: String, message: String, chain: String = "") =
      ComposePreviewTasks.RenderErrorInsight(
        exception = exception,
        message = message,
        frame = null,
        chain = chain,
      )

    assertThat(
        ComposePreviewTasks.missingAgpRClass(
          insight("java.lang.ClassNotFoundException", "androidx.customview.poolingcontainer.R\$id")
        )
      )
      .isEqualTo("androidx.customview.poolingcontainer.R\$id")
    // The cascade every other preview reports: the wrapper is an initialiser error and only the
    // chain names the class-loading failure.
    assertThat(
        ComposePreviewTasks.missingAgpRClass(
          insight(
            "java.lang.ExceptionInInitializerError",
            "androidx/customview/poolingcontainer/R\$id",
            chain = "ExceptionInInitializerError \u2192 NoClassDefFoundError",
          )
        )
      )
      .isEqualTo("androidx.customview.poolingcontainer.R\$id")
    // A class-loading failure over something that is not a resource table.
    assertThat(
        ComposePreviewTasks.missingAgpRClass(
          insight("java.lang.ClassNotFoundException", "com.example.PreviewsKt")
        )
      )
      .isNull()
    // An R table named by a failure that is not about loading a class.
    assertThat(
        ComposePreviewTasks.missingAgpRClass(
          insight("java.lang.IllegalStateException", "androidx.customview.poolingcontainer.R\$id")
        )
      )
      .isNull()
  }
}
