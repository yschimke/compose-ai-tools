package ee.schimke.composeai.cli

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The CLI copy of compose-preview-server's deep-link builder (#1241), against the same spec rules
 * and the same expectations as the server's `OpenAiDeepLinksTest`, plus `show --link`'s parsing.
 */
class OpenAiDeepLinksTest {
  private val marketplacePlugin = OpenAiPlugin("compose-preview", marketplace = "yschimke")
  private val directPlugin = OpenAiPlugin("plugin_abc123")

  @Test
  fun `spec example round-trips`() {
    assertEquals(
      "codex://plugins/bits-and-bolts/app/cad.library?path=%2Fparts%3Ftag%3Dbolt%26sort%3Dasc",
      OpenAiDeepLinks.link(
        OpenAiPlugin("bits-and-bolts"),
        "cad.library",
        "/parts?tag=bolt&sort=asc",
      ),
    )
  }

  @Test
  fun `desktop, mobile and web forms`() {
    val path = "/project/ws-1"
    assertEquals(
      "codex://plugins/compose-preview@yschimke/app/previews_library?path=%2Fproject%2Fws-1",
      OpenAiDeepLinks.link(marketplacePlugin, OpenAiDeepLinks.LIBRARY_TOOL, path),
    )
    assertEquals(
      "chatgpt://plugins/compose-preview@yschimke/app/previews_library?path=%2Fproject%2Fws-1",
      OpenAiDeepLinks.link(
        marketplacePlugin,
        OpenAiDeepLinks.LIBRARY_TOOL,
        path,
        OpenAiDeepLinks.Surface.MOBILE,
      ),
    )
    // The web form carries no marketplace.
    assertEquals(
      "https://chatgpt.com/plugins/compose-preview/app/previews_library?path=%2Fproject%2Fws-1",
      OpenAiDeepLinks.link(
        marketplacePlugin,
        OpenAiDeepLinks.LIBRARY_TOOL,
        path,
        OpenAiDeepLinks.Surface.WEB,
      ),
    )
    // A plugin published directly to ChatGPT omits `@{marketplace}`.
    assertEquals(
      "codex://plugins/plugin_abc123/app/catalog_library",
      OpenAiDeepLinks.link(directPlugin, "catalog_library"),
    )
  }

  @Test
  fun `the path begins with a slash, has no fragment, and root is omitted`() {
    assertFailsWith<IllegalArgumentException> {
      OpenAiDeepLinks.link(directPlugin, "t", "preview/x")
    }
    assertFailsWith<IllegalArgumentException> {
      OpenAiDeepLinks.link(directPlugin, "t", "/preview/x#frag")
    }
    assertFalse(OpenAiDeepLinks.link(directPlugin, "t", "/").contains("?path="))
  }

  @Test
  fun `a preview URI is encoded twice and decodes back to the library route`() {
    val uri = "compose-preview://ws-1/_app/com.example.HomeKt.HomePreview?config=dark&x=a b"
    val path = OpenAiDeepLinks.previewPath(uri)
    assertTrue(path.startsWith("/preview/compose-preview%3A%2F%2Fws-1%2F_app%2F"), path)
    assertFalse(path.removePrefix("/preview/").contains("/"))
    val link = OpenAiDeepLinks.previewLink(marketplacePlugin, uri)
    assertFalse(link.contains('#'))

    // What the host does: take the `path` query value and percent-decode it once.
    val query = URI(link).rawQuery
    assertTrue(query.startsWith("path="), query)
    val appPath = OpenAiDeepLinks.decode(query.removePrefix("path="))
    assertEquals(path, appPath)
    assertTrue(appPath.startsWith("/"))
    assertFalse(appPath.contains("#"))
    // Then the app decodes its route segment back to the URI.
    assertEquals(uri, OpenAiDeepLinks.decode(appPath.removePrefix("/preview/")))
  }

  @Test
  fun `the link is a valid URI with the plugin id and tool name as single segments`() {
    val odd = OpenAiPlugin("my plugin/1", marketplace = "team@corp")
    val link =
      OpenAiDeepLinks.link(odd, "ui builder/open", "/design/${OpenAiDeepLinks.encode("d 1")}")
    assertEquals(
      "codex://plugins/my%20plugin%2F1@team%40corp/app/ui%20builder%2Fopen?path=%2Fdesign%2Fd%25201",
      link,
    )
    val parsed = URI(link)
    assertEquals("codex", parsed.scheme)
    assertEquals(
      listOf("my%20plugin%2F1@team%40corp", "app", "ui%20builder%2Fopen"),
      parsed.rawPath.split('/').filter { it.isNotEmpty() },
    )
  }

