package ee.schimke.composeai.buildlogic

/**
 * Which version each coordinate carries on a release where only some modules publish.
 *
 * A pure function over the three inputs a release has — the tag's version, the set of modules that
 * are publishing, and the committed record of what everything last published at — so the two
 * callers cannot drift. `ComposeAiMavenPublishingPlugin` uses it to set `project.version`, which is
 * what a POM names its project dependencies at; `:bom` uses it to pin every constraint. If those
 * two ever disagreed the BOM would promise a set that the POMs contradict.
 */
object PublishedVersions {
  /**
   * The version [artifactId] carries.
   *
   * [publishSet] is `null` when the release publishes everything — a `workflow_dispatch` recovery
   * run, or any release before the plan script has run — in which case everything takes
   * [tagVersion].
   *
   * A module outside the publish set takes the version it last published at. Not finding one is an
   * error rather than a fallback to [tagVersion]: silently stamping the tag onto a module that is
   * not being uploaded is what publishes a POM naming a coordinate that does not exist.
   */
  fun resolve(
    artifactId: String,
    tagVersion: String,
    publishSet: Collection<String>?,
    manifestText: String,
  ): String {
    if (publishSet == null || artifactId in publishSet) return tagVersion
    return recordedVersion(artifactId, manifestText)
      ?: error(
        "'$artifactId' is not in the publish set and has no entry in publishing-manifest.json, " +
          "so there is no version it can safely carry. That file is not committed: the release " +
          "plan writes it from Maven Central (maven-publish-plan.sh --write-manifest). An empty " +
          "name here means a project path that resolves to no artifact id."
      )
  }

  /**
   * The version the manifest records for [artifactId], or null.
   *
   * A regex rather than a JSON parser: build-logic carries no JSON dependency, and the file is
   * written by this repository's own release job to a shape `PublishedVersionsTest` pins.
   */
  fun recordedVersion(artifactId: String, manifestText: String): String? =
    Regex("\"${Regex.escape(artifactId)}\"\\s*:\\s*\"([^\"]+)\"")
      .find(manifestText)
      ?.groupValues
      ?.get(1)

  /**
   * Parses the `-Pcomposeai.publishSet` property.
   *
   * The distinction between absent and empty is load-bearing, and the two must not be collapsed:
   *
   *  * **absent** (`null`) -- the release did not compute a plan, so publish everything. This is the
   *    old behaviour and the `workflow_dispatch` recovery path.
   *  * **present but empty** (`""`) -- the plan ran and found nothing to publish, which happens for
   *    a releasable change confined to `.github/` or the docs. Publish nothing (bar the BOM).
   *
   * Treating an empty property as "publish everything" would upload all 26 coordinates on exactly
   * the releases that need none of them, while `record-published.py` recorded none of them --
   * defeating the saving and leaving the manifest disagreeing with Central.
   */
  fun parsePublishSet(property: String?): Set<String>? =
    property?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
}
