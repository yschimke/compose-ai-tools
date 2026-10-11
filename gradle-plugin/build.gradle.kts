import java.util.Properties

plugins {
  id("composeai.maven-publishing")
  `java-gradle-plugin`
  `kotlin-dsl`
  id("org.jetbrains.kotlin.plugin.serialization") version embeddedKotlinVersion
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.tapmoc)
}

ktfmt { googleStyle() }

gradlePlugin {
  website.set("https://github.com/yschimke/compose-ai-tools")
  vcsUrl.set("https://github.com/yschimke/compose-ai-tools.git")
  plugins {
    create("composePreview") {
      id = "ee.schimke.composeai.preview"
      implementationClass = "ee.schimke.composeai.plugin.ComposePreviewPlugin"
      displayName = "Compose Preview Plugin"
      description =
        "Discover and render Jetpack Compose / Compose Multiplatform @Preview functions to PNG"
      tags.set(listOf("compose", "preview", "android", "jetpack-compose", "rendering"))
    }
  }
}

// Publish to Maven Central via the Central Portal; `-SNAPSHOT` versions go to the snapshot repo.

dependencies {
  // `previews.json` schema types, in a separate library so non-Gradle build systems can use it
  // without the plugin or AGP.
  api(project(":preview-discovery"))

  // `daemon-launch.json` schema + builder; assembles a descriptor from pre-resolved inputs.
  api(project(":daemon-launch-builder"))

  // The config-only plugin and shared `composePreview { }` DSL, which this runtime plugin registers
  // tasks against, so the two coexist in one build. `api` because the extension types are public
  // DSL.
  api(project(":gradle-plugin-config"))

  implementation(libs.classgraph)
  implementation(libs.kotlinx.serialization.json)
  // ASM reads method bodies for @Composable call targets (ClassGraph only sees signatures).
  implementation(libs.asm)
  // OkHttp, not Ktor, for device-art prefetch: Ktor 3 needs newer coroutines than the Gradle daemon
  // ships (`NoSuchMethodError`).
  implementation(libs.okhttp)
  compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")

  // Test-only, for `AndroidPreviewLaunchParityTest`, keeping the daemon client off consumers'
  // buildscript classpaths. This included build doesn't get the main build's daemon BOM, and the
  // catalog's `daemon-client` has no version, hence the platform.
  testImplementation(platform(libs.composeai.daemon.bom))
  testImplementation(libs.composeai.daemon.client)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(gradleTestKit())
}

// Functional tests use Gradle TestKit
val functionalTest =
  sourceSets.create("functionalTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }

val functionalTestImplementation =
  configurations.getByName("functionalTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
  }

val functionalTestRuntimeOnly =
  configurations.getByName("functionalTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
  }

val functionalTestTask =
  tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnit()

    // Print full failure messages: these E2Es carry their diagnostics in the message.
    testLogging {
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
      events("failed")
    }

    // Android functional tests resolve the renderer AAR closure from `~/.m2`, so publish first when
    // both run.
    mustRunAfter("publishToMavenLocal")

    // Surface the host's `~/.m2/repository`, the plugin's compile-time version, and the Android
    // SDK location (for synthetic-project `local.properties`) to the test JVM.
    systemProperty(
      "ee.schimke.composeai.functionalTest.mavenLocal",
      providers.systemProperty("user.home").map { "$it/.m2/repository" }.get(),
    )
    systemProperty("ee.schimke.composeai.functionalTest.pluginVersion", project.version.toString())
    // `ANDROID_HOME` or `local.properties`; empty makes the test skip.
    systemProperty("ee.schimke.composeai.functionalTest.androidSdkDir", resolveAndroidSdk(rootDir))
    // Opt-in gate for the slow daemon-spawn round-trip; CI enables it via
    // `functionalTestWithAndroid`.
    val cliA11yE2E = providers.gradleProperty("cli.a11y.e2e").orNull == "true"
    systemProperty("composeai.functionalTest.cliA11yE2E", cliA11yE2E.toString())
    // Opt-in gate for [BundleRenderEndToEndFunctionalTest] (one Desktop JVM per preview); enabled
    // by `functionalTestWithBundleRender`.
    val bundleRenderE2E = providers.gradleProperty("bundle.render.e2e").orNull == "true"
    systemProperty("composeai.functionalTest.cliBundleRender", bundleRenderE2E.toString())
    // Opt-in gate for [AndroidBundleDaemonRenderFunctionalTest] (needs an SDK, one daemon per
    // bundle); enabled by `functionalTestWithAndroidBundleDaemon` after building bundles.
    val androidBundleDaemonE2E =
      providers.gradleProperty("bundle.daemon.android.e2e").orNull == "true"
    systemProperty(
      "composeai.functionalTest.androidBundleDaemon",
      androidBundleDaemonE2E.toString(),
    )
    // The CLI fetches the Android daemon runtime on first use (the path this e2e exercises);
    // `-Dcomposeai.cli.libDaemonAndroidDir` overrides it.
    systemProperty(
      "composeai.functionalTest.libDaemonAndroidDir",
      providers.gradleProperty("bundle.daemon.android.libDir").orNull ?: "",
    )
    // Sample bundles built by the root build; passed unconditionally for config-cache safety, and
    // the test skips when absent.
    val samplesDir = rootDir.parentFile?.resolve("samples")
    systemProperty(
      "composeai.functionalTest.wearBundle",
      samplesDir?.resolve("wear/build/compose-previews/bundle.png")?.absolutePath ?: "",
    )
    systemProperty(
      "composeai.functionalTest.remoteComposeBundle",
      samplesDir?.resolve("remotecompose/build/compose-previews/bundle.png")?.absolutePath ?: "",
    )
    // The `:cli:installDist` binary, passed unconditionally: a configuration-time existence check
    // would be cached as "missing" after the first run. The test checks existence itself.
    val cliBinaryPath =
      rootDir.parentFile?.resolve("cli/build/install/compose-preview/bin/compose-preview")
    systemProperty("composeai.functionalTest.cliBinary", cliBinaryPath?.absolutePath ?: "")
  }

