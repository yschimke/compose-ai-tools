package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.BuilderPolicy
import org.junit.Test

/**
 * Pins [remapPolicyPreviewIds] — the half of the bundle's carried record that names PREVIEWS.
 *
 * The policy is merged in from the full record so that selecting one preview does not revert a
 * component to defaults, but `declaredBy` and `conflicting` are preview ids and everything else in
 * the bundle has been through [assignBundleEntryIds]. Carried unmapped, they put ids in
 * `components.json` that the bundle's own `previews.json` does not have — the exact invariant the
 * merge's own comment states, broken by the merge that comment describes.
 */
class RemapPolicyPreviewIdsTest {

  private val bundleIds =
    assignBundleEntryIds(listOf("pkg.Card_A B", "pkg.Card_A_B", "pkg.Card_Plain"))

  @Test
  fun `a sanitised preview id is carried in the bundle's namespace`() {
    val remapped =
      remapPolicyPreviewIds(BuilderPolicy(declaredBy = listOf("pkg.Card_A B")), bundleIds)!!

    assertThat(remapped.declaredBy).containsExactly(bundleIds.getValue("pkg.Card_A B"))
    assertThat(remapped.declaredBy.single()).doesNotContain(" ")
  }

  @Test
  fun `a collision suffix is carried, not reconstructed`() {
    // Two raw ids sanitise to the same form, so one gains a `_1` that nothing outside the bundle
    // could derive. This is why the mapping is applied rather than the raw id being sanitised again
    // at the point of use.
    val second = bundleIds.getValue("pkg.Card_A_B")
    val first = bundleIds.getValue("pkg.Card_A B")
    assertThat(second).isNotEqualTo(first)

    val remapped =
      remapPolicyPreviewIds(BuilderPolicy(conflicting = listOf("pkg.Card_A_B")), bundleIds)!!
    assertThat(remapped.conflicting).containsExactly(second)
  }

  @Test
  fun `a preview the bundle did not select is dropped, not named raw`() {
    // Naming a preview that is not in this bundle is what made the unmapped form wrong, so an
    // unselected one is left out rather than carried through in either form.
    val remapped =
      remapPolicyPreviewIds(
        BuilderPolicy(
          declaredBy = listOf("pkg.Card_Plain", "pkg.Card_NotBundled"),
          conflicting = listOf("pkg.Card_NotBundled"),
        ),
        bundleIds,
      )!!

    assertThat(remapped.declaredBy).containsExactly(bundleIds.getValue("pkg.Card_Plain"))
    assertThat(remapped.conflicting).isEmpty()
  }

  @Test
  fun `the rest of the policy is untouched and a null policy stays null`() {
    // `ambiguousWith` is component ids and `traits` / `malformed` are not ids at all, so of the
    // policy's five string lists only the two preview-id ones are mapped.
    val policy =
      BuilderPolicy(
        id = "wear-m3/card",
        canvas = "p",
        traits = listOf("scrollable"),
        ambiguousWith = listOf("pkg.OtherComponent"),
        malformed = listOf("canvas"),
        declaredBy = listOf("pkg.Card_Plain"),
      )

    val remapped = remapPolicyPreviewIds(policy, bundleIds)!!
    assertThat(remapped)
      .isEqualTo(policy.copy(declaredBy = listOf(bundleIds.getValue("pkg.Card_Plain"))))

    assertThat(remapPolicyPreviewIds(null, bundleIds)).isNull()
  }
}
