package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Multi-preview meta-annotation for a notification rendered under several environment axes.
 * Compose tooling fans this out into one preview entry per `@Preview` below, identically in
 * Android Studio's preview pane and in this project's Robolectric pipeline.
 *
 * The axes are deliberately the ones `@Preview` already owns — theme (`uiMode`), locale, and
 * `fontScale`. Style / content axes (BigText vs Messaging vs Inbox, long-title vs no-icon, ...)
 * are separate factory functions, mirroring how Compose component previews work:
 * `ButtonDefault` vs `ButtonDisabled` are separate `@Preview`s, not a fan-out from one.
 *
 * Locale fan-out resolves real translated strings via `values-<lang>/notification_strings.xml`
 * — `ar` flips layout direction *and* loads the Arabic body, `de` / `ja` pick up their language
 * resources, `en` is the default-qualifier `values/`. Demonstrates the variants-via-multi-preview
 * approach discussed on issue #1249.
 */
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Arabic", locale = "ar")
@Preview(name = "German", locale = "de")
@Preview(name = "Japanese", locale = "ja")
@Preview(name = "Large font", fontScale = 1.5f)
annotation class NotificationVariants
