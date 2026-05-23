package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Scenario **SInteractiveKeyDispatch (real mode, desktop)** — closes the deferred real-mode harness
 * item from issue #1203 for the ``interactive/...`` wire surface. Mirrors
 * [S1LifecycleRealModeTest]'s boot path but exercises:
 *
 * - `initialize` / `initialized` handshake.
 * - `interactive/start` against `KeyPressColorSquare` — daemon advertises a real held session
 *   (`heldSession = true`) instead of the v1 stateless fallback, proving the manifest router plumbs
 *   `previewSpecResolver` through to the underlying `DesktopHost`.
 * - `interactive/input` notification with `kind = KEY_DOWN` and `keyCode = "29"` (Android
 *   `KEYCODE_A`). The daemon must accept the wire shape without faulting — a regression that trips
 *   `tryDecode` (e.g. a missing `keyCode` field, an unknown `kind` enum) would surface as a
 *   stderr-side error and abort the subprocess.
 * - `interactive/stop`, `shutdown`, `exit`.
 *
 * **Why no pixel assertion here.** `renderNow` renders against the stateless one-shot path, not the
 * held interactive session — so the render *after* `interactive/input` paints the pre-keyDown
 * composition. Asserting the state mutation requires a frame coming back from the held session,
 * which only happens via `stream/start` + `composestream/frame` (a richer harness surface than this
 * scenario needs to pin).
 *
 * The actual key-mutates-composition contract is covered three ways:
 * - In-process: `DesktopInteractiveSessionTest.key_down_input_flips_state_and_repaints`.
 * - Recording wire (also subprocess): [SRecordingKeyDispatchRealModeTest] — uses
 *   `recording/script` + `recording/stop` to assert frame 0 is green AND evidence is `APPLIED`.
 *
 * **Skipped under fake mode.** Run with:
 * ```
 * ./gradlew :daemon:harness:test -Pharness.host=real \
 *   --tests "*SInteractiveKeyDispatchRealModeTest"
 * ```
 */
class SInteractiveKeyDispatchRealModeTest {

  @Test
  fun real_mode_interactive_keyDown_wire_shape_round_trips() {
    Assume.assumeTrue(
      "Skipping SInteractiveKeyDispatchRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping SInteractiveKeyDispatchRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val moduleBuildDir = File("build")
    val rendersDir =
      File(moduleBuildDir, "daemon-harness/renders/skey-interactive-real").apply {
        deleteRecursively()
        mkdirs()
      }
    val manifestFile =
      File(moduleBuildDir, "daemon-harness/manifests/skey-interactive-previews.json").apply {
        parentFile.mkdirs()
      }
    // Drives PreviewManifestRouter — the previewId `key-press-color-square` resolves to the
    // `KeyPressColorSquare` composable in :daemon:desktop's testFixtures source set (the same
    // fixture the in-process `DesktopInteractiveSessionTest` exercises).
    manifestFile.writeText(
      """
      {
        "previews": [
          {
            "id": "key-press-color-square",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "KeyPressColorSquare",
            "widthPx": 64,
            "heightPx": 64,
            "density": 1.0,
            "showBackground": true,
            "outputBaseName": "skey-interactive"
          }
        ]
      }
      """
        .trimIndent()
    )

    val classpath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }

    val launcher =
      RealDesktopHarnessLauncher(
        rendersDir = rendersDir,
        previewsManifest = manifestFile,
        classpath = classpath,
      )

    val client = HarnessClient.start(launcher)
    try {
      val initResult = client.initialize()
      assertEquals(2, initResult.protocolVersion)
      client.sendInitialized()

      // 1. `interactive/start` — the manifest router must forward the previewSpecResolver
      // through to the parent DesktopHost so a real held session is returned (not the v1
      // fallback). `heldSession = false` would mean the daemon couldn't acquire a v2 scene,
      // which is the regression we're guarding against.
      val startResult = client.interactiveStart(previewId = PREVIEW_ID)
      assertNotNull("interactive/start must return a frameStreamId", startResult.frameStreamId)
      assertTrue("frameStreamId must be non-blank", startResult.frameStreamId.isNotBlank())
      assertTrue(
        "real desktop daemon must advertise a held session for KeyPressColorSquare; " +
          "got fallbackReason=${startResult.fallbackReason}. The manifest router must wire a " +
          "previewSpecResolver through to the underlying DesktopHost.",
        startResult.heldSession,
      )

      // 2. Dispatch KEY_DOWN(KEYCODE_A) — decimal-string wire format per `InteractiveKeyCodes`.
      // The daemon must accept the wire shape without faulting; a `tryDecode` failure on the
      // notification (missing `keyCode` field, unknown `kind` enum) would log an error to
      // stderr and we'd see it in the asserted-clean shutdown below.
      client.interactiveInput(
        frameStreamId = startResult.frameStreamId,
        kind = InteractiveInputKind.KEY_DOWN,
        keyCode = "29",
      )

      // 3. Counterpart KEY_UP — the same wire shape but the daemon's release path.
      client.interactiveInput(
        frameStreamId = startResult.frameStreamId,
        kind = InteractiveInputKind.KEY_UP,
        keyCode = "29",
      )

      // 4. Explicit `interactive/stop` — the daemon tears down the held session synchronously.
      // Issue #1229: pre-fix, this raced the `LaunchedEffect { requestFocus() }` in
      // `KeyPressColorSquare`, tripping a Compose `SnapshotStateObserver` multithread warning
      // (and a follow-on Skiko SIGABRT). The fix pins every scene touch — setUp, dispatch,
      // render, the listener's observer install/dispose, and tearDown — to a single
      // per-session executor thread inside `DesktopInteractiveSession`, so an explicit stop
      // here is wire-equivalent to letting shutdown drive the teardown.
      client.interactiveStop(frameStreamId = startResult.frameStreamId)

      // 5. Shutdown — daemon exits cleanly.
      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      // Sanity — no daemon-side serialisation / dispatch failures during the round-trip.
      val stderr = client.dumpStderr()
      assertFalse(
        "daemon stderr must not contain serialization / dispatch failures during the interactive " +
          "round-trip; got:\n$stderr",
        stderr.contains("SerializationException") ||
          stderr.contains("at ee.schimke.composeai.daemon.JsonRpcServer.tryDecode"),
      )
      // Issue #1229 regression guard — pre-fix, the explicit `interactive/stop` above raced the
      // `LaunchedEffect { requestFocus() }` in `KeyPressColorSquare` and tripped Compose's
      // multithread-access warning on the global `SnapshotStateObserver`. The per-session
      // scene executor pins every recomposer/effect/teardown step to one thread; if a future
      // change regresses that and the warning starts firing again, fail loudly here instead of
      // letting the follow-on Skiko SIGABRT take down the subprocess silently.
      assertFalse(
        "daemon stderr must not contain a SnapshotStateObserver multithread-access warning " +
          "(issue #1229 regression); got:\n$stderr",
        stderr.contains("Detected multithreaded access to SnapshotStateObserver"),
      )
    } catch (t: Throwable) {
      System.err.println(
        "SInteractiveKeyDispatchRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  companion object {
    private const val PREVIEW_ID = "key-press-color-square"
  }
}
