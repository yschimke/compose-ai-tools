plugins {
  id("composeai.maven-publishing")
  // No versions: the parent build's `kotlin-dsl` already supplies the embedded Kotlin plugins, and
  // a version trips Gradle's "already on the classpath" check.
  kotlin("jvm")
  kotlin("plugin.serialization")
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.tapmoc)
}

ktfmt { googleStyle() }

// The `previews.json` schema and ClassGraph scan as a pure-JVM library, so non-Gradle consumers
// (Bazel, Amper) can produce conforming manifests without the plugin or AGP (see
// `contrib/README.md`). Lives in this composite so the plugin can use
// `project(":preview-discovery")` directly.

// Generator and discovery behaviour is shared with the JVM/WASM screen-model module.
// ScreenDocument and its value/action DTOs come from the contracts artifact on both paths,
// so neither publication defines a competing copy of those classes.
sourceSets.named("main") {
  kotlin.srcDir(rootDir.resolve("../screen/generator/src/commonMain/kotlin"))
}

dependencies {
  api(libs.kotlinx.serialization.json)
  // The contracts BOM supplies the versions; declared here because this module doesn't apply
  // `composeai.base-conventions`. `api` so consumers (including buildscript classpaths) get the
  // constraint; `implementation` constraints aren't exported.
  api(platform(libs.composeai.contracts.bom))
  api(libs.composeai.screen.document)
  // Wire types for `components.json` / `ui-builder.*.json`; `api` because the generator's public
  // signatures use them.
  api(libs.composeai.component.catalog.protocol)
  // ClassGraph drives `PreviewDiscovery.discover(...)`; same coordinate as the plugin, so only one
  // copy is loaded.
  api(libs.classgraph)
  // ASM reads method bodies for @Composable call targets (ClassGraph only sees signatures).
  api(libs.asm)
  // Reads `@kotlin.Metadata` for real parameter lists; `implementation` keeps metadata types off
  // the published API.
  implementation(libs.kotlin.metadata.jvm)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}"
  )
}

// Compile external consumers, including deliberately broken references, against this module's
// real output and dependencies rather than a stubbed copy of the typed API.
tasks.named<Test>("test") {
  systemProperty("typedAdapterCompileClasspath", sourceSets["test"].runtimeClasspath.asPath)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "preview-discovery",
    displayName = "Compose Preview — Discovery",
    description =
      "Schema types for `previews.json`, the manifest format consumed by the compose-preview daemon. Lets non-Gradle build systems produce conforming manifests without depending on Gradle or AGP.",
  )
  inceptionYear.set("2026")
}

// `PreviewDiscoveryCli` is what Bazel / Amper shell out to: `java -cp <resolved-classpath>
// ee.schimke.composeai.discovery.PreviewDiscoveryCli ...`. Dependencies are `api` so POM-resolving
// consumers get the full classpath. `Main-Class` helps only when the runtime closure is already
// beside the jar; `java -jar` on the bare jar fails (no `Class-Path`, no uber-jar). See
// `docs/NON_GRADLE_INTEGRATION.md`.
tasks.named<Jar>("jar").configure {
  manifest { attributes("Main-Class" to "ee.schimke.composeai.discovery.PreviewDiscoveryCli") }
}

// The published policy schema is a test input: `UiBuilderPolicySchemaTest` checks it against the
// serial names, so without this a schema edit wouldn't re-run the test.
tasks.named<Test>("test").configure {
  inputs
    .file(
      // `..` because this is an included build whose root isn't the repository; a wrong path fails
      // loudly at configuration time.
      rootProject.layout.projectDirectory.file(
        "../scripts/design-artifacts/ui-builder.policy.schema.json"
      )
    )
    .withPropertyName("uiBuilderPolicySchema")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
