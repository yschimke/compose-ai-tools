// `compose-preview android-simulator`: composition root and `-javaagent` (`HotSwap`) that wires
// `:simulator-core`, `:simulator-wearable`, `:simulator-mobile`, and `:simulator-wear` together
// beside Robolectric's `org.robolectric:simulator` in `lib-simulator/`.
import java.util.Properties

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  `java-library`
}

val androidJar: Provider<File> =
  providers
    .environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(
      providers
        .fileContents(isolated.rootProject.projectDirectory.file("local.properties"))
        .asText
        .map { text ->
          Properties().apply { load(text.reader()) }.getProperty("sdk.dir").orEmpty().trim()
        },
    )
    .map { sdk ->
      val platforms = File(sdk, "platforms")
      platforms
        .listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
        .orEmpty()
        .map { File(it, "android.jar") }
        .filter { it.isFile }
        .maxByOrNull {
          it.parentFile.name.removePrefix("android-").substringBefore('.').toIntOrNull() ?: 0
        }
        ?: error(
          "`:simulator-host` compiles against an Android SDK platform's android.jar, and none was " +
            "found under $platforms. Install a platform (sdkmanager \"platforms;android-36\").",
        )
    }

dependencies {
  api(project(":simulator-core"))
  api(project(":simulator-wearable"))
  api(project(":simulator-mobile"))
  api(project(":simulator-wear"))

  val robolectricSlices: List<Provider<MinimalExternalModuleDependency>> =
    listOf(
      libs.robolectricSimulator,
      libs.robolectric,
      libs.robolectricShadowsFramework,
      libs.robolectricShadowapi,
      libs.robolectricPluginapi,
      libs.robolectricAnnotations,
      libs.robolectricUtils,
      libs.robolectricSandbox,
      libs.robolectricResources,
    )
  for (slice in robolectricSlices) {
    compileOnly(slice) { isTransitive = false }
  }
  compileOnly(libs.javax.annotation.api)
  compileOnly(libs.javax.inject)
  compileOnly(libs.guava.android)
  compileOnly(files(androidJar))
  compileOnly(libs.dex2jar.tools)
  compileOnly(libs.junit)
  compileOnly(libs.asm)

  testCompileOnly(files(androidJar))
  testImplementation(libs.junit)
  testImplementation(libs.robolectric) { isTransitive = false }
  testImplementation(libs.robolectricSandbox) { isTransitive = false }
  testImplementation(libs.robolectricAnnotations) { isTransitive = false }
  testImplementation(libs.robolectricShadowapi) { isTransitive = false }
  testImplementation(libs.guava.android)
  testImplementation(libs.json.org)
  testImplementation(libs.dex2jar.tools)
}

// The simulator JVM also loads this jar as its `-javaagent`, so a reload can redefine the app's
// changed classes in place (`hotswap.HotSwap`) instead of restarting it.
tasks.named<Jar>("jar") {
  manifest {
    attributes(
      "Premain-Class" to "ee.schimke.composeai.simulator.hotswap.HotSwap",
      "Can-Redefine-Classes" to "true",
    )
  }
}
