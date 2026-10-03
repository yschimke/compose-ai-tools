plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

/**
 * The Compose compiler plugin jar, handed to the embedded compiler in tests so a fixture is
 * compiled exactly as a consumer's composable would be — the redirect is checked against the
 * instructions the real Compose lowering produces, not a hand-written approximation of them.
 */
val composeCompilerPlugin: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
}

dependencies {
  // Provided by the Kotlin compiler that loads the plugin. Built against one Kotlin line; the
  // Gradle plugin attaches it only to a consumer on that line (`ThemePinning.SUPPORTED_KOTLIN`).
  compileOnly(libs.kotlin.compiler.embeddable)

  composeCompilerPlugin(libs.kotlin.compose.compiler.plugin.embeddable)

  testImplementation(libs.kotlin.compiler.embeddable)
  // Fixture compile classpath: the embedded compiler is given this test JVM's own classpath, so
  // Material 3 and the Compose runtime it compiles against are the ones resolved here.
  testImplementation(libs.jetbrains.compose.material3)
  testImplementation(project(":theme-pin-runtime"))
  testImplementation(libs.junit)
}

tasks.test {
  val pluginJar = tasks.jar.flatMap { it.archiveFile }
  val composePluginFiles: FileCollection = composeCompilerPlugin
  inputs.file(pluginJar)
  inputs.files(composePluginFiles)
  jvmArgumentProviders += CommandLineArgumentProvider {
    listOf(
      "-DthemePin.pluginJar=${pluginJar.get().asFile.absolutePath}",
      "-DthemePin.composePluginJar=${composePluginFiles.singleFile.absolutePath}",
    )
  }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "theme-pin-compiler-plugin",
    displayName = "Compose Preview — Theme Pin Compiler Plugin",
    description =
      "Kotlin compiler plugin that points a project's own calls to Material 3 `MaterialTheme` at " +
        "`theme-pin-runtime`'s identical-signature drop-in, so a preview catalog's selected theme " +
        "recolours previews that install their own theme. Opt-in; attached by the compose-preview " +
        "Gradle plugin only to render compilations on a supported Kotlin line.",
  )
  inceptionYear.set("2026")
}
