package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem

/**
 * The whole-screen view is an enhancement over a catalog's grid, so every failure mode here must
 * degrade to "no screens" rather than taking the catalog down — which is what these cover, along
 * with the one rule that is easy to get subtly wrong: a page is only advertised when its backdrop
 * is a readable PNG, because the alternative is a stage that can paint nothing.
 */
class ServePageBackdropStoreTest {

  /** An 8-byte PNG signature is enough — the store checks the header, never decodes the image. */
  private val pngHeader =
    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(16)

  private fun store(
    json: String?,
    images: Map<String, ByteArray> = mapOf("upcoming.png" to pngHeader),
  ): ServePageBackdropStore {
    val root = Files.createTempDirectory("backdrops").toFile().also { it.deleteOnExit() }
    val dir = File(root, PageBackdropManifest.DIRECTORY)
    dir.mkdirs()
    if (json != null) File(dir, PageBackdropManifest.INDEX_FILE).writeText(json)
    images.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
    return ServePageBackdropStore.load(root, FileSystem.SYSTEM)
  }

  private fun manifest(
    pages: String,
    version: Int = 1,
    fileKey: String = "ocdacdEsnHipMJD3egzxKb",
  ) = """{"version":$version,"source":"figma","fileKey":"$fileKey","pages":[$pages]}"""

  private val upcoming =
    """
    {"id":"upcoming","name":"Upcoming-Mobile","nodeId":"56615:48121",
     "frame":{"width":412,"height":954},
     "image":{"uri":"upcoming.png","scale":2},
     "placements":[
       {"nodeId":"1:1","name":"App bar","bounds":{"x":0,"y":48,"width":412,"height":64},
        "depth":0,"ref":"figma:ocdacdEsnHipMJD3egzxKb/1:1","link":"manifest",
        "code":"sections/TopAppBars.kt#MediumTopAppBarSticker","previewId":"top-app-bar__light",
        "confidence":"high"},
       {"nodeId":"1:2","name":"Status bar","bounds":{"x":0,"y":0,"width":412,"height":48},
        "depth":0,"ref":"figma:ocdacdEsnHipMJD3egzxKb/1:2","link":"unlinked"}]}
    """
      .trimIndent()

  @Test
  fun `no manifest yields an empty store`() {
    assertTrue(store(null).pages.isEmpty())
  }

  @Test
  fun `malformed manifest is ignored rather than thrown`() {
    assertTrue(store("{ not json").pages.isEmpty())
  }

  @Test
  fun `a manifest from a future version is ignored wholesale`() {
    assertTrue(store(manifest(upcoming, version = 99)).pages.isEmpty())
  }

  @Test
  fun `a page and its placements survive a well-formed manifest`() {
    val loaded = store(manifest(upcoming))
    val page = assertNotNull(loaded.page("upcoming"))
    assertEquals("Upcoming-Mobile", page.name)
    assertEquals(412.0, page.frame.width)
    assertEquals(2, page.placements.size)
    assertEquals("top-app-bar__light", page.placements.first().previewId)
    assertTrue(page.placements.last().isUnlinked)
    assertEquals("ocdacdEsnHipMJD3egzxKb", loaded.fileKey)
    assertEquals(pngHeader.size, loaded.image("upcoming")?.size)
  }

  @Test
  fun `a page whose backdrop is missing or not a PNG is never advertised`() {
    // The whole point of checking at load: a page in the manifest with no drawable image would
    // otherwise render a stage that can only paint a broken image.
    assertTrue(store(manifest(upcoming), images = emptyMap()).pages.isEmpty())
    assertTrue(
      store(manifest(upcoming), images = mapOf("upcoming.png" to "<svg/>".toByteArray()))
        .pages
        .isEmpty()
    )
  }

  @Test
  fun `an image path that escapes the manifest directory is refused`() {
    val escaping = upcoming.replace("\"uri\":\"upcoming.png\"", "\"uri\":\"../../etc/passwd\"")
    assertTrue(store(manifest(escaping)).pages.isEmpty())
  }

  @Test
  fun `an unroutable page id is dropped and its siblings survive`() {
    val bad = upcoming.replace("\"id\":\"upcoming\"", "\"id\":\"../escape\"")
    val good =
      upcoming
        .replace("\"id\":\"upcoming\"", "\"id\":\"home\"")
        .replace("\"uri\":\"upcoming.png\"", "\"uri\":\"home.png\"")
    val loaded =
      store(
        manifest("$bad,$good"),
        images = mapOf("upcoming.png" to pngHeader, "home.png" to pngHeader),
      )
    assertEquals(listOf("home"), loaded.pages.map { it.id })
  }

  @Test
  fun `a duplicate page id keeps the first declaration`() {
    val second = upcoming.replace("\"name\":\"Upcoming-Mobile\"", "\"name\":\"Impostor\"")
    val loaded = store(manifest("$upcoming,$second"))
    assertEquals(1, loaded.pages.size)
    assertEquals("Upcoming-Mobile", loaded.pages.single().name)
  }

  @Test
  fun `a page with no usable frame is dropped, and an undrawable placement with it`() {
    val zeroFrame = upcoming.replace("\"width\":412,\"height\":954", "\"width\":0,\"height\":954")
    assertTrue(store(manifest(zeroFrame)).pages.isEmpty())

    val zeroBounds =
      upcoming.replace(
        "\"bounds\":{\"x\":0,\"y\":48,\"width\":412,\"height\":64}",
        "\"bounds\":{\"x\":0,\"y\":48,\"width\":0,\"height\":64}",
      )
    val loaded = store(manifest(zeroBounds))
    assertEquals(1, loaded.pages.single().placements.size)
    assertEquals("Status bar", loaded.pages.single().placements.single().name)
  }

  @Test
  fun `a hostile file key yields no deep link rather than an attacker-chosen href`() {
    val loaded = store(manifest(upcoming, fileKey = "javascript:alert(1)"))
    assertEquals("", loaded.fileKey)
    assertNull(ServeFigmaSpec.url(loaded.fileKey, "56615:48121"))
  }

  @Test
  fun `an unknown link method reads as unlinked`() {
    val odd = upcoming.replace("\"link\":\"manifest\"", "\"link\":\"vibes\"")
    // The four methods are the contract. A fifth must not be drawn in the "linked" colour or
    // counted as coverage — so it degrades to unlinked, the safe reading.
    val placement = store(manifest(odd)).pages.single().placements.first()
    assertEquals(BackdropPlacement.LINK_UNLINKED, placement.link)
    assertTrue(placement.isUnlinked)
  }
}
