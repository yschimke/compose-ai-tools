package ee.schimke.composeai.dwf

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

class WffRemoteComposeTest {
  private val samplesDir = File(checkNotNull(System.getProperty("dwf.wearOsSamplesDir")))
  private val sampleNames = samplesDir.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()

  /**
   * Drawables a sample references but does not ship (Weather's `<Word resource="ic_mist">` has no
   * `ic_mist.png` upstream). They must surface as diagnostics, and nothing else may.
   */
  private val shippedMissing = mapOf("Weather" to setOf("ic_mist"))

  private fun WffCompileResult.problems(sample: String): List<WffDiagnostic> =
    diagnostics.filter { d ->
      d.severity != WffSeverity.INFO &&
        !(d.code == "MISSING_RESOURCE" &&
          shippedMissing[sample].orEmpty().any { "'$it'" in d.message })
    }

  private fun resDir(sample: String) = File(samplesDir, sample)

  private fun xml(sample: String) = File(resDir(sample), "raw/watchface.xml").readText()

  /** The editor's shape: res-relative path to bytes. */
  private fun inMemory(sample: String): Map<String, ByteArray> {
    val root = resDir(sample)
    return root
      .walkTopDown()
      .filter { it.isFile }
      .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
  }

  @Test
  fun `the Wear OS samples are all present`() {
    assertThat(sampleNames)
      .containsExactly(
        "Complications",
        "Flavors",
        "PhotosMask",
        "PhotosMulti",
        "SimpleAnalog",
        "SimpleDigital",
        "Weather",
      )
  }

  @Test
  fun `every sample compiles from a res directory`() {
    for (sample in sampleNames) {
      val result =
        WffRemoteCompose.compile(
          xml(sample),
          WffResourceSource.ofDirectory(resDir(sample)),
        )
      assertThat(result.problems(sample)).isEmpty()
      assertThat(result.isSuccess).isTrue()
      assertThat(result.rcBytes!!.size).isGreaterThan(128)
      assertThat(result.namedValues).isNotEmpty()
      // The bytes are a real document the player can decode.
      RcDocumentCodec.decode(result.rcBytes!!)
    }
  }

  @Test
  fun `every sample compiles from in-memory resources to the same document`() {
    for (sample in sampleNames) {
      val fromDir =
        WffRemoteCompose.compile(xml(sample), WffResourceSource.ofDirectory(resDir(sample)))
      val fromMemory = WffRemoteCompose.compile(xml(sample), WffResourceSource.of(inMemory(sample)))
      assertThat(fromMemory.isSuccess).isTrue()
      assertThat(fromMemory.problems(sample)).isEmpty()
      assertThat(fromMemory.rcBytes).isEqualTo(fromDir.rcBytes)
      assertThat(fromMemory.namedValues).isEqualTo(fromDir.namedValues)
    }
  }

  @Test
  fun `a drawable a sample references but does not ship is reported`() {
    val result =
      WffRemoteCompose.compile(xml("Weather"), WffResourceSource.ofDirectory(resDir("Weather")))
    assertThat(result.diagnostics.filter { it.code == "MISSING_RESOURCE" }.map { it.message })
      .containsExactly("Resource 'ic_mist' not found; drawing a placeholder.")
  }

  @Test
  fun `a leading res prefix on in-memory paths is tolerated`() {
    val prefixed = inMemory("SimpleAnalog").mapKeys { "res/${it.key}" }
    val result = WffRemoteCompose.compile(xml("SimpleAnalog"), WffResourceSource.of(prefixed))
    assertThat(result.diagnostics.filter { it.severity == WffSeverity.WARNING }).isEmpty()
  }

  // --- named values -------------------------------------------------------------------------

  @Test
  fun `named value manifest of every sample is documented and well formed`() {
    for (sample in sampleNames) {
      val specs =
        WffRemoteCompose.compile(xml(sample), WffResourceSource.ofDirectory(resDir(sample)))
          .namedValues
      assertThat(specs.map { it.name }).containsNoDuplicates()
      for (spec in specs) {
        assertThat(spec.name).doesNotContain("USER:")
        assertThat(spec.description).isNotEmpty()
        // A `dwf.` / `metrics.` variable falling through to OTHER means DwfVariables grew a key
        // `describeNamedValue` does not document yet.
        if (spec.name.startsWith("dwf.") || spec.name.startsWith("metrics.")) {
          assertThat(spec.category).isNotEqualTo(NamedValueCategory.OTHER)
        }
        val typeMatchesDefault =
          when (spec.default) {
            is WffValue.Text -> spec.type == NamedValueType.TEXT
            is WffValue.Float -> spec.type == NamedValueType.FLOAT
            is WffValue.Int -> spec.type == NamedValueType.INT
            is WffValue.Bool -> spec.type == NamedValueType.BOOLEAN
            is WffValue.Color -> spec.type == NamedValueType.COLOR
          }
        assertThat(typeMatchesDefault).isTrue()
      }
      println("named values [$sample]: " + specs.joinToString { it.name })
    }
  }

