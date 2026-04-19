package com.example.sampleremotecompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemotePreview

@Suppress("RestrictedApiAndroidX")
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Stand up a single Remote Compose document at runtime so the
            // sample has a launchable activity. The preview functions live in
            // `Previews.kt` — this activity is only here to satisfy the
            // `android.application` plugin.
            RemotePreview(profile = RcPlatformProfiles.ANDROIDX) {
                Container { RemoteButtonEnabled() }
            }
        }
    }
}
