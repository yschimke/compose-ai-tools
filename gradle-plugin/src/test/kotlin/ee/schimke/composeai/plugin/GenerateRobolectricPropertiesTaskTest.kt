package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of the generated `robolectric.properties`:
 * `sdk`, `graphicsMode`, `shadows`, and the `application` toggle driven by
 * `composePreview.useConsumerApplication`. The `sdk` + `graphicsMode` keys
 * live here rather than on `@Config` / `@GraphicsMode` to avoid JUnit's
 * `AnnotationParser` resolving `android.app.Application` during test-class
 * discovery — see issue #142 and `GenerateRobolectricPropertiesTask` KDoc.
 */
class GenerateRobolectricPropertiesTaskTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `default emits sdk graphicsMode application shadows`() {
        val body = generate(useConsumerApplication = false)
        assertThat(body).contains("sdk=${GenerateRobolectricPropertiesTask.TARGET_SDK}")
        assertThat(body).contains("graphicsMode=NATIVE")
        assertThat(body).contains("application=android.app.Application")
        assertThat(body).contains("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat")
    }

    @Test
    fun `useConsumerApplication drops application line but keeps sdk graphicsMode shadows`() {
        val body = generate(useConsumerApplication = true)
        assertThat(body).contains("sdk=${GenerateRobolectricPropertiesTask.TARGET_SDK}")
        assertThat(body).contains("graphicsMode=NATIVE")
        assertThat(body).doesNotContain("application=")
        assertThat(body).contains("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat")
    }

    private fun generate(useConsumerApplication: Boolean): String {
        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task = project.tasks.register(
            "generateRobolectricProperties",
            GenerateRobolectricPropertiesTask::class.java,
        ).get()
        task.useConsumerApplication.set(useConsumerApplication)
        task.outputDir.set(tmp.newFolder("out"))
        task.generate()
        val file = task.outputDir.get().asFile
            .resolve("ee/schimke/composeai/renderer/robolectric.properties")
        return file.readText()
    }
}