  @Test
  fun `manifest lists the ambient switch, complication slots and config the document reads`() {
    val complications =
      WffRemoteCompose.compile(
          xml("Complications"),
          WffResourceSource.ofDirectory(resDir("Complications")),
        )
        .namedValues
        .associateBy { it.name }
    assertThat(complications).containsKey("dwf.is_ambient")
    assertThat(complications["dwf.is_ambient"]!!.type).isEqualTo(NamedValueType.BOOLEAN)
    assertThat(complications["dwf.is_ambient"]!!.category).isEqualTo(NamedValueCategory.AMBIENT)
    assertThat(complications.keys.filter { it.startsWith("dwf.complication.") }).isNotEmpty()
    assertThat(complications.values.filter { it.category == NamedValueCategory.COMPLICATION })
      .isNotEmpty()

    val flavors =
      WffRemoteCompose.compile(xml("Flavors"), WffResourceSource.ofDirectory(resDir("Flavors")))
        .namedValues
    assertThat(flavors.filter { it.category == NamedValueCategory.CONFIGURATION }).isNotEmpty()

    val weather =
      WffRemoteCompose.compile(xml("Weather"), WffResourceSource.ofDirectory(resDir("Weather")))
        .namedValues
    assertThat(weather.filter { it.category == NamedValueCategory.WEATHER }).isNotEmpty()
  }

  @Test
  fun `ambient option is the default of dwf is_ambient`() {
    fun default(ambient: Boolean) =
      WffRemoteCompose.compile(
          xml("SimpleAnalog"),
          WffResourceSource.ofDirectory(resDir("SimpleAnalog")),
          WffCompileOptions(ambient = ambient),
        )
        .namedValues
        .single { it.name == "dwf.is_ambient" }
        .default
    assertThat(default(false)).isEqualTo(WffValue.Bool(false))
    assertThat(default(true)).isEqualTo(WffValue.Bool(true))
  }

  @Test
  fun `a host holding only rc bytes can read the manifest back`() {
    val result =
      WffRemoteCompose.compile(
        xml("SimpleAnalog"),
        WffResourceSource.ofDirectory(resDir("SimpleAnalog")),
      )
    val names = extractNamedValueSpecs(result.rcBytes!!).map { it.name }
    assertThat(names).containsExactlyElementsIn(result.namedValues.map { it.name })
  }

  // --- options ------------------------------------------------------------------------------

  @Test
  fun `size and density options reach the document header`() {
    val native =
      WffRemoteCompose.compile(
        xml("SimpleAnalog"),
        WffResourceSource.ofDirectory(resDir("SimpleAnalog")),
      )
    val small =
      WffRemoteCompose.compile(
        xml("SimpleAnalog"),
        WffResourceSource.ofDirectory(resDir("SimpleAnalog")),
        WffCompileOptions(widthPx = 192, heightPx = 192, density = 2f),
      )
    assertThat(native.widthPx).isEqualTo(native.watchFaceWidth)
    assertThat(small.widthPx).isEqualTo(192)
    assertThat(small.heightPx).isEqualTo(192)
    assertThat(small.watchFaceWidth).isEqualTo(native.watchFaceWidth)
    assertThat(small.rcBytes).isNotEqualTo(native.rcBytes)
    assertThat(small.isSuccess).isTrue()
  }

  @Test
  fun `wffVersion is range checked and echoed`() {
    val ok =
      WffRemoteCompose.compile(xml("SimpleAnalog"), options = WffCompileOptions(wffVersion = 4))
    assertThat(ok.wffVersion).isEqualTo(4)
    val bad =
      WffRemoteCompose.compile(xml("SimpleAnalog"), options = WffCompileOptions(wffVersion = 9))
    assertThat(bad.wffVersion).isEqualTo(WffCompileOptions.DEFAULT_WFF_VERSION)
    assertThat(bad.diagnostics.map { it.code }).contains("INVALID_OPTION")
    assertThat(bad.isSuccess).isTrue()
  }

  // --- degradation, never a throw -----------------------------------------------------------

