package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the contract of [AndroidPreviewSupport.SCENECORE_SPATIAL_BACKEND_SEGMENT] — the per-segment
 * matcher the `composePreviewRenderXr` classpath filter uses to drop scenecore's on-device spatial
 * backends (whose `SpatialCoreXrExtensionsHolderProvider.<clinit>` needs the device-only
 * `com.android.extensions.xr.*` classes) while leaving every consumer file alone.
 *
 * The matcher must cover the artifact shapes Gradle actually puts on a resolved test classpath
 * (module-cache dirs, transformed AAR dirs, transformed runtime jars) and must NOT match consumer
 * checkout/module directories that merely contain the prefix — matching the raw absolute path would
 * silently strip a consumer's own build outputs (Codex review on #2183).
 */
class ScenecoreSpatialBackendSegmentTest {

  private fun matchesAnySegment(path: String): Boolean =
    path.split('/').any { AndroidPreviewSupport.SCENECORE_SPATIAL_BACKEND_SEGMENT.matches(it) }

  @Test
  fun `matches module cache and transform artifact shapes`() {
    val paths =
      listOf(
        "modules-2/files-2.1/androidx.xr.scenecore/scenecore-spatial-core/1.0.0-alpha16/415387e/scenecore-spatial-core-1.0.0-alpha16.aar",
        "modules-2/files-2.1/androidx.xr.scenecore/scenecore-spatial-rendering/1.0.0-alpha16/66e0561/scenecore-spatial-rendering-1.0.0-alpha16.aar",
        "9.6.0/transforms/691435c/transformed/scenecore-spatial-core-1.0.0-alpha16/jars/classes.jar",
        "9.6.0/transforms/3f01318/transformed/scenecore-spatial-rendering-1.0.0-alpha16-runtime.jar",
      )
    for (path in paths) {
      assertThat(matchesAnySegment(path)).isTrue()
    }
  }

  @Test
  fun `does not match consumer directories that merely contain the prefix`() {
    val paths =
      listOf(
        "home/dev/scenecore-spatial-demo/app/build/intermediates/classes/debug",
        "home/dev/checkouts/scenecore-spatial-demo-app/build/libs/app.jar",
        "work/my-scenecore-spatial-core-fork/build/classes",
        "work/scenecore-spatial-core-fork/build/classes",
        "androidx.xr.scenecore/scenecore-testing/1.0.0-alpha16/39aef67/scenecore-testing-1.0.0-alpha16.aar",
        "androidx.xr.scenecore/scenecore-runtime/1.0.0-alpha16/f67473c/scenecore-runtime-1.0.0-alpha16.aar",
      )
    for (path in paths) {
      assertThat(matchesAnySegment(path)).isFalse()
    }
  }
}
