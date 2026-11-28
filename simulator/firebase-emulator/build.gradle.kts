// Real Firebase Local Emulator Suite integration: Hub discovery, firebase.json parsing,
// Firestore/Auth/RTDB REST admin client, and reflective Android SDK useEmulator() auto-wiring.
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
          "`:simulator-firebase-emulator` compiles against an Android SDK platform's android.jar," +
            " and none was found under $platforms.",
        )
    }

dependencies {
  compileOnly(files(androidJar))

  testCompileOnly(files(androidJar))
  testImplementation(libs.junit)
  testImplementation(libs.json.org)
}
