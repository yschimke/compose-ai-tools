package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuidelinesIncludesTest {
  private val packUrl = "https://raw.githubusercontent.com/o/r/v1/general.guidelines.json"

  private val pack =
    """
    {
      "schema": "compose-ui-builder/catalog-guidelines/v1",
      "catalog": "general", "platform": "any", "version": 1,
      "frames": [{"kind": "device"}, {"kind": "unrolled", "heightFactor": 3, "whenScrolls": true}],
      "rules": [
        {"id": "general.everywhere", "kind": "structure", "severity": "info", "guidance": "g",
         "check": "everywhere?", "source": "https://developer.android.com/a"},
        {"id": "general.phone-only", "platforms": ["mobile"], "kind": "structure",
         "severity": "warning", "guidance": "g", "check": "phone?",
         "source": "https://developer.android.com/b"},
        {"id": "general.wear-only", "platforms": ["wear"], "kind": "structure",
         "severity": "warning", "guidance": "g", "check": "wear?",
         "source": "https://developer.android.com/c", "surfaces": ["screen"]},
        {"id": "general.overridden", "kind": "structure", "severity": "info", "guidance": "pack",
         "check": "pack?", "source": "https://developer.android.com/d"},
        {"id": "general.excluded", "kind": "structure", "severity": "info", "guidance": "g",
         "check": "excluded?", "source": "https://developer.android.com/e"},
        {"id": "general.v7", "kind": "structure", "severity": "info", "guidance": "g",
         "check": "v7?", "source": "https://developer.android.com/f",
         "profiles": ["launcher-widgets-v7"]}
      ]
    }
    """
      .trimIndent()

  private val packBytes = pack.toByteArray()
  private val pin = GuidelinesIncludes.sha256(packBytes)

  private fun catalog(include: String, platform: String = "wear") =
    """
    {
      "schema": "compose-ui-builder/catalog-guidelines/v1",
      "catalog": "wear-m3", "platform": "$platform", "version": 4,
      "frames": [{"kind": "device"}],
      "rules": [
        {"id": "wear.own", "kind": "visual", "severity": "warning", "guidance": "g",
         "check": "own?", "source": "https://developer.android.com/w"},
        {"id": "general.overridden", "kind": "structure", "severity": "warning",
         "guidance": "catalog", "check": "catalog?", "source": "https://developer.android.com/x"}
      ],
      "includes": [$include]
    }
    """
      .trimIndent()

  private val fetched = mutableListOf<String>()
  private val fetch: (String) -> ByteArray = { url ->
    fetched += url
    if (url == packUrl) packBytes else error("unexpected $url")
  }

  @Test
  fun `a pinned pack is merged by platform, override and exclusion`() {
    val loaded =
      CatalogGuidelinesLoader.parse(
        catalog("""{"url": "$packUrl", "sha256": "$pin", "exclude": ["general.excluded"]}"""),
        "wear-m3",
        fetch,
      )
    val guidelines = loaded.guidelines!!
    assertThat(loaded.problem).isNull()
    assertThat(guidelines.rules.map { it.id })
      .containsExactly(
        "wear.own",
        "general.overridden",
        "general.everywhere",
        "general.wear-only",
        "general.v7",
      )
      .inOrder()
    // The catalog's own rule of the same id wins.
    assertThat(guidelines.rules.single { it.id == "general.overridden" }.guidance)
      .isEqualTo("catalog")
    // The catalog's device frame is kept once; the pack's unrolled frame is added.
    assertThat(guidelines.frames.map { it.kind }).containsExactly("device", "unrolled").inOrder()
    assertThat(guidelines.version).isEqualTo(4)
    assertThat(guidelines.platform).isEqualTo("wear")
    assertThat(fetched).containsExactly(packUrl)
  }

  @Test
  fun `a phone catalog takes the phone rules and not the wear ones`() {
    val guidelines =
      CatalogGuidelinesLoader.parse(
          catalog("""{"url": "$packUrl", "sha256": "$pin"}""", platform = "mobile"),
          null,
          fetch,
        )
        .guidelines!!
    assertThat(guidelines.rules.map { it.id }).contains("general.phone-only")
    assertThat(guidelines.rules.map { it.id }).doesNotContain("general.wear-only")
  }

  @Test
  fun `an include's profiles narrow only the rules naming none`() {
    val guidelines =
      CatalogGuidelinesLoader.parse(
          catalog("""{"url": "$packUrl", "sha256": "$pin", "profiles": ["androidx"]}"""),
          null,
          fetch,
        )
        .guidelines!!
    val byId = guidelines.rules.associateBy { it.id }
    assertThat(byId.getValue("general.everywhere").profiles).containsExactly("androidx")
    assertThat(byId.getValue("general.v7").profiles).containsExactly("launcher-widgets-v7")
    assertThat(byId.getValue("wear.own").profiles).isEmpty()
  }

  @Test
  fun `a pack that does not match its pin refuses the file`() {
    val loaded =
      CatalogGuidelinesLoader.parse(
        catalog("""{"url": "$packUrl", "sha256": "${"0".repeat(64)}"}"""),
        null,
        fetch,
      )
    assertThat(loaded.guidelines).isNull()
    assertThat(loaded.problem).contains("does not match its pin")
  }

  @Test
  fun `an include is https and pinned, or nothing is fetched`() {
    val http =
      CatalogGuidelinesLoader.parse(
        catalog("""{"url": "http://example.com/p.json", "sha256": "$pin"}"""),
        null,
        fetch,
      )
    assertThat(http.problem).contains("not an https URL")
    val unpinned =
      CatalogGuidelinesLoader.parse(
        catalog("""{"url": "$packUrl", "sha256": "ABC"}"""),
        null,
        fetch,
      )
    assertThat(unpinned.problem).contains("no sha256")
    assertThat(fetched).isEmpty()
  }

  @Test
  fun `a fetch that fails refuses the file rather than checking part of it`() {
    val loaded =
      CatalogGuidelinesLoader.parse(catalog("""{"url": "$packUrl", "sha256": "$pin"}"""), null) {
        throw java.io.IOException("offline")
      }
    assertThat(loaded.guidelines).isNull()
    assertThat(loaded.problem).contains("could not be read: offline")
  }

  @Test
  fun `includes are not resolved without a fetcher, and packs may not nest`() {
    val unresolved =
      CatalogGuidelinesLoader.parse(catalog("""{"url": "$packUrl", "sha256": "$pin"}"""))
    assertThat(unresolved.guidelines).isNull()
    assertThat(unresolved.problem).contains("not resolved")

    val nestingPack =
      pack.replaceFirst(
        "\"rules\"",
        "\"includes\": [{\"url\": \"$packUrl\", \"sha256\": \"$pin\"}], \"rules\"",
      )
    val nestingBytes = nestingPack.toByteArray()
    val nesting =
      CatalogGuidelinesLoader.parse(
        catalog("""{"url": "$packUrl", "sha256": "${GuidelinesIncludes.sha256(nestingBytes)}"}"""),
        null,
      ) {
        nestingBytes
      }
    assertThat(nesting.problem).contains("may not nest")
  }

  @Test
  fun `a flat file parses as it always did`() {
    val flat = catalog("").replace(",\n  \"includes\": []", "")
    val loaded = CatalogGuidelinesLoader.parse(flat, "wear-m3")
    assertThat(loaded.problem).isNull()
    assertThat(loaded.guidelines!!.rules).hasSize(2)
  }

  @Test
  fun `more includes than the bound refuse the file`() {
    val many =
      (1..GuidelinesIncludes.MAX_INCLUDES + 1).joinToString(",") {
        """{"url": "$packUrl", "sha256": "$pin"}"""
      }
    val loaded = CatalogGuidelinesLoader.parse(catalog(many), null, fetch)
    assertThat(loaded.problem).contains("more than")
    assertThat(fetched).isEmpty()
  }
}
