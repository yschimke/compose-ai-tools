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
        .trimIndent(),
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

  @Test
  fun `library-injected activities are dropped`() {
    // A merged manifest carries every library's activities alongside the app's own. None of them
    // are app screens; Firebase Auth's two headless trampolines in particular NPE on launch and
    // used to land in the Confetti catalogs as permanently-broken cards.
    val merged =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.example.app">
          <application>
              <activity android:name=".MainActivity" android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>
              <activity android:name="androidx.compose.ui.tooling.PreviewActivity" />
              <activity android:name="com.google.android.gms.common.api.GoogleApiActivity" />
              <activity android:name="com.google.firebase.auth.internal.GenericIdpActivity" />
              <activity android:name="com.google.firebase.auth.internal.RecaptchaActivity" />
              <activity android:name="com.example.app.SettingsActivity" />
          </application>
      </manifest>
      """
        .trimIndent()

    val activities = AppTourDiscovery.parseManifestActivities(merged.byteInputStream())

    assertEquals(
      listOf("com.example.app.MainActivity", "com.example.app.SettingsActivity"),
      activities.map { it.className },
    )
  }

  @Test
  fun `an app that owns a library-adjacent namespace keeps its own activities`() {
    // The exclusion list matches by prefix, so it has to name LIBRARY namespaces, never whole
    // vendor ones. Now in Android really does ship as `com.google.samples.apps.nowinandroid`: a
    // bare `com.google.` prefix would classify every one of its screens as library-injected and
    // leave its catalog with no activities at all, while still dropping the Firebase trampolines
    // sitting one package over.
    val merged =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.google.samples.apps.nowinandroid">
          <application>
              <activity android:name=".MainActivity" android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>
              <activity android:name="com.google.firebase.auth.internal.RecaptchaActivity" />
              <activity android:name="com.google.android.gms.common.api.GoogleApiActivity" />
          </application>
      </manifest>
      """
        .trimIndent()

    val activities = AppTourDiscovery.parseManifestActivities(merged.byteInputStream())

    assertEquals(
      listOf("com.google.samples.apps.nowinandroid.MainActivity"),
      activities.map { it.className },
    )
  }

  private val spatialManifestXml =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="com.example.quest">
        <application>
            <activity android:name=".QuestActivity" android:exported="true">
                <meta-data android:name="com.meta.spatial.sdk.Theme" android:value="true" />
                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
            </activity>
            <activity android:name=".SpatialPanelActivity">
                <meta-data android:name="ee.schimke.composeai.preview.skip" android:value="true" />
            </activity>
            <activity android:name=".NotSkippedActivity">
                <meta-data android:name="ee.schimke.composeai.preview.skip" android:value="false" />
            </activity>
            <activity android:name="com.example.quest.SettingsActivity" />
        </application>
    </manifest>
    """
      .trimIndent()

  @Test
  fun `skip meta-data drops the activity preview but keeps the activity`() {
    val activities = AppTourDiscovery.parseManifestActivities(spatialManifestXml.byteInputStream())

    assertEquals(4, activities.size)
    assertTrue(activities.single { it.className.endsWith(".SpatialPanelActivity") }.skipPreview)
    // Only the skip key opts out; other meta-data and an explicit `false` don't.
    assertFalse(activities.single { it.className.endsWith(".QuestActivity") }.skipPreview)
    assertFalse(activities.single { it.className.endsWith(".NotSkippedActivity") }.skipPreview)

    val previews = AppTourDiscovery.buildActivityPreviews(activities, isWear = false)
    assertEquals(
      listOf(
        "activity__QuestActivity",
        "activity__NotSkippedActivity",
        "activity__SettingsActivity",
      ),
      previews.map { it.id },
    )
  }

  @Test
  fun `excludeActivities drops matching activity previews, launcher included`() {
    val activities = AppTourDiscovery.parseManifestActivities(spatialManifestXml.byteInputStream())

    val previews =
      AppTourDiscovery.buildActivityPreviews(
        activities,
        isWear = false,
        excludeActivities =
          setOf("com.example.quest.QuestActivity", "com.example.quest.NotInTheManifest"),
      )

    assertEquals(
      listOf("activity__NotSkippedActivity", "activity__SettingsActivity"),
      previews.map { it.id },
    )
  }

  @Test
  fun `the skip flag is not written to previews json`() {
    val activity = ManifestActivity(className = "com.example.A", skipPreview = true)
    val encoded =
      kotlinx.serialization.json
        .Json { encodeDefaults = true }
        .encodeToString(ManifestActivity.serializer(), activity)

    assertFalse(encoded, encoded.contains("skipPreview"))
  }

  @Test
  fun `resolveActivityClassName resolves relative and bare names against the package`() {
    val pkg = "com.example.quest"
    assertEquals(
      "com.example.quest.QuestActivity",
      AppTourDiscovery.resolveActivityClassName(".QuestActivity", pkg),
    )
    assertEquals(
      "com.example.quest.QuestActivity",
      AppTourDiscovery.resolveActivityClassName("QuestActivity", pkg),
    )
    assertEquals(
      "com.example.other.QuestActivity",
      AppTourDiscovery.resolveActivityClassName(" com.example.other.QuestActivity ", pkg),
    )
    assertEquals(".QuestActivity", AppTourDiscovery.resolveActivityClassName(".QuestActivity", ""))
    assertEquals(
      ".QuestActivity",
      AppTourDiscovery.resolveActivityClassName(".QuestActivity", null),
    )
  }

  @Test
  fun `discovery honours excludeActivities and still tours an excluded launcher`() {
    val dir = createTempDirectory("exclude-activities").toFile()
    val manifest = File(dir, "AndroidManifest.xml").apply { writeText(spatialManifestXml) }
    val tour =
      File(dir, "home.json").apply {
        writeText("""{ "steps": [ { "label": "back", "back": true } ] }""")
      }

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = emptyList(),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":quest",
          variantName = "debug",
          projectDirectory = dir,
          failOnEmpty = false,
          mergedManifest = manifest,
          tourSpecFiles = listOf(tour),
          excludeActivities = setOf("com.example.quest.QuestActivity"),
        ),
      )

    val result = outcome as PreviewDiscovery.Outcome.Success
    val ids = result.manifest.previews.map { it.id }
    assertFalse(ids.toString(), "activity__QuestActivity" in ids)
    assertFalse(ids.toString(), "activity__SpatialPanelActivity" in ids)
    assertTrue(ids.toString(), "activity__SettingsActivity" in ids)
    // The index keeps every entry point, and the tour's default start is still the launcher.
    assertEquals(4, result.manifest.activities.size)
    val tourPreview = result.manifest.previews.single { it.id == "apptour__home" }
    assertEquals(
      "com.example.quest.QuestActivity",
      tourPreview.params.launchIntent?.activityClassName,
    )
  }
}