  @Test
  fun `encode leaves only unreserved characters and decode inverts it`() {
    val raw = "/a b?c=d&e=f/g:h@i+j~k_l.m-n/ü😀"
    val encoded = OpenAiDeepLinks.encode(raw)
    assertTrue(Regex("[A-Za-z0-9._~%-]*").matches(encoded), encoded)
    assertTrue(encoded.contains("%20"))
    assertTrue(encoded.contains("%2B"))
    assertEquals(raw, OpenAiDeepLinks.decode(encoded))
    assertFailsWith<IllegalArgumentException> { OpenAiDeepLinks.decode("%2") }
    assertFailsWith<IllegalArgumentException> { OpenAiDeepLinks.decode("%zz") }
  }

  @Test
  fun `plugin comes from the environment and is never guessed`() {
    assertNull(OpenAiPlugin.fromEnvironment(emptyMap()))
    assertNull(OpenAiPlugin.fromEnvironment(mapOf(OpenAiPlugin.PLUGIN_ID_ENV to " ")))
    assertEquals(
      OpenAiPlugin("cp", "mk"),
      OpenAiPlugin.fromEnvironment(
        mapOf(OpenAiPlugin.PLUGIN_ID_ENV to "cp", OpenAiPlugin.MARKETPLACE_ENV to "mk")
      ),
    )
    assertEquals(
      OpenAiPlugin("cp"),
      OpenAiPlugin.fromEnvironment(mapOf(OpenAiPlugin.PLUGIN_ID_ENV to "cp")),
    )
  }

  @Test
  fun `show --link without a plugin id prints the guidance instead of a link`() {
    assertEquals(ShowLinkRequest.Off, ShowLinkRequest.parse(listOf("--json"), emptyMap()))
    val missing = ShowLinkRequest.parse(listOf("--link", "--json"), emptyMap())
    assertIs<ShowLinkRequest.Unavailable>(missing)
    assertEquals(ShowLinkRequest.GUIDANCE, missing.message)
    assertTrue(missing.message.contains("--openai-plugin-id"))
    assertTrue(missing.message.contains(OpenAiPlugin.PLUGIN_ID_ENV))
    assertFalse(missing.message.contains("://"), "guidance must not look like a link")
    // A marketplace alone is not enough.
    assertIs<ShowLinkRequest.Unavailable>(
      ShowLinkRequest.parse(listOf("--link", "--openai-marketplace", "mk"), emptyMap())
    )
  }

  @Test
  fun `show --link - flags beat the environment, and the surface picks the scheme`() {
    val env =
      mapOf(OpenAiPlugin.PLUGIN_ID_ENV to "env-id", OpenAiPlugin.MARKETPLACE_ENV to "env-mk")
    assertEquals(
      ShowLinkRequest.Ready(OpenAiPlugin("env-id", "env-mk"), OpenAiDeepLinks.Surface.DESKTOP),
      ShowLinkRequest.parse(listOf("--link"), env),
    )
    assertEquals(
      ShowLinkRequest.Ready(OpenAiPlugin("flag-id", "env-mk"), OpenAiDeepLinks.Surface.WEB),
      ShowLinkRequest.parse(listOf("--link=web", "--openai-plugin-id", "flag-id"), env),
    )
    assertEquals(
      ShowLinkRequest.Ready(OpenAiPlugin("flag-id", "flag-mk"), OpenAiDeepLinks.Surface.MOBILE),
      ShowLinkRequest.parse(
        listOf("--link=mobile", "--openai-plugin-id=flag-id", "--openai-marketplace=flag-mk"),
        emptyMap(),
      ),
    )
    assertIs<ShowLinkRequest.Unavailable>(ShowLinkRequest.parse(listOf("--link=fax"), env))
  }

  @Test
  fun `the preview URI matches the server's compose-preview URI shape`() {
    val root = Files.createTempDirectory("ws").toFile()
    val uri = requireNotNull(composePreviewUri(root, "samples:android", "com.example.FooKt.Foo"))
    val canonical = root.canonicalFile
    val workspace =
      ee.schimke.composeai.daemon.client.WorkspaceId.derive(canonical.name, canonical).value
    assertEquals("compose-preview://$workspace/_samples_android/com.example.FooKt.Foo", uri)
    assertEquals(uri, composePreviewUri(root, ":samples:android", "com.example.FooKt.Foo"))
    assertNull(composePreviewUri(root, "app", "a/b"))
    assertNull(composePreviewUri(root, "app", "a?b"))
    assertNull(composePreviewUri(File(root, "x"), "app", ""))
  }

  @Test
  fun `json output gains a link per preview, by position`() {
    val encoded =
      """{"schema":"compose-preview-show/v2","previews":[{"id":"a"},{"id":"b"}],"counts":null}"""
    val out = injectPreviewLinks(encoded, listOf("codex://x", null), pretty = false)
    val previews = Json.parseToJsonElement(out).jsonObject.getValue("previews").jsonArray
    assertEquals("codex://x", previews[0].jsonObject.getValue("link").jsonPrimitive.content)
    assertNull(previews[1].jsonObject["link"])
    assertEquals("b", previews[1].jsonObject.getValue("id").jsonPrimitive.content)
  }
}
