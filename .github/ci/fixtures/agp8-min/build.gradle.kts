// Root build for the AGP 8.13 fixture. Only `apply false` declarations live
// here; the `:app` module turns each on as needed.
plugins {
  id("com.android.application") version "8.13.0" apply false
  id("org.jetbrains.kotlin.android") version "2.0.21" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
