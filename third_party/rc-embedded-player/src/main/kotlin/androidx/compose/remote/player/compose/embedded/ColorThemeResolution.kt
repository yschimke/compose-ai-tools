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

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.Theme

/** The colour group whose indices [ANDROID_COLOR_NAMES] names. */
internal const val ANDROID_COLOR_GROUP: String = "android"

/**
 * `android.R.color` resource names, indexed by the resource index a document records.
 *
 * Transcribed from `ThemeSupport.AndroidColors` in `remote-player-view` 1.0.0-alpha17 — the table
 * the View player actually resolves a document's indices against, and therefore the authority.
 *
 * **Not derived from `Rc.AndroidColors` reflectively**, which is what this file did first and is
 * the obvious-looking shortcut. At alpha17 those creation-side constants are wrong for 21 of the
 * 196 indices, and wrong quietly: `SYSTEM_ACCENT2_200` is `30`, colliding with
 * `SYSTEM_ACCENT2_1000`, so index `31` has no constant and index `30` resolves to whichever field
 * reflection happens to yield — a real resource, and the wrong colour. Twenty more are misspelled
 * against the resource they select (`SYSTEM_ERROR_620` at index `62`, where the resource is
 * `system_error_10`; the entire `system_neutral1_*` run at 78–90 spelled `SYSTEM_NEUTRAL78_0`,
 * `SYSTEM_NEUTRAL79_790`, …), so they match nothing and the colour silently keeps its fallback.
 *
 * Kept in sync with `RcAndroidSystemColors` in `:rc-player-protocol` by
 * `AndroidColorTableDriftTest` — the two players must read a given index as the same resource, and
 * the module boundary means the table cannot simply be shared.
 */
