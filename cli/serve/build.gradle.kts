import java.io.File
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

// `compose-preview serve` — the preview server, as its own module.
//
// #3824 preparation item 7. `serve` was a package inside `:cli`, and a package has no boundary: the
// only thing keeping the server from reaching into the CLI was `scripts/check-serve-seam.py`, a
// source scanner. This module makes it a build fact. Nothing here can see `:cli`, because `:cli`
// depends on this and Gradle has no cycles — and `checkServeModuleBoundary` below says so in a way
// that survives someone adding the dependency back.
//
// Package note: the sources keep `ee.schimke.composeai.cli.serve`. Renaming the package is a
// separate change from moving the module — the lesson `:bundle-format` taught twice (see
// docs/design/PREVIEW_SERVER_SPLIT.md) is that the two are independent, and doing them together
// makes a 300-file move unreviewable.
//
// The direction that remains is `:cli` -> here, which is what the seam register calls
// `serveInternalsUsedByCli`: 102 symbols on `main`, dominated by `ServeCommand.kt`. That is the
// rest of item 7 and is deliberately NOT addressed by this module extraction.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  // `FakeRenderSession` is scaffolding this module's own tests share with `:cli`'s
  // `BundleRenderKnobTest`, which drives `bundle render --knob` against a fake session rather than
  // a daemon subprocess. A test fixture rather than a `main` source: it must not reach the server's
  // runtime classpath.
  `java-test-fixtures`
}

// Same derivation as `:cli` (PLUGIN_VERSION in CI, a patch-bumped SNAPSHOT off
// `.release-please-manifest.json` locally). Without it Gradle leaves `project.version` as
// `unspecified`, `generateServeVersionResource` below writes `version=unspecified`, and the server
// reports that string through `/version`, the session handshake, the page footers and every bug
// report — silently, because nothing type-checks a version string.
//
// A separate assignment from `:cli`'s rather than a shared one, deliberately: the two agree today
// because they ship together, and the whole point of #3824 is that one day they will not.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

// The jar lands in the CLI distribution's `lib/` beside a hundred third-party jars, where a bare
// `serve.jar` (the default from the project name) says nothing and could collide. Same naming as
// `:cli`'s `compose-preview` and `:mcp`'s `compose-preview-mcp`.
base { archivesName.set("compose-preview-serve") }

dependencies {
  // Published wire-format DTOs and the bundle format. `api` because they appear in this module's
  // own signatures, which `:cli` reads.
  api(project(":preview-data-api"))
  api(project(":bundle-format"))
  api(project(":bundle-coordinates"))
  api(project(":daemon:core"))
  api(project(":daemon-client"))
  api(project(":render-session-api"))
  api(project(":render-session-subprocess"))

  implementation(project(":common-io"))
  implementation(project(":data-layoutinspector-core"))
  implementation(project(":data-theme-core"))
  implementation(project(":data-pseudolocale-core"))
  implementation(project(":data-preview-overrides-core"))
  implementation(project(":data-remotecompose-core"))
  implementation(project(":data-render-core"))

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.server.compression)
  implementation(libs.ktor.server.auto.head.response)
  implementation(libs.classgraph)
  implementation(libs.jmdns)

  // BTA *interfaces only* — the playground compiler references `BtaCompileSession`'s
  // build-tools-api parameter types (`CompilerPlugin`, `KotlinLogger`, `SourcesChanges`) to drive
  // an in-process compile. `:daemon:core` declares this as `implementation`, so it is not
  // transitive; the impl JARs ride in the CLI distribution's `lib-bta/`, not here.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  testImplementation(kotlin("test"))

  // The fixture source set compiles against the module's own API and the render-session contract
  // it fakes.
  testFixturesImplementation(kotlin("test"))
  testFixturesApi(project(":render-session-api"))

  // In-memory FileSystem for the playground and store tests, which assert on-disk output without
  // touching the real FS. Okio itself is on the compile classpath via `:common-io`; the fake ships
  // separately.
  testImplementation(libs.okio.fakefilesystem)

  // The *parse-only* PSI spike (`PsiParseSpikeTest`) measures whether a Kotlin frontend parse can
  // replace the playground cleaner's text passes. Deliberately `testImplementation` and nothing
  // else — the server's own runtime classpath must stay free of the compiler frontend, the same
  // rule `:cli` applies. If the spike says yes, the real change loads these jars through the
  // isolated `lib-bta/` classloader, not from here.
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}"
  )
}

// Sidecar jars the server loads through an ISOLATED classloader, never from its own classpath:
// the BTA implementation + Compose compiler plugin (`lib-bta/`) and the Kotlin parser behind the
// playground's source cleaner (`lib-usage-psi/`). `:cli` declares the same two configurations for
// the distribution it stages; these exist so the serve tests that exercise those reflective load
// paths get the real jars handed to them, exactly as they did while they lived in `:cli`.
//
// Without them the tests do not fail — they take the fallback branch and pass, which is worse: the
// parser-backed rewrite and the in-process compile, the whole point of both code paths, would have
// no coverage at all while CI stayed green.
val composePreviewBta =
  configurations.create("composePreviewBta") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

