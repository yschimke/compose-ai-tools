package ee.schimke.composeai.buildlogic

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/**
 * Fails when a POM this project would publish names a dependency with no version that neither its
 * own `<dependencyManagement>` nor a BOM it imports there supplies.
 *
 * Maven Central refuses such a POM ("Dependency version information is missing"), and a Central
 * deployment is all-or-nothing, so one module's versionless dependency stops every module in the
 * release from publishing. A versionless coordinate is easy to write — `api("group:artifact")`
 * substituted from an included build resolves locally — and nothing before the release's own
 * upload reads the POM, which is how 2.59.0 and 2.60.0 never reached Central (#5953, fixed in
 * #5981). CI runs this on every affected publishing project, so the pull request that introduces
 * one fails instead.
 */
abstract class CheckPomDependencyVersions : DefaultTask() {

  /** The POMs the project's publications generate (`generatePomFileFor*Publication`). */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val pomFiles: ConfigurableFileCollection

  /**
   * Gradle's user home, whose module cache holds the BOMs the POMs import once [resolvedClasspath]
   * is resolved. A BOM still missing from it can't be read, so that POM is skipped with a warning
   * — the check never fails on what it can't see.
   */
  @get:Internal abstract val gradleUserHome: DirectoryProperty

  /**
   * The project's runtime classpath. Not read: resolving it as an input is what puts the imported
   * BOMs in the module cache before this task runs, so a cold cache or a freshly bumped BOM is
   * checked rather than skipped.
   */
  @get:Classpath abstract val resolvedClasspath: ConfigurableFileCollection

  @TaskAction
  fun checkVersions() {
    val offenders = pomFiles.files.filter(File::isFile).sorted().flatMap(::versionlessDependencies)
    check(offenders.isEmpty()) {
      "Maven Central rejects a POM naming a dependency without a version, and with it every " +
        "other module in the release:\n" +
        offenders.joinToString("\n") { "  $it" } +
        "\nDeclare the version, or for a coordinate substituted from an included build map it " +
        "from the resolution result: publishing.publications.withType<MavenPublication>()" +
        ".configureEach { versionMapping { allVariants { fromResolutionResult() } } }."
    }
  }

  private fun versionlessDependencies(pom: File): List<String> {
    val root = parse(pom)
    val managed = mutableSetOf<String>()
    var unreadableImport = false
    collectManaged(root, managed, mutableSetOf()) { unreadableImport = true }
    if (unreadableImport) {
      logger.warn(
        "${pom.parentFile.name}/${pom.name} imports a BOM missing from the Gradle module cache; " +
          "its versionless dependencies are not checked."
      )
      return emptyList()
    }
    return root
      .child("dependencies")
      ?.children("dependency")
      .orEmpty()
      .filter { it.text("version").isNullOrBlank() && it.managementKey() !in managed }
      .map { "${pom.parentFile.name}/${pom.name}: ${it.managementKey()}" }
  }

  /** Every coordinate [project]'s `<dependencyManagement>` versions, following imported BOMs. */
  private fun collectManaged(
    project: Element,
    managed: MutableSet<String>,
    seen: MutableSet<String>,
    onUnreadable: () -> Unit,
  ) {
    val entries =
      project.child("dependencyManagement")?.child("dependencies")?.children("dependency")
    for (entry in entries.orEmpty()) {
      if (entry.text("scope") != "import") {
        managed += entry.managementKey()
        continue
      }
      if (!seen.add("${entry.coordinate()}:${entry.text("version")}")) continue
      val bom = cachedPom(entry) ?: return onUnreadable()
      collectManaged(parse(bom), managed, seen, onUnreadable)
    }
  }

  /** [bom]'s POM in the module cache, `modules-2/files-2.1/<group>/<name>/<version>/<sha1>/`. */
  private fun cachedPom(bom: Element): File? {
    val group = bom.text("groupId") ?: return null
    val name = bom.text("artifactId") ?: return null
    val version = bom.text("version") ?: return null
    val dir =
      gradleUserHome.get().asFile.resolve("caches/modules-2/files-2.1/$group/$name/$version")
    return dir.listFiles()?.firstNotNullOfOrNull { hashDir ->
      hashDir.resolve("$name-$version.pom").takeIf(File::isFile)
    }
  }

  private fun parse(file: File): Element =
    DocumentBuilderFactory.newInstance()
      .apply { isNamespaceAware = false }
      .newDocumentBuilder()
      .parse(file)
      .documentElement

  private fun Element.coordinate(): String = "${text("groupId")}:${text("artifactId")}"

  /**
   * How Maven matches a dependency to its management entry: group, artifact, type (default `jar`)
   * and classifier. A versionless `test-jar` of a managed jar is still unversioned.
   */
  private fun Element.managementKey(): String {
    val type = text("type")?.takeIf { it.isNotEmpty() } ?: "jar"
    val classifier = text("classifier")?.takeIf { it.isNotEmpty() }
    return listOfNotNull(coordinate(), type, classifier).joinToString(":")
  }

  private fun Element.text(name: String): String? = child(name)?.textContent?.trim()

  private fun Element.child(name: String): Element? = children(name).firstOrNull()

  private fun Element.children(name: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter {
      it.tagName == name
    }
  }
}
