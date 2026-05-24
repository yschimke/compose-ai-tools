// Stage-2 spike for the kotlinc-in-daemon investigation. Standalone proof-of-concept
// that the Kotlin Build Tools API (BTA) can compile a `@Composable` source file with
// the Compose compiler plugin loaded, in-process, with no Gradle.
//
// NOT published. NOT wired into the daemon. The only artifact this module produces
// today is a test report demonstrating BTA + Compose-plugin viability against Kotlin
// 2.3.21. See docs/daemon/BTA-SPIKE.md for goals + exit criteria + what to do next.

plugins { alias(libs.plugins.kotlin.jvm) }

// Same JDK floor as the rest of the daemon modules — BTA's `kotlin-build-tools-impl`
// is built against JDK 17 in the 2.3.x line, matching our `ComposeAiJvmConventionsPlugin`
// toolchain. Bumping later (e.g. to chase a 2.4 line) is fine but track it explicitly.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
  // BTA public interface — what the spike code compiles against. Experimental in 2.3.x
  // (requires `@OptIn(ExperimentalBuildToolsApi::class)`); KGP 2.3.20 uses BTA by default
  // for Kotlin/JVM, so the impl side is well-exercised even if the public API surface
  // hasn't stabilised. See https://kotlinlang.org/docs/build-tools-api.html.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  // BTA implementation — loaded into an isolated classloader by `KotlinToolchain
  // .loadImplementation(...)`. Version MUST match the Kotlin compiler version we want
  // BTA to drive; the artifact is only resolved at runtime, but having it on the test
  // classpath is what makes that classloader lookup work.
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.versions.kotlin.get()}")

  // Compose compiler plugin — same JAR `org.jetbrains.kotlin.plugin.compose` resolves
  // to. The `-embeddable` variant shadows kotlin-stdlib so it can co-exist with the
  // BTA impl classloader's own stdlib without symbol collisions.
  testRuntimeOnly(
    "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}"
  )

  // Compose runtime — needed on the *compile* classpath the spike feeds to BTA so
  // that `@Composable` and `Composer` resolve. We don't link against it from the
  // spike's own code, hence `testRuntimeOnly`. Pinned to whatever the compose-bom-stable
  // BOM is currently on; the spike doesn't care about version-skew across the runtime
  // line because it only compiles toy fixtures.
  testRuntimeOnly(platform(libs.compose.bom.stable))
  testRuntimeOnly("androidx.compose.runtime:runtime")

  testImplementation(libs.junit)
}

// Companion fixture — compiled by Gradle's standard `compileKotlin` so the spike's
// Gradle-parity test (`BtaCompilerGradleParityTest`) has a reference artefact to diff against.
// `:daemon:bta-host-fixture` is a single-source module that holds nothing but the same
// `fixture/Greeting.kt` the BTA tests rewrite into a `tmp` folder. Wired as `testImplementation`
// so Gradle compiles it before the test runs; we don't actually link against the fixture
// classes, only read its `.class` output off disk.
dependencies { testImplementation(project(":daemon:bta-host-fixture")) }

// Surface where to find the BTA impl JAR + Compose compiler plugin JAR + Compose runtime
// classpath at test time so `BtaCompilerTest` can hand them to the in-process BTA
// session without re-resolving the same coordinates. We resolve eagerly here so the
// configuration-cache serialiser sees a plain file collection instead of a
// `NamedDomainObjectProvider`. The `joinToString` runs at task-action time (lazy
// `Provider.map`) so artifact downloads on a fresh cache still happen during the
// task graph, not at configuration time.
tasks.named<Test>("test") {
  val testRuntime =
    configurations.named("testRuntimeClasspath").map {
      it.files.joinToString(File.pathSeparator) { jar -> jar.absolutePath }
    }
  // Gradle-compiled fixture inputs for the parity test.
  val fixtureProject = project(":daemon:bta-host-fixture")
  val fixtureClassesDir =
    fixtureProject.layout.buildDirectory.dir("classes/kotlin/main").map { it.asFile.absolutePath }
  val fixtureSourceDir =
    fixtureProject.layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dcomposeai.bta.testRuntimeClasspath=${testRuntime.get()}",
        "-Dcomposeai.bta.fixtureGradleClassesDir=${fixtureClassesDir.get()}",
        "-Dcomposeai.bta.fixtureSourceDir=$fixtureSourceDir",
      )
    }
  )
}
