package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [AndroidPreviewSupport.hasPreviewDependency].
 *
 * Two paths matter:
 * 1. The cheap declared-deps walk over `*Implementation` / `*Api` / `*RuntimeOnly` buckets — what
 *    plain `com.android.application` / `com.android.library` consumers hit. Doesn't resolve any
 *    classpath.
 * 2. The transitive walk over the resolved `${variant}RuntimeClasspath` graph — added for
 *    issue #241. The CMP-Android canonical layout (`:composeApp` applies `com.android.application`
 *    and depends on `:shared` via `project(":shared")`) only has the Compose preview tooling on the
 *    runtime classpath transitively; declared-only inspection rejected those modules with a "no
 *    known @Preview dependency declared" log line, even though the tooling was right there in the
 *    resolved graph.
 *
 * The tests stay AGP-free on purpose: ProjectBuilder gives us enough to build a
 * `${variant}RuntimeClasspath`-shaped resolvable configuration and a multi-project graph through
 * the standard `java-library` + `java` plugins. The transitive case asserts on a `requested` but
 * unresolved coord — the walker treats declared intent as a signal even when the artifact didn't
 * download (offline cache miss, transient 503), which keeps the regression locked in without making
 * the test depend on real network access to an AAR's Maven metadata.
 */
class HasPreviewDependencyTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `direct declared dep on a preview signal is detected via cheap path`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    // No repositories configured: the cheap path inspects `allDependencies` —
    // a declared `Dependency` doesn't trigger resolution. This exercises the
    // pre-#241 path and confirms it still works.
    project.configurations.create("debugImplementation")
    project.dependencies.add(
      "debugImplementation",
      "org.jetbrains.compose.components:components-ui-tooling-preview:0.0.0-stub",
    )

    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isTrue()
  }

  @Test
  fun `transitive preview signal in the runtime classpath is detected via graph walk`() {
    // Multi-project setup: `:lib` declares the preview signal as a real api dep;
    // `:app` only depends on `:lib` via project dep, with no preview-related
    // declarative bucket. Mirrors the issue #241 layout (CMP-Android `:shared`
    // exposing Compose preview tooling, `:composeApp` consuming it transitively).
    val rootProject = ProjectBuilder.builder().withName("root").withProjectDir(tmp.root).build()
    val lib =
      ProjectBuilder.builder()
        .withName("lib")
        .withProjectDir(tmp.newFolder("lib"))
        .withParent(rootProject)
        .build()
    val app =
      ProjectBuilder.builder()
        .withName("app")
        .withProjectDir(tmp.newFolder("app"))
        .withParent(rootProject)
        .build()

    // `java-library` gives us the standard `apiElements` / `runtimeElements`
    // outgoing variants on `:lib` and matching `runtimeClasspath` consumer on
    // `:app`. Plain `java` plugin would also work but `java-library` is closer
    // to how a real shared module is shaped.
    lib.plugins.apply("java-library")
    app.plugins.apply("java")
    app.repositories.mavenCentral()
    lib.repositories.mavenCentral()

    // `:lib` declares the preview signal on `api` so it propagates to consumers'
    // runtime classpaths. Keep the version pinned and avoid `-android`-suffixed
    // artifacts to keep AGP's variant attributes out of the picture — this test
    // is about the graph walk, not AGP integration.
    lib.dependencies.add(
      "api",
      "org.jetbrains.compose.components:components-ui-tooling-preview:1.7.5",
    )

    // `:app` shapes the runtime classpath like AGP would: a `${variant}RuntimeClasspath`
    // configuration extending from the consumer's `implementation` bucket, which in
    // turn depends on `:lib` via project dep. Cheap path can't see the preview
    // signal here — only the graph walk over `debugRuntimeClasspath` finds it
    // through the transitive `:lib` → preview-tooling chain.
    val implementation = app.configurations.getByName("implementation")
    implementation.dependencies.add(app.dependencies.project(mapOf("path" to ":lib")))
    app.configurations.create("debugRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
      extendsFrom(implementation)
      attributes.attribute(
        org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
        app.objects.named(
          org.gradle.api.attributes.Usage::class.java,
          org.gradle.api.attributes.Usage.JAVA_RUNTIME,
        ),
      )
    }

    assertThat(AndroidPreviewSupport.hasPreviewDependency(app, "debug")).isTrue()
  }

  @Test
  fun `unrelated runtime classpath returns false from both paths`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.repositories.mavenCentral()

    project.configurations.create("debugImplementation")
    project.dependencies.add("debugImplementation", "com.google.guava:guava:33.0.0-jre")

    project.configurations.create("debugRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    project.dependencies.add("debugRuntimeClasspath", "com.google.guava:guava:33.0.0-jre")

    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isFalse()
  }

  @Test
  fun `missing variant runtime classpath returns false without throwing`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    // No declarative-bucket match, no `${variant}RuntimeClasspath` configuration —
    // mirrors a fresh module before AGP has wired its variant configurations.
    // Function should return false instead of throwing.
    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isFalse()
  }
}
