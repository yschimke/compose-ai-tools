package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Grammar of `@PermissionPreview(grants = [...])` (issue #3676).
 *
 * The parser is exercised directly rather than through a Gradle build because the failure this
 * annotation exists to prevent is silent: a preview *named* "granted" that captures the denied
 * branch. Every way an entry can fail to parse is therefore a case here, and each one must both
 * drop the entry and say so — a dropped entry with no warning is indistinguishable from the bug.
 *
 * FQN discovery and the end-to-end stamp onto `previews.json` are covered by
 * `DiscoveryFunctionalTest.composePreviewDiscover stamps @PermissionPreview grants onto every
 * capture`.
 */
class PreviewDiscoveryPermissionGrantsTest {

  private val owner = "com.example.CameraKt.CameraPermissionGrantedPreview"

  private fun parse(
    vararg entries: String
  ): Pair<Map<String, PermissionGrantCaptureState>, List<String>> {
    val warnings = mutableListOf<String>()
    val grants = PreviewDiscovery.parsePermissionGrants(entries.toList(), owner, warnings)
    return grants to warnings
  }

  @Test
  fun `parses a permission and grant state into the capture model`() {
    val (grants, warnings) = parse("android.permission.CAMERA=granted")

    assertThat(grants)
      .containsExactly("android.permission.CAMERA", PermissionGrantCaptureState.GRANTED)
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `grant state is case-insensitive and both sides are trimmed`() {
    // The annotation is written by hand, so `GRANTED` and a stray space are ordinary author input,
    // not malformed entries — rejecting them would be pure friction.
    val (grants, warnings) =
      parse(" android.permission.CAMERA = GRANTED ", "android.permission.RECORD_AUDIO=Denied")

    assertThat(grants)
      .containsExactly(
        "android.permission.CAMERA",
        PermissionGrantCaptureState.GRANTED,
        "android.permission.RECORD_AUDIO",
        PermissionGrantCaptureState.DENIED,
      )
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `iteration order follows declaration order`() {
    val (grants, _) =
      parse(
        "android.permission.RECORD_AUDIO=denied",
        "android.permission.CAMERA=granted",
        "android.permission.ACCESS_FINE_LOCATION=granted",
      )

    assertThat(grants.keys)
      .containsExactly(
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
      )
      .inOrder()
  }

  @Test
  fun `an entry with no separator is dropped with a warning naming it`() {
    val (grants, warnings) = parse("android.permission.CAMERA")

    assertThat(grants).isEmpty()
    assertThat(warnings).hasSize(1)
    assertThat(warnings.single()).contains("@PermissionPreview")
    assertThat(warnings.single()).contains("android.permission.CAMERA")
    assertThat(warnings.single()).contains(owner)
  }

  @Test
  fun `an unrecognised grant state is dropped with a warning`() {
    // `maybe` is the shape of the mistake worth catching: it looks like a state, so nothing else
    // in the pipeline would flag it, and the capture would silently fall back to denied.
    val (grants, warnings) = parse("android.permission.CAMERA=maybe")

    assertThat(grants).isEmpty()
    assertThat(warnings.single()).contains("granted or denied")
  }

  @Test
  fun `a blank permission name is dropped with a warning`() {
    val (grants, warnings) = parse("  =granted")

    assertThat(grants).isEmpty()
    assertThat(warnings).hasSize(1)
  }

  @Test
  fun `a malformed entry does not discard its well-formed siblings`() {
    // Discovery's policy for an unusable annotation is "cost the thing it would have added, keep
    // going" — failing the whole annotation here would take a working grant down with a typo.
    val (grants, warnings) =
      parse("android.permission.CAMERA=granted", "android.permission.RECORD_AUDIO", "=denied")

    assertThat(grants)
      .containsExactly("android.permission.CAMERA", PermissionGrantCaptureState.GRANTED)
    assertThat(warnings).hasSize(2)
  }

  @Test
  fun `only the first separator splits so a value-side equals is not a second boundary`() {
    // No Android permission constant contains `=`, but splitting on all of them would turn a
    // vendor permission that does into a wrong grant rather than a rejected one.
    val (grants, warnings) = parse("com.vendor.PERM=x=granted")

    assertThat(grants).isEmpty()
    assertThat(warnings).hasSize(1)
  }

  @Test
  fun `a repeated permission keeps the first state and warns only when they disagree`() {
    val (agreeing, agreeingWarnings) =
      parse("android.permission.CAMERA=granted", "android.permission.CAMERA=granted")
    assertThat(agreeing)
      .containsExactly("android.permission.CAMERA", PermissionGrantCaptureState.GRANTED)
    assertThat(agreeingWarnings).isEmpty()

    val (conflicting, conflictingWarnings) =
      parse("android.permission.CAMERA=granted", "android.permission.CAMERA=denied")
    assertThat(conflicting)
      .containsExactly("android.permission.CAMERA", PermissionGrantCaptureState.GRANTED)
    assertThat(conflictingWarnings.single()).contains("conflicting states")
  }

  @Test
  fun `no entries yields an empty map and no warnings`() {
    val (grants, warnings) = parse()

    assertThat(grants).isEmpty()
    assertThat(warnings).isEmpty()
  }
}
