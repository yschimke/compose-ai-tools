package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the player ids: each names the implementation that draws, and `cmp-` means the CMP player.
 */
class RcPlayerBackendTest {

  @Test
  fun `every backend's wire id is the implementation name`() {
    assertEquals(
      mapOf(
        RcPlayerBackend.CAMAELON_JS to "camaelon-js",
        RcPlayerBackend.CMP_WASM to "cmp-wasm",
        RcPlayerBackend.ANDROIDX_VIEW to "androidx-view",
        RcPlayerBackend.ANDROIDX_EMBEDDED to "androidx-embedded",
        RcPlayerBackend.CMP_ANDROID to "cmp-android",
        RcPlayerBackend.CMP_JVM to "cmp-jvm",
      ),
      RcPlayerBackend.entries.associateWith { it.wire },
    )
    for (backend in RcPlayerBackend.entries) {
      assertEquals(backend, RcPlayerBackend.fromWire(backend.wire))
    }
  }

  @Test
  fun `legacy spellings are still accepted on input`() {
    val legacy =
      mapOf(
        "java" to RcPlayerBackend.ANDROIDX_VIEW,
        "view" to RcPlayerBackend.ANDROIDX_VIEW,
        "embedded" to RcPlayerBackend.ANDROIDX_EMBEDDED,
        "rcplayer-jvm" to RcPlayerBackend.CMP_JVM,
        "rcplayer-wasm" to RcPlayerBackend.CMP_WASM,
        "js" to RcPlayerBackend.CAMAELON_JS,
        " JAVA " to RcPlayerBackend.ANDROIDX_VIEW,
      )
    for ((raw, backend) in legacy) assertEquals(backend, RcPlayerBackend.fromWire(raw), raw)
  }

  @Test
  fun `retired and stale names name nothing`() {
    assertNull(RcPlayerBackend.fromWire("cmp"))
    assertNull(RcPlayerBackend.fromWire("androidx-embedded-jvm"))
    assertNull(RcPlayerBackend.fromWire(null))
  }

  @Test
  fun `cmp-android as a request is the CMP player, reached by playerId`() {
    val backend = RcPlayerBackend.fromWire("cmp-android")
    assertEquals(RcPlayerBackend.CMP_ANDROID, backend)
    assertNull(backend?.playerKind)
    assertEquals("cmp-android", backend?.daemonPlayerId)
    assertEquals(RcPlayerBackend.CMP_ANDROID, RcPlayerBackend.serverSideFromParam("cmp-android"))
    assertFalse(RcPlayerBackend.isNonDaemonLane("cmp-android"))
  }

  @Test
  fun `the AndroidX players ride the player enum`() {
    assertEquals(RemoteComposePlayerKind.VIEW, RcPlayerBackend.ANDROIDX_VIEW.playerKind)
    assertEquals(RemoteComposePlayerKind.EMBEDDED, RcPlayerBackend.ANDROIDX_EMBEDDED.playerKind)
    assertNull(RcPlayerBackend.ANDROIDX_VIEW.daemonPlayerId)
    assertNull(RcPlayerBackend.ANDROIDX_EMBEDDED.daemonPlayerId)
  }

  @Test
  fun `lanes that never ride the daemon are recognised by canonical and legacy ids`() {
    for (raw in
      listOf("camaelon-js", "js", "cmp-wasm", "rcplayer-wasm", "cmp-jvm", "rcplayer-jvm")) {
      assertTrue(RcPlayerBackend.isNonDaemonLane(raw), raw)
      assertNull(RcPlayerBackend.serverSideFromParam(raw), raw)
    }
  }

  @Test
  fun `a legacy capturePlayer cmp-android reads as the AndroidX embedded player`() {
    // Older daemons recorded `cmp-android` for the embedded player and `java` for the View player.
    assertEquals(
      RcPlayerBackend.ANDROIDX_EMBEDDED,
      RcPlayerBackend.fromCapturePlayer("cmp-android"),
    )
    assertEquals(RcPlayerBackend.ANDROIDX_VIEW, RcPlayerBackend.fromCapturePlayer("java"))
    // What new daemons write.
    assertEquals(
      RcPlayerBackend.ANDROIDX_EMBEDDED,
      RcPlayerBackend.fromCapturePlayer("androidx-embedded"),
    )
    assertEquals(RcPlayerBackend.ANDROIDX_VIEW, RcPlayerBackend.fromCapturePlayer("androidx-view"))
    // A capture is only ever drawn by an AndroidX player.
    assertNull(RcPlayerBackend.fromCapturePlayer("cmp-jvm"))
    assertNull(RcPlayerBackend.fromCapturePlayer("camaelon-js"))
    assertNull(RcPlayerBackend.fromCapturePlayer(null))
  }

  @Test
  fun `rc-compare column ids are unchanged`() {
    // They key assets staged in already-published catalogs.
    assertEquals("js", RcPlayerBackend.CAMAELON_JS.rcCompareLane)
    assertEquals("embedded", RcPlayerBackend.ANDROIDX_EMBEDDED.rcCompareLane)
    assertEquals("cmp-jvm", RcPlayerBackend.CMP_JVM.rcCompareLane)
    assertEquals("cmp-wasm", RcPlayerBackend.CMP_WASM.rcCompareLane)
    assertNull(RcPlayerBackend.ANDROIDX_VIEW.rcCompareLane)
    assertNull(RcPlayerBackend.CMP_ANDROID.rcCompareLane)
  }
}
