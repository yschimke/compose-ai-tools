import java.util.zip.ZipFile

plugins { java }

// The graphics-path versions an app ships today: 1.0.1, which every stable Compose UI up to 1.12.x
// pins and which loads its library unconditionally, and 1.1.0, the latest stable, which loads it
// only on Dalvik and needs no binary on API 34+.
val graphicsPathVersions = mapOf("pinnedByCompose" to "1.0.1", "latestStable" to "1.1.0")

val hostNativesDir: File =
  (findProperty("hostNativesDir") as String?)?.let(::File)
    ?: rootDir.resolve(
      "../build/" +
        if (System.getProperty("os.name").startsWith("Mac")) "macos-universal" else "linux-x86_64"
    )

val aar = configurations.create("aar") { isTransitive = false }

val graphicsPathAars =
  graphicsPathVersions.mapValues { (name, version) ->
    configurations.create("graphicsPath${name.replaceFirstChar(Char::uppercase)}") {
      isTransitive = false
      dependencies.add(project.dependencies.create("androidx.graphics:graphics-path:$version@aar"))
    }
  }

dependencies {
  aar("androidx.test:monitor:1.7.2@aar")
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
val graphicsPathClasses = graphicsPathAars.mapValues { (name, conf) -> aarClasses(name, conf) }

dependencies {
  testImplementation(monitorClasses)
  testCompileOnly(graphicsPathClasses.getValue("pinnedByCompose"))
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

tasks.register("verifyHostNatives") {
  group = "verification"
  description = "Checks the host-natives binaries in -PhostNativesDir against Robolectric."
  dependsOn(withHostLibrary, withoutLibrary, latestStable)
}
