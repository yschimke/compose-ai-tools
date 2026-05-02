package com.example.samplewear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.wear.compose.material3.TimeSource
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private const val nanosPerMinute = 60_000_000_000L
private val previewStartTime: LocalTime = LocalTime.of(10, 10)

object FixedPreviewTimeSource : TimeSource {
    @Composable
    override fun currentTime(): String {
        var elapsedMinutes by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            var startFrameNanos = -1L
            while (true) {
                withFrameNanos { frameNanos ->
                    if (startFrameNanos < 0L) {
                        startFrameNanos = frameNanos
                    }
                    elapsedMinutes = (frameNanos - startFrameNanos) / nanosPerMinute
                }
            }
        }

        val currentTimeText by remember {
            derivedStateOf {
                previewStartTime
                    .plusMinutes(elapsedMinutes)
                    .format(timeFormatter)
            }
        }
        return currentTimeText
    }
}
