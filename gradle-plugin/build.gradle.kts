import java.util.Properties

plugins {
  id("composeai.maven-publishing")
  `java-gradle-plugin`
  `kotlin-dsl`
  alias(libs.plugins.kotlin.serialization)
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

// Publish to both GitHub Packages (legacy / CI convenience) and Maven Central
// via the new Central Portal. Snapshots (version ending in `-SNAPSHOT`) route
// automatically to `https://central.sonatype.com/repository/maven-snapshots/`.

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

    // `AccessibilityAndroidFunctionalTest` exercises the full external-consumer Android render
    // path: synthetic `com.android.library` project + AGP + Robolectric + the plugin's
    // auto-injected `ee.schimke.composeai:renderer-android:<plugin-version>` Maven coordinate.
    // The synthetic project resolves *both* AGP and our plugin through its own `plugins { ... }`
    // block (`id("com.android.library") version ...`, `id("ee.schimke.composeai.preview")
    // version ...`) so they share one classloader hierarchy — `withPluginClasspath()` is
    // deliberately *not* used by that test class, because doing so loaded our plugin (and AGP)
    // twice on different loaders and made `AndroidComponentsExtension` identity checks fail.
    //
    // The test `assumeTrue`s out cleanly when the AAR or plugin POM isn't in `~/.m2`, so the
    // default `:gradle-plugin:check` stays fast: the caller (CI / dev) is expected to invoke
    // `./gradlew functionalTestWithAndroid` from the parent build, which pre-publishes the
    // renderer AAR closure *and* the plugin itself to mavenLocal.
    //
    // Ordering between the two parent-scheduled tasks (`:gradle-plugin:publishToMavenLocal` and
    // `:gradle-plugin:functionalTest`, both `dependsOn`d by the root `functionalTestWithAndroid`
    // task) is enforced via `mustRunAfter` below so plain `:gradle-plugin:functionalTest` runs
    // don't drag in a publish.

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
// plugin via GitHub Packages rather than includeBuild).
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
