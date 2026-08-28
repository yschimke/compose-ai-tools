package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseCommandTest {
  @Test
  fun `browse supplies the streamlined local-project defaults`() {
    assertEquals(
      listOf(
        "--module",
        ":app",
        "--discover",
        "--component-browser",
        "--no-history",
        "--open-browser",
      ),
      BrowseCommand.serveArgs(listOf("--module", ":app")),
    )
  }

  @Test
  fun `browse does not duplicate defaults already supplied`() {
    val args =
      listOf("--discover", "--component-browser", "--no-history", "--port", "9000", "--no-open")

    assertEquals(args.filterNot { it == "--no-open" }, BrowseCommand.serveArgs(args))
  }

  @Test
  fun `browse with no options discovers the whole project`() {
    assertEquals(
      listOf("--discover", "--component-browser", "--no-history", "--open-browser"),
      BrowseCommand.serveArgs(emptyList()),
    )
  }
}
