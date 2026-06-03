package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.provider.ProviderFactory
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Pins the plugin-side reader half of the auto-provision contract: the platform token and the
 * well-known cache binary path that [AndroidPreviewSupport] feeds into the
 * `composePreviewCompositeXr` binary-resolution chain. The CLI writer (`XrCompositeProvision` in
 * `:cli`) derives the identical path from the same release version + host platform, so both sides
 * are unit-tested independently and must agree.
 */
class XrCompositeCachePathTest {

  private val providers: ProviderFactory = ProjectBuilder.builder().build().providers

  private fun of(value: String?) =
    if (value == null) providers.provider { null as String? } else providers.provider { value }

  @Test
  fun `platform token maps the published release matrix`() {
    assertThat(AndroidPreviewSupport.xrCompositePlatformToken("Linux", "amd64"))
      .isEqualTo("linux-x86_64")
    assertThat(AndroidPreviewSupport.xrCompositePlatformToken("Linux", "x86_64"))
      .isEqualTo("linux-x86_64")
    assertThat(AndroidPreviewSupport.xrCompositePlatformToken("Mac OS X", "aarch64"))
      .isEqualTo("macos-arm64")
    assertThat(AndroidPreviewSupport.xrCompositePlatformToken("Windows 11", "amd64"))
      .isEqualTo("windows-x86_64")
    assertThat(AndroidPreviewSupport.xrCompositePlatformToken("Linux", "aarch64")).isNull()
    assertThat(AndroidPreviewSupport.xrCompositePlatformToken("Mac OS X", "x86_64")).isNull()
  }

  @Test
  fun `cache path honours XDG_CACHE_HOME`() {
    val path =
      AndroidPreviewSupport.xrCompositeCacheBinaryPath(
        version = "0.13.1",
        xdgCacheHome = of("/xdg"),
        userHome = of("/home/u"),
        osName = of("Linux"),
        osArch = of("amd64"),
      )
    assertThat(path.get())
      .isEqualTo(File("/xdg/composeai/xr-composite/0.13.1/linux-x86_64/xr-composite").path)
  }

  @Test
  fun `cache path falls back to home dot-cache and uses exe on windows`() {
    val path =
      AndroidPreviewSupport.xrCompositeCacheBinaryPath(
        version = "0.13.1",
        xdgCacheHome = of(null),
        userHome = of("/home/u"),
        osName = of("Windows 11"),
        osArch = of("amd64"),
      )
    assertThat(path.get())
      .isEqualTo(
        File("/home/u/.cache/composeai/xr-composite/0.13.1/windows-x86_64/xr-composite.exe").path
      )
  }

  @Test
  fun `cache path is absent for unpublished platforms`() {
    val path =
      AndroidPreviewSupport.xrCompositeCacheBinaryPath(
        version = "0.13.1",
        xdgCacheHome = of("/xdg"),
        userHome = of("/home/u"),
        osName = of("Linux"),
        osArch = of("aarch64"),
      )
    assertThat(path.isPresent).isFalse()
  }
}
