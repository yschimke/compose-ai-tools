import java.util.zip.ZipFile

plugins { java }

// The graphics-path versions an app ships today: 1.0.1, which every stable Compose UI up to 1.12.x
// pins and which loads its library unconditionally, and 1.1.0, the latest stable, which loads it
// only on Dalvik and needs no binary on API 34+.
val graphicsPathVersions = mapOf("pinnedByCompose" to "1.0.1", "latestStable" to "1.1.0")

val hostNativesDir: File =
  (findProperty("hostNativesDir") as String?)?.let(::File)
    ?: rootDir.resolve(
      "../build/graphics-path/" +
        if (System.getProperty("os.name").startsWith("Mac")) "macos-universal" else "linux-x86_64"
    )

// Robolectric fetches android-all itself, outside Gradle's repositories, so the private Maven cache
// coo.ee/env sandboxes configure (and route Gradle through with an init script) has to be handed to
// it as well. Without all three variables Robolectric uses Maven Central, as on CI.
val mavenCache =
  listOf("COO_MAVEN_URL", "COO_MAVEN_USER", "COO_MAVEN_TOKEN")
    .map { providers.environmentVariable(it).orNull?.trim().orEmpty() }
    .takeIf { values -> values.all { it.isNotEmpty() } }

val aar = configurations.create("aar") { isTransitive = false }

// The Filament release host-natives/filament builds filament-jni from: its Java classes have to
// match that library's native methods exactly.
val filamentVersion =
  rootDir
    .resolve("../filament/recipe.properties")
    .readLines()
    .first { it.startsWith("version=") }
    .removePrefix("version=")
val filamentAar =
  configurations.create("filamentAar") {
    isTransitive = false
    dependencies.add(
      project.dependencies.create(
        "com.google.android.filament:filament-android:$filamentVersion@aar"
      )
    )
  }
val filamentDir: File =
  (findProperty("filamentDir") as String?)?.let(::File)
    ?: rootDir.resolve("../build/filament/linux-x86_64")

val graphicsPathAars =
  graphicsPathVersions.mapValues { (name, version) ->
    configurations.create("graphicsPath${name.replaceFirstChar(Char::uppercase)}") {
      isTransitive = false
      dependencies.add(project.dependencies.create("androidx.graphics:graphics-path:$version@aar"))
    }
  }

dependencies {
  aar("androidx.test:monitor:1.8.0@aar")
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
  // graphics-path 1.1.0 converts conics with androidx.collection's FloatFloatPair.
  testImplementation(libs.androidx.collection)
  // Robolectric's runner reads `@Config` outside the sandbox, which needs the framework's classes.
  testImplementation("org.robolectric:android-all:14-robolectric-10818077")
}

/** `classes.jar` out of each AAR in [from], since a plain JVM build cannot consume an AAR. */
fun aarClasses(name: String, from: Configuration): FileCollection {
  val out = layout.buildDirectory.dir("aar-classes/$name")
  val task =
    tasks.register("extract${name.replaceFirstChar(Char::uppercase)}Classes") {
      inputs.files(from)
      outputs.dir(out)
      doLast {
        val dir = out.get().asFile.apply { deleteRecursively() }.apply { mkdirs() }
        from.files.forEach { aarFile ->
          ZipFile(aarFile).use { zip ->
            zip.getInputStream(zip.getEntry("classes.jar")).use { input ->
              dir.resolve(aarFile.nameWithoutExtension + ".jar").outputStream().use(input::copyTo)
            }
          }
        }
      }
    }
  return files(task).asFileTree.matching { include("*.jar") }
}

val monitorClasses = aarClasses("monitor", aar)
val filamentClasses = aarClasses("filament", filamentAar)
val graphicsPathClasses = graphicsPathAars.mapValues { (name, conf) -> aarClasses(name, conf) }

dependencies {
  testImplementation(monitorClasses)
  testCompileOnly(graphicsPathClasses.getValue("pinnedByCompose"))
  testCompileOnly(filamentClasses)
}

tasks.test { enabled = false }

val emptyLibraryDir = layout.buildDirectory.dir("empty-library-path")

fun verification(name: String, graphicsPath: String, libraryDir: Provider<File>, expect: String) =
  tasks.register<Test>(name) {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + graphicsPathClasses.getValue(graphicsPath)
    // A JNI library binds to one class loader per process, and each API level is its own sandbox.
    forkEvery = 1
    jvmArgs(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
    inputs.files(libraryDir).withPropertyName("libraryDir")
    doFirst {
      libraryDir.get().mkdirs()
      systemProperty("java.library.path", libraryDir.get().absolutePath)
    }
    mavenCache?.let { (url, user, token) ->
      systemProperty("robolectric.dependency.repo.url", "${url.trimEnd('/')}/central")
      systemProperty("robolectric.dependency.repo.username", user)
      systemProperty("robolectric.dependency.repo.password", token)
    }
    filter { excludeTestsMatching("*FilamentTest") }
    systemProperty("hostNatives.expect", expect)
    systemProperty("hostNatives.graphicsPath", graphicsPathVersions.getValue(graphicsPath))
    testLogging {
      events("passed", "failed")
      showStandardStreams = true
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
  }

val withHostLibrary =
  verification("verifyPinnedByCompose", "pinnedByCompose", provider { hostNativesDir }, "segments")
    .apply {
      configure {
        doFirst {
          check(hostNativesDir.resolve("host-natives.properties").isFile) {
            "no host-natives build in $hostNativesDir; run host-natives/build.sh first"
          }
        }
      }
    }
val withoutLibrary =
  verification(
    "verifyPinnedByComposeWithoutLibrary",
    "pinnedByCompose",
    emptyLibraryDir.map { it.asFile },
    "missing-library",
  )
val latestStable =
  verification("verifyLatestStable", "latestStable", emptyLibraryDir.map { it.asFile }, "segments")

// host-natives/filament: an Engine on headless EGL rendering onto a Surface. Needs libEGL and
// libGL (Mesa's software drivers will do) on the machine.
tasks.register<Test>("verifyFilament") {
  group = "verification"
  description = "Checks the filament-jni build in -PfilamentDir against Robolectric."
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath + filamentClasses
  filter { includeTestsMatching("*FilamentTest") }
  jvmArgs(
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
  )
  inputs.dir(filamentDir).withPropertyName("filamentDir")
  doFirst {
    check(filamentDir.resolve("host-natives.properties").isFile) {
      "no host-natives filament build in $filamentDir; run host-natives/build.sh filament first"
    }
    systemProperty("java.library.path", filamentDir.absolutePath)
  }
  mavenCache?.let { (url, user, token) ->
    systemProperty("robolectric.dependency.repo.url", "${url.trimEnd('/')}/central")
    systemProperty("robolectric.dependency.repo.username", user)
    systemProperty("robolectric.dependency.repo.password", token)
  }
  testLogging {
    events("passed", "failed")
    showStandardStreams = true
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

tasks.register("verifyHostNatives") {
  group = "verification"
  description = "Checks the host-natives binaries in -PhostNativesDir against Robolectric."
  dependsOn(withHostLibrary, withoutLibrary, latestStable)
}
