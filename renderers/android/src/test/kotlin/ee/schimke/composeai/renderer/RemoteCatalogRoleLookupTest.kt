package ee.schimke.composeai.renderer

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.remote.material3.RemoteColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every Remote Material 3 colour role must resolve to its OWN value.
 *
 * The failure this exists for did not look like a bug. `RemoteCatalogValues` matched a role's
 * getter with `startsWith`, and `getPrimary` is a prefix of `getPrimaryDim` and
 * `getPrimaryContainer` — eleven of the twenty-nine roles collide that way. So `primary` resolved
 * to whichever of its siblings `Class.getMethods()` happened to list first, an order the JVM does
 * not specify, and the `Remote theme colours` catalog raster silently changed colours between runs:
 * `main` committed four different PNGs for that one preview as its own baseline across sixteen
 * consecutive updates, with `primary` reading `primaryDim`'s value in some of them. Nothing failed;
 * the visual-diff bot simply flagged an unrelated preview on every pull request.
 *
 * Reading a neighbour's colour is worse than the churn it caused. The catalog and its
 * [CatalogTokenSidecar] are inventory — what a consumer's theme actually contains — so a role
 * reporting the wrong value is a wrong answer delivered confidently, not a rendering artefact.
 */
class RemoteCatalogRoleLookupTest {

  @Test
  fun `each colour role reads its own getter, not a longer sibling's`() {
    val roles = catalogColorRoles(RemoteColorScheme()).toMap()

    // The eleven roles whose getter name is a strict prefix of another role's. Every one of these
    // was resolvable to the wrong colour.
    assertEquals(RemoteColorScheme.expected(3), roles["primary"])
    assertEquals(RemoteColorScheme.expected(5), roles["onPrimary"])
    assertEquals(RemoteColorScheme.expected(8), roles["secondary"])
    assertEquals(RemoteColorScheme.expected(10), roles["onSecondary"])
    assertEquals(RemoteColorScheme.expected(13), roles["tertiary"])
    assertEquals(RemoteColorScheme.expected(15), roles["onTertiary"])
    assertEquals(RemoteColorScheme.expected(18), roles["surfaceContainer"])
    assertEquals(RemoteColorScheme.expected(20), roles["onSurface"])
    assertEquals(RemoteColorScheme.expected(22), roles["outline"])
    assertEquals(RemoteColorScheme.expected(27), roles["error"])
    assertEquals(RemoteColorScheme.expected(29), roles["onError"])
  }

  @Test
  fun `the longer siblings still read themselves`() {
    // The other half of the contract: narrowing the match must not have cost the roles that were
    // only ever reachable by their own full name.
    val roles = catalogColorRoles(RemoteColorScheme()).toMap()

    assertEquals(RemoteColorScheme.expected(1), roles["primaryDim"])
    assertEquals(RemoteColorScheme.expected(2), roles["primaryContainer"])
    assertEquals(RemoteColorScheme.expected(4), roles["onPrimaryContainer"])
    assertEquals(RemoteColorScheme.expected(16), roles["surfaceContainerLow"])
    assertEquals(RemoteColorScheme.expected(17), roles["surfaceContainerHigh"])
    assertEquals(RemoteColorScheme.expected(19), roles["onSurfaceVariant"])
    assertEquals(RemoteColorScheme.expected(21), roles["outlineVariant"])
    assertEquals(RemoteColorScheme.expected(26), roles["errorContainer"])
  }

  @Test
  fun `no two roles answer with the same colour`() {
    // The property that makes the sheet trustworthy, stated directly. A prefix mix-up always shows
    // up here as a duplicate, whichever way `getMethods()` happened to order the class that run.
    val values = catalogColorRoles(RemoteColorScheme()).map { it.second }

    assertEquals(29, values.size)
    assertEquals(values.size, values.toSet().size)
  }

  private fun catalogColorRoles(scheme: Any): List<Pair<String, Color>> =
    RemoteCatalogValues.colorSchemeRoles(scheme)
}
