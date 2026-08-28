package ee.schimke.composeai.apptour

import ee.schimke.composeai.renderer.PreviewManifestLoader
import ee.schimke.composeai.renderer.RenderPreviewEntry
import ee.schimke.composeai.renderer.RobolectricRenderTestBase
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Renders the manifest's app-level previews — `kind=ACTIVITY` and `kind=APP_TOUR`.
 *
 * Identical rendering logic to `RobolectricRenderTest` (both are thin `@Parameters` wrappers around
 * [RobolectricRenderTestBase]); the entire reason this is a second class is the **Application**
 * Robolectric hands it.
 *
 * Robolectric resolves the Application per test class, from the `robolectric.properties` files it
 * merges down the class's own package hierarchy — never per test method. The renderer package's
 * file pins `application=android.app.Application` so an isolated composable never runs the
 * consumer's `Application.onCreate()`; that is right for a composable and wrong for an Activity,
 * which *is* the app. Launched against the stub, a Hilt Activity dies on contact —
 *
 * ```
 * java.lang.IllegalStateException: Hilt Activity must be attached to an @HiltAndroidApp
 * Application. Did you forget to specify your Application's class name in your manifest's
 * <application />'s android:name attribute?
 *     at dagger.hilt.android.internal.managers.ActivityComponentManager.createComponent(…)
 * ```
 *
 * — and so does a Koin one ("KoinApplication has not been started"), and one whose
 * `AppComponentFactory` constructs it through DI ("Couldn't call constructor"). Across the whole
 * catalog fleet that was 39 of 45 activity-tour renders; not one tour rendered in any app that uses
 * app-level DI.
 *
 * Hence the split. This class sits in `ee.schimke.composeai.apptour` — deliberately NOT a
 * subpackage of `ee.schimke.composeai.renderer`, since a nested package would inherit that lane's
 * `application=` line and have to override it — and the plugin writes it a properties file of its
 * own that leaves the Application to the merged manifest. A module's composable previews keep the
 * stub; only its tours pay for the real `onCreate()`.
 *
 * The consumer's `Application.onCreate()` genuinely failing under Robolectric is now the failure
 * mode, and it is contained: it fails this class, not the module's composable previews. Set
 * `composePreview.appTourUseConsumerApplication = false` to put this lane back on the stub.
 *
 * Never sharded. `RobolectricRenderTest` gets `_ShardN` subclasses when `composeAiPreview.shards >
 * 1`; app-level previews are a handful per module at most (one per manifest activity), and the cost
 * that matters here is the Application init this class pays once per sandbox — which sharding would
 * multiply, not divide.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class AppTourRobolectricRenderTest(
  preview: RenderPreviewEntry,
  @Suppress("UNCHECKED_CAST") previewArgs: List<Any?>,
) : RobolectricRenderTestBase(preview, previewArgs) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun previews(): List<Array<Any>> =
      PreviewManifestLoader.loadShard(0, 1, PreviewManifestLoader.Lane.APP)
  }
}
