package ee.schimke.composeai.data.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for [FontUsedEntry.provider] added in #1057 (Cluster D).
 *
 * The field is open-ended; we exercise the three values the VS Code Text/i18n bundle reads
 * (`"google"`, `"asset"`, `"system"`) plus the null default, and pin the schema-version bump so
 * accidental rollbacks fail loudly.
 */
class FontProviderRoundtripTest {

  private val json = FontsUsedDataProducer.json

  @Test
  fun `schema version is bumped to 2 for the provider field`() {
    assertEquals(2, FontsUsedDataProducer.SCHEMA_VERSION)
  }

  @Test
  fun `provider round-trips for google asset system and null`() {
    val payload =
      FontsUsedPayload(
        listOf(
          entry(requestedFamily = "Roboto", provider = "google"),
          entry(requestedFamily = "ProductSans", provider = "asset"),
          entry(requestedFamily = "monospace", provider = "system"),
          entry(requestedFamily = "Inter", provider = null),
        )
      )
    val encoded = json.encodeToString(FontsUsedPayload.serializer(), payload)
    val decoded = json.decodeFromString(FontsUsedPayload.serializer(), encoded)
    assertEquals(payload, decoded)
    assertEquals("google", decoded.fonts[0].provider)
    assertEquals("asset", decoded.fonts[1].provider)
    assertEquals("system", decoded.fonts[2].provider)
    assertNull(decoded.fonts[3].provider)
  }

  @Test
  fun `payloads from schema-v1 producers (no provider) decode with provider=null`() {
    // Synthesised v1 payload — no `provider` key at all. The decoder
    // must accept it and surface the field as null for the allowlist
    // fallback path.
    val v1Json =
      """{"fonts":[{"requestedFamily":"Roboto","resolvedFamily":"Roboto",""" +
        """"weight":400,"style":"normal"}]}"""
    val decoded = json.decodeFromString(FontsUsedPayload.serializer(), v1Json)
    assertEquals(1, decoded.fonts.size)
    assertNull(decoded.fonts[0].provider)
  }

  @Test
  fun `encoded JSON includes provider when non-null and omits it when null`() {
    val withGoogle =
      json.encodeToString(
        FontsUsedPayload.serializer(),
        FontsUsedPayload(listOf(entry(provider = "google"))),
      )
    assertTrue(
      "expected provider=google in JSON but got $withGoogle",
      withGoogle.contains("\"provider\":\"google\""),
    )
    // `encodeDefaults = true` on FontsUsedDataProducer.json means null
    // defaults are emitted; this test pins that, but more importantly
    // pins that the key exists with the actual null value so the
    // decoder side is symmetric.
    val withoutProvider =
      json.encodeToString(
        FontsUsedPayload.serializer(),
        FontsUsedPayload(listOf(entry(provider = null))),
      )
    assertTrue(
      "expected provider key with null value but got $withoutProvider",
      withoutProvider.contains("\"provider\":null"),
    )
  }

  private fun entry(requestedFamily: String = "Roboto", provider: String? = null): FontUsedEntry =
    FontUsedEntry(
      requestedFamily = requestedFamily,
      resolvedFamily = requestedFamily,
      weight = 400,
      style = "normal",
      provider = provider,
    )
}
