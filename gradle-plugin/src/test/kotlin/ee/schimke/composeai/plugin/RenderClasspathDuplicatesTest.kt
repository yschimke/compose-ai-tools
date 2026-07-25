package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class RenderClasspathDuplicatesTest {

  private val cache = "/home/u/.gradle/caches/modules-2/files-2.1"
  private val sha = "0123456789abcdef0123456789abcdef01234567"

  private fun cached(group: String, name: String, version: String) =
    "$cache/$group/$name/$version/$sha/$name-$version.jar"

  /** Mirrors what `AndroidPreviewClasspath.buildArtifactCoordinates` hands the detector. */
  private fun coordinates(vararg entries: Pair<String, String>) = entries.toMap()

  private fun module(group: String, name: String, version: String): Pair<String, String> =
    cached(group, name, version) to "$group:$name:$version"

  @Test
  fun `flags the bouncycastle split that broke a11y renders`() {
    // homeassistant-remotecompose#495: Robolectric (via renderer-android) resolved bcprov 1.85 in
    // the renderer graph while the consumer's own mockserver test dep resolved 1.84 in the
    // unit-test graph. Both jars reached one classloader and BC's post-quantum
    // `compositekem.KeyFactorySpi.<clinit>` linked against the wrong `IANAObjectIdentifiers`.
    val paths =
      listOf(
        cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
        cached("org.bouncycastle", "bcutil-jdk18on", "1.84"),
        cached("org.bouncycastle", "bcprov-jdk18on", "1.84"),
      )
    val duplicates =
      RenderClasspathDuplicates.find(
        paths,
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcutil-jdk18on", "1.84"),
          module("org.bouncycastle", "bcprov-jdk18on", "1.84"),
        ),
      )

    assertThat(duplicates.map { it.coordinate }).containsExactly("org.bouncycastle:bcprov-jdk18on")
    assertThat(duplicates.single().versions).containsExactly("1.85", "1.84").inOrder()
    // The winner is the earliest entry — that's the one the JVM actually loads, so it's what the
    // report has to name.
    assertThat(duplicates.single().winner.version).isEqualTo("1.85")
  }

  @Test
  fun `clean classpath reports nothing`() {
    val duplicates =
      RenderClasspathDuplicates.find(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcutil-jdk18on", "1.84"),
          cached("androidx.test", "core", "1.6.1"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcutil-jdk18on", "1.84"),
          module("androidx.test", "core", "1.6.1"),
        ),
      )

    assertThat(duplicates).isEmpty()
  }

  @Test
  fun `same artifact name under different groups is not a duplicate`() {
    // `androidx.core:core`, `androidx.test:core` and this repo's own `:daemon:core` all land on
    // the render classpath as files called `core-<version>`. Grouping on the filename alone
    // reported them as one module at three versions — which is why the detector asks Gradle for
    // coordinates instead of parsing paths.
    val androidxCore = "/c/transforms/aa/transformed/core-1.18.0/jars/classes.jar"
    val testCore = "/c/transforms/bb/transformed/core-1.6.1/jars/classes.jar"
    val daemonCore = "/w/daemon/core/build/libs/core-0.17.20-SNAPSHOT.jar"

    val duplicates =
      RenderClasspathDuplicates.find(
        listOf(androidxCore, testCore, daemonCore),
        coordinates(
          androidxCore to "androidx.core:core:1.18.0",
          testCore to "androidx.test:core:1.6.1",
          daemonCore to "project::daemon:core:",
        ),
      )

    assertThat(duplicates).isEmpty()
  }

  @Test
  fun `unversioned classpath entries are never flagged`() {
    // R.jar, class dirs, the unit-test config dir and android.jar are legitimately unversioned and
    // appear on every render classpath. They're absent from the coordinate map, and flagging them
    // would make the warning useless.
    val duplicates =
      RenderClasspathDuplicates.find(
        listOf(
          "/w/app/build/intermediates/compile_and_runtime_r_class_jar/debugUnitTest/R.jar",
          "/w/app/build/tmp/kotlin-classes/debug",
          "/w/app/build/intermediates/unit_test_config_directory/debugUnitTest",
          "/opt/android-sdk/platforms/android-36/android.jar",
          "/w/app/build/generated/composeai/render-shards/classes",
        )
      )

    assertThat(duplicates).isEmpty()
  }

  @Test
  fun `AGP runtime-variant jar is not a second version`() {
    // The render classpath deliberately pulls BOTH the `jar` and `android-classes` artifact views,
    // so every AAR shows up as `<name>-<version>/jars/classes.jar` AND
    // `<name>-<version>-runtime.jar`. Same module, same version — reporting the pair made the
    // warning fire 77 times on samples/android and rendered it worthless.
    val classes = "/c/transforms/aa/transformed/glance-1.2.0-rc01/jars/classes.jar"
    val runtime = "/c/transforms/bb/transformed/glance-1.2.0-rc01-runtime.jar"

    val duplicates =
      RenderClasspathDuplicates.find(
        listOf(classes, runtime),
        coordinates(
          classes to "androidx.glance:glance:1.2.0-rc01",
          runtime to "androidx.glance:glance:1.2.0-rc01",
        ),
      )

    assertThat(duplicates).isEmpty()
  }

  @Test
  fun `an entry missing from the coordinate map joins an unambiguous module`() {
    // Backstop for a jar that reaches the classpath from a configuration the map doesn't cover.
    // Only one group owns the name `monitor`, so attribution is safe.
    val known = "/c/transforms/aa/transformed/monitor-1.8.0/jars/classes.jar"
    val unmapped = "/c/transforms/bb/transformed/monitor-1.6.1-runtime.jar"

    val duplicates =
      RenderClasspathDuplicates.find(
        listOf(known, unmapped),
        coordinates(known to "androidx.test:monitor:1.8.0"),
      )

    assertThat(duplicates.map { it.coordinate }).containsExactly("androidx.test:monitor")
    assertThat(duplicates.single().versions).containsExactly("1.8.0", "1.6.1").inOrder()
  }

  @Test
  fun `an unmapped entry whose name spans several groups is skipped`() {
    // Attributing it would be a coin flip, and a false duplicate trains people to ignore the
    // warning. Silently skipping is the cheaper failure.
    val androidxCore = "/c/transforms/aa/transformed/core-1.18.0/jars/classes.jar"
    val testCore = "/c/transforms/bb/transformed/core-1.6.1/jars/classes.jar"
    val unmapped = "/c/transforms/cc/transformed/core-9.9.9/jars/classes.jar"

    val duplicates =
      RenderClasspathDuplicates.find(
        listOf(androidxCore, testCore, unmapped),
        coordinates(
          androidxCore to "androidx.core:core:1.18.0",
          testCore to "androidx.test:core:1.6.1",
        ),
      )

    assertThat(duplicates).isEmpty()
  }

  @Test
  fun `splits name from version at the first digit-led segment`() {
    // The boundary rule has to survive artifact names that themselves contain dashes and
    // versions that contain several.
    assertThat(RenderClasspathDuplicates.splitNameVersion("bcprov-jdk18on-1.85"))
      .isEqualTo("bcprov-jdk18on" to "1.85")
    assertThat(RenderClasspathDuplicates.splitNameVersion("robolectric-4.17-beta-2"))
      .isEqualTo("robolectric" to "4.17-beta-2")
    assertThat(RenderClasspathDuplicates.splitNameVersion("ui-android-1.9.5"))
      .isEqualTo("ui-android" to "1.9.5")
    assertThat(
        RenderClasspathDuplicates.splitNameVersion(
          "listenablefuture-9999.0-empty-to-avoid-conflict-with-guava"
        )
      )
      .isEqualTo("listenablefuture" to "9999.0-empty-to-avoid-conflict-with-guava")
    assertThat(RenderClasspathDuplicates.splitNameVersion("classes")).isNull()
  }

  @Test
  fun `report names the module, every version, and the winning jar`() {
    val duplicates =
      RenderClasspathDuplicates.find(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcprov-jdk18on", "1.84"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcprov-jdk18on", "1.84"),
        ),
      )

    val report = RenderClasspathDuplicates.report(duplicates, ":app:composePreviewRender")

    assertThat(report).contains(":app:composePreviewRender")
    assertThat(report).contains("org.bouncycastle:bcprov-jdk18on")
    assertThat(report).contains("1.85, 1.84")
    assertThat(report).contains("wins: ${cached("org.bouncycastle", "bcprov-jdk18on", "1.85")}")
    assertThat(report).contains("also: ${cached("org.bouncycastle", "bcprov-jdk18on", "1.84")}")
    assertThat(report).contains("composePreview.classpathDuplicates=fail")
  }

  @Test
  fun `check warns by default and fails only in fail mode`() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("probe").get()
    val files =
      listOf(
        project.file(cached("org.bouncycastle", "bcprov-jdk18on", "1.85")),
        project.file(cached("org.bouncycastle", "bcprov-jdk18on", "1.84")),
      )
    val coords = files.associate {
      it.absolutePath to
        "org.bouncycastle:bcprov-jdk18on:${it.name.substringAfterLast('-').removeSuffix(".jar")}"
    }

    // warn: reports without breaking the build — a duplicate is a strong smell, not proof the
    // clashing classes are ever loaded, so upgrading the plugin must not fail existing consumers.
    RenderClasspathDuplicates.check(task, files, RenderClasspathDuplicates.MODE_WARN, coords)
    // off: silent even with duplicates present.
    RenderClasspathDuplicates.check(task, files, RenderClasspathDuplicates.MODE_OFF, coords)

    val thrown =
      runCatching {
          RenderClasspathDuplicates.check(task, files, RenderClasspathDuplicates.MODE_FAIL, coords)
        }
        .exceptionOrNull()

    assertThat(thrown).isInstanceOf(GradleException::class.java)
    assertThat(thrown!!.message).contains("org.bouncycastle:bcprov-jdk18on")
  }

  @Test
  fun `check is a no-op on a clean classpath even in fail mode`() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("probeClean").get()
    val file = project.file(cached("androidx.test", "core", "1.6.1"))

    RenderClasspathDuplicates.check(
      task,
      listOf(file),
      RenderClasspathDuplicates.MODE_FAIL,
      mapOf(file.absolutePath to "androidx.test:core:1.6.1"),
    )
  }
}
