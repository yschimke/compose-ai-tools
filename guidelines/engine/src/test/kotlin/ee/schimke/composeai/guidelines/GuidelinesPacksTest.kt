package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shared rule packs under `guidelines/packs/`, which catalogs layer through `includes`:
 * `compose-ui` everywhere Compose runs, `wear-compose` over it on Wear, and `remote-compose` with
 * `launcher-widgets` or `wear-widgets` for Remote Compose widgets.
 */
class GuidelinesPacksTest {
  private val dir = File(System.getProperty("guidelines.packs.dir", "../packs"))

  private val texts: Map<String, String> =
    dir
      .listFiles { file -> file.name.endsWith(".guidelines.json") }!!
      .associate { it.name.removeSuffix(".guidelines.json") to it.readText() }

  private val packs: Map<String, CatalogGuidelinesV1> = texts.mapValues { (name, text) ->
    val loaded = CatalogGuidelinesLoader.parse(text)
    assertWithMessage(name).that(loaded.problem).isNull()
    loaded.guidelines!!
  }

  @Test
  fun `the packs are the five layers, flat and within bounds`() {
    assertThat(packs.keys).containsExactlyElementsIn(PACKS)
    texts.forEach { (name, text) ->
      assertWithMessage(name).that(GuidelinesIncludes.declared(text)).isEmpty()
      assertWithMessage(name)
        .that(text.toByteArray().size)
        .isLessThan(GuidelinesIncludes.MAX_PACK_BYTES)
      assertWithMessage(name).that(packs.getValue(name).catalog).isEqualTo(name)
      assertWithMessage(name).that(packs.getValue(name).about).isNotEmpty()
    }
  }

  @Test
  fun `every rule quotes official guidance, links it and asks a known question`() {
    packs.forEach { (name, pack) ->
      pack.rules.forEach { rule ->
        val at = "$name ${rule.id}"
        assertWithMessage(at).that(rule.guidance.trim()).isNotEmpty()
        assertWithMessage(at).that(rule.source).matches("https://developer\\.android\\.com/.+")
        assertWithMessage(at).that(rule.check).contains("?")
        assertWithMessage(at)
          .that(rule.kind)
          .isIn(listOf(GuidelineRuleV1.KIND_STRUCTURE, GuidelineRuleV1.KIND_VISUAL))
        assertWithMessage(at)
          .that(rule.severity)
          .isIn(listOf(GuidelineRuleV1.SEVERITY_WARNING, GuidelineRuleV1.SEVERITY_INFO))
        assertWithMessage(at).that(rule.scope).isEqualTo(GuidelineRuleV1.SCOPE_SUBJECT)
        assertWithMessage(at).that(PLATFORMS).containsAtLeastElementsIn(rule.platforms)
        assertWithMessage(at).that(SURFACES).containsAtLeastElementsIn(rule.surfaces)
        assertWithMessage(at).that(PROFILES).containsAtLeastElementsIn(rule.profiles)
      }
    }
  }

  @Test
  fun `ids are unique across packs, except the overrides a later layer declares`() {
    packs.forEach { (name, pack) ->
      assertWithMessage(name).that(pack.rules.map { it.id }).containsNoDuplicates()
    }
    val owners = mutableMapOf<String, String>()
    for ((name, pack) in packs) {
      for (rule in pack.rules) {
        val earlier = owners[rule.id]
        if (earlier == null) {
          owners[rule.id] = name
          continue
        }
        val declared = OVERRIDES[name to earlier].orEmpty() + OVERRIDES[earlier to name].orEmpty()
        assertWithMessage("${rule.id} is in both $earlier and $name")
          .that(declared)
          .contains(rule.id)
      }
    }
    // Every declared override really replaces a rule of the layer beneath it.
    OVERRIDES.forEach { (layers, ids) ->
      val (upper, lower) = layers
      ids.forEach { id ->
        assertWithMessage("$upper overrides $id")
          .that(packs.getValue(upper).rules.map { it.id })
          .contains(id)
        assertWithMessage("$upper overrides $id")
          .that(packs.getValue(lower).rules.map { it.id })
          .contains(id)
      }
    }
  }

  @Test
  fun `remote and widget packs carry no text-entry rule, and widget packs ask only of widgets`() {
    for (name in listOf("remote-compose", "launcher-widgets", "wear-widgets")) {
      packs.getValue(name).rules.forEach { rule ->
        TEXT_ENTRY.forEach { word ->
          assertWithMessage("$name ${rule.id}").that(rule.id).doesNotContain(word)
        }
      }
    }
    for (name in listOf("launcher-widgets", "wear-widgets")) {
      packs.getValue(name).rules.forEach {
        assertWithMessage("$name ${it.id}")
          .that(it.surfaces)
          .containsExactly(GuidelineSurfaces.WIDGET)
      }
    }
    packs
      .getValue("compose-ui")
      .rules
      .filter { rule -> TEXT_ENTRY.any { it in rule.id } }
      .forEach {
        assertWithMessage(it.id).that(it.surfaces).doesNotContain(GuidelineSurfaces.WIDGET)
      }
  }