  @Test
  fun `missing resources degrade to placeholders with a diagnostic`() {
    val result = WffRemoteCompose.compile(xml("SimpleAnalog"), WffResourceSource.Empty)
    assertThat(result.isSuccess).isTrue()
    val missing = result.diagnostics.filter { it.code == "MISSING_RESOURCE" }
    assertThat(missing).isNotEmpty()
    assertThat(missing.all { it.severity == WffSeverity.WARNING }).isTrue()

    // With the resources the same document draws real bitmaps, so the bytes differ.
    val full =
      WffRemoteCompose.compile(
        xml("SimpleAnalog"),
        WffResourceSource.ofDirectory(resDir("SimpleAnalog")),
      )
    assertThat(result.rcBytes).isNotEqualTo(full.rcBytes)

    val bare =
      WffRemoteCompose.compile(
        xml("SimpleAnalog"),
        WffResourceSource.Empty,
        WffCompileOptions(placeholderMissingResources = false),
      )
    assertThat(bare.isSuccess).isTrue()
    assertThat(bare.diagnostics.any { it.code == "MISSING_RESOURCE" }).isTrue()
    assertThat(bare.rcBytes).isNotEqualTo(result.rcBytes)
  }

  @Test
  fun `a partial in-memory tree reports only what is missing`() {
    val all = inMemory("SimpleAnalog")
    val dropped = all.keys.first { it.startsWith("drawable") && it.endsWith(".png") }
    val result = WffRemoteCompose.compile(xml("SimpleAnalog"), WffResourceSource.of(all - dropped))
    assertThat(result.isSuccess).isTrue()
    val names = result.diagnostics.filter { it.code == "MISSING_RESOURCE" }
    assertThat(names).isNotEmpty()
    assertThat(names.all { dropped.substringAfterLast('/').substringBeforeLast('.') in it.message })
      .isTrue()
  }

  @Test
  fun `a missing custom font is reported`() {
    val face =
      """<WatchFace width="450" height="450"><Scene><PartText x="0" y="0" width="450" height="100">""" +
        """<Text><Font family="@font/nope" size="40"><Template>hi</Template></Font></Text></PartText></Scene></WatchFace>"""
    val missing =
      WffRemoteCompose.compile(face).diagnostics.filter { it.code == "MISSING_RESOURCE" }
    assertThat(missing.map { it.message }.joinToString()).contains("@font/nope")
    val present =
      WffRemoteCompose.compile(face, WffResourceSource.of(mapOf("font/nope.ttf" to ByteArray(4))))
    assertThat(present.diagnostics.none { it.code == "MISSING_RESOURCE" }).isTrue()
  }

  @Test
  fun `a missing color inside a vector drawable is reported`() {
    val face =
      """<WatchFace width="450" height="450"><Scene><PartImage x="0" y="0" width="100" height="100">""" +
        """<Image resource="ic_vec"/></PartImage></Scene></WatchFace>"""
    val vector =
      """<vector xmlns:android="http://schemas.android.com/apk/res/android" """ +
        """android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">""" +
        """<path android:fillColor="@color/gone" android:pathData="M0,0h24v24h-24z"/></vector>"""
    val result =
      WffRemoteCompose.compile(
        face,
        WffResourceSource.of(mapOf("drawable/ic_vec.xml" to vector.toByteArray())),
      )
    assertThat(
        result.diagnostics
          .filter { it.code == "MISSING_RESOURCE" }
          .map { it.message }
          .joinToString(),
      )
      .contains("@color/gone")
  }

  @Test
  fun `bad input is a diagnostic not an exception`() {
    for (bad in listOf("", "not xml at all", "<WatchFace", "<Other/>", "<WatchFace></Oops>")) {
      val result = WffRemoteCompose.compile(bad)
      assertThat(result.rcBytes).isNull()
      assertThat(result.isSuccess).isFalse()
      assertThat(result.namedValues).isEmpty()
      assertThat(result.diagnostics.any { it.severity == WffSeverity.ERROR }).isTrue()
    }
  }

  @Test
  fun `a minimal watch face compiles with no resources and no scene`() {
    val result =
      WffRemoteCompose.compile("""<WatchFace width="454" height="454"><Scene/></WatchFace>""")
    assertThat(result.isSuccess).isTrue()
    assertThat(result.watchFaceWidth).isEqualTo(454)
  }

  // --- latency ------------------------------------------------------------------------------

  @Test
  fun `compile latency`() {
    val xml = xml("SimpleAnalog")
    val res = WffResourceSource.of(inMemory("SimpleAnalog"))
    val cold = WffRemoteCompose.compile(xml, res).compileMillis
    repeat(20) { WffRemoteCompose.compile(xml, res) } // JIT warm-up
    val warm = List(30) { WffRemoteCompose.compile(xml, res).compileMillis }.sorted()
    println(
      "WFF compile latency (SimpleAnalog, in-memory res): cold=${cold}ms " +
        "warm median=${warm[warm.size / 2]}ms p90=${warm[(warm.size * 9) / 10]}ms " +
        "min=${warm.first()}ms max=${warm.last()}ms",
    )
    // Reported, deliberately not gated: the goal is well under 100 ms warm.
    assumeTrue(true)
  }
}