val composePreviewUsagePsi =
  configurations.create("composePreviewUsagePsi") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.versions.kotlin.get()}",
  )
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}",
  )
  add("composePreviewUsagePsi", project(":usage-source-psi"))
}

tasks.withType<Test>().configureEach {
  // JUnit 5, as `:cli` runs these same tests today — `kotlin("test")` resolves its junit5 variant
  // off the back of this, which is where the `org.junit.jupiter` API (`@TempDir`, `@Test`) comes
  // from. Without it the platform defaults to JUnit 4 and roughly a dozen serve test classes stop
  // compiling.
  useJUnitPlatform()

  // Catalog checkouts for the usage-snippet corpus (`UsageSnippetCorpusTest`, which moved here with
  // the serve sources). Absent by default, so the corpus is a no-op in a normal build;
  // `scripts/usage-corpus.sh` supplies them. Forwarded rather than read from the environment so the
  // paths show up in the build's own inputs. `repos` is ONE property carrying every checkout as
  // `name=path,name=path`, rather than a key per catalog: a fixed key list silently ignores any
  // checkout not named in it, so adding a third catalog would produce an empty corpus and a
  // passing run.
  for (key in
    listOf(
      "composeai.usageCorpus.repos",
      "composeai.usageCorpus.out",
      "composeai.usageCorpus.samples",
    )) {
    providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
  }

  // Through a `CommandLineArgumentProvider` (resolved at execution time, declared as an input) so
  // the configuration cache stays valid rather than resolving a configuration at configuration
  // time. Moved here with the tests; see the configurations above for why an absent jar is a
  // silent pass rather than a failure.
  val btaJars = composePreviewBta.incoming.files
  inputs.files(btaJars).withPropertyName("libBtaJars").withNormalizer(ClasspathNormalizer::class)
  val usagePsiJars = composePreviewUsagePsi.incoming.files
  inputs
    .files(usagePsiJars)
    .withPropertyName("libUsagePsiJars")
    .withNormalizer(ClasspathNormalizer::class)

  // The shared wire fixtures under `scripts/design-artifacts/fixtures/`, which
  // `ServeIssueReportTest` and `ServeParityIssuesStoreTest` read straight off disk rather than
  // through the test classpath. Undeclared, Gradle cannot know that editing one changes what those
  // tests assert, so a fixture edit could be served UP-TO-DATE or from the build cache without the
  // assertions ever running.
  inputs
    .files(
      layout.projectDirectory.dir("../../scripts/design-artifacts/fixtures").asFileTree.matching {
        include("*.json")
      }
    )
    .withPropertyName("sharedWireFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dcomposeai.libBtaJars=" + btaJars.joinToString(File.pathSeparator) { it.absolutePath },
        "-Dcomposeai.usagePsi.jars=" +
          (usagePsiJars + btaJars).joinToString(File.pathSeparator) { it.absolutePath },
      )
    }
  )
}

// The boundary #3824 preparation item 7 asks for, now that there is a classpath to check.
//
// `scripts/check-serve-seam.py` proved this by scanning source, because until this module existed
// there was no classpath to look at — `serve` was a package, and packages have no boundary. That
// scanner stays (it still measures the `:cli` -> here direction, which the build permits and the
// split needs to shrink). What it could never do is prove the *reverse*: that nothing here reaches
// into `:cli`. A source scanner can be defeated by reflection, by a string literal, or by a rule
// its tokenizer does not model. A resolved classpath cannot.
//
// The check is deliberately not "does `:cli` appear in my dependency block" — that is a fact about
// this file, which is exactly the thing a mistake would edit. It walks the RESOLVED runtime
// classpath, transitives included, so a `:cli` dependency arriving through some third module fails
// here too.
//
// Also forbidden: the renderer and plugin implementations, mirroring `:cli`'s own
// `checkCliDaemonLibraryBoundary` and the `forbiddenPackages` list in the seam allowlist. An
// extracted preview server is a protocol client; it never loads a renderer in its own JVM.
abstract class CheckServeModuleBoundary : DefaultTask() {
  /**
   * Every component on the resolved runtime classpath, as a stable identity string: `project :cli`
   * for a project in this build, `module <group>:<name>` for anything resolved from a repository.
   *
   * Identity rather than file location, and that distinction is the whole point. An earlier version
   * of this task compared each classpath *file* against the forbidden projects' `projectDir`s,
   * which silently passed the case it most needed to catch: `renderers/desktop` publishes as
   * `ee.schimke.composeai:renderer-desktop`, so once it arrives as a published or transitively
   * substituted Maven dependency its jar sits in Gradle's cache, under no project directory at all.
   * The prefix compare found nothing and the boundary reported clean.
   */
  @get:Input abstract val resolvedComponents: SetProperty<String>

  @get:Input abstract val forbiddenProjects: SetProperty<String>

  @get:Input abstract val forbiddenModules: SetProperty<String>

