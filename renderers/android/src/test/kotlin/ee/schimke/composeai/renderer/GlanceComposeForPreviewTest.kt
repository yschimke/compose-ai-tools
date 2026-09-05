package ee.schimke.composeai.renderer

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.renderer.GlanceComposeForPreview.Argument
import ee.schimke.composeai.renderer.GlanceComposeForPreview.compose
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage of [GlanceComposeForPreview] — the reflective resolution that lets the Glance app-widget
 * renderer run against whatever `androidx.glance:glance-appwidget` the *rendered project* brought,
 * rather than the 1.2.0 this module compiles against.
 *
 * The regression: `composeForPreview` did not exist before Glance 1.2.0, so a compiled call site
 * took out every app-widget preview in a project on 1.1.x with `NoSuchMethodError` naming line 71
 * of our own renderer (compose-ai-tools#5056). The version shapes under test are transcribed from
 * the published AARs into `GlanceComposerFixtures.kt`.
 *
 * Robolectric because half of this is invocation, and `RemoteViews` / `AppWidgetProviderInfo` are
 * not constructible against the stub `android.jar`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlanceComposeForPreviewTest {

  private class TestWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) = Unit
  }

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  private val info
    get() =
      AppWidgetProviderInfo().apply {
        minWidth = 300
        minHeight = 200
      }

  @Before fun setUp() = GlanceComposerCalls.reset()

  @Test
  fun `resolves composeForPreview on the Glance 1_2_0 shape`() {
    val plan = GlanceComposeForPreview.resolveIn(Glance120ComposerFixture::class.java)

    assertTrue("composeForPreview is the preview path proper", plan.composesPreview)
    assertEquals(
      listOf(Argument.WIDGET, Argument.CONTEXT, Argument.WIDGET_CATEGORY, Argument.PROVIDER_INFO),
      plan.arguments,
    )
  }

  @Test
  fun `falls back to compose on a Glance version that predates composeForPreview`() {
    val plan = GlanceComposeForPreview.resolveIn(Glance11xComposerFixture::class.java)

    assertFalse("1.1.x has no composeForPreview at all", plan.composesPreview)
    assertEquals(
      listOf(
        Argument.WIDGET,
        Argument.CONTEXT,
        Argument.ABSENT, // GlanceId — a preview binds no widget
        Argument.ABSENT, // options Bundle
        Argument.SIZE,
        Argument.ABSENT, // state
      ),
      plan.arguments,
    )
  }

  @Test
  fun `matches the compose extension through its value-class name mangling`() {
    // `DpSize` is a value class, so Kotlin compiles `compose` to `compose-<hash>`. The hash is a
    // function of the signature and free to change between releases — matching it would be the
    // same brittleness in a new costume.
    val plan = GlanceComposeForPreview.resolveIn(Glance11xComposerFixture::class.java)

    assertTrue(
      "expected a mangled `compose-…`, got ${plan.method.name}",
      plan.method.name.startsWith("compose"),
    )
    assertFalse(plan.method.name.contains("\$default"))
  }

  @Test
  fun `prefers composeForPreview when both entry points are present`() {
    val plan = GlanceComposeForPreview.resolveIn(Glance120ComposerFixture::class.java)

    assertEquals("composeForPreview", plan.method.name)
  }

  @Test
  fun `resolves a later signature that grows a parameter it knows how to fill`() {
    val plan = GlanceComposeForPreview.resolveIn(FutureComposerFixture::class.java)

    assertTrue(plan.composesPreview)
    assertEquals(
      listOf(
        Argument.WIDGET,
        Argument.CONTEXT,
        Argument.WIDGET_CATEGORY,
        Argument.PROVIDER_INFO,
        Argument.SIZE,
      ),
      plan.arguments,
    )
  }

  @Test
  fun `refuses a composer whose parameters it cannot fill, naming the version floor`() {
    try {
      GlanceComposeForPreview.resolveIn(UnsupportedComposerFixture::class.java)
      fail("a composer taking a type we cannot supply must not resolve")
    } catch (e: GlanceComposerUnavailableException) {
      val message = e.message.orEmpty()
      assertTrue("names the version floor: $message", message.contains("1.2.0"))
      assertTrue("names what it did find: $message", message.contains("composeForPreview"))
    }
  }

  @Test
  fun `reports a render classpath with no Glance at all`() {
    // A loader that delegates only to the bootstrap classpath — no androidx anywhere.
    val bare = object : ClassLoader(null) {}

    try {
      GlanceComposeForPreview.resolve(bare)
      fail("a classpath without glance-appwidget must not resolve")
    } catch (e: GlanceComposerUnavailableException) {
      assertTrue(e.message.orEmpty().contains("glance-appwidget"))
      assertTrue(e.cause is ClassNotFoundException)
    }
  }

  @Test
  fun `fills composeForPreview by parameter type and returns its RemoteViews`() {
    val plan = GlanceComposeForPreview.resolveIn(Glance120ComposerFixture::class.java)

    val views = runBlocking {
      plan.compose(
        widget = TestWidget(),
        context = context,
        widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        info = info,
        size = DpSize(300.dp, 200.dp),
      )
    }

    assertNotNull(views)
    assertEquals(
      listOf(
        "composeForPreview(widget=TestWidget, " +
          "widgetCategory=${AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN}, info=300x200)"
      ),
      GlanceComposerCalls.recorded,
    )
  }

  @Test
  fun `hands the pre-1_2_0 compose fallback the size and defaults the rest`() {
    // Not nulls: Glance 1.1.x defaults `compose(id = …)` to `createFakeAppWidgetId()` and then
    // casts it to `AppWidgetId`, so our own null died with an NPE inside `runComposition` — the
    // real 1.1.1 failure this path was written for. Going through the `${'$'}default` bridge is
    // what lets the library fill its own parameters.
    val plan = GlanceComposeForPreview.resolveIn(Glance11xComposerFixture::class.java)

    runBlocking {
      plan.compose(
        widget = TestWidget(),
        context = context,
        widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        info = info,
        size = DpSize(300.dp, 200.dp),
      )
    }

    assertEquals(
      listOf(
        "compose(id=fake-widget-id, options=null, " +
          "size=${DpSize(300.dp, 200.dp)}, state=library-default-state)"
      ),
      GlanceComposerCalls.recorded,
    )
  }

  @Test
  fun `masks exactly the parameters it has no value for`() {
    val plan = GlanceComposeForPreview.resolveIn(Glance11xComposerFixture::class.java)

    assertNotNull("the compose overload has defaults, so a bridge must exist", plan.defaults)
    // Value parameters, receiver excluded: context=0, id=1, options=2, size=3, state=4. We supply
    // context and size; the other three are the library's to fill.
    assertEquals(0b10110, plan.defaultsMask)
    assertTrue(plan.usesDefaults)
  }

  @Test
  fun `calls the real method directly when it fills every parameter`() {
    // The 1.2.0 path supplies all of context, widgetCategory and info, so there is nothing to
    // default and the bridge stays out of it — the call is exactly what the old compiled call site
    // made, which is why the pinned-Glance renders are byte-identical.
    val plan = GlanceComposeForPreview.resolveIn(Glance120ComposerFixture::class.java)

    assertEquals(0, plan.defaultsMask)
    assertFalse(plan.usesDefaults)
  }

  @Test
  fun `propagates a throw from the composition as itself, not wrapped in reflection`() {
    val plan = GlanceComposeForPreview.resolveIn(ThrowingComposerFixture::class.java)

    try {
      runBlocking {
        plan.compose(
          widget = TestWidget(),
          context = context,
          widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
          info = info,
          size = DpSize(300.dp, 200.dp),
        )
      }
      fail("the preview's own exception must reach the sidecar")
    } catch (e: IllegalStateException) {
      // An InvocationTargetException here would put reflection's frames on the sidecar's
      // `topAppFrame` and bury the message the preview author needs.
      assertTrue(e.message.orEmpty().startsWith("preview threw"))
    }
  }

  @Test
  fun `refuses a composer that returns something other than RemoteViews`() {
    val plan = GlanceComposeForPreview.resolveIn(WrongReturnComposerFixture::class.java)

    try {
      runBlocking {
        plan.compose(
          widget = TestWidget(),
          context = context,
          widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
          info = info,
          size = DpSize(300.dp, 200.dp),
        )
      }
      fail("a non-RemoteViews return must be reported, not ClassCastException'd downstream")
    } catch (e: GlanceComposerUnavailableException) {
      assertTrue(e.message.orEmpty().contains("java.lang.String"))
    }
  }

  @Test
  fun `resolves against the real Glance on this module's classpath`() {
    // The pin in gradle/libs.versions.toml is 1.2.0, so the preview path is what we expect here —
    // and this is the one test that would notice the day a Glance bump changes the signature.
    val plan = GlanceComposeForPreview.resolve(GlanceAppWidget::class.java.classLoader)

    assertTrue("real Glance ${plan.describe()}", plan.composesPreview)
    assertEquals(
      listOf(Argument.WIDGET, Argument.CONTEXT, Argument.WIDGET_CATEGORY, Argument.PROVIDER_INFO),
      plan.arguments,
    )
  }
}