  @Test
  fun `each catalog's layering resolves as the matrix says`() {
    // wear-m3: compose-ui, then wear-compose, which replaces the text-entry and sign-in rules.
    val wear = merged("wear", "compose-ui", "wear-compose")
    val wearById = wear.rules.associateBy { it.id }
    assertThat(wear.rules.map { it.id }).containsNoDuplicates()
    OVERRIDES.getValue("wear-compose" to "compose-ui").forEach {
      assertThat(wearById.getValue(it).source).contains("/training/wearables/")
    }
    assertThat(wearById.getValue("compose.input.keyboard-options").check)
      .contains("RemoteInputIntentHelper")
    assertThat(wearById.getValue("compose.sign-in.credential-manager").check)
      .contains("RemoteAuthClient")
    assertThat(wearById).containsKey("wear-compose.rotary.scrolling")
    assertThat(wearById).doesNotContainKey("compose.theme.dark-theme")

    // m3-catalog: compose-ui alone keeps the phone versions.
    val mobile = merged("mobile", "compose-ui").rules.associateBy { it.id }
    assertThat(mobile.getValue("compose.input.keyboard-options").check).contains("KeyboardOptions")
    assertThat(mobile).containsKey("compose.theme.dark-theme")
    assertThat(mobile.keys.filter { it.startsWith("wear-compose.") }).isEmpty()

    // glimmer-catalog: compose-ui without its touch-target rule.
    assertThat(merged("glasses", "compose-ui").rules.map { it.id })
      .doesNotContain("compose.a11y.touch-target")

    // remote-m3's catalogs: remote-compose with the widget pack for their host.
    for ((platform, widgets) in
      listOf("wear" to "wear-widgets", "launcher" to "launcher-widgets")) {
      val ids = merged(platform, "remote-compose", widgets).rules.map { it.id }
      assertThat(ids).containsNoDuplicates()
      assertThat(ids.filter { id -> TEXT_ENTRY.any { it in id } }).isEmpty()
    }
  }

  /**
   * Every guidance is quoted word for word from its source page. Needs the network, so it runs only
   * with GUIDELINES_VERIFY_QUOTES=1; a quote may skip words with ` … `.
   */
  @Test
  fun `every guidance appears verbatim on its source page`() {
    assumeTrue(System.getenv("GUIDELINES_VERIFY_QUOTES") == "1")
    val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    val pages = mutableMapOf<String, String>()
    val missing = mutableListOf<String>()
    for ((name, pack) in packs) {
      for (rule in pack.rules) {
        val page =
          pages.getOrPut(rule.source) {
            http.newCall(Request.Builder().url(rule.source).build()).execute().use {
              pageText(it.body.string())
            }
          }
        rule.guidance
          .split(" … ")
          .map(::normalise)
          .filterNot { it in page }
          .forEach { missing += "$name ${rule.id}: $it" }
      }
    }
    assertThat(missing).isEmpty()
  }

  private fun pageText(html: String): String =
    normalise(
      html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
    )

  private fun normalise(text: String): String =
    text
      .replace('’', '\'')
      .replace('“', '"')
      .replace('”', '"')
      .replace(' ', ' ')
      .replace(Regex("\\s+"), " ")
      .replace(Regex(" ([.,)])"), "$1")
      .trim()

  private fun merged(platform: String, vararg names: String): CatalogGuidelinesV1 =
    GuidelinesIncludes.merge(
      CatalogGuidelinesV1.Builder("c", platform, 1).build(),
      names.map {
        GuidelinesIncludes.Include("https://example.com/$it.json", "0".repeat(64)) to
          packs.getValue(it)
      },
    )

  private companion object {
    val PACKS =
      listOf("compose-ui", "wear-compose", "remote-compose", "launcher-widgets", "wear-widgets")

    /** Rules a later layer replaces on purpose: (upper, lower) to the ids. */
    val OVERRIDES: Map<Pair<String, String>, Set<String>> =
      mapOf(
        ("wear-compose" to "compose-ui") to
          setOf("compose.sign-in.credential-manager", "compose.input.keyboard-options")
      )

    /** Words a text-entry or sign-in rule's id carries. */
    val TEXT_ENTRY = listOf(".input.", ".sign-in.", ".forms.")

    /** The platform words catalog guidelines declare. */
    val PLATFORMS = listOf("mobile", "foundation", "wear", "glasses", "remote-compose", "launcher")
    val SURFACES =
      listOf(GuidelineSurfaces.SCREEN, GuidelineSurfaces.WIDGET, GuidelineSurfaces.COMPONENT)
    val PROFILES =
      listOf(
        "launcher-widgets-v6",
        "launcher-widgets-v7",
        "launcher-widgets-v7+experimental",
        "wear-widgets",
        "androidx",
      )
  }
}
