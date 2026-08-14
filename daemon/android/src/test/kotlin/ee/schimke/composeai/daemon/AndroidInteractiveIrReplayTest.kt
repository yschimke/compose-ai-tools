package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.data.render.extensions.IrReplayComposableProvider
import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression coverage for live mode on an **IR-backed** preview — a preview whose consumer bytecode
 * pack-time minimisation deliberately dropped (`PreviewBundleFormat`: "the enclosing class is
 * dropped from the minimisation closure seed"), leaving the bundle's `classes/app.jar` without it.
 *
 * `RenderEngine.render` has replayed those previews from their carried IR since schema v5, but
 * `RobolectricHost`'s held-rule loop did not — it went straight to `Class.forName`, so every
 * `interactive/start` against a fully IR-backed catalog failed with `ClassNotFoundException` and
 * the viewer reported "input requires a live stream — unavailable". The published `remote-m3`
 * catalog is exactly that shape: all 28 previews carry an `ir/<id>.rc` document and its
 * `classes/app.jar` is an empty 22-byte jar, so live mode could never work there.
 *
 * [replaysIrBackedPreviewAndStillFailsWithoutIr] pins the fix in both directions: the IR-backed id
 * now composes its replay, while an identically-shaped id the bundle carries no IR for still fails
 * resolution — so the pass can't be a false positive from some other fallback quietly rendering a
 * frame.
 *
 * The replay composable is a fake registered through the same `ServiceLoader` SPI
 * `:data-remotecompose-connector` uses in production (see the `META-INF/services` resource beside
 * this source set). `:daemon:android` has no Remote Compose connector on its own classpath — the
 * real player is supplied by the consumer app — so the fake is the only provider for the
 * `remotecompose` format here and can't shadow a real one.
 */
class AndroidInteractiveIrReplayTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun clearIrProperties() {
    System.clearProperty(BundleIrReplayStore.BUNDLE_MANIFEST_PATH_PROP)
    System.clearProperty(BundleIrReplayStore.IR_DIR_PROP)
    BundleIrReplayStore.resetForTest()
  }

  /**
   * Both directions in one host. They share a `RobolectricHost` because booting two sandboxes is
   * the expensive part of this test (see `AndroidInteractiveSessionTest`'s note on cold-boot cost)
   * and because the two acquires are the *same* preview shape differing only in whether the bundle
   * carries IR for that id — which is precisely the fix's decision point.
   */
  @Test
  fun replaysIrBackedPreviewAndStillFailsWithoutIr() {
    val outputDir = tempFolder.newFolder("ir-interactive-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    seedIrBundle(IR_PREVIEW_ID)

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = ::specForAbsentClass)
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = IR_PREVIEW_ID,
          classLoader = AndroidInteractiveIrReplayTest::class.java.classLoader!!,
        )
      try {
        val result = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("held IR replay must produce a PNG path", result.pngPath)
        // The fake replay composable paints solid green. Any other outcome — a blank frame, the
        // activity background — means the held loop did not route through IR replay.
        val greenPct = greenPct(File(result.pngPath!!))
        assertTrue(
          "expected the held frame to be ≥95% the replay composable's green " +
            "(got ${"%.2f".format(greenPct * 100)}%)",
          greenPct >= 0.95,
        )
      } finally {
        session.close()
      }

      // Negative control, so the pass above can't be a false positive from some other fallback
      // quietly painting a frame: an identically-shaped preview whose id the bundle carries NO IR
      // for still reflects its class, and still fails the way the bug report did. Expressed as a
      // second id rather than by clearing the store's system properties — the held loop reads
      // `BundleIrReplayStore` from inside the Robolectric sandbox, whose statics outlive a
      // host, so a cleared-and-reloaded store is not observable from here.
      try {
        host
          .acquireInteractiveSession(
            previewId = NO_IR_PREVIEW_ID,
            classLoader = AndroidInteractiveIrReplayTest::class.java.classLoader!!,
          )
          .close()
        fail("acquire must fail when the preview's class is absent and no IR is carried")
      } catch (e: UnsupportedOperationException) {
        assertTrue(
          "expected the start error to name the missing class, got: ${e.message}",
          e.message.orEmpty().contains("ClassNotFoundException"),
        )
      }
    } finally {
      host.shutdown()
    }
  }

  /**
   * A spec naming a class that genuinely isn't on any classloader here — standing in for the
   * consumer bytecode a v5 bundle drops. [RenderSpec.previewId] is left null on purpose so the test
   * also covers `acquireInteractiveSession`'s fallback to the acquire argument for the IR key,
   * which is the shape every `previewSpecResolver` in this module builds.
   */
  private fun specForAbsentClass(previewId: String): RenderSpec? =
    if (previewId != IR_PREVIEW_ID && previewId != NO_IR_PREVIEW_ID) null
    else
      RenderSpec(
        className = ABSENT_CLASS_FQN,
        functionName = "ButtonGroupRemote",
        widthPx = IR_WIDTH_PX,
        heightPx = IR_HEIGHT_PX,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "interactive-ir-replay",
      )

  /**
   * Write the two artefacts [BundleIrReplayStore] reads — a `bundle.json` carrying one
   * `intermediateRepresentations` descriptor and the IR bytes beside it — and point the store's
   * system properties at them, exactly as `ServeBundleDaemon.materialize` does for a live catalog.
   * The bytes are opaque here: the fake replay composable ignores them, so this test stays about
   * the dispatch decision rather than the Remote Compose wire format.
   */
  private fun seedIrBundle(previewId: String) {
    val irDir = tempFolder.newFolder("ir")
    File(irDir, "$previewId.rc").writeBytes(byteArrayOf(1, 2, 3, 4))
    val manifest = tempFolder.newFile("bundle.json")
    manifest.writeText(
      """
      {
        "intermediateRepresentations": [
          {
            "previewId": "$previewId",
            "format": "${BundleIrReplayStore.FORMAT_REMOTECOMPOSE}",
            "path": "ir/$previewId.rc"
          }
        ]
      }
      """
        .trimIndent()
    )
    System.setProperty(BundleIrReplayStore.BUNDLE_MANIFEST_PATH_PROP, manifest.absolutePath)
    System.setProperty(BundleIrReplayStore.IR_DIR_PROP, irDir.absolutePath)
    BundleIrReplayStore.resetForTest()
  }

  /**
   * Fraction of pixels within tolerance of [REPLAY_GREEN]. Inlined for the same reason
   * `AndroidInteractiveSessionTest` inlines its own pixel helper — pulling `:daemon:harness`'s
   * `PixelDiff` would invert the dependency graph.
   */
  private fun greenPct(png: File): Double {
    val img = ImageIO.read(png) ?: error("could not decode $png")
    var hits = 0
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          kotlin.math.abs(r - ((REPLAY_GREEN shr 16) and 0xFF)) <= 8 &&
            kotlin.math.abs(g - ((REPLAY_GREEN shr 8) and 0xFF)) <= 8 &&
            kotlin.math.abs(b - (REPLAY_GREEN and 0xFF)) <= 8
        ) {
          hits++
        }
      }
    }
    return hits.toDouble() / (img.width * img.height)
  }

  private companion object {
    /**
     * Shaped like the id that reported the bug — `remote-m3`'s `ButtonGroupRemote` sticker, whose
     * `interactive/start` failed with `ClassNotFoundException:
     * com.example.designcatalogremotem3.CatalogPreviewsKt`.
     */
    const val IR_PREVIEW_ID: String =
      "com.example.designcatalogremotem3.CatalogPreviewsKt." +
        "ButtonGroupRemote_width_320dp_height_240dp_dpi_320"

    /**
     * Same absent class, but an id the seeded bundle carries no `intermediateRepresentation` for.
     */
    const val NO_IR_PREVIEW_ID: String =
      "com.example.designcatalogremotem3.CatalogPreviewsKt." +
        "CardRemote_width_320dp_height_240dp_dpi_320"

    const val ABSENT_CLASS_FQN: String = "com.example.designcatalogremotem3.CatalogPreviewsKt"

    const val IR_WIDTH_PX: Int = 120

    const val IR_HEIGHT_PX: Int = 120

    const val REPLAY_GREEN: Int = 0x2E7D32
  }
}

/**
 * Registered via `src/test/resources/META-INF/services/...IrReplayComposableProvider`, mirroring
 * how `:data-remotecompose-connector` registers `RemoteComposeIrReplay`.
 */
class FakeRemoteComposeIrReplayProvider : IrReplayComposableProvider {
  override val format: String = BundleIrReplayStore.FORMAT_REMOTECOMPOSE

  override fun replayClass(): Class<*> = FakeRemoteComposeIrReplay::class.java
}

/**
 * Stand-in for the connector's replay composable: the `@Composable Replay(bytes: ByteArray)` shape
 * plus a no-arg constructor the renderer resolves through `getDeclaredComposableMethod`. Paints a
 * flat colour so a captured frame proves which branch composed it, and ignores the bytes — the real
 * player's document decoding is the connector's business, not this dispatch test's.
 */
class FakeRemoteComposeIrReplay {
  @Composable
  @Suppress("UNUSED_PARAMETER")
  fun Replay(bytes: ByteArray) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF2E7D32)))
  }
}
