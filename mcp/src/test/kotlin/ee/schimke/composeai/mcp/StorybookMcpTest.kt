package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import org.junit.Test

/** Unit coverage for the pure Storybook-MCP id / resolution adapter ([StorybookMcp]). */
class StorybookMcpTest {

  private val ws = WorkspaceId("demo-1a2b3c4d")

  private fun previewUri(module: String, fqn: String, config: String? = null): String =
    PreviewUri(workspaceId = ws, modulePath = module, previewFqn = fqn, config = config).toUri()

  private fun resource(uri: String, name: String, description: String?): ResourceDescriptor =
    ResourceDescriptor(uri = uri, name = name, description = description, mimeType = "image/png")

  @Test
  fun sanitize_and_toId_follow_the_CSF_convention() {
    assertThat(StorybookMcp.sanitize("Red Box")).isEqualTo("red-box")
    assertThat(StorybookMcp.sanitize("com.example/PreviewsKt")).isEqualTo("com-example-previewskt")
    assertThat(StorybookMcp.toId("samples/android/Previews", "Red Box"))
      .isEqualTo("samples-android-previews--red-box")
    // A blank side is dropped rather than leaving a dangling separator.
    assertThat(StorybookMcp.toId("", "Greeting")).isEqualTo("greeting")
  }

  @Test
  fun stories_mint_module_grouped_titles_from_the_uri() {
    val uri = previewUri(":samples:android", "com.example.PreviewsKt.RedBoxPreview")
    val stories = StorybookMcp.stories(listOf(resource(uri, "RedBoxPreview", "Red Box")))
    assertThat(stories).hasSize(1)
    val s = stories.single()
    assertThat(s.title).isEqualTo("samples/android/Previews")
    assertThat(s.name).isEqualTo("Red Box")
    assertThat(s.storyId).isEqualTo("samples-android-previews--red-box")
    assertThat(s.uri).isEqualTo(uri)
    assertThat(s.fqn).isEqualTo("com.example.PreviewsKt.RedBoxPreview")
  }

  @Test
  fun name_falls_back_to_the_function_and_folds_in_the_config_variant() {
    // No display name (description == fqn) → the function simple name; config qualifier appended.
    val fqn = "com.example.MainKt.GreetingPreview"
    val uri = previewUri(":app", fqn, config = "Dark")
    val stories = StorybookMcp.stories(listOf(resource(uri, "GreetingPreview", fqn)))
    val s = stories.single()
    assertThat(s.title).isEqualTo("app/Main")
    assertThat(s.name).isEqualTo("GreetingPreview (Dark)")
    assertThat(s.storyId).isEqualTo("app-main--greetingpreview-dark")
  }

  @Test
  fun colliding_slugs_get_deterministic_suffixes_and_stay_one_to_one() {
    // Two previews in the same file with the same display name derive the same slug; the second
    // gets a deterministic suffix, and each id still round-trips to its own distinct uri.
    val primary = previewUri(":ui", "com.example.ButtonsKt.PrimaryPreview")
    val secondary = previewUri(":ui", "com.example.ButtonsKt.SecondaryPreview")
    val resources =
      listOf(
        resource(primary, "PrimaryPreview", "Button"),
        resource(secondary, "SecondaryPreview", "Button"),
      )
    val stories = StorybookMcp.stories(resources)
    assertThat(stories.map { it.storyId })
      .containsExactly("ui-buttons--button", "ui-buttons--button-2")
      .inOrder()
    assertThat(StorybookMcp.resolveUri("ui-buttons--button", resources)).isEqualTo(primary)
    assertThat(StorybookMcp.resolveUri("ui-buttons--button-2", resources)).isEqualTo(secondary)
  }

  @Test
  fun resolveUri_matches_minted_ids_and_the_native_uri_escape_hatch() {
    val uri = previewUri(":samples:android", "com.example.PreviewsKt.RedBoxPreview")
    val resources = listOf(resource(uri, "RedBoxPreview", "Red Box"))
    // Minted story id → native uri.
    assertThat(StorybookMcp.resolveUri("samples-android-previews--red-box", resources))
      .isEqualTo(uri)
    // Raw native uri accepted verbatim when it's in the catalog.
    assertThat(StorybookMcp.resolveUri(uri, resources)).isEqualTo(uri)
    // Unknown id / uri not in the catalog → null.
    assertThat(StorybookMcp.resolveUri("no--such", resources)).isNull()
    assertThat(StorybookMcp.resolveUri(previewUri(":other", "com.other.Foo"), resources)).isNull()
  }
}
