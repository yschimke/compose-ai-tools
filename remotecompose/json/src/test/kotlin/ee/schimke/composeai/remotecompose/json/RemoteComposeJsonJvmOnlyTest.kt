package ee.schimke.composeai.remotecompose.json

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * This module runs on a bare JVM, and that is a property rather than a coincidence.
 *
 * The whole reason the codec is worth having at layer 1 is that the Gradle plugin can compile a
 * JSON sidecar during IR resolution and the server can compile a playground document in-process —
 * neither of which has an Android runtime or a Robolectric sandbox to offer. `remote-core` and
 * `remote-creation-core` are plain `java-library` publications, so that works today.
 *
 * It would stop working silently. Every other Remote Compose artifact — `remote-creation`,
 * `remote-player-core`, `remote-player-view`, `remote-tooling-preview` — is an Android AAR, they
 * all sit in the same `dependencyManagement` block in the POM, and adding one to reach a single
 * helper class is a two-character edit that compiles fine and then dies in a consumer's daemon with
 * `NoClassDefFoundError: android/graphics/Paint`. This test is where that edit fails instead.
 *
 * It asserts on the *loadability* of `android.graphics.Paint` rather than on the resolved
 * classpath, because an AAR reaching this module transitively is exactly the case a build-file
 * assertion would miss.
 */
class RemoteComposeJsonJvmOnlyTest {

  @Test
  fun `no android runtime on the classpath`() {
    val android =
      try {
        Class.forName("android.graphics.Paint", false, javaClass.classLoader)
      } catch (_: ClassNotFoundException) {
        null
      }

    assertThat(android).isNull()
  }

  @Test
  fun `both directions work with no android runtime`() {
    val json =
      """{"header":{"width":10,"height":10},"root":[{"box":{"modifiers":[{"size":10.0}]}}]}"""

    val dump = RemoteComposeJson.dumpToJsonObject(RemoteComposeJson.compile(json))

    assertThat(dump.keys).contains("operations")
  }
}