tasks.check { dependsOn(functionalTestTask) }

/**
 * Android SDK from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties` (AGP's precedence);
 * empty when unset so the test can skip.
 */
fun resolveAndroidSdk(rootDir: java.io.File): String {
  System.getenv("ANDROID_HOME")
    ?.takeIf { it.isNotBlank() }
    ?.let {
      return it
    }
  System.getenv("ANDROID_SDK_ROOT")
    ?.takeIf { it.isNotBlank() }
    ?.let {
      return it
    }
  val localProps = rootDir.resolve("local.properties")
  if (localProps.exists()) {
    val props = Properties().apply { localProps.inputStream().use { load(it) } }
    props
      .getProperty("sdk.dir")
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return it
      }
  }
  return ""
}

// Bake the plugin version into a resource so external consumers resolve the matching renderer at
// runtime.
val generatePluginVersionResource =
  tasks.register("generatePluginVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/plugin-version-resource")
    val pluginVersion = project.version.toString()
    // XR fake versions baked from the catalog so injection can't drift from `:renderer-xr` /
    // samples.
    val xrCompose = libs.versions.xr.compose.get()
    val xrRuntimeTesting = libs.versions.xr.runtime.testing.get()
    val xrScenecoreTesting = libs.versions.xr.scenecore.testing.get()
    val xrArcoreTesting = libs.versions.xr.arcore.testing.get()
    // The renderer-xr AAR is released separately (compose-preview-xr) and pinned here.
    val xrRenderer = libs.versions.xr.renderer.get()
    // Pinned `xr-composite` release, shared with the CLI so both use the same cache directory.
    val xrComposite = libs.versions.xr.composite.get()
    // The compose-preview-daemon release the renderers resolve at for external consumers
    // (`PreviewDaemonVersion`); they publish on their own line.
    val previewDaemon = libs.versions.composeai.preview.daemon.get()
    // The Kotlin the theme-pin compiler plugin is built with; `ThemePinning` only attaches it to
    // consumers on the same line, since compiler plugins link against compiler internals.
    val themePinKotlin = libs.versions.kotlin.get()
    inputs.property("version", pluginVersion)
    inputs.property("previewDaemon", previewDaemon)
    inputs.property("themePinKotlin", themePinKotlin)
    inputs.property("xrCompose", xrCompose)
    inputs.property("xrRuntimeTesting", xrRuntimeTesting)
    inputs.property("xrScenecoreTesting", xrScenecoreTesting)
    inputs.property("xrArcoreTesting", xrArcoreTesting)
    inputs.property("xrRenderer", xrRenderer)
    inputs.property("xrComposite", xrComposite)
    outputs.dir(outputDir)
    doLast {
      val base =
        outputDir.get().file("ee/schimke/composeai/plugin/plugin-version.properties").asFile
      base.parentFile.mkdirs()
      base.writeText(
        "version=$pluginVersion\npreviewDaemon=$previewDaemon\nthemePinKotlin=$themePinKotlin\n"
      )
      val xr =
        outputDir.get().file("ee/schimke/composeai/plugin/xr-fake-versions.properties").asFile
      xr.writeText(
        buildString {
          append("compose=$xrCompose\n")
          append("runtimeTesting=$xrRuntimeTesting\n")
          append("scenecoreTesting=$xrScenecoreTesting\n")
          append("arcoreTesting=$xrArcoreTesting\n")
          append("renderer=$xrRenderer\n")
          append("composite=$xrComposite\n")
        }
      )
    }
  }

sourceSets.main.get().resources.srcDir(generatePluginVersionResource)

composeAiMavenPublishing {
  coordinates(
    artifactId = "compose-preview-plugin",
    displayName = "Compose Preview Gradle Plugin",
    description =
      "Gradle plugin to discover and render Jetpack Compose / Compose Multiplatform @Preview functions to PNG outside Android Studio.",
  )
  inceptionYear.set("2025")
}

// Make publish tasks on this included build's root recurse into every subproject, so the published
// plugin's `api(project(...))` deps (notably `:gradle-plugin-config`'s DSL types) resolve. Every
// `api(project(...))` above needs an edge here. `tasks.matching` tolerates the Central tasks being
// registered later.
listOf("publishToMavenLocal", "publishToMavenCentral", "publishAndReleaseToMavenCentral").forEach {
  taskName ->
  tasks
    .matching { it.name == taskName }
    .configureEach {
      dependsOn(":preview-discovery:$taskName")
      dependsOn(":daemon-launch-builder:$taskName")
      dependsOn(":gradle-plugin-config:$taskName")
    }
}
