package ee.schimke.composeai.daemon

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Producer-side test for the `data/navigation` artefact. Builds a real `ComponentActivity` under
 * Robolectric so we exercise the genuine `Intent` + `OnBackPressedDispatcher` machinery instead
 * of a hand-rolled stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationDataProductTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `producer captures default Robolectric intent and empty back-pressed state`() {
    val rootDir = Files.createTempDirectory("nav-default").toFile()
    val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    try {
      NavigationDataProducer.writeArtifacts(
        rootDir = rootDir,
        previewId = "preview-default",
        activity = controller.get(),
      )
      val payload = readPayload(rootDir, "preview-default")
      val intent = payload["intent"]!!.jsonObject
      // Robolectric's ActivityScenario / buildActivity launches with a default Intent — `action`
      // is null when no explicit action was set on the controller's intent. The artefact still
      // ships the field so non-Robolectric backends can populate it.
      assertNull(
        "default Robolectric intent should expose no action",
        intent["action"]?.jsonPrimitive?.contentOrNull,
      )
      val back = payload["onBackPressed"]!!.jsonObject
      assertFalse(
        "no callback registered → hasEnabledCallbacks must be false",
        back["hasEnabledCallbacks"]!!.jsonPrimitive.boolean,
      )
    } finally {
      controller.close()
      rootDir.deleteRecursively()
    }
  }

  @Test
  fun `toWireIntent surfaces a deep-link Intent with simple-typed extras`() {
    // Drive the Intent → wire-shape mapping directly so the test isn't entangled with
    // ActivityScenario's intent-handling quirks (Robolectric's `buildActivity(cls, intent)` doesn't
    // always preserve extras through `setup()` — we exercise the field-level mapping here and the
    // end-to-end activity capture in [NavigationExtensionTest.process writes navigation json…]).
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse("app://route/profile/42")).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
        setPackage("com.example.app")
        component = ComponentName("com.example.app", "com.example.app.MainActivity")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra("user_id", 42)
        putExtra("show_fab", true)
        putExtra("source", "notification")
        putExtra("session_ms", 1_700_000_000_000L)
        // Parcelable extras must be dropped — round-tripping them through the JSON wire shape
        // would require a Parcel serialiser the data product deliberately doesn't ship.
        putExtra("origin_uri", Uri.parse("app://from"))
      }
    val wire = with(NavigationDataProducer) { intent.toWireIntent() }

    assertEquals(Intent.ACTION_VIEW, wire.action)
    assertEquals("app://route/profile/42", wire.dataUri)
    assertEquals("com.example.app", wire.packageName)
    assertTrue("component must be present", wire.component!!.contains("MainActivity"))
    assertTrue(
      "Default + Browsable categories must travel through",
      Intent.CATEGORY_DEFAULT in wire.categories && Intent.CATEGORY_BROWSABLE in wire.categories,
    )

    assertEquals(42, wire.extras["user_id"]!!.jsonPrimitive.intOrNull)
    assertEquals(true, wire.extras["show_fab"]!!.jsonPrimitive.boolean)
    assertEquals("notification", wire.extras["source"]!!.jsonPrimitive.contentOrNull)
    assertEquals(1_700_000_000_000L, wire.extras["session_ms"]!!.jsonPrimitive.longOrNull)
    assertFalse(
      "Parcelable extras must be dropped — only simple types cross the wire",
      "origin_uri" in wire.extras,
    )
  }

  @Test
  fun `producer reports hasEnabledCallbacks when a BackHandler-style callback is registered`() {
    val rootDir = Files.createTempDirectory("nav-back").toFile()
    val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    try {
      val callback =
        object : OnBackPressedCallback(/* enabled = */ true) {
          override fun handleOnBackPressed() {
            // no-op — we only care that the dispatcher reports the callback.
          }
        }
      controller.get().onBackPressedDispatcher.addCallback(callback)
      NavigationDataProducer.writeArtifacts(
        rootDir = rootDir,
        previewId = "preview-back",
        activity = controller.get(),
      )
      val payload = readPayload(rootDir, "preview-back")
      val back = payload["onBackPressed"]!!.jsonObject
      assertTrue(
        "an enabled OnBackPressedCallback must surface as hasEnabledCallbacks=true",
        back["hasEnabledCallbacks"]!!.jsonPrimitive.boolean,
      )
    } finally {
      controller.close()
      rootDir.deleteRecursively()
    }
  }

  @Test
  fun `toWireIntent drops bundle extras whose value type isn't a simple primitive or string`() {
    // Cross-check the `else -> continue` arm in the producer: a custom-type extra (here an
    // IntArray and a nested Bundle, but the same applies to byte arrays and Parcelables) must
    // not appear in the payload, while sibling string / boolean extras stay.
    val intent =
      Intent().apply {
        putExtra("flag", true)
        putExtra("ints", intArrayOf(1, 2, 3))
        putExtra("nested", Bundle().apply { putString("inner", "v") })
        putExtra("name", "rendered")
      }
    val wire = with(NavigationDataProducer) { intent.toWireIntent() }
    assertEquals(true, wire.extras["flag"]!!.jsonPrimitive.boolean)
    assertEquals("rendered", wire.extras["name"]!!.jsonPrimitive.contentOrNull)
    assertFalse("IntArray extras must be skipped", "ints" in wire.extras)
    assertFalse("nested Bundle extras must be skipped", "nested" in wire.extras)
  }

  private fun readPayload(rootDir: java.io.File, previewId: String): JsonObject {
    val file = rootDir.resolve(previewId).resolve(NavigationDataProducer.FILE)
    assertTrue("navigation.json should exist for $previewId", file.exists())
    return json.parseToJsonElement(file.readText()).jsonObject
  }
}
