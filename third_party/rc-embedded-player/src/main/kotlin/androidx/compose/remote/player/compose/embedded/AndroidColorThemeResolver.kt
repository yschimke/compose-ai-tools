/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.remote.player.compose.embedded

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.remote.core.CoreDocument

/**
 * Resolves the indexed `android` `ColorTheme` group against framework resources — the embedded
 * player's equivalent of `ThemeSupport.AndroidColorEngine` in `remote-player-view`.
 *
 * Resolved by *name* rather than through `android.R.color.<name>`: the table spans resources
 * introduced across API 31–34, so a name lookup degrades to "not found" on a device that predates
 * one instead of tying this module to the compileSdk that first declared it.
 */
@SuppressLint("DiscouragedApi")
internal fun resolveAndroidThemeColors(context: Context, document: CoreDocument) {
  val resources = context.resources
  resolveThemedColors(document) { name ->
    when (val id = resources.getIdentifier(name, "color", "android")) {
      0 -> null
      // `getColor(id, null)`: these are plain colour resources, not theme attributes, so there is
      // no theme to resolve against — and supplying one would let the *device's* night mode pick
      // between them, which is the choice the document's own light/dark indices exist to make.
      else -> runCatching { resources.getColor(id, null) }.getOrNull()
    }
  }
}