internal val ANDROID_COLOR_NAMES: List<String> =
  listOf(
    "background_dark", // 0
    "background_light", // 1
    "black", // 2
    "darker_gray", // 3
    "holo_blue_bright", // 4
    "holo_blue_dark", // 5
    "holo_blue_light", // 6
    "holo_green_dark", // 7
    "holo_green_light", // 8
    "holo_orange_dark", // 9
    "holo_orange_light", // 10
    "holo_purple", // 11
    "holo_red_dark", // 12
    "holo_red_light", // 13
    "system_accent1_0", // 14
    "system_accent1_10", // 15
    "system_accent1_100", // 16
    "system_accent1_1000", // 17
    "system_accent1_200", // 18
    "system_accent1_300", // 19
    "system_accent1_400", // 20
    "system_accent1_50", // 21
    "system_accent1_500", // 22
    "system_accent1_600", // 23
    "system_accent1_700", // 24
    "system_accent1_800", // 25
    "system_accent1_900", // 26
    "system_accent2_0", // 27
    "system_accent2_10", // 28
    "system_accent2_100", // 29
    "system_accent2_1000", // 30
    "system_accent2_200", // 31
    "system_accent2_300", // 32
    "system_accent2_400", // 33
    "system_accent2_50", // 34
    "system_accent2_500", // 35
    "system_accent2_600", // 36
    "system_accent2_700", // 37
    "system_accent2_800", // 38
    "system_accent2_900", // 39
    "system_accent3_0", // 40
    "system_accent3_10", // 41
    "system_accent3_100", // 42
    "system_accent3_1000", // 43
    "system_accent3_200", // 44
    "system_accent3_300", // 45
    "system_accent3_400", // 46
    "system_accent3_50", // 47
    "system_accent3_500", // 48
    "system_accent3_600", // 49
    "system_accent3_700", // 50
    "system_accent3_800", // 51
    "system_accent3_900", // 52
    "system_background_dark", // 53
    "system_background_light", // 54
    "system_control_activated_dark", // 55
    "system_control_activated_light", // 56
    "system_control_highlight_dark", // 57
    "system_control_highlight_light", // 58
    "system_control_normal_dark", // 59
    "system_control_normal_light", // 60
    "system_error_0", // 61
    "system_error_10", // 62
    "system_error_100", // 63
    "system_error_1000", // 64
    "system_error_200", // 65
    "system_error_300", // 66
    "system_error_400", // 67
    "system_error_50", // 68
    "system_error_500", // 69
    "system_error_600", // 70
    "system_error_700", // 71
    "system_error_800", // 72
    "system_error_900", // 73
    "system_error_container_dark", // 74
    "system_error_container_light", // 75
    "system_error_dark", // 76
    "system_error_light", // 77
    "system_neutral1_0", // 78
    "system_neutral1_10", // 79
    "system_neutral1_100", // 80
    "system_neutral1_1000", // 81
    "system_neutral1_200", // 82
    "system_neutral1_300", // 83
    "system_neutral1_400", // 84
    "system_neutral1_50", // 85
    "system_neutral1_500", // 86
    "system_neutral1_600", // 87
    "system_neutral1_700", // 88
    "system_neutral1_800", // 89
    "system_neutral1_900", // 90
    "system_neutral2_0", // 91
    "system_neutral2_10", // 92
    "system_neutral2_100", // 93
    "system_neutral2_1000", // 94
    "system_neutral2_200", // 95
    "system_neutral2_300", // 96
    "system_neutral2_400", // 97
    "system_neutral2_50", // 98
    "system_neutral2_500", // 99
    "system_neutral2_600", // 100
    "system_neutral2_700", // 101
    "system_neutral2_800", // 102
    "system_neutral2_900", // 103
    "system_on_background_dark", // 104
    "system_on_background_light", // 105
    "system_on_error_container_dark", // 106
    "system_on_error_container_light", // 107
    "system_on_error_dark", // 108
    "system_on_error_light", // 109
    "system_on_primary_container_dark", // 110
    "system_on_primary_container_light", // 111
    "system_on_primary_dark", // 112
    "system_on_primary_fixed", // 113
    "system_on_primary_fixed_variant", // 114
    "system_on_primary_light", // 115
    "system_on_secondary_container_dark", // 116
    "system_on_secondary_container_light", // 117
    "system_on_secondary_dark", // 118
    "system_on_secondary_fixed", // 119
    "system_on_secondary_fixed_variant", // 120
    "system_on_secondary_light", // 121
    "system_on_surface_dark", // 122
    "system_on_surface_disabled", // 123
    "system_on_surface_light", // 124
    "system_on_surface_variant_dark", // 125
    "system_on_surface_variant_light", // 126
    "system_on_tertiary_container_dark", // 127
    "system_on_tertiary_container_light", // 128
    "system_on_tertiary_dark", // 129
    "system_on_tertiary_fixed", // 130
    "system_on_tertiary_fixed_variant", // 131
    "system_on_tertiary_light", // 132
    "system_outline_dark", // 133
    "system_outline_disabled", // 134
    "system_outline_light", // 135
    "system_outline_variant_dark", // 136
    "system_outline_variant_light", // 137
    "system_palette_key_color_neutral_dark", // 138
    "system_palette_key_color_neutral_light", // 139
    "system_palette_key_color_neutral_variant_dark", // 140
    "system_palette_key_color_neutral_variant_light", // 141
    "system_palette_key_color_primary_dark", // 142
    "system_palette_key_color_primary_light", // 143
    "system_palette_key_color_secondary_dark", // 144
    "system_palette_key_color_secondary_light", // 145
    "system_palette_key_color_tertiary_dark", // 146
    "system_palette_key_color_tertiary_light", // 147
    "system_primary_container_dark", // 148
    "system_primary_container_light", // 149
    "system_primary_dark", // 150
    "system_primary_fixed", // 151
    "system_primary_fixed_dim", // 152
    "system_primary_light", // 153
    "system_secondary_container_dark", // 154
    "system_secondary_container_light", // 155
    "system_secondary_dark", // 156
    "system_secondary_fixed", // 157
    "system_secondary_fixed_dim", // 158
    "system_secondary_light", // 159
    "system_surface_bright_dark", // 160
    "system_surface_bright_light", // 161
    "system_surface_container_dark", // 162
    "system_surface_container_high_dark", // 163
    "system_surface_container_high_light", // 164
    "system_surface_container_highest_dark", // 165
    "system_surface_container_highest_light", // 166
    "system_surface_container_light", // 167
    "system_surface_container_low_dark", // 168
    "system_surface_container_low_light", // 169
    "system_surface_container_lowest_dark", // 170
    "system_surface_container_lowest_light", // 171
    "system_surface_dark", // 172
    "system_surface_dim_dark", // 173
    "system_surface_dim_light", // 174
    "system_surface_disabled", // 175
    "system_surface_light", // 176
    "system_surface_variant_dark", // 177
    "system_surface_variant_light", // 178
    "system_tertiary_container_dark", // 179
    "system_tertiary_container_light", // 180
    "system_tertiary_dark", // 181
    "system_tertiary_fixed", // 182
    "system_tertiary_fixed_dim", // 183
    "system_tertiary_light", // 184
    "system_text_hint_inverse_dark", // 185
    "system_text_hint_inverse_light", // 186
    "system_text_primary_inverse_dark", // 187
    "system_text_primary_inverse_disable_only_dark", // 188
    "system_text_primary_inverse_disable_only_light", // 189
    "system_text_primary_inverse_light", // 190
    "system_text_secondary_and_tertiary_inverse_dark", // 191
    "system_text_secondary_and_tertiary_inverse_disabled_dark", // 192
    "system_text_secondary_and_tertiary_inverse_disabled_light", // 193
    "system_text_secondary_and_tertiary_inverse_light", // 194
    "tab_indicator_text", // 195
  )

