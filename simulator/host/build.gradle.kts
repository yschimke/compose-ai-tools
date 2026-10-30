// `compose-preview launch`: what runs inside the launched simulator JVM, beside Robolectric's own
// `org.robolectric:simulator`.
//
// Java, not Kotlin, on purpose. The control plugin is loaded by the Robolectric sandbox class
// loader, which serves the *app's* classes ahead of the runtime classpath — so a `kotlin.*`
// reference here would bind to whatever (possibly R8-shrunk) Kotlin stdlib the APK carries. Plain
// Java against the framework and Robolectric has nothing of the app's to collide with.
//
// Compiles against the SDK's `android.jar` (ANDROID_HOME / ANDROID_SDK_ROOT / `local.properties`
// `sdk.dir`), the same discovery the Android daemon launch uses, rather than a 186 MB `android-all`
// from Maven. Never published: the CLI stages the jar into `lib-simulator/`.
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
        }
    )
    .map { sdk ->
      val platforms = File(sdk, "platforms")
      // Highest platform first; the plugin uses public API from 29 (`WindowInspector`) up.
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
            "found under $platforms. Install a platform (sdkmanager \"platforms;android-36\")."
        )
    }

// Compile-only stand-ins for the slice of Play services' Wearable API the paired data layer
// implements (`DataLayer*`). At run time those classes come from the APK itself, the app's own
// play-services-wearable, so the stubs are never packaged: they only give javac the signatures.
val gmsStubs: SourceSet = sourceSets.create("gmsStubs")

dependencies {
  "gmsStubsCompileOnly"(files(androidJar))
  compileOnly(gmsStubs.output)

  // Robolectric's simulator and the slices of Robolectric the plugin touches. Non-transitive: the
  // full graph pulls the 250 MB native runtime, and at run time all of it comes from the Android
  // daemon sidecar anyway.
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

  // `ApkClassConverter` runs in its own JVM with `lib-dex2jar/` on the classpath.
  compileOnly(libs.dex2jar.tools)

  testImplementation(libs.junit)
  testImplementation(libs.dex2jar.tools)
}
