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

// `withPluginClasspath()` reads from `pluginUnderTestMetadata.pluginClasspath`, which by default
// is just `sourceSets.main.runtimeClasspath`. AGP is `compileOnly` at the main level (so it
// stays out of the published plugin JAR) — that means `AndroidComponentsExtension` is not
// reachable from the plugin's classloader at functional-test time, and
// `AndroidPreviewSupport.configure` throws `NoClassDefFoundError` the moment a synthetic
// project applies `com.android.library`.
//
// Pull in **only the AGP API artifacts** (`gradle-api` + transitive `gradle-common-api` /
// `gradle-settings-api`), not the full `gradle` runtime. The runtime jar drags in
// `kotlin-gradle-plugin:2.2.10` plus its bundled Kotlin compiler, which then collides with the
// `kotlin-compose-compiler-plugin-embeddable:2.3.21` the synthetic project resolves through its
// own plugin block — TestKit puts both classes on the same classloader hierarchy and Kotlin
// trips with "language version 2.0 incompatible with ComposeComponentRegistrar". The API jar
// alone carries the AndroidComponentsExtension / Variant / CommonExtension / SingleArtifact
// types that `AndroidPreviewSupport` reaches for; the synthetic project still gets the full
// AGP runtime through its own `id("com.android.library")` plugin block.
val agpForFunctionalTest by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

dependencies {
  // Mark non-transitive: even `gradle-api` drags in `kotlin-stdlib:2.2.10`, and any Kotlin
  // version other than the one the synthetic project's compose-compiler-plugin was built for
  // (`2.3.21`) trips "language version 2.0 incompatible with ComposeComponentRegistrar". The
  // bare `gradle-api`, `gradle-common-api`, and `gradle-settings-api` jars carry the AGP types
  // the plugin reaches for; we add the three explicit coords below so all referenced classes
  // resolve without dragging Kotlin transitively.
  agpForFunctionalTest("com.android.tools.build:gradle-api:${libs.versions.agp.get()}") {
    isTransitive = false
  }
  agpForFunctionalTest("com.android.tools.build:gradle-common-api:${libs.versions.agp.get()}") {
    isTransitive = false
  }
  agpForFunctionalTest("com.android.tools.build:gradle-settings-api:${libs.versions.agp.get()}") {
    isTransitive = false
  }
}

tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
  pluginClasspath.from(agpForFunctionalTest)
}

val functionalTestTask =
  tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnit()

    // `AccessibilityAndroidFunctionalTest` exercises the full external-consumer Android render
    // path: synthetic `com.android.library` project + AGP + Robolectric + the plugin's
    // auto-injected `ee.schimke.composeai:renderer-android:<plugin-version>` Maven coordinate.
    // The synthetic project's `dependencyResolutionManagement.repositories { mavenLocal() }`
    // catches the AAR. The test `assumeTrue`s out cleanly when the AAR isn't in `~/.m2`, so
    // the default `:gradle-plugin:check` stays fast — the caller (CI / dev) is expected to run
    // `./gradlew :renderer-android:publishToMavenLocal` first when they want the Android-flavour
    // coverage.
    //
    // We don't `dependsOn(":renderer-android:publishToMavenLocal")` here because `:gradle-plugin`
    // is an `includeBuild`, and cross-build task dependencies on the *parent* build would have
    // to flow the other direction (parent → child). The skip-and-document model keeps the
    // included-build boundary clean.

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
