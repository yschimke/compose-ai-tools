// Wear OS form-factor simulator surfaces and services: WearSystemUi, WearHome, WatchState,
// SystemData, WearPlatformServices (Weather + WearHealthServices), Tiles, Watch Faces (androidx
// and Declarative / WFF), and the staged Wear Tile / Remote Compose renderer AARs.
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
          "`:simulator-wear` compiles against an Android SDK platform's android.jar, and none " +
            "was found under $platforms.",
        )
    }

dependencies {
  api(project(":simulator-core"))
  api(project(":simulator-wearable"))

  val robolectricSlices: List<Provider<MinimalExternalModuleDependency>> =
    listOf(
      libs.robolectric,
      libs.robolectricShadowsFramework,
      libs.robolectricShadowapi,
      libs.robolectricAnnotations,
      libs.robolectricUtils,
      libs.robolectricSandbox,
      libs.robolectricResources,
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

tasks.withType<Test>().configureEach {
  jvmArgs(
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
  )
}

// The Wear tile renderer and Remote Compose View player for `--launcher`'s tiles and widgets
// carousel, unpacked from AARs into jars beside `:simulator-wear`.
val tileRenderer =
  configurations.create("tileRenderer") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
  }

dependencies {
  for (aar in
    listOf<Provider<MinimalExternalModuleDependency>>(
      libs.wear.tiles.renderer,
      libs.wear.protolayout.renderer,
      libs.wear.protolayout.expression.pipeline,
      libs.wear.asProvider(),
      libs.vectordrawable.seekable,
      libs.compose.remote.player.view,
      libs.compose.remote.player.core,
      libs.customview,
    )) {
    addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>("tileRenderer", aar) {
      artifact { type = "aar" }
    }
  }
}

val R_OVERRIDES =
  mapOf(
    "androidx.wear.protolayout.renderer:style.ProtoLayoutBaseTheme" to
      "android.R.style.TextAppearance_Material",
    "androidx.wear.protolayout.renderer:styleable.ProtoLayoutFontSet" to
      "{ android.R.attr.fontFamily, android.R.attr.fontFamily, android.R.attr.fontFamily }",
  )

@CacheableTask
abstract class UnpackWearTileRenderer : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val aars: ConfigurableFileCollection
  @get:OutputDirectory abstract val jars: DirectoryProperty
  @get:OutputDirectory abstract val sources: DirectoryProperty
  @get:Input abstract val overrides: MapProperty<String, String>

  @TaskAction
  fun unpack() {
    val jarDir =
      jars.get().asFile.apply {
        deleteRecursively()
        mkdirs()
      }
    val sourceDir =
      sources.get().asFile.apply {
        deleteRecursively()
        mkdirs()
      }
    var nextId = 0x7e7e0001
    for (aar in aars.files.sortedBy { it.name }) {
      ZipFile(aar).use { zip ->
        val name = aar.name.removeSuffix(".aar")
        zip.getEntry("classes.jar")?.let { entry ->
          zip.getInputStream(entry).use { input ->
            File(jarDir, "tile-renderer-$name.jar").outputStream().use { input.copyTo(it) }
          }
        }
        val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).reader().readText()
        val pkg = Regex("""package="([^"]+)"""").find(manifest)!!.groupValues[1]
        val rTxt = zip.getEntry("R.txt")?.let { e -> zip.getInputStream(e).reader().readText() }
        if (rTxt.isNullOrBlank()) return@use
        val byType = sortedMapOf<String, MutableList<String>>()
        for (line in rTxt.lines().filter { it.isNotBlank() }) {
          val (kind, type, field, value) = line.split(" ", limit = 4)
          val initializer =
            when {
              overrides.get().containsKey("$pkg:$type.$field") ->
                overrides.get().getValue("$pkg:$type.$field")
              kind == "int[]" ->
                "{ " +
                  value.trim().removePrefix("{").removeSuffix("}").trim().let { v ->
                    v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                  } +
                  " }"
              type == "styleable" -> value
              type == "id" -> "0x" + Integer.toHexString(nextId++)
              else -> "0"
            }
          byType.getOrPut(type) { mutableListOf() } +=
            "    public static ${if (kind == "int[]") "int[]" else "int"} $field = $initializer;"
        }
        val file = File(sourceDir, pkg.replace('.', '/') + "/R.java")
        file.parentFile.mkdirs()
        file.writeText(
          buildString {
            appendLine("// Generated from $name's R.txt by :simulator-wear's unpackTileRenderer.")
            appendLine("package $pkg;")
            appendLine("public final class R {")
            for ((type, fields) in byType) {
              appendLine("  public static final class $type {")
              fields.forEach { appendLine(it) }
              appendLine("  }")
            }
            appendLine("}")
          },
        )
      }
    }
  }
}

val unpackTileRenderer =
  tasks.register<UnpackWearTileRenderer>("unpackTileRenderer") {
    aars.from(tileRenderer)
    jars.set(layout.buildDirectory.dir("tile-renderer/jars"))
    sources.set(layout.buildDirectory.dir("tile-renderer/r-src"))
    overrides.set(R_OVERRIDES)
  }

val tileRendererR =
  sourceSets.create("tileRendererR") { java.srcDir(unpackTileRenderer.flatMap { it.sources }) }

dependencies { "tileRendererRCompileOnly"(files(androidJar)) }

val tileRendererRJar =
  tasks.register<Jar>("tileRendererRJar") {
    archiveFileName.set("tile-renderer-r.jar")
    destinationDirectory.set(layout.buildDirectory.dir("tile-renderer/r-jar"))
    from(tileRendererR.output)
  }

val simulatorJvmLibs =
  configurations.create("simulatorJvmLibs") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
  }

dependencies { add("simulatorJvmLibs", libs.compose.remote.core) }

dependencies {
  runtimeOnly(
    files(unpackTileRenderer.flatMap { it.jars }.map { dir -> dir.asFileTree.files.sorted() })
      .builtBy(unpackTileRenderer),
  )
  runtimeOnly(files(tileRendererRJar.flatMap { it.archiveFile }).builtBy(tileRendererRJar))
  runtimeOnly(files(simulatorJvmLibs))
}

@CacheableTask
abstract class UnpackWearTestAars : DefaultTask() {
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
        implementation(project(":simulator-core"))
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
  tasks.register<UnpackWearTestAars>("unpackRobolectricTestJars") {
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
