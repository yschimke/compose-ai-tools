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

import android.content.Context
import androidx.compose.remote.core.CoreDocument

/** Resolves the indexed `android` ColorTheme group the same way the View player does. */
internal fun resolveAndroidThemeColors(context: Context, document: CoreDocument) {
  val indexedColorNames = runCatching {
    Class.forName("androidx.compose.remote.creation.Rc\$AndroidColors")
  }
    .getOrNull()
    ?.fields
    ?.mapNotNull { field ->
      if (field.type == Short::class.javaPrimitiveType) field.getShort(null).toInt() to field.name
      else null
    }
    ?.toMap()
    .orEmpty()

  document.themedColors.orEmpty().forEach { colorTheme ->
    if (colorTheme.mColorGroupName != ANDROID_COLOR_GROUP) return@forEach

    fun resolve(index: Short, fallback: Int): Int {
      val name = indexedColorNames[index.toInt()]?.lowercase() ?: return fallback
      return runCatching {
          val resourceId = android.R.color::class.java.getField(name).getInt(null)
          context.getColor(resourceId)
        }
        .getOrDefault(fallback)
    }

    colorTheme.mLightMode = resolve(colorTheme.mLightModeIndex, colorTheme.mLightModeFallback)
    colorTheme.mDarkMode = resolve(colorTheme.mDarkModeIndex, colorTheme.mDarkModeFallback)
  }
}

private const val ANDROID_COLOR_GROUP = "android"
