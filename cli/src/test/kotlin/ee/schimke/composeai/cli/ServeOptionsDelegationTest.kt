package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ServeCommand`'s implementations of the [ee.schimke.composeai.cli.serve.ServeOptions] members
 * that exist to bind something `:cli` has and the server must not see.
 *
 * These are one-line delegations, which is exactly why they need a test: each forwards to a
 * package-level function of the *same name*, and an unqualified call inside an override resolves to
 * the override. `previewIdMatchesRequest` shipped that way and recursed until the stack went — with
 * a fully green `:cli:test`, because nothing here exercised the Gradle-backed serve path. A
 * delegation that calls itself is invisible to every test that does not call it.
 */
class ServeOptionsDelegationTest {
  @Test
  fun `the preview matcher delegates to the shared rule instead of recursing`() {
    val cmd = ServeCommand(emptyList())
    // No selectors set: the shared rule matches everything. Recursion shows up as
    // StackOverflowError.
    assertTrue(cmd.previewIdMatchesRequest("com.example.FooKt.Bar", null, null, null, null, null))
    // And it really is the shared rule, not a vacuous `true`.
    assertEquals(
      false,
      cmd.previewIdMatchesRequest(
        "com.example.FooKt.Bar",
        exactId = "other.Id",
        null,
        null,
        null,
        null,
      ),
    )
    assertTrue(
      cmd.previewIdMatchesRequest("com.example.FooKt.Bar", null, filter = "foo", null, null, null)
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
