package ee.schimke.composeai.discovery

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTourDiscoveryTest {

  private val manifestXml =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="com.example.app">
        <application android:label="Sample">
            <activity android:name=".MainActivity" android:exported="true">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
            </activity>
            <activity android:name="com.example.app.DetailActivity" android:label="Detail">
                <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <category android:name="android.intent.category.DEFAULT" />
                    <data android:scheme="sample" android:host="detail" />
                </intent-filter>
            </activity>
            <activity android:name=".DisabledActivity" android:enabled="false" />
            <receiver android:name=".SomeReceiver" />
        </application>
    </manifest>
    """
      .trimIndent()

  @Test
  fun `parses activities with launcher flag and intent filters`() {
    val activities = AppTourDiscovery.parseManifestActivities(manifestXml.byteInputStream())

    assertEquals(2, activities.size)

    val main = activities.single { it.className == "com.example.app.MainActivity" }
    assertTrue(main.launcher)
    assertTrue(main.exported)
    assertEquals(listOf("android.intent.action.MAIN"), main.intentFilters.single().actions)

    val detail = activities.single { it.className == "com.example.app.DetailActivity" }
    assertFalse(detail.launcher)
    assertFalse(detail.exported)
    assertEquals("Detail", detail.label)
    val filter = detail.intentFilters.single()
    assertEquals(listOf("android.intent.action.VIEW"), filter.actions)
    assertEquals(listOf("sample"), filter.dataSchemes)
    assertEquals(listOf("detail"), filter.dataHosts)
  }

  @Test
  fun `malformed manifest yields empty list`() {
    assertTrue(AppTourDiscovery.parseManifestActivities("<manifest".byteInputStream()).isEmpty())
  }

  @Test
  fun `activity previews mark only non-launcher captures optional`() {
    val activities = AppTourDiscovery.parseManifestActivities(manifestXml.byteInputStream())
    val previews = AppTourDiscovery.buildActivityPreviews(activities, isWear = false)

    assertEquals(2, previews.size)
    val main = previews.single { it.id == "activity__MainActivity" }
    assertEquals(PreviewKind.ACTIVITY, main.params.kind)
    assertEquals("com.example.app.MainActivity", main.className)
    assertFalse(main.captures.single().optional)
    assertEquals("renders/activity__MainActivity.png", main.captures.single().renderOutput)

    val detail = previews.single { it.id == "activity__DetailActivity" }
    assertTrue(detail.captures.single().optional)
  }

  @Test
  fun `tour spec becomes an APP_TOUR preview with a synthesized launch step`() {
    val dir = createTempDirectory("tours").toFile()
    val spec = File(dir, "checkout.json")
    spec.writeText(
      """
      {
        "name": "checkout",
        "steps": [
          { "label": "open cart", "click": { "text": "Cart" } },
          { "label": "back", "back": true },
          { "label": "deep link", "intent": { "action": "android.intent.action.VIEW", "data": "sample://detail" } }
        ]
      }
      """
        .trimIndent()
    )
    val launcher =
      AppTourDiscovery.parseManifestActivities(manifestXml.byteInputStream()).single { it.launcher }
    val warnings = mutableListOf<String>()
    val previews =
      AppTourDiscovery.buildTourPreviews(
        listOf(spec),
        launcherActivity = launcher,
        isWear = false,
        warnings = warnings,
      )

    assertTrue(warnings.isEmpty())
    val tour = previews.single()
    assertEquals("apptour__checkout", tour.id)
    assertEquals(PreviewKind.APP_TOUR, tour.params.kind)
    assertEquals("com.example.app.MainActivity", tour.params.launchIntent?.activityClassName)

    assertEquals(4, tour.captures.size)
    val launch = tour.captures.first()
    assertEquals(0, launch.tourStep?.index)
    assertEquals("launch", launch.tourStep?.label)
    assertNull(launch.tourStep?.click)
    assertEquals("renders/apptour__checkout_step00_launch.png", launch.renderOutput)

    val click = tour.captures[1]
    assertEquals("Cart", click.tourStep?.click?.text)
    assertEquals("renders/apptour__checkout_step01_open_cart.png", click.renderOutput)
    assertTrue(tour.captures[2].tourStep?.back == true)
    assertEquals("sample://detail", tour.captures[3].tourStep?.intent?.data)
  }

  @Test
  fun `tour without start intent and no launcher is skipped with a warning`() {
    val dir = createTempDirectory("tours").toFile()
    val spec = File(dir, "orphan.json")
    spec.writeText("""{ "steps": [ { "label": "x", "back": true } ] }""")

    val warnings = mutableListOf<String>()
    val previews =
      AppTourDiscovery.buildTourPreviews(
        listOf(spec),
        launcherActivity = null,
        isWear = false,
        warnings = warnings,
      )

    assertTrue(previews.isEmpty())
    assertEquals(1, warnings.size)
  }
}