/**
 * Resolve [document]'s themed colours through [lookup], overwriting each operation's
 * `mLightMode`/`mDarkMode` in place, and return how many modes resolved.
 *
 * Call this **before** the document's `ColorTheme` operations are applied: `ColorTheme.apply` reads
 * `mLightMode`/`mDarkMode` — values its constructor seeded from the captured *fallbacks* — so
 * resolving afterwards leaves the fallback already loaded into the context.
 *
 * [lookup] maps an `android.R.color` name to an ARGB value and returns `null` when the running
 * platform has no such resource. `null`, a negative index (how a document says "no resource for
 * this mode"), or an index past the end of the table all leave that mode's captured fallback in
 * place, so resolution failing never makes a document render worse than it did before.
 *
 * Only the [ANDROID_COLOR_GROUP] group is resolved: an index means nothing without knowing whose
 * table it indexes, so another vendor's group keeps its fallbacks rather than being resolved
 * against Android's.
 *
 * Platform-neutral on purpose — the JVM lane shares this file and supplies its own [lookup] (or
 * none, which is the honest answer for a headless renderer with no system palette).
 */
internal fun resolveThemedColors(document: CoreDocument, lookup: (name: String) -> Int?): Int {
  var resolved = 0
  document.themedColors.orEmpty().forEach { colorTheme ->
    if (colorTheme.mColorGroupName != ANDROID_COLOR_GROUP) return@forEach
    fun resolve(index: Short): Int? = ANDROID_COLOR_NAMES.getOrNull(index.toInt())?.let(lookup)
    resolve(colorTheme.mLightModeIndex)?.let {
      colorTheme.mLightMode = it
      resolved++
    }
    resolve(colorTheme.mDarkModeIndex)?.let {
      colorTheme.mDarkMode = it
      resolved++
    }
  }
  return resolved
}

/**
 * Turn a requested theme into the concrete mode a `ColorTheme` selects with.
 *
 * [Theme.LIGHT] and [Theme.DARK] pass through. [Theme.SYSTEM] and [Theme.UNSPECIFIED] are not modes
 * but questions — one asks for the host's setting, the other says nothing — and both are answered
 * from [systemInDarkTheme].
 *
 * Neither may be passed through to the operation. `ColorTheme.apply` selects light only for
 * `Theme.LIGHT` and dark for everything else, so an unanswered question renders the whole document
 * dark with nothing having decided that — which is not a conservative default, it is a wrong one.
 */
internal fun resolveThemeMode(theme: Int, systemInDarkTheme: Boolean): Int =
  when (theme) {
    Theme.SYSTEM,
    Theme.UNSPECIFIED -> if (systemInDarkTheme) Theme.DARK else Theme.LIGHT
    else -> theme
  }
