package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ComponentSelectionReason
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.junit.Test

/**
 * Pins the resolved-graph walk used by both the render-path gate
 * ([ValidatePreviewToolingPresentTask.containsPreviewTooling]) and the doctor task's at-action
 * transitive-detection signal.
 *
 * Hand-rolled stubs for `ResolvedComponentResult` because Gradle's TestKit fixtures don't make
 * isolated graph construction easy. We're only exercising the BFS + coord matching here — the
 * fixture intentionally implements no more than the walk consumes.
 */
class ValidatePreviewToolingPresentTaskTest {

  @Test
  fun `direct preview tooling coord on the root is detected`() {
    val root =
      moduleNode("androidx.compose.ui", "ui-tooling-preview", "1.9.5", dependencies = emptyList())
    assertThat(ValidatePreviewToolingPresentTask.containsPreviewTooling(root)).isTrue()
  }

  @Test
  fun `preview tooling on a transitive dep is detected`() {
    val tooling =
      moduleNode("androidx.compose.ui", "ui-tooling-preview", "1.9.5", dependencies = emptyList())
    val shared =
      moduleNode("com.example", "shared", "1.0", dependencies = listOf(resolvedDep(tooling)))
    val app = moduleNode("com.example", "app", "1.0", dependencies = listOf(resolvedDep(shared)))

    assertThat(ValidatePreviewToolingPresentTask.containsPreviewTooling(app)).isTrue()
  }

  @Test
  fun `cycles in the resolved graph don't stack-overflow the walk`() {
    val a = moduleNode("com.example", "a", "1.0", dependencies = emptyList())
    val b = moduleNode("com.example", "b", "1.0", dependencies = emptyList())
    // Wire a <-> b after construction so we can close the cycle.
    setDependencies(a, listOf(resolvedDep(b)))
    setDependencies(b, listOf(resolvedDep(a)))

    assertThat(ValidatePreviewToolingPresentTask.containsPreviewTooling(a)).isFalse()
  }

  @Test
  fun `no preview tooling on the graph returns false`() {
    val unrelated = moduleNode("com.example", "unrelated", "1.0", dependencies = emptyList())
    val app = moduleNode("com.example", "app", "1.0", dependencies = listOf(resolvedDep(unrelated)))

    assertThat(ValidatePreviewToolingPresentTask.containsPreviewTooling(app)).isFalse()
  }

  @Test
  fun `JetBrains Compose preview tooling coord is detected`() {
    val tooling =
      moduleNode(
        "org.jetbrains.compose.components",
        "components-ui-tooling-preview",
        "1.7.5",
        dependencies = emptyList(),
      )
    val app = moduleNode("com.example", "app", "1.0", dependencies = listOf(resolvedDep(tooling)))

    assertThat(ValidatePreviewToolingPresentTask.containsPreviewTooling(app)).isTrue()
  }

  // --- Fixture helpers --------------------------------------------------

  private fun moduleNode(
    group: String,
    module: String,
    version: String,
    dependencies: List<DependencyResult>,
  ): MutableModuleComponentNode =
    MutableModuleComponentNode(
      id = StubModuleId(group, module, version),
      dependenciesList = dependencies.toMutableList(),
    )

  private fun setDependencies(node: MutableModuleComponentNode, deps: List<DependencyResult>) {
    node.dependenciesList.clear()
    node.dependenciesList.addAll(deps)
  }

  private fun resolvedDep(selected: ResolvedComponentResult): ResolvedDependencyResult =
    StubResolvedDependency(selected)

  private data class StubModuleId(
    private val group: String,
    private val module: String,
    private val version: String,
  ) : ModuleComponentIdentifier {
    override fun getDisplayName(): String = "$group:$module:$version"

    override fun getGroup(): String = group

    override fun getModule(): String = module

    override fun getVersion(): String = version

    override fun getModuleIdentifier(): org.gradle.api.artifacts.ModuleIdentifier =
      object : org.gradle.api.artifacts.ModuleIdentifier {
        override fun getGroup(): String = group

        override fun getName(): String = module
      }
  }

  /**
   * Stub `ResolvedComponentResult` carrying just the bits the walk consumes (`id` +
   * `dependencies`). Mutable dependencies list so the cycle test can wire the back-edge after
   * construction.
   */
  private class MutableModuleComponentNode(
    private val id: ComponentIdentifier,
    val dependenciesList: MutableList<DependencyResult>,
  ) : ResolvedComponentResult {
    override fun getId(): ComponentIdentifier = id

    override fun getDependencies(): MutableSet<DependencyResult> = dependenciesList.toMutableSet()

    override fun getDependents(): MutableSet<ResolvedDependencyResult> = mutableSetOf()

    override fun getModuleVersion() = null

    override fun getSelectionReason(): ComponentSelectionReason =
      throw UnsupportedOperationException("not needed by the walk")

    override fun getVariants(): MutableList<ResolvedVariantResult> = mutableListOf()

    override fun getDependenciesForVariant(
      variant: ResolvedVariantResult
    ): MutableList<DependencyResult> = dependenciesList
  }

  private class StubResolvedDependency(private val selected: ResolvedComponentResult) :
    ResolvedDependencyResult {
    override fun getRequested() = throw UnsupportedOperationException("not needed")

    override fun isConstraint(): Boolean = false

    override fun getFrom(): ResolvedComponentResult =
      throw UnsupportedOperationException("not needed")

    override fun getSelected(): ResolvedComponentResult = selected

    override fun getResolvedVariant(): ResolvedVariantResult =
      throw UnsupportedOperationException("not needed")
  }
}
