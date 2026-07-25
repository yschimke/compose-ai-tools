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
  fun `module-level check ignores a split family - each coordinate is at one version`() {
    // Scope boundary, not a clean bill of health: bcprov 1.85 next to bcutil 1.84 is one version
    // per coordinate, so `find` correctly sees nothing. Catching it needs `findFamilySkew` (below)
    // — without that, this classpath passes `classpathDuplicates=fail` and still link-errors.
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
  fun `family check catches the bouncycastle split that the module check cannot`() {
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcutil-jdk18on", "1.84"),
          cached("org.bouncycastle", "bcpkix-jdk18on", "1.84"),
          cached("androidx.test", "core", "1.6.1"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcutil-jdk18on", "1.84"),
          module("org.bouncycastle", "bcpkix-jdk18on", "1.84"),
          module("androidx.test", "core", "1.6.1"),
        ),
      )

    assertThat(skews.map { it.group }).containsExactly("org.bouncycastle")
    assertThat(skews.single().versions).containsExactly("1.85", "1.84").inOrder()
    assertThat(skews.single().coordinates)
      .containsExactly("bcprov-jdk18on:1.85", "bcutil-jdk18on:1.84", "bcpkix-jdk18on:1.84")
      .inOrder()
  }

  @Test
  fun `an aligned family is not reported`() {
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcutil-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcpkix-jdk18on", "1.85"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcutil-jdk18on", "1.85"),
          module("org.bouncycastle", "bcpkix-jdk18on", "1.85"),
        ),
      )

    assertThat(skews).isEmpty()
  }

  @Test
  fun `one coordinate at two versions is left to the module check, not double-reported`() {
    // `find` already names this; repeating it as a family skew would be noise, and noise is what
    // makes a warning ignorable.
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcprov-jdk18on", "1.84"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcprov-jdk18on", "1.84"),
        ),
      )

    assertThat(skews).isEmpty()
  }

  @Test
  fun `BouncyCastle FIPS artifacts are not a family - they version independently`() {
    // `org.bouncycastle` also publishes the FIPS line, whose versions advance on their own
    // schedule: `bc-fips:2.1.0` alongside `bcpkix-fips:2.1.9` is a correct classpath, and both
    // coordinates really do exist at those versions. Matching the whole group would report it as
    // skew and break an innocent build under `classpathDuplicates=fail`.
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.bouncycastle", "bc-fips", "2.1.0"),
          cached("org.bouncycastle", "bcpkix-fips", "2.1.9"),
          cached("org.bouncycastle", "bctls-fips", "2.1.20"),
        ),
        coordinates(
          module("org.bouncycastle", "bc-fips", "2.1.0"),
          module("org.bouncycastle", "bcpkix-fips", "2.1.9"),
          module("org.bouncycastle", "bctls-fips", "2.1.20"),
        ),
      )

    assertThat(skews).isEmpty()
  }

  @Test
  fun `FIPS jars alongside a real jdk18on skew do not pollute the report`() {
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcutil-jdk18on", "1.84"),
          cached("org.bouncycastle", "bc-fips", "2.1.0"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcutil-jdk18on", "1.84"),
          module("org.bouncycastle", "bc-fips", "2.1.0"),
        ),
      )

    assertThat(skews.single().coordinates)
      .containsExactly("bcprov-jdk18on:1.85", "bcutil-jdk18on:1.84")
      .inOrder()
  }

  @Test
  fun `the legacy jdk15to18 line still counts as the same train`() {
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk15to18", "1.85"),
          cached("org.bouncycastle", "bcutil-jdk15to18", "1.84"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk15to18", "1.85"),
          module("org.bouncycastle", "bcutil-jdk15to18", "1.84"),
        ),
      )

    assertThat(skews.single().group).isEqualTo("org.bouncycastle")
  }

  @Test
  fun `hamcrest remediation pins down to 1_3 rather than aligning up`() {
    // Aligning Hamcrest UP is the one thing that must not be suggested: `hamcrest-core:2.2` does
    // exist (as a shim), so a virtual platform resolves fine — straight onto 2.x, where Espresso's
    // 2-arg `AllOf.allOf` is gone. That's why remediation is per-family.
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.hamcrest", "hamcrest", "2.2"),
          cached("org.hamcrest", "hamcrest-core", "1.3"),
        ),
        coordinates(
          module("org.hamcrest", "hamcrest", "2.2"),
          module("org.hamcrest", "hamcrest-core", "1.3"),
        ),
      )

    val report = RenderClasspathDuplicates.reportFamilySkew(skews, ":app:composePreviewRender")

    assertThat(report).contains("org.hamcrest")
    assertThat(report).contains("useTarget(\"org.hamcrest:hamcrest-core:1.3\")")
    assertThat(report).doesNotContain("belongsTo")
    assertThat(report).doesNotContain("virtual-platform")
  }

  @Test
  fun `a classpath with both faults reports both before failing`() {
    // Throwing on the family skew alone would hide the duplicate until the reader fixed it and
    // re-ran — a full render's wall-clock to learn something already known on the first pass.
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("probeBoth").get()
    val provNew = project.file(cached("org.bouncycastle", "bcprov-jdk18on", "1.85"))
    val provOld = project.file(cached("org.bouncycastle", "bcprov-jdk18on", "1.84"))
    val util = project.file(cached("org.bouncycastle", "bcutil-jdk18on", "1.84"))
    val coords =
      mapOf(
        provNew.absolutePath to "org.bouncycastle:bcprov-jdk18on:1.85",
        provOld.absolutePath to "org.bouncycastle:bcprov-jdk18on:1.84",
        util.absolutePath to "org.bouncycastle:bcutil-jdk18on:1.84",
      )

    val thrown =
      runCatching {
          RenderClasspathDuplicates.check(
            task,
            listOf(provNew, provOld, util),
            RenderClasspathDuplicates.MODE_FAIL,
            coords,
          )
        }
        .exceptionOrNull()

    assertThat(thrown).isInstanceOf(GradleException::class.java)
    // The family skew…
    assertThat(thrown!!.message).contains("one release train")
    // …and the duplicate module, in one message.
    assertThat(thrown.message).contains("at more than one version")
    assertThat(thrown.message).contains("org.bouncycastle:bcprov-jdk18on")
  }

  @Test
  fun `a group outside the known-families table is not guessed at`() {
    // `com.example` might well be one release train, but the plugin has no way to know that, and
    // inventing families would produce exactly the false reports this detector avoids elsewhere.
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("com.example", "thing-core", "1.0"),
          cached("com.example", "thing-util", "2.0"),
        ),
        coordinates(
          module("com.example", "thing-core", "1.0"),
          module("com.example", "thing-util", "2.0"),
        ),
      )

    assertThat(skews).isEmpty()
  }

  @Test
  fun `family report names the coordinates and hands back a pasteable alignment rule`() {
    val skews =
      RenderClasspathDuplicates.findFamilySkew(
        listOf(
          cached("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          cached("org.bouncycastle", "bcutil-jdk18on", "1.84"),
        ),
        coordinates(
          module("org.bouncycastle", "bcprov-jdk18on", "1.85"),
          module("org.bouncycastle", "bcutil-jdk18on", "1.84"),
        ),
      )

    val report = RenderClasspathDuplicates.reportFamilySkew(skews, ":app:composePreviewRender")

    assertThat(report).contains(":app:composePreviewRender")
    assertThat(report).contains("org.bouncycastle")
    assertThat(report).contains("bcprov-jdk18on:1.85")
    assertThat(report).contains("bcutil-jdk18on:1.84")
    assertThat(report).contains("BouncyCastleAlignmentRule : ComponentMetadataRule")
    assertThat(report).contains("belongsTo")
    // The caveat matters as much as the snippet: the rule is project-wide, so applying it can move
    // a version the consumer's app actually ships.
    assertThat(report).contains("EVERY configuration")
  }

  @Test
  fun `check fails on a family skew in fail mode and warns by default`() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("probeFamily").get()
    val prov = project.file(cached("org.bouncycastle", "bcprov-jdk18on", "1.85"))
    val util = project.file(cached("org.bouncycastle", "bcutil-jdk18on", "1.84"))
    val coords =
      mapOf(
        prov.absolutePath to "org.bouncycastle:bcprov-jdk18on:1.85",
        util.absolutePath to "org.bouncycastle:bcutil-jdk18on:1.84",
      )

    RenderClasspathDuplicates.check(
      task,
      listOf(prov, util),
      RenderClasspathDuplicates.MODE_WARN,
      coords,
    )
    RenderClasspathDuplicates.check(
      task,
      listOf(prov, util),
      RenderClasspathDuplicates.MODE_OFF,
      coords,
    )

    val thrown =
      runCatching {
          RenderClasspathDuplicates.check(
            task,
            listOf(prov, util),
            RenderClasspathDuplicates.MODE_FAIL,
            coords,
          )
        }
        .exceptionOrNull()

    assertThat(thrown).isInstanceOf(GradleException::class.java)
    assertThat(thrown!!.message).contains("one release train")
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
