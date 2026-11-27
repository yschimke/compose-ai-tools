// Shared core of the Robolectric simulator host: navigation, protocol, displays, inputs,
// networking, media, permissions, notifications, background work, AppSearch shadows, and the
// APK dex-to-jar converter.
//
// Plain Java against android.jar and Robolectric slices — no Kotlin stdlib to collide with the
// simulated APK's own classes.
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
          "`:simulator-core` compiles against an Android SDK platform's android.jar, and none was " +
            "found under $platforms. Install a platform (sdkmanager \"platforms;android-36\").",
        )
    }

// Compile-only stand-ins for the androidx AppSearch types (`Shadow*Storage`). The APK supplies
// the real classes at run time.
val androidxStubs: SourceSet = sourceSets.create("androidxStubs")

dependencies {
  compileOnly(androidxStubs.output)

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

tasks.withType<Test>().configureEach {
  jvmArgs(
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
  )
}

@CacheableTask
abstract class UnpackCoreAarClasses : DefaultTask() {
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

@Suppress("UnstableApiUsage")
testing {
  suites {
    register<JvmTestSuite>("robolectricTest") {
      useJUnit(libs.versions.junit)
      dependencies {
        implementation(files(sourceSets.main.map { it.output.classesDirs }))
        implementation(libs.robolectric)
        implementation(files(androidJar))
      }
      targets.all { testTask.configure { systemProperty("robolectric.logging", "stderr") } }
    }
  }
}

val robolectricTestAars =
  configurations.create("robolectricTestAars") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies { "robolectricTestAars"(libs.robolectric) }

val unpackRobolectricTestJars =
  tasks.register<UnpackCoreAarClasses>("unpackRobolectricTestJars") {
    aars.from(
      robolectricTestAars.elements.map { set -> set.filter { it.asFile.name.endsWith(".aar") } },
    )
    jars.set(layout.buildDirectory.dir("robolectric-test-jars"))
  }

dependencies {
  "robolectricTestRuntimeOnly"(
    files(
        unpackRobolectricTestJars.flatMap { it.jars }.map { dir -> dir.asFileTree.files.sorted() },
      )
      .builtBy(unpackRobolectricTestJars),
  )
}

tasks.named("test") { dependsOn(testing.suites.named("robolectricTest")) }
