package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeOverridesTest {

  private fun ok(
    params: Map<String, String>
  ): ee.schimke.composeai.daemon.protocol.PreviewOverrides {
    val parsed = ServeOverrides.parse(params)
    assertTrue(parsed is OverrideParse.Ok, "expected Ok, got $parsed")
    return parsed.overrides
  }

  @Test
  fun `empty params leave every field null`() {
    val o = ok(emptyMap())
    assertNull(o.uiMode)
    assertNull(o.device)
    assertNull(o.localeTag)
    assertNull(o.fontScale)
    assertNull(o.orientation)
    assertNull(o.widthPx)
    assertNull(o.heightPx)
    assertNull(o.density)
    assertNull(o.inspectionMode)
    assertNull(o.slotMode)
    assertNull(o.talkBack)
    assertNull(o.touchOverlay)
    assertNull(o.themeProvider)
    assertNull(o.focus)
    assertNull(o.clearBackground)
  }

  @Test
  fun `focus tab index maps to a focus override with the overlay drawn`() {
    val o = ok(mapOf("focus" to "2"))
    assertEquals(2, o.focus?.tabIndex)
    assertEquals(true, o.focus?.overlay)
  }

  @Test
  fun `a malformed focus index is rejected`() {
    for (bad in listOf("focus" to "yes", "focus" to "-1", "focus" to "1.5")) {
      val parsed = ServeOverrides.parse(mapOf(bad))
      assertTrue(parsed is OverrideParse.Invalid, "expected Invalid for $bad, got $parsed")
    }
  }

  @Test
  fun `cache key differs when a focus override is applied`() {
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("focus" to "0"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("focus" to "0"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("focus" to "1"))),
    )
  }

  @Test
  fun `empty params leave the gesture override null`() {
    assertNull(ok(emptyMap()).gestures)
  }

  @Test
  fun `gestures true shows the hint affordance`() {
    val o = ok(mapOf("gestures" to "true"))
    assertEquals(true, o.gestures?.showHints)
    assertEquals(true, ok(mapOf("gestures" to "1")).gestures?.showHints)
  }

  @Test
  fun `gestures false clears the hint affordance`() {
    val o = ok(mapOf("gestures" to "false"))
    assertEquals(false, o.gestures?.showHints)
    assertEquals(false, ok(mapOf("gestures" to "0")).gestures?.showHints)
  }

  @Test
  fun `a malformed gestures value is rejected`() {
    for (bad in listOf("gestures" to "yes", "gestures" to "maybe", "gestures" to "2")) {
      val parsed = ServeOverrides.parse(mapOf(bad))
      assertTrue(parsed is OverrideParse.Invalid, "expected Invalid for $bad, got $parsed")
    }
  }

  @Test
  fun `cache key differs when a gesture override is applied`() {
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("gestures" to "true"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("gestures" to "true"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("gestures" to "false"))),
    )
  }

  @Test
  fun `maps each field`() {
    val o =
      ok(
        mapOf(
          "uiMode" to "dark",
          "device" to "id:pixel_5",
          "localeTag" to "ja-JP",
          "fontScale" to "1.3",
          "density" to "2.0",
          "widthPx" to "400",
          "heightPx" to "800",
          "orientation" to "landscape",
          "inspectionMode" to "true",
          "slotMode" to "true",
          "talkBack" to "true",
          "touchOverlay" to "1",
          "themeProvider" to "com.example.BrandDarkThemeCatalog",
        )
      )
    assertEquals(UiMode.DARK, o.uiMode)
    assertEquals("id:pixel_5", o.device)
    assertEquals("ja-JP", o.localeTag)
    assertEquals(1.3f, o.fontScale)
    assertEquals(2.0f, o.density)
    assertEquals(400, o.widthPx)
    assertEquals(800, o.heightPx)
    assertEquals(Orientation.LANDSCAPE, o.orientation)
    assertEquals(true, o.inspectionMode)
    assertEquals(true, o.slotMode)
    assertEquals(true, o.talkBack)
    assertEquals(true, o.touchOverlay)
    assertEquals("com.example.BrandDarkThemeCatalog", o.themeProvider)
  }

  @Test
  fun `blank values are treated as absent`() {
    val o = ok(mapOf("uiMode" to "", "device" to "", "fontScale" to ""))
    assertNull(o.uiMode)
    assertNull(o.device)
    assertNull(o.fontScale)
  }

  @Test
  fun `invalid enums and numbers are rejected with a reason`() {
    for (bad in
      listOf(
        mapOf("uiMode" to "purple"),
        mapOf("orientation" to "sideways"),
        mapOf("fontScale" to "huge"),
        mapOf("fontScale" to "-1"),
        mapOf("density" to "0"),
        mapOf("widthPx" to "wide"),
        mapOf("widthPx" to "-5"),
        mapOf("inspectionMode" to "maybe"),
        mapOf("slotMode" to "maybe"),
        mapOf("talkBack" to "maybe"),
        mapOf("touchOverlay" to "maybe"),
        mapOf("background" to "polkadot"),
        mapOf("clearBackground" to "maybe"),
      )) {
      val parsed = ServeOverrides.parse(bad)
      assertTrue(parsed is OverrideParse.Invalid, "expected Invalid for $bad, got $parsed")
      assertTrue(parsed.message.isNotBlank())
    }
  }

  @Test
  fun `background clear aliases map to clearBackground true`() {
    for (v in listOf("clear", "transparent", "none", "off", "CLEAR")) {
      assertEquals(true, ok(mapOf("background" to v)).clearBackground, "background=$v")
    }
    for (v in listOf("default", "show", "on")) {
      assertEquals(false, ok(mapOf("background" to v)).clearBackground, "background=$v")
    }
    // Raw boolean spelling.
    assertEquals(true, ok(mapOf("clearBackground" to "true")).clearBackground)
    assertEquals(false, ok(mapOf("clearBackground" to "false")).clearBackground)
    // `background` wins over `clearBackground` when both are present.
    assertEquals(
      true,
      ok(mapOf("background" to "clear", "clearBackground" to "false")).clearBackground,
    )
    // Absent leaves it null (discovery-time background).
    assertNull(ok(emptyMap()).clearBackground)
  }

  @Test
  fun `cache key differs when clearBackground changes`() {
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("background" to "clear"))),
    )
  }

  @Test
  fun `cache key is stable for equal overrides and order-independent`() {
    val a = ok(mapOf("uiMode" to "dark", "device" to "id:pixel_5", "fontScale" to "1.3"))
    val b = ok(mapOf("fontScale" to "1.3", "device" to "id:pixel_5", "uiMode" to "dark"))
    assertEquals(ServeOverrides.cacheKey("preview.A", a), ServeOverrides.cacheKey("preview.A", b))
  }

  @Test
  fun `cache key differs by preview id and by any override field`() {
    val base = ok(mapOf("uiMode" to "light"))
    val dark = ok(mapOf("uiMode" to "dark"))
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", base),
      ServeOverrides.cacheKey("preview.A", dark),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", base),
      ServeOverrides.cacheKey("preview.B", base),
    )
    // slotMode participates, so a slot-map render isn't coalesced onto the normal render's cache.
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("slotMode" to "true"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    // The live overlay flags participate too, so a crafted /render?talkBack / ?touchOverlay can't
    // collide with the baked render's cache entry.
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("talkBack" to "true"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("touchOverlay" to "true"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    // A themeProvider selection participates, so rendering a preview under two different declared
    // themes (or a theme vs the default) doesn't coalesce onto one cache entry.
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("themeProvider" to "com.example.BrandDark"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("themeProvider" to "com.example.BrandDark"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("themeProvider" to "com.example.BrandLight"))),
    )
  }

  @Test
  fun `knob params parse to typed named overrides`() {
    val o =
      ok(
        mapOf(
          "knob.label" to "string:Tap me",
          "knob.count" to "int:3",
          "knob.weight" to "float:1.5",
          "knob.enabled" to "bool:true",
          "knob.tint" to "color:#FF0000",
        )
      )
    val named = o.namedOverrides
    assertNotNull(named)
    assertEquals(PreviewOverrideValue.StringValue("Tap me"), named["label"])
    assertEquals(PreviewOverrideValue.IntValue(3), named["count"])
    assertEquals(PreviewOverrideValue.FloatValue(1.5f), named["weight"])
    assertEquals(PreviewOverrideValue.BooleanValue(true), named["enabled"])
    assertEquals(PreviewOverrideValue.ColorValue("#FF0000"), named["tint"])
  }

  @Test
  fun `bool knob accepts 1 and is false otherwise`() {
    val o = ok(mapOf("knob.a" to "bool:1", "knob.b" to "bool:0", "knob.c" to "bool:nope"))
    assertEquals(PreviewOverrideValue.BooleanValue(true), o.namedOverrides!!["a"])
    assertEquals(PreviewOverrideValue.BooleanValue(false), o.namedOverrides!!["b"])
    assertEquals(PreviewOverrideValue.BooleanValue(false), o.namedOverrides!!["c"])
  }

  @Test
  fun `indexed knob key keeps its bracketed wire key`() {
    val o = ok(mapOf("knob.rowLabel[2]" to "string:Hi"))
    assertEquals(PreviewOverrideValue.StringValue("Hi"), o.namedOverrides!!["rowLabel[2]"])
  }

  @Test
  fun `no knob params leave named overrides null`() {
    val o = ok(mapOf("uiMode" to "dark"))
    assertNull(o.namedOverrides)
  }

  @Test
  fun `blank knob key or value is ignored`() {
    val o = ok(mapOf("knob." to "string:x", "knob.label" to ""))
    assertNull(o.namedOverrides)
  }

  @Test
  fun `bare knob value without a kind prefix parses as a string`() {
    // A colon-less value (the viewer's default wire form) is a string when nothing types it.
    val o = ok(mapOf("knob.label" to "Tap me"))
    assertEquals(PreviewOverrideValue.StringValue("Tap me"), o.namedOverrides!!["label"])
    // A value whose prefix is not a recognised kind is taken whole, not split on the colon.
    val o2 = ok(mapOf("knob.label" to "mystery:y"))
    assertEquals(PreviewOverrideValue.StringValue("mystery:y"), o2.namedOverrides!!["label"])
  }

  @Test
  fun `bare knob value is typed from the declared kinds`() {
    val kinds = mapOf("count" to "int", "weight" to "float", "enabled" to "bool", "tint" to "color")
    val o =
      (ServeOverrides.parse(
          mapOf(
            "knob.count" to "3",
            "knob.weight" to "1.5",
            "knob.enabled" to "true",
            "knob.tint" to "#FF0000",
          ),
          kinds,
        ) as OverrideParse.Ok)
        .overrides
    val named = o.namedOverrides!!
    assertEquals(PreviewOverrideValue.IntValue(3), named["count"])
    assertEquals(PreviewOverrideValue.FloatValue(1.5f), named["weight"])
    assertEquals(PreviewOverrideValue.BooleanValue(true), named["enabled"])
    assertEquals(PreviewOverrideValue.ColorValue("#FF0000"), named["tint"])
  }

  @Test
  fun `an explicit kind prefix still wins for an undeclared knob`() {
    // Legacy `<kind>:<value>` links keep working when nothing declares the knob's type.
    val o = ok(mapOf("knob.count" to "int:7"))
    assertEquals(PreviewOverrideValue.IntValue(7), o.namedOverrides!!["count"])
  }

  @Test
  fun `a declared string knob keeps a value that looks like a typed prefix`() {
    // A string knob edited to `int:3` / `color:#fff` must survive verbatim: the prefix does not
    // match the declared kind, so it is not stripped or retyped.
    val kinds = mapOf("label" to "string")
    val o =
      (ServeOverrides.parse(
          mapOf("knob.label" to "int:3", "knob.label2" to "color:#fff"),
          kinds + ("label2" to "string"),
        ) as OverrideParse.Ok)
        .overrides
    val named = o.namedOverrides!!
    assertEquals(PreviewOverrideValue.StringValue("int:3"), named["label"])
    assertEquals(PreviewOverrideValue.StringValue("color:#fff"), named["label2"])
  }

  @Test
  fun `a matching kind prefix on a declared knob is still honoured`() {
    // An old `knob.label=string:Hi` link (prefix matches the declared kind) keeps meaning "Hi".
    val o =
      (ServeOverrides.parse(mapOf("knob.count" to "int:7"), mapOf("count" to "int"))
          as OverrideParse.Ok)
        .overrides
    assertEquals(PreviewOverrideValue.IntValue(7), o.namedOverrides!!["count"])
  }

  @Test
  fun `malformed typed knob values are rejected with a reason`() {
    for (bad in
      listOf(
        mapOf("knob.count" to "int:three"), // explicit int, not an integer
        mapOf("knob.weight" to "float:heavy"), // explicit float, not a number
      )) {
      val parsed = ServeOverrides.parse(bad)
      assertTrue(parsed is OverrideParse.Invalid, "expected Invalid for $bad, got $parsed")
      assertTrue(parsed.message.isNotBlank())
    }
  }

  @Test
  fun `a bare value declared as a number must still be numeric`() {
    val parsed = ServeOverrides.parse(mapOf("knob.count" to "three"), mapOf("count" to "int"))
    assertTrue(parsed is OverrideParse.Invalid, "expected Invalid, got $parsed")
  }

  @Test
  fun `cache key differs when a knob value changes`() {
    val a = ok(mapOf("knob.label" to "string:Tap me"))
    val b = ok(mapOf("knob.label" to "string:Press me"))
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", a),
      ServeOverrides.cacheKey("preview.A", b),
    )
  }

  @Test
  fun `cache key is knob-order independent`() {
    val a = ok(mapOf("knob.label" to "string:Hi", "knob.count" to "int:2"))
    val b = ok(mapOf("knob.count" to "int:2", "knob.label" to "string:Hi"))
    assertEquals(ServeOverrides.cacheKey("preview.A", a), ServeOverrides.cacheKey("preview.A", b))
  }

  @Test
  fun `size-bound params parse to the min-max fields`() {
    val o =
      ok(
        mapOf(
          "minWidthPx" to "120",
          "minHeightPx" to "48",
          "maxWidthPx" to "400",
          "maxHeightPx" to "800",
        )
      )
    assertEquals(120, o.minWidthPx)
    assertEquals(48, o.minHeightPx)
    assertEquals(400, o.maxWidthPx)
    assertEquals(800, o.maxHeightPx)
  }

  @Test
  fun `empty params leave the size bounds null`() {
    val o = ok(emptyMap())
    assertNull(o.minWidthPx)
    assertNull(o.minHeightPx)
    assertNull(o.maxWidthPx)
    assertNull(o.maxHeightPx)
  }

  @Test
  fun `a non-positive or malformed size bound is rejected`() {
    for (bad in
      listOf(
        mapOf("minWidthPx" to "0"),
        mapOf("minHeightPx" to "-3"),
        mapOf("maxWidthPx" to "wide"),
        mapOf("maxHeightPx" to "1.5"),
      )) {
      val parsed = ServeOverrides.parse(bad)
      assertTrue(parsed is OverrideParse.Invalid, "expected Invalid for $bad, got $parsed")
      assertTrue(parsed.message.isNotBlank())
    }
  }

  @Test
  fun `a min bound above the max on the same axis is rejected`() {
    assertTrue(
      ServeOverrides.parse(mapOf("minWidthPx" to "500", "maxWidthPx" to "200"))
        is OverrideParse.Invalid
    )
    assertTrue(
      ServeOverrides.parse(mapOf("minHeightPx" to "900", "maxHeightPx" to "300"))
        is OverrideParse.Invalid
    )
    // Equal bounds are a degenerate-but-valid fixed range.
    assertTrue(
      ServeOverrides.parse(mapOf("minWidthPx" to "200", "maxWidthPx" to "200")) is OverrideParse.Ok
    )
  }

  @Test
  fun `cache key differs when a size bound changes`() {
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("maxWidthPx" to "300"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("minWidthPx" to "100"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("minWidthPx" to "200"))),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("minHeightPx" to "100"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("maxHeightPx" to "100"))),
    )
  }

  @Test
  fun `preview mode parses known wire values`() {
    assertEquals(PreviewMode.SNAPSHOT, PreviewMode.parse("snapshot"))
    assertEquals(PreviewMode.LIVE, PreviewMode.parse("live"))
    assertNull(PreviewMode.parse("bogus"))
    assertNull(PreviewMode.parse(null))
  }

  @Test
  fun `no remote compose params leaves the facet null`() {
    assertNull(ok(emptyMap()).remoteCompose)
    // A generic knob alone must NOT synthesise a remoteCompose facet.
    assertNull(ok(mapOf("knob.label" to "Hi")).remoteCompose)
  }

  @Test
  fun `rc named seeds map to typed remote named values`() {
    val rc =
      ok(
          mapOf(
            "rc.label" to "Tap me",
            "rc.count" to "int:3",
            "rc.ratio" to "float:0.5",
            "rc.gap" to "dp:8",
            "rc.on" to "bool:true",
            "rc.stopColor" to "color:#FF8800",
          )
        )
        .remoteCompose
    assertNotNull(rc)
    assertEquals(RemoteNamedValue.StringValue("Tap me"), rc.namedValues["label"])
    assertEquals(RemoteNamedValue.IntValue(3), rc.namedValues["count"])
    assertEquals(RemoteNamedValue.FloatValue(0.5f), rc.namedValues["ratio"])
    assertEquals(RemoteNamedValue.DpValue(8f), rc.namedValues["gap"])
    assertEquals(RemoteNamedValue.BooleanValue(true), rc.namedValues["on"])
    assertEquals(RemoteNamedValue.ColorValue("#FF8800"), rc.namedValues["stopColor"])
    assertNull(rc.profile)
  }

  @Test
  fun `a bare rc value is a string`() {
    val rc = ok(mapOf("rc.label" to "Ship it")).remoteCompose
    assertEquals(RemoteNamedValue.StringValue("Ship it"), rc?.namedValues?.get("label"))
  }

  @Test
  fun `an unknown rc kind prefix is treated as part of the string`() {
    // `weird` isn't a known kind, so the whole value is the string — never a hard error.
    val rc = ok(mapOf("rc.label" to "weird:value")).remoteCompose
    assertEquals(RemoteNamedValue.StringValue("weird:value"), rc?.namedValues?.get("label"))
  }

  @Test
  fun `a malformed typed rc value is rejected`() {
    for (bad in listOf("rc.count" to "int:x", "rc.ratio" to "float:nan!", "rc.gap" to "dp:big")) {
      val parsed = ServeOverrides.parse(mapOf(bad))
      assertTrue(parsed is OverrideParse.Invalid, "expected Invalid for $bad, got $parsed")
    }
  }

  @Test
  fun `blank rc name or value is skipped`() {
    // `rc.` with no name, and a present-but-blank value, both drop out rather than seeding.
    assertNull(ok(mapOf("rc." to "x", "rc.label" to "")).remoteCompose)
  }

  @Test
  fun `rc profile parses known wire names and rejects unknown`() {
    assertEquals(
      RemoteComposeProfile.ANDROIDX,
      ok(mapOf("rcProfile" to "androidx")).remoteCompose?.profile,
    )
    assertEquals(
      RemoteComposeProfile.WEAR_WIDGETS,
      ok(mapOf("rcProfile" to "wearWidgets")).remoteCompose?.profile,
    )
    // Case-insensitive on the wire name.
    assertEquals(
      RemoteComposeProfile.WIDGETS_V6,
      ok(mapOf("rcProfile" to "WIDGETSV6")).remoteCompose?.profile,
    )
    assertTrue(ServeOverrides.parse(mapOf("rcProfile" to "bogus")) is OverrideParse.Invalid)
  }

  @Test
  fun `rc profile alone populates the facet`() {
    val rc = ok(mapOf("rcProfile" to "androidx9")).remoteCompose
    assertNotNull(rc)
    assertEquals(RemoteComposeProfile.ANDROIDX9, rc.profile)
    assertTrue(rc.namedValues.isEmpty())
  }

  @Test
  fun `isOverrideParam accepts fixed rc and knob keys and rejects others`() {
    assertTrue(ServeOverrides.isOverrideParam("uiMode"))
    assertTrue(ServeOverrides.isOverrideParam("rcProfile"))
    assertTrue(ServeOverrides.isOverrideParam("rc.label"))
    assertTrue(ServeOverrides.isOverrideParam("knob.count"))
    assertTrue(!ServeOverrides.isOverrideParam("cacheBuster"))
  }

  @Test
  fun `cache key differs when a remote compose facet changes`() {
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("rc.label" to "A"))),
      ServeOverrides.cacheKey("preview.A", ok(emptyMap())),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("rc.label" to "A"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("rc.label" to "B"))),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("rcProfile" to "androidx"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("rcProfile" to "androidx9"))),
    )
    // Order-independent + stable for equal seeds.
    assertEquals(
      ServeOverrides.cacheKey("preview.A", ok(mapOf("rc.a" to "1", "rc.b" to "2"))),
      ServeOverrides.cacheKey("preview.A", ok(mapOf("rc.b" to "2", "rc.a" to "1"))),
    )
  }
}