  @TaskAction
  fun checkBoundary() {
    val forbidden =
      forbiddenProjects.get().map { "project $it" } + forbiddenModules.get().map { "module $it" }
    val hits = resolvedComponents.get().filter { it in forbidden }.sorted()

    check(hits.isEmpty()) {
      "The preview server must not depend on the CLI or on a renderer implementation — that is " +
        "the whole point of #3824's split, and it is why `serve` is a module rather than a " +
        "package. Found on :cli:serve's resolved runtimeClasspath: ${hits.joinToString(", ")}"
    }
  }
}

tasks.register<CheckServeModuleBoundary>("checkServeModuleBoundary") {
  description = "Fails if the CLI, a renderer, or the Gradle plugin reaches the server's classpath."
  group = "verification"

  resolvedComponents.set(
    configurations.named("runtimeClasspath").flatMap { configuration ->
      configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts
          .map { artifact ->
            when (val id = artifact.id.componentIdentifier) {
              is ProjectComponentIdentifier -> "project ${id.projectPath}"
              is ModuleComponentIdentifier -> "module ${id.group}:${id.module}"
              else -> "other ${id.displayName}"
            }
          }
          .toSet()
      }
    }
  )

  // The same implementations named twice, because they can arrive by two different routes and the
  // identity differs between them.
  forbiddenProjects.set(
    listOf(":cli", ":daemon:android", ":daemon:desktop", ":renderer-android", ":renderer-desktop")
  )
  // Their published coordinates, for the transitive case a project-path check cannot see. The
  // Gradle plugin is here and *only* here: it lives in an included build (`includeBuild`), so
  // `project(":gradle-plugin")` does not resolve from this build and it could never have been in
  // a project-path list — the previous task advertised it in its own description and then had no
  // rule for it.
  forbiddenModules.set(
    listOf(
      "ee.schimke.composeai:renderer-android",
      "ee.schimke.composeai:renderer-desktop",
      "ee.schimke.composeai:daemon-android",
      "ee.schimke.composeai:daemon-desktop",
      "ee.schimke.composeai:compose-preview-plugin",
      "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin",
    )
  )
}

tasks.named("check") { dependsOn("checkServeModuleBoundary") }

// The version the server reports, as its own resource.
//
// `ServeVersion.kt` used to read `:cli`'s `cli-version.properties`, which was fine while `serve`
// shipped inside the CLI jar and stopped being fine the moment it did not: the resource is
// generated into `:cli`'s source set, so every `/version` read threw
// "cli-version.properties missing from compose-preview jar" and the routing tests came back 500.
// That was the change `ServeVersion.kt`'s comment said this day would need.
//
// Same shape as `:cli`'s `generateCliVersionResource`, and the same value — both derive from
// `project.version`, which honours the `PLUGIN_VERSION` env override CI sets and the
// `.release-please-manifest.json` fallback for local builds. They are separate facts that happen
// to agree today; when the server ships from its own repo they stop agreeing, and nothing here has
// to change for that.
val generateServeVersionResource =
  tasks.register("generateServeVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/serve-version-resource")
    val serveVersion = project.version.toString()
    inputs.property("version", serveVersion)
    outputs.dir(outputDir)
    doLast {
      val file =
        outputDir.get().file("ee/schimke/composeai/cli/serve/serve-version.properties").asFile
      file.parentFile.mkdirs()
      file.writeText("version=$serveVersion\n")
    }
  }

sourceSets.main.get().resources.srcDir(generateServeVersionResource)

// The typefaces the served viewer registers for its client-side Remote Compose lanes
// (`ServeRcFonts`): without them the browser lane paints a document's generic families in whatever
// the *viewer's* machine calls `sans-serif`, at different metrics and without the Medium weight,
// while the baked PNG beside it used these files (issue #3480).
//
// STAGED, not committed a second time. The source is the one vendored directory the offline parity
// harness reads (`scripts/design-artifacts/rc-fonts.mjs`'s `DEFAULT_FONTS_DIR`) and the snapshot
// renderer rasterizes with, so "the viewer's faces" and "the faces parity is measured against"
// cannot become different files. The named-family faces in that directory (Orbitron, Lobster Two)
// are deliberately left out — the player fetches those itself through `WebFonts.ts`; only the four
// behind the generic families need registering, and they are what `ServeRcFonts.FACES` declares
// (`ServeRcFontsTest` fails when this list and that table disagree).
val stageRcFontResources =
  tasks.register<Sync>("stageRcFontResources") {
    description =
      "Stage the vendored generic-family faces the serve viewer registers for its RC lanes."
    from(rootDir.resolve("samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts")) {
      include(
        "Roboto-Regular.ttf",
        "Roboto-Medium.ttf",
        "NotoSerif-Regular.ttf",
        "DroidSansMono.ttf",
        // The faces' own licence, so the jar carries it beside the bytes.
        "LICENSE.txt",
      )
      into("rc-fonts")
    }
    into(layout.buildDirectory.dir("generated/rc-font-resources"))
  }

sourceSets.main.get().resources.srcDir(stageRcFontResources)
