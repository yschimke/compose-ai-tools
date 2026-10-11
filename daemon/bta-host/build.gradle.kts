// Standalone Kotlin Build Tools API (BTA) parity/soak harness. The in-process compile it proved out
// ships in `:daemon:core` (`BtaCompileSession`, behind `composePreview.daemon.compileInProcess`).
// Not published; kept for its parity, incremental-compile and classloader-leak tests guarding that
// path.

plugins {
  id("composeai.base-conventions")
  alias(libs.plugins.kotlin.jvm)
}

// Same JDK floor as the rest of the daemon modules — BTA's `kotlin-build-tools-impl`
// is built against JDK 17 in the 2.3.x line, matching our `ComposeAiJvmConventionsPlugin`
// toolchain. Bumping later (e.g. to chase a 2.4 line) is fine but track it explicitly.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
  // BTA public API (experimental in 2.3.x, needs `@OptIn(ExperimentalBuildToolsApi::class)`). See
  // https://kotlinlang.org/docs/build-tools-api.html.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  // BTA implementation, loaded into an isolated classloader at runtime; its version must match the
  // Kotlin compiler BTA should drive.
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.versions.kotlin.get()}")

  // Compose compiler plugin — same JAR `org.jetbrains.kotlin.plugin.compose` resolves
  // to. The `-embeddable` variant shadows kotlin-stdlib so it can co-exist with the
  // BTA impl classloader's own stdlib without symbol collisions.
  testRuntimeOnly(
    "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}"
  )

  // Compose runtime on the classpath fed to BTA, so `@Composable` and `Composer` resolve in
  // fixtures.
  testRuntimeOnly(platform(libs.compose.bom.stable))
  testRuntimeOnly("androidx.compose.runtime:runtime")

  testImplementation(libs.junit)
}

// `:daemon:bta-host-fixture` compiles the same fixture with Gradle's `compileKotlin`, giving
// `BtaCompilerGradleParityTest` a reference `.class` output to diff against.
dependencies { testImplementation(project(":daemon:bta-host-fixture")) }

// Pass the BTA impl, Compose plugin and runtime classpaths to the tests as a plain file collection
// (configuration-cache friendly), joined lazily at execution so downloads happen in the task graph.
tasks.named<Test>("test") {
  val testRuntime =
    configurations.named("testRuntimeClasspath").map {
      it.files.joinToString(File.pathSeparator) { jar -> jar.absolutePath }
    }
  // Gradle-compiled fixture inputs for the parity test. `:daemon:bta-host-fixture` is compiled via
  // the `testImplementation` dependency above; reference its outputs by path anchored at the build
  // root, since Isolated Projects forbids reaching into another project's `layout`.
  val fixtureDir = layout.settingsDirectory.dir("daemon/bta-host-fixture")
  val fixtureClassesDir = fixtureDir.dir("build/classes/kotlin/main").asFile.absolutePath
  val fixtureSourceDir = fixtureDir.dir("src/main/kotlin").asFile.absolutePath
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dcomposeai.bta.testRuntimeClasspath=${testRuntime.get()}",
        "-Dcomposeai.bta.fixtureGradleClassesDir=$fixtureClassesDir",
        "-Dcomposeai.bta.fixtureSourceDir=$fixtureSourceDir",
      )
    }
  )
}
