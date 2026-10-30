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

// The Wear tile renderer and Remote Compose View player for `--launcher`'s tiles and widgets
// carousel. Apps do not ship them, so the simulator
// does: each AAR's `classes.jar` goes into `lib-simulator/` beside this module's jar, where the
// sandbox finds it after the app's own classes (an app's protolayout wins; the renderer is pinned
// to the version the preview renderer draws tiles with). The AARs' resources are not in the APK's
// resource table, so their `R` classes are generated here from each `R.txt`: ids get unique
// application-range values (they key `View.setTag`), every other resource 0, which theme lookups
// treat as unset and fall back to defaults. The renderer's font theme is the exception
// ([R_OVERRIDES]): it insists on a font family, so its base theme and font-set attributes point at
// the framework's `TextAppearance.Material` and `android:fontFamily` (sans-serif).
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

/** `R` fields given a framework resource instead of 0, by `package:type.name`. */
val R_OVERRIDES =
  mapOf(
    "androidx.wear.protolayout.renderer:style.ProtoLayoutBaseTheme" to
      "android.R.style.TextAppearance_Material",
    "androidx.wear.protolayout.renderer:styleable.ProtoLayoutFontSet" to
      "{ android.R.attr.fontFamily, android.R.attr.fontFamily, android.R.attr.fontFamily }",
  )

/** Unpacks each AAR's classes as `<artifact>.jar`, and writes Java `R` sources from its R.txt. */
@CacheableTask
abstract class UnpackTileRenderer : DefaultTask() {
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
          val fieldName = field
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
            "    public static ${if (kind == "int[]") "int[]" else "int"} $fieldName = $initializer;"
        }
        val file = File(sourceDir, pkg.replace('.', '/') + "/R.java")
        file.parentFile.mkdirs()
        file.writeText(
          buildString {
            appendLine("// Generated from $name's R.txt by :simulator-host's unpackTileRenderer.")
            appendLine("package $pkg;")
            appendLine("public final class R {")
            for ((type, fields) in byType) {
              appendLine("  public static final class $type {")
              fields.forEach { appendLine(it) }
              appendLine("  }")
            }
            appendLine("}")
          }
        )
      }
    }
  }
}

val unpackTileRenderer =
  tasks.register<UnpackTileRenderer>("unpackTileRenderer") {
    aars.from(tileRenderer)
    jars.set(layout.buildDirectory.dir("tile-renderer/jars"))
    sources.set(layout.buildDirectory.dir("tile-renderer/r-src"))
    overrides.set(R_OVERRIDES)
  }

// A separate jar: the generated `R` classes must not be compiled into `:simulator-host` itself,
// where an app that ships these libraries would see two.
val tileRendererR =
  sourceSets.create("tileRendererR") { java.srcDir(unpackTileRenderer.flatMap { it.sources }) }

dependencies { "tileRendererRCompileOnly"(files(androidJar)) }

val tileRendererRJar =
  tasks.register<Jar>("tileRendererRJar") {
    archiveFileName.set("tile-renderer-r.jar")
    destinationDirectory.set(layout.buildDirectory.dir("tile-renderer/r-jar"))
    from(tileRendererR.output)
  }

dependencies {
  runtimeOnly(
    files(unpackTileRenderer.flatMap { it.jars }.map { dir -> dir.asFileTree.files.sorted() })
      .builtBy(unpackTileRenderer)
  )
  runtimeOnly(files(tileRendererRJar.flatMap { it.archiveFile }).builtBy(tileRendererRJar))
}
