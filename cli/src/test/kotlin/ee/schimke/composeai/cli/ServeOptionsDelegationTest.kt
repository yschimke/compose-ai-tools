package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two callbacks `ServeCommand` binds to CLI-owned policy: preview matching and init-script
 * injection.
 *
 * Both are tiny enough to look infallible and important enough to pin. The preview matcher once
 * forwarded through a same-named override that called itself until the stack went, with a green
 * suite because nothing exercised this Gradle-backed wiring path.
 */
class ServeOptionsDelegationTest {
  @Test
  fun `the preview matcher delegates to the shared rule instead of recursing`() {
    val cmd = ServeCommand(emptyList())
    // No selectors set: the shared rule matches everything. Recursion shows up as
    // StackOverflowError.
    assertTrue(
      cmd.options.previewIdMatchesRequest("com.example.FooKt.Bar", null, null, null, null, null)
    )
    // And it really is the shared rule, not a vacuous `true`.
    assertEquals(
      false,
      cmd.options.previewIdMatchesRequest(
        "com.example.FooKt.Bar",
        exactId = "other.Id",
        null,
        null,
        null,
        null,
      ),
    )
    assertTrue(
      cmd.options.previewIdMatchesRequest(
        "com.example.FooKt.Bar",
        null,
        filter = "foo",
        null,
        null,
        null,
      )
    )
  }

  @Test
  fun `the init-script binding delegates with args bound`() {
    // Different arity from the package-level function, so this one cannot self-resolve — asserted
    // so a future signature change that makes it able to is caught here rather than at runtime.
    val root = File(".").absoluteFile
    assertEquals(
      autoInjectInitScriptArgs(emptyList(), projectRoot = root),
      ServeCommand(emptyList()).autoInjectInitScriptArgs(root),
    )
  }
}
