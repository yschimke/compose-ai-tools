package ee.schimke.composeai.themepin.compiler

import org.jetbrains.kotlin.backend.jvm.extensions.ClassGeneratorExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Registers [ThemePinClassGeneratorExtension] on a JVM / Android compilation.
 *
 * The compose-preview Gradle plugin attaches this plugin only when a build opts into theme pinning
 * (`composePreview.themePinning=true`, which the import pipeline sets in its throwaway checkout)
 * and only on a Kotlin line it was built against — see `ThemePinning` in the Gradle plugin. A
 * first-party build never gets it, so nothing it ships is touched.
 */
@OptIn(ExperimentalCompilerApi::class)
class ThemePinCompilerPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String = PLUGIN_ID
  override val supportsK2: Boolean = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    ClassGeneratorExtension.registerExtension(ThemePinClassGeneratorExtension())
  }

  companion object {
    const val PLUGIN_ID: String = "ee.schimke.composeai.theme-pin"
  }
}
