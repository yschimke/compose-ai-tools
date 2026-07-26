package ee.schimke.composeai.daemon

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import org.junit.Test

/**
 * Diagnostic: what can we actually introspect off a `Font` instance *inside the render sandbox*?
 *
 * `googleFontFamilyName` identifies a downloadable face by enumerating `font.javaClass.methods` and
 * looking for `toFontRequest()`. That call is not free of assumptions: `Class.getMethods()` resolves
 * every method's parameter and return types, and `toFontRequest()` returns
 * `androidx.core.provider.FontRequest` — so on a classpath where that class is absent, enumerating
 * methods throws `NoClassDefFoundError` before `getName()` is ever considered. `FontResolverRecorder`
 * reads `getDeclaredField` instead and does recover the name, which is why `fonts-used.json` shows
 * `Font(GoogleFont("Orbitron", …))` for the very render whose `compose-figma.svg` says Roboto.
 *
 * This prints the shape rather than asserting a fix, so the failure mode is recorded in the open
 * before anything is changed.
 */
class GoogleFontIntrospectionDiagnosticTest {

  @Test
  fun `report what a downloadable Font exposes`() {
    val provider =
      GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = 0,
      )
    val font =
      Font(
        googleFont = GoogleFont("Orbitron"),
        fontProvider = provider,
        weight = FontWeight.Medium,
        style = FontStyle.Normal,
      )

    println("TOFU-DIAG class      = ${font.javaClass.name}")
    println("TOFU-DIAG toString   = $font")

    val declaredFields =
      runCatching { font.javaClass.declaredFields.map { it.name } }
        .fold(onSuccess = { it.toString() }, onFailure = { "THREW ${(it as Throwable).javaClass.name}: ${it.message}" })
    println("TOFU-DIAG fields     = $declaredFields")

    val methods =
      runCatching { font.javaClass.methods.map { it.name }.filter { it.startsWith("get") || it == "toFontRequest" } }
        .fold(onSuccess = { it.toString() }, onFailure = { "THREW ${(it as Throwable).javaClass.name}: ${it.message}" })
    println("TOFU-DIAG methods    = $methods")

    val viaField =
      runCatching {
          font.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(font) as? String
        }
        .fold(onSuccess = { it.toString() }, onFailure = { "THREW ${(it as Throwable).javaClass.name}" })
    println("TOFU-DIAG name field = $viaField")

    // `googleFontFamilyName` is internal to the connector, so replay its exact gate here: it bails
    // unless `toFontRequest()` shows up in the enumerated methods.
    val gate =
      runCatching {
          font.javaClass.methods.none { it.name == "toFontRequest" && it.parameterCount == 0 }
        }
        .fold(
          onSuccess = { if (it) "gate REJECTS (no toFontRequest) -> null" else "gate accepts" },
          onFailure = { "gate THREW ${(it as Throwable).javaClass.name}: ${it.message}" },
        )
    println("TOFU-DIAG helper gate = $gate")
  }
}
