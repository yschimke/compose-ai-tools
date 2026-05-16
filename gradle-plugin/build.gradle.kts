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

// Publish to Maven Central via the Central Portal. Snapshots (version
// ending in `-SNAPSHOT`) route automatically to
// `https://central.sonatype.com/repository/maven-snapshots/`.

dependencies {
  implementation(libs.classgraph)
  implementation(libs.kotlinx.serialization.json)
  // ASM walks the preview method's bytecode to extract @Composable call targets — ClassGraph only
  // surfaces annotations + signatures, not method-body invocations. Used by PreviewTargetInference.
  implementation(libs.asm)
  compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(gradleTestKit())
}

// Functional tests use Gradle TestKit
val functionalTest by sourceSets.creating {
  compileClasspath += sourceSets.main.get().output
  runtimeClasspath += sourceSets.main.get().output
}

val functionalTestImplementation by configurations.getting {
  extendsFrom(configurations.testImplementation.get())
}

val functionalTestRuntimeOnly by configurations.getting {
  extendsFrom(configurations.testRuntimeOnly.get())
}

val functionalTestTask =
  tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnit()

    // `CliA11yEndToEndFunctionalTest` (and any future Android-flavour functional test) requires
    // a `publishToMavenLocal` pre-step so its synthetic `com.android.library` project can
    // resolve the renderer AAR closure from `~/.m2`. The `mustRunAfter` ensures the publish
    // runs first when both are scheduled in one Gradle invocation (the
    // `functionalTestWithAndroid` task in the root build does exactly this).
    mustRunAfter("publishToMavenLocal")

    // Surface the host's `~/.m2/repository`, the plugin's compile-time version, and the Android
    // SDK location (for synthetic-project `local.properties`) to the test JVM.
    systemProperty(
      "ee.schimke.composeai.functionalTest.mavenLocal",
      providers.systemProperty("user.home").map { "$it/.m2/repository" }.get(),
    )
    systemProperty("ee.schimke.composeai.functionalTest.pluginVersion", project.version.toString())
    // Resolve sdk.dir from `ANDROID_HOME` (CI) or `local.properties` (dev). Empty string when
    // neither is set — the test then `assumeFalse`s out so devs without an SDK don't see a hard
    // failure.
    systemProperty("ee.schimke.composeai.functionalTest.androidSdkDir", resolveAndroidSdk(rootDir))
    // Opt-in `cli.a11y.e2e=true` gate for the daemon-spawn round-trip
    // (`CliA11yEndToEndFunctionalTest`). Default off — the test cold-starts a Robolectric JVM
    // per render and a daemon JVM per module, so it's too slow for `./gradlew check`. CI runs
    // it via the root build's `functionalTestWithAndroid` task with the flag flipped on.
    val cliA11yE2E = providers.gradleProperty("cli.a11y.e2e").orNull == "true"
    systemProperty("composeai.functionalTest.cliA11yE2E", cliA11yE2E.toString())
    // Opt-in `bundle.render.e2e=true` gate for [BundleRenderEndToEndFunctionalTest]. Spawns
    // Compose Desktop JVM per preview (~1-2s cold start), too slow for the default check loop.
    // Root build's `functionalTestWithBundleRender` task flips this on.
    val bundleRenderE2E = providers.gradleProperty("bundle.render.e2e").orNull == "true"
    systemProperty("composeai.functionalTest.cliBundleRender", bundleRenderE2E.toString())
    // Path to the compose-preview CLI binary built by `:cli:installDist`. The test invokes it
    // directly as a subprocess — that's the actual subject of the e2e. Empty string when the
    // binary hasn't been built; the test self-skips. The root build wires the dependency.
    val cliBinary =
      rootDir.parentFile?.resolve("cli/build/install/compose-preview/bin/compose-preview")
    systemProperty(
      "composeai.functionalTest.cliBinary",
      if (cliBinary?.isFile == true) cliBinary.absolutePath else "",
    )
  }

tasks.check { dependsOn(functionalTestTask) }

/**
 * Reads the Android SDK location from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or the host project's
 * `local.properties` (the same precedence AGP itself uses). Returns an empty string when none are
 * set so the functional test can `assumeFalse` it out cleanly on dev environments without an SDK
 * installed.
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

// Bake the plugin's own version into a resource so it can resolve a matching
// `renderer-android` AAR at runtime for external consumers (who apply the
// plugin via Maven Central rather than includeBuild).
val generatePluginVersionResource by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/plugin-version-resource")
  val pluginVersion = project.version.toString()
  inputs.property("version", pluginVersion)
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("ee/schimke/composeai/plugin/plugin-version.properties").asFile
    file.parentFile.mkdirs()
    file.writeText("version=$pluginVersion\n")
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
