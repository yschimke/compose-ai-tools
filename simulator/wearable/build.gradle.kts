// Shared mobile + Wear OS wearable bridge: paired Wearable Data Layer (DataClient, MessageClient,
// NodeClient, CapabilityClient, ChannelClient), RemoteIntents, WearCompanion, PhoneCompanion,
// PeerPresence, and Play Services Wearable shadows.
import java.util.Properties
import java.util.zip.ZipFile

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
          "`:simulator-wearable` compiles against an Android SDK platform's android.jar, and " +
            "none was found under $platforms.",
        )
    }

// Compile-only stand-ins for Play services' Wearable API (`DataLayer*`). At run time those classes
// come from the APK's own play-services-wearable.
val gmsStubs: SourceSet = sourceSets.create("gmsStubs")

dependencies {
  api(project(":simulator-core"))
  "gmsStubsCompileOnly"(files(androidJar))
  compileOnly(gmsStubs.output)

  val robolectricSlices: List<Provider<MinimalExternalModuleDependency>> =
    listOf(
      libs.robolectric,
      libs.robolectricShadowsFramework,
      libs.robolectricShadowapi,
      libs.robolectricAnnotations,
      libs.robolectricUtils,
      libs.robolectricSandbox,
    )
  for (slice in robolectricSlices) {
    compileOnly(slice) { isTransitive = false }
  }
  compileOnly(libs.guava.android)
  compileOnly(files(androidJar))

  testCompileOnly(files(androidJar))
  testImplementation(libs.junit)
  testImplementation(libs.guava.android)
  testImplementation(libs.json.org)
}

val gmsTestAars =
  configurations.create("gmsTestAars") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
  }

dependencies {
  for (aar in listOf(libs.play.services.tasks, libs.play.services.basement)) {
    addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>("gmsTestAars", aar) {
      artifact { type = "aar" }
    }
  }
}

@CacheableTask
abstract class UnpackWearableAarClasses : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val aars: ConfigurableFileCollection
  @get:OutputDirectory abstract val jars: DirectoryProperty

  @TaskAction
  fun unpack() {
    val dir =
      jars.get().asFile.apply {
        deleteRecursively()
        mkdirs()
      }
    for (aar in aars.files) {
      ZipFile(aar).use { zip ->
        val entry = zip.getEntry("classes.jar") ?: return@use
        zip.getInputStream(entry).use { input ->
          File(dir, aar.name.removeSuffix(".aar") + ".jar").outputStream().use { input.copyTo(it) }
        }
      }
    }
  }
}

val unpackGmsTestJars =
  tasks.register<UnpackWearableAarClasses>("unpackGmsTestJars") {
    aars.from(gmsTestAars)
    jars.set(layout.buildDirectory.dir("gms-test-jars"))
  }

dependencies {
  testImplementation(
    files(unpackGmsTestJars.flatMap { it.jars }.map { dir -> dir.asFileTree.files.sorted() })
      .builtBy(unpackGmsTestJars),
  )
}
