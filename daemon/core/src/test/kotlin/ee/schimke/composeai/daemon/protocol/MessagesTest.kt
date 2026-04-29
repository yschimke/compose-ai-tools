package ee.schimke.composeai.daemon.protocol

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trips each protocol message through kotlinx.serialization and asserts structural equality
 * with the on-disk fixture under
 * [docs/daemon/protocol-fixtures/](../../../../../../../docs/daemon/protocol-fixtures/).
 *
 * The same fixtures are consumed by Stream C's TypeScript test suite — they are the cross-language
 * source of truth for the wire format. A round-trip here protects against three regressions:
 *
 * 1. Field rename without updating the fixture.
 * 2. Missing `@SerialName` on an enum value the JSON spells differently.
 * 3. Required field becoming optional or vice-versa silently.
 *
 * Comparison is done at the [JsonElement] level (not raw text) so whitespace, key ordering, and
 * trailing-newline differences in the fixtures don't make the test brittle.
 */
class MessagesTest {

  private val json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
  }

  @Test fun roundTripInitializeParams() = roundTrip<InitializeParams>("client-initialize.json")

  @Test
  fun roundTripInitializeResult() = roundTrip<InitializeResult>("daemon-initializeResult.json")

  @Test fun roundTripSetVisibleParams() = roundTrip<SetVisibleParams>("client-setVisible.json")

  @Test fun roundTripSetFocusParams() = roundTrip<SetFocusParams>("client-setFocus.json")

  @Test fun roundTripFileChangedParams() = roundTrip<FileChangedParams>("client-fileChanged.json")

  @Test fun roundTripRenderNowParams() = roundTrip<RenderNowParams>("client-renderNow.json")

  @Test fun roundTripRenderNowResult() = roundTrip<RenderNowResult>("daemon-renderNowResult.json")

  @Test
  fun roundTripDiscoveryUpdatedParams() =
    roundTrip<DiscoveryUpdatedParams>("daemon-discoveryUpdated.json")

  @Test
  fun roundTripRenderStartedParams() = roundTrip<RenderStartedParams>("daemon-renderStarted.json")

  @Test
  fun roundTripRenderFinishedParams() =
    roundTrip<RenderFinishedParams>("daemon-renderFinished.json")

  @Test
  fun roundTripRenderFailedParams() = roundTrip<RenderFailedParams>("daemon-renderFailed.json")

  @Test
  fun roundTripClasspathDirtyParams() =
    roundTrip<ClasspathDirtyParams>("daemon-classpathDirty.json")

  @Test
  fun roundTripSandboxRecycleParams() =
    roundTrip<SandboxRecycleParams>("daemon-sandboxRecycle.json")

  @Test
  fun roundTripDaemonWarmingParams() = roundTrip<DaemonWarmingParams>("daemon-daemonWarming.json")

  @Test
  fun roundTripDaemonReadyParams() {
    // Empty-object payload — read raw, decode, re-encode, compare.
    val text = fixture("daemon-daemonReady.json")
    val parsed = json.decodeFromString(DaemonReadyParams.serializer(), text)
    val reEncoded = json.encodeToString(DaemonReadyParams.serializer(), parsed)
    assertEquals(json.parseToJsonElement(text), json.parseToJsonElement(reEncoded))
  }

  @Test fun roundTripLogParams() = roundTrip<LogParams>("daemon-log.json")

  @Test fun roundTripJsonRpcRequest() = roundTrip<JsonRpcRequest>("envelope-request.json")

  @Test fun roundTripJsonRpcResponse() = roundTrip<JsonRpcResponse>("envelope-response.json")

  @Test
  fun roundTripJsonRpcNotification() = roundTrip<JsonRpcNotification>("envelope-notification.json")

  @Test
  fun roundTripJsonRpcErrorResponse() {
    val text = fixture("envelope-errorResponse.json")
    val parsed = json.decodeFromString(JsonRpcResponse.serializer(), text)
    val reEncoded = json.encodeToString(JsonRpcResponse.serializer(), parsed)
    assertEquals(json.parseToJsonElement(text), json.parseToJsonElement(reEncoded))
  }

  /**
   * Smoke-test that fixture coverage is at least as broad as the inventory in
   * [docs/daemon/protocol-fixtures/README.md]. This catches a "we added a new message but forgot
   * the fixture" regression in PR review.
   */
  @Test
  fun fixtureInventoryMatchesExpected() {
    val present = fixturesDir().list()?.filter { it.endsWith(".json") }?.toSet().orEmpty()
    val expected =
      setOf(
        "client-initialize.json",
        "daemon-initializeResult.json",
        "client-setVisible.json",
        "client-setFocus.json",
        "client-fileChanged.json",
        "client-renderNow.json",
        "daemon-renderNowResult.json",
        "daemon-discoveryUpdated.json",
        "daemon-renderStarted.json",
        "daemon-renderFinished.json",
        "daemon-renderFailed.json",
        "daemon-classpathDirty.json",
        "daemon-sandboxRecycle.json",
        "daemon-daemonWarming.json",
        "daemon-daemonReady.json",
        "daemon-log.json",
        "envelope-request.json",
        "envelope-response.json",
        "envelope-notification.json",
        "envelope-errorResponse.json",
      )
    val missing = expected - present
    assertEquals("missing protocol fixtures: $missing", emptySet<String>(), missing)
  }

  // -- helpers ----------------------------------------------------------------

  private inline fun <reified T : Any> roundTrip(fixtureName: String) {
    val text = fixture(fixtureName)
    val serializer = kotlinx.serialization.serializer<T>()
    val parsed = json.decodeFromString(serializer, text)
    val reEncoded = json.encodeToString(serializer, parsed)
    val originalTree = json.parseToJsonElement(text)
    val reEncodedTree = json.parseToJsonElement(reEncoded)
    assertEquals("round-trip mismatch for $fixtureName", originalTree, reEncodedTree)
  }

  private fun fixture(name: String): String = File(fixturesDir(), name).readText()

  private fun fixturesDir(): File {
    // Walk up from the working directory until we find docs/daemon/
    // protocol-fixtures. Robolectric tests get a per-test cwd that is the
    // module's project dir, so a relative path of "../docs/..." works in
    // both Gradle and IDE runs.
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "docs/daemon/protocol-fixtures")
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    error("could not locate docs/daemon/protocol-fixtures from ${System.getProperty("user.dir")}")
  }
}
