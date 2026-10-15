package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.bundle.BundleReader
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DependencyPolicyTest {
  private val root: File = Files.createTempDirectory("dependency-policy").toFile()

  @AfterTest fun cleanUp() = root.deleteRecursively().let {}

  private fun jar(name: String, vararg entries: Pair<String, String>): File =
    root.resolve(name).apply {
      parentFile.mkdirs()
      ZipOutputStream(outputStream()).use { zip ->
        entries.forEach { (entry, text) ->
          zip.putNextEntry(
            ZipEntry(entry).apply { setTimeLocal(LocalDateTime.of(2020, 1, 1, 0, 0)) }
          )
          zip.write(text.toByteArray())
          zip.closeEntry()
        }
      }
    }

  private fun policy(json: String): DependencyPolicy =
    DependencyPolicy.parse(Json.parseToJsonElement(json).jsonObject)

  private fun maven(gav: String, sha: String? = null): BundleReader.ClasspathEntry.Maven =
    gav.split(':').let { BundleReader.ClasspathEntry.Maven(it[0], it[1], it[2], "jar", sha) }

  private fun releases(vararg versions: Pair<String, List<String>>): ReleasedVersions {
    val map = versions.toMap()
    return ReleasedVersions { group, artifact -> map["$group:$artifact"] }
  }

  // --- configuration ---------------------------------------------------------------------------

  @Test
  fun `parse defaults to today's chain and opts into the new signals`() {
    val parsed = policy("{}")
    assertEquals(DependencyPolicy.DEFAULT_CHAIN, parsed.default)
    assertTrue(parsed.explicit && parsed.versionStamps && parsed.ownerLabels)
    assertFalse(DependencyPolicy.DEFAULT.explicit)
    assertFalse(DependencyPolicy.DEFAULT.versionStamps || DependencyPolicy.DEFAULT.ownerLabels)
    val full =
      policy(
        """{"default":["coordinate-verified","embed"],"allowTransformed":true,"versionStamps":false,
           "rules":[{"match":"androidx.*","policy":["build-target","closest-released","embed"]},
                    {"match":"com.example:lib","policy":"embed","allowTransformed":true}]}"""
      )
    assertEquals(
      listOf(DependencyPolicyStep.COORDINATE_VERIFIED, DependencyPolicyStep.EMBED),
      full.default,
    )
    assertTrue(full.allowTransformed)
    assertFalse(full.versionStamps)
    assertEquals(listOf(DependencyPolicyStep.EMBED), full.rules[1].policy)
    assertTrue(full.rules[1].allowTransformed)
  }

  @Test
  fun `parse rejects unknown keys, policies and empty chains`() {
    assertFailsWith<IllegalArgumentException> { policy("""{"defaults":["embed"]}""") }
    assertFailsWith<IllegalArgumentException> { policy("""{"default":["latest"]}""") }
    assertFailsWith<IllegalArgumentException> { policy("""{"default":[]}""") }
    assertFailsWith<IllegalArgumentException> { policy("""{"rules":[{"policy":["embed"]}]}""") }
    assertFailsWith<IllegalArgumentException> {
      policy("""{"rules":[{"match":"a.*","policy":["embed"],"when":"x"}]}""")
    }
  }

  @Test
  fun `rules glob the group, or group and artifact, and the first match wins`() {
    val parsed =
      policy(
        """{"rules":[{"match":"androidx.compose.ui:ui-*","policy":["embed"]},
                     {"match":"androidx.*","policy":["closest-released"]},
                     {"match":"com.?xample","policy":["coordinate-verified"]}]}"""
      )
    assertEquals(
      "androidx.compose.ui:ui-*",
      parsed.ruleFor("androidx.compose.ui", "ui-text")?.match,
    )
    assertEquals("androidx.*", parsed.ruleFor("androidx.compose.ui", "ui")?.match)
    assertEquals("androidx.*", parsed.ruleFor("androidx.core", "core")?.match)
    assertNull(parsed.ruleFor("androidx", "core"))
    assertEquals("com.?xample", parsed.ruleFor("com.example", "lib")?.match)
    assertNull(parsed.ruleFor("com.examples", "lib"))
    assertNull(parsed.ruleFor("org.jetbrains", "annotations"))
  }

  // --- policies ---------------------------------------------------------------------------------

  private val built = "b".repeat(64)

  private fun apply(
    coordinate: BundleReader.ClasspathEntry.Maven?,
    policy: DependencyPolicy,
    pins: List<BundleReader.ClasspathEntry.Maven> = emptyList(),
    releases: ReleasedVersions = ReleasedVersions.NONE,
    embedAll: Boolean = false,
  ): PolicyOutcome =
    applyDependencyPolicy(
      coordinate?.let { BazelMavenMatch(it, "test") },
      built,
      pins,
      policy,
      releases,
      embedAll,
    )

  @Test
  fun `coordinate keeps a mismatched coordinate and embed is the terminal fallback`() {
    val pin = maven("org.example:lib:1.0", "a".repeat(64))
    val kept = apply(pin, DependencyPolicy.DEFAULT, listOf(pin))
    assertEquals(pin, kept.coordinate)
    assertEquals("coordinate", kept.policy)
    val unidentified = apply(null, DependencyPolicy.DEFAULT)
    assertNull(unidentified.coordinate)
    assertEquals("embed", unidentified.policy)
    assertEquals(listOf(PolicyFallback("coordinate", "unidentified")), unidentified.fallbacks)
    assertNull(apply(pin, DependencyPolicy.DEFAULT, listOf(pin), embedAll = true).coordinate)
  }

  @Test
  fun `coordinate-verified needs a published version and a matching checksum`() {
    val strict = policy("""{"default":["coordinate-verified","embed"]}""")
    val matching = maven("org.example:lib:1.0", built)
    assertEquals(matching, apply(matching, strict, listOf(matching)).coordinate)

    // Bazel's processed_ jar: published, but the bytes differ.
    val processed = maven("org.example:lib:1.0", "a".repeat(64))
    val rejected = apply(processed, strict, listOf(processed))
    assertNull(rejected.coordinate)
    assertEquals(
      listOf(PolicyFallback("coordinate-verified", "checksum-mismatch")),
      rejected.fallbacks,
    )

    // ...unless the transform is declared harmless, globally or per rule.
    val lenient = policy("""{"default":["coordinate-verified","embed"],"allowTransformed":true}""")
    assertEquals("transformed-allowed", apply(processed, lenient, listOf(processed)).reason)
    val perRule =
      policy(
        """{"default":["coordinate-verified","embed"],
            "rules":[{"match":"org.example","policy":["coordinate-verified"],"allowTransformed":true}]}"""
      )
    assertEquals(processed, apply(processed, perRule, listOf(processed)).coordinate)

    // Unpinned: published-ness comes from the release lookup; unknown is a fallback, not a guess.
    val unpinned = maven("org.example:lib:2.0")
    assertEquals(
      listOf(PolicyFallback("coordinate-verified", "publication-unknown")),
      apply(unpinned, strict).fallbacks,
    )
    assertEquals(
      "unpublished",
      apply(unpinned, strict, releases = releases("org.example:lib" to listOf("1.0")))
        .fallbacks
        .single()
        .reason,
    )
    assertEquals(
      "checksum-unknown",
      apply(unpinned, strict, releases = releases("org.example:lib" to listOf("2.0")))
        .fallbacks
        .single()
        .reason,
    )
    assertEquals(
      unpinned,
      apply(unpinned, lenient, releases = releases("org.example:lib" to listOf("2.0"))).coordinate,
    )
  }

  @Test
  fun `closest-released substitutes the nearest release at or below an unpublished stamp`() {
    val stamped = maven("androidx.compose.ui:ui-android:1.14.0-alpha01")
    val chain =
      policy("""{"rules":[{"match":"androidx.*","policy":["closest-released","embed"]}]}""")
    val versions =
      releases(
        "androidx.compose.ui:ui-android" to
          listOf("1.12.0", "1.13.0-alpha01", "1.13.0-beta01", "1.14.0-SNAPSHOT", "1.14.0")
      )
    val outcome = apply(stamped, chain, releases = versions)
    assertEquals("1.13.0-beta01", outcome.coordinate?.version)
    assertEquals(stamped, outcome.substitutedFrom)
    assertEquals("unpublished-version", outcome.reason)
    assertEquals("androidx.*", outcome.rule)

    // A published stamp is used as-is.
    val published =
      apply(maven("androidx.compose.ui:ui-android:1.13.0-beta01"), chain, releases = versions)
    assertNull(published.substitutedFrom)
    assertEquals("1.13.0-beta01", published.coordinate?.version)

    // A pinned snapshot is mutable: even an exact match is replaced by the nearest release.
    val snapshot =
      apply(maven("androidx.compose.ui:ui-android:1.14.0-SNAPSHOT"), chain, releases = versions)
    assertEquals("1.13.0-beta01", snapshot.coordinate?.version)
    assertEquals("1.14.0-SNAPSHOT", snapshot.substitutedFrom?.version)

    // Nothing earlier, or nothing known: the next step.
    assertEquals(
      listOf(PolicyFallback("closest-released", "no-earlier-release")),
      apply(maven("androidx.compose.ui:ui-android:1.0.0"), chain, releases = versions).fallbacks,
    )
    assertEquals(
      listOf(PolicyFallback("closest-released", "publication-unknown")),
      apply(stamped, chain).fallbacks,
    )
  }

  @Test
  fun `offline closest-released uses only lockfile-pinned versions`() {
    val chain = policy("""{"default":["closest-released","embed"]}""")
    val pin = maven("androidx.core:core:1.12.0", "c".repeat(64))
    val outcome = apply(maven("androidx.core:core:1.13.0-alpha01"), chain, listOf(pin))
    assertEquals(pin, outcome.coordinate)
    assertEquals("1.13.0-alpha01", outcome.substitutedFrom?.version)
  }

  @Test
  fun `fallbacks are tried in order and recorded, build-target always falls through`() {
    val chain =
      policy(
        """{"default":["build-target","coordinate-verified","closest-released","coordinate","embed"]}"""
      )
    val coordinate = maven("org.example:lib:2.0")
    val outcome = apply(coordinate, chain)
    assertEquals("coordinate", outcome.policy)
    assertEquals(
      listOf(
        PolicyFallback("build-target", "build-target-unsupported"),
        PolicyFallback("coordinate-verified", "publication-unknown"),
        PolicyFallback("closest-released", "publication-unknown"),
      ),
      outcome.fallbacks,
    )
    val exhausted = apply(coordinate, policy("""{"default":["build-target"]}"""))
    assertNull(exhausted.coordinate)
    assertEquals("embed", exhausted.policy)
    assertEquals(PolicyFallback("chain", "exhausted"), exhausted.fallbacks.last())
  }

  @Test
  fun `maven versions order pre-releases below their release`() {
    val sorted =
      listOf("1.14.0-alpha01", "1.13.0", "1.13.0-rc01", "1.13.0-beta02", "1.13.0-beta01", "1.9.0")
        .sortedWith(::compareMavenVersions)
    assertEquals(
      listOf("1.9.0", "1.13.0-beta01", "1.13.0-beta02", "1.13.0-rc01", "1.13.0", "1.14.0-alpha01"),
      sorted,
    )
    assertTrue(compareMavenVersions("2.0.0-Beta1", "2.0.0-RC1") < 0)
    assertTrue(isSnapshotVersion("1.14.0-SNAPSHOT"))
  }

  // --- identification ---------------------------------------------------------------------------

  private val stampEntry = "META-INF/androidx.compose.ui_ui.version" to "1.14.0-alpha01\n"

  @Test
  fun `version stamps identify androidx jars only when asked`() {
    val variant = jar("out/ui-android.jar", stampEntry)
    assertNull(matchBazelMaven(variant, emptyList()))
    val match = matchBazelMaven(variant, emptyList(), versionStamps = true, ownerLabel = null)!!
    assertEquals("version-stamp", match.method)
    assertEquals(maven("androidx.compose.ui:ui-android:1.14.0-alpha01"), match.coordinate)

    // Versioned and lib-prefixed names map to the variant too.
    assertEquals(
      "ui-android",
      matchBazelMaven(
          jar("x/processed_ui-android-1.14.0-alpha01.jar", stampEntry),
          emptyList(),
          true,
          null,
        )
        ?.coordinate
        ?.artifact,
    )
    assertEquals(
      "ui-android",
      matchBazelMaven(jar("x/libui-android.jar", stampEntry), emptyList(), true, null)
        ?.coordinate
        ?.artifact,
    )
    // A name that says nothing: a lockfile sibling decides, else the stamped root artifact.
    val opaque = jar("x/classes.jar", stampEntry)
    assertEquals(
      "ui-android",
      matchBazelMaven(opaque, listOf(maven("androidx.compose.ui:ui-android:1.13.0")), true, null)
        ?.coordinate
        ?.artifact,
    )
    assertEquals("ui", matchBazelMaven(opaque, emptyList(), true, null)?.coordinate?.artifact)
    // An exact pin lends its checksum.
    val pinned = maven("androidx.compose.ui:ui-android:1.14.0-alpha01", "d".repeat(64))
    assertEquals(
      "d".repeat(64),
      matchBazelMaven(variant, listOf(pinned), true, null)?.coordinate?.sha256,
    )
  }

  @Test
  fun `non-androidx version files and merged stamps do not identify a jar`() {
    val coroutines = jar("x/coroutines.jar", "META-INF/kotlinx_coroutines_core.version" to "1.9.0")
    assertNull(matchBazelMaven(coroutines, emptyList(), true, null))
    val merged = jar("x/merged.jar", stampEntry, "META-INF/androidx.core_core.version" to "1.15.0")
    assertNull(matchBazelMaven(merged, emptyList(), true, null))
    val nested = jar("x/nested.jar", "META-INF/sub/androidx.core_core.version" to "1.15.0")
    assertNull(matchBazelMaven(nested, emptyList(), true, null))
  }

  @Test
  fun `owner labels identify pinned external artifacts and ignore workspace targets`() {
    val opaque = jar("x/opaque.jar", "A.class" to "a")
    val pin = maven("androidx.compose.ui:ui-android:1.7.0", "e".repeat(64))
    val pins = listOf(pin, maven("androidx.compose.ui:ui:1.7.0"))
    val match = matchBazelMaven(opaque, pins, false, "@maven//:androidx_compose_ui_ui_android")!!
    assertEquals("owner-label", match.method)
    assertEquals(pin, match.coordinate)
    assertEquals(
      pin,
      matchBazelMaven(
          opaque,
          pins,
          false,
          "@@rules_jvm_external~~maven~maven//:androidx_compose_ui_ui_android",
        )
        ?.coordinate,
    )
    assertNull(matchBazelMaven(opaque, pins, false, "//app:androidx_compose_ui_ui_android"))
    assertNull(matchBazelMaven(opaque, pins, false, "@maven//:unknown"))
  }

  // --- the bundle -------------------------------------------------------------------------------

  private fun decisions(fixture: PolicyFixture, bundle: File): List<JsonObject> =
    Json.parseToJsonElement(fixture.decisions(bundle)).jsonArray.map { it.jsonObject }

  @Test
  fun `a configured policy records rule, policy, fallbacks and substitution`() {
    val fixture = PolicyFixture(root.resolve("fixture"))
    val bundle = root.resolve("approximate.png")
    val manifest =
      fixture.write(
        bundle,
        DependencyResolutionOptions(
          policy =
            policy(
              """{"default":["coordinate-verified","embed"],
                  "rules":[{"match":"androidx.*","policy":["build-target","closest-released","embed"]}]}"""
            ),
          releasedVersions =
            releases("androidx.compose.ui:ui-android" to listOf("1.13.0-alpha01", "1.13.0-beta01")),
        ),
      )
    assertEquals("approximate", manifest.resolution)
    val maven = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    // Only the stdlib is verified; the processed_ jar and the unpinned pom jar are embedded.
    assertEquals(
      listOf(
        "org.jetbrains.kotlin:kotlin-stdlib:2.0.0",
        "androidx.compose.ui:ui-android:1.13.0-beta01",
      ),
      maven.map { it.gav() },
    )
    val records = decisions(fixture, bundle).associateBy { it["jar"]!!.jsonPrimitive.content }
    val stamp = records.getValue("ui-android.jar")
    assertEquals("version-stamp", stamp["method"]!!.jsonPrimitive.content)
    assertEquals("androidx.*", stamp["rule"]!!.jsonPrimitive.content)
    assertEquals("closest-released", stamp["policy"]!!.jsonPrimitive.content)
    assertEquals(
      "androidx.compose.ui:ui-android:1.13.0-beta01",
      stamp["coordinate"]!!.jsonPrimitive.content,
    )
    assertEquals(
      "androidx.compose.ui:ui-android:1.14.0-alpha01",
      stamp["substitutedFrom"]!!.jsonPrimitive.content,
    )
    assertEquals("unpublished-version", stamp["reason"]!!.jsonPrimitive.content)
    assertEquals(
      "build-target",
      (stamp["fallbacks"] as JsonArray).single().jsonObject["policy"]!!.jsonPrimitive.content,
    )
    val processed = records.getValue("processed_ui.jar")
    assertEquals("embed", processed["policy"]!!.jsonPrimitive.content)
    assertEquals(
      "checksum-mismatch",
      (processed["fallbacks"] as JsonArray).single().jsonObject["reason"]!!.jsonPrimitive.content,
    )
    assertEquals("default", processed["rule"]!!.jsonPrimitive.content)
  }

  @Test
  fun `under no network an unpinned stamp falls back to embed and the bundle is not approximate`() {
    val fixture = PolicyFixture(root.resolve("fixture"))
    val manifest =
      fixture.write(
        root.resolve("offline.png"),
        DependencyResolutionOptions(
          policy =
            policy("""{"rules":[{"match":"androidx.*","policy":["closest-released","embed"]}]}"""),
          releasedVersions = ReleasedVersions.NONE,
        ),
      )
    assertEquals("mixed", manifest.resolution)
    assertTrue(
      manifest.classpath.none {
        it is BundleReader.ClasspathEntry.Maven && it.group.startsWith("androidx")
      }
    )
  }

  @Test
  fun `an explicit default chain changes only the decision record, and embed-deps still embeds all`() {
    val fixture = PolicyFixture(root.resolve("fixture"))
    val base = root.resolve("base.png").also { fixture.write(it) }
    val explicit =
      fixture.write(
        root.resolve("explicit.png"),
        DependencyResolutionOptions(
          policy = policy("""{"versionStamps":false,"ownerLabels":false}""")
        ),
      )
    assertEquals(BundleReader.readMetadata(base).manifest, explicit)
    assertTrue(decisions(fixture, root.resolve("explicit.png")).all { "policy" in it })
    val embedded =
      fixture.write(
        root.resolve("embedded.png"),
        DependencyResolutionOptions(policy = policy("""{"default":["coordinate"]}""")),
        embedDeps = true,
      )
    assertEquals("embedded", embedded.resolution)
    assertTrue(
      decisions(fixture, root.resolve("embedded.png")).all {
        it["rule"]!!.jsonPrimitive.content == "embed-deps"
      }
    )
  }

  @Test
  fun `owner labels never reach the bundle`() {
    val fixture = PolicyFixture(root.resolve("fixture"))
    val bundle = root.resolve("owners.png")
    fixture.write(
      bundle,
      DependencyResolutionOptions(
        policy = policy("{}"),
        runtimeJarOwners =
          mapOf(
            fixture.local to "//secret/team/app:runtime",
            fixture.processed to "@maven//:org_jetbrains_compose_ui_ui",
          ),
      ),
    )
    val bytes = String(BundleReader.extractZipBytes(bundle), Charsets.ISO_8859_1)
    assertFalse("secret/team" in bytes)
    assertFalse("@maven" in bytes)
    val records = decisions(fixture, bundle).associateBy { it["jar"]!!.jsonPrimitive.content }
    assertEquals(
      "owner-label",
      records.getValue("processed_ui.jar")["method"]!!.jsonPrimitive.content,
    )
  }

  // --- release lookup ---------------------------------------------------------------------------

  private val metadata =
    """<metadata><groupId>androidx.core</groupId><artifactId>core</artifactId><versioning>
       <latest>1.15.0</latest><versions><version>1.13.0</version><version>1.15.0-beta01</version>
       <version>1.15.0</version></versions></versioning></metadata>"""

  @Test
  fun `maven metadata is fetched from the right repository, cached and read offline`() {
    val cache = root.resolve("cache")
    val fetched = mutableListOf<String>()
    var clock = 1_000_000L
    val online =
      MavenMetadataVersions(
        cache,
        network = true,
        fetch = {
          fetched += it
          metadata
        },
        now = { clock },
      )
    assertEquals(
      listOf("1.13.0", "1.15.0-beta01", "1.15.0"),
      online.versions("androidx.core", "core"),
    )
    assertEquals(
      listOf("https://dl.google.com/android/maven2/androidx/core/core/maven-metadata.xml"),
      fetched,
    )
    // Fresh cache: no second fetch.
    online.versions("androidx.core", "core")
    assertEquals(1, fetched.size)
    // Offline reads the cache whatever its age; uncached is unknown.
    val offline = MavenMetadataVersions(cache, network = false, fetch = { error("no network") })
    assertEquals(3, offline.versions("androidx.core", "core")?.size)
    assertNull(offline.versions("org.example", "lib"))
    // A failed refresh keeps the stale copy.
    clock += 48L * 60 * 60 * 1000
    val failing =
      MavenMetadataVersions(
        cache,
        network = true,
        fetch = { null },
        now = { clock + System.currentTimeMillis() },
      )
    assertEquals(3, failing.versions("androidx.core", "core")?.size)
    assertEquals(
      MavenMetadataVersions.MAVEN_CENTRAL,
      MavenMetadataVersions.repositoryFor("org.jetbrains.kotlin"),
    )
    // The bare groups too: com.google.android:material lives on Google Maven.
    for (group in listOf("com.google.android", "com.google.android.material", "com.android")) {
      assertEquals(
        MavenMetadataVersions.GOOGLE_MAVEN,
        MavenMetadataVersions.repositoryFor(group),
        group,
      )
    }
    assertEquals(
      MavenMetadataVersions.MAVEN_CENTRAL,
      MavenMetadataVersions.repositoryFor("com.google.androidx"),
    )
    assertNull(offline.versions("../etc", "passwd"))
  }

  @Test
  fun `runtime jar owners are read from the raw artifacts document`() {
    val artifacts =
      Json.parseToJsonElement(
          """{"schemaVersion":1,"runtimeJarOwners":{"external/maven/ui.jar":"@maven//:ui","/abs/x.jar":"//app:x"}}"""
        )
        .jsonObject
    val owners = runtimeJarOwners(artifacts, root)
    assertEquals("@maven//:ui", owners[root.resolve("external/maven/ui.jar").absoluteFile])
    assertEquals("//app:x", owners[File("/abs/x.jar")])
    assertTrue(runtimeJarOwners(Json.parseToJsonElement("{}").jsonObject, root).isEmpty())
  }

  @Test
  fun `a policy file may be the block or a bazel json holding it`() {
    val block = root.resolve("policy.json").apply { writeText("""{"default":["embed"]}""") }
    assertEquals(listOf(DependencyPolicyStep.EMBED), readDependencyPolicy(block).default)
    val config =
      root.resolve("bazel.json").apply {
        writeText("""{"target":"//app","dependencyPolicy":{"default":"coordinate-verified"}}""")
      }
    assertEquals(
      listOf(DependencyPolicyStep.COORDINATE_VERIFIED),
      readDependencyPolicy(config).default,
    )
  }
}
