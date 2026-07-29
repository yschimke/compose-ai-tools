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

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.remote.core.RemoteComposeState
import androidx.compose.remote.core.SystemClock
import androidx.compose.remote.player.compose.embedded.SnapshotRemoteComposeState
import androidx.compose.remote.player.compose.embedded.StoreBackedRemoteContext
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the embedded player's value layer on a **plain desktop JVM** — no Android, no Robolectric.
 *
 * This is the first execution of any of this player off Android, and it is what the CMP split in
 * `PROVENANCE.md` exists to reach. Compiling is most of the claim (the module wouldn't build if a
 * shared file secretly needed the platform), but compiling isn't running:
 * `SnapshotRemoteComposeState` is backed by Compose's snapshot system, and this asserts that
 * machinery actually works under the Desktop runtime rather than merely resolving against it.
 *
 * Scope is honest about where the port has got to: the value/expression layer runs, the draw path
 * does not exist here yet. See the sequencing in `PROVENANCE.md`.
 */
class DesktopRemoteContextTest {

  /** Minimal concrete context; the clock only matters to the time-variable ids, unused here. */
  private class TestContext(state: RemoteComposeState) : StoreBackedRemoteContext(SystemClock()) {
    init {
      mRemoteComposeState = state
    }
  }

  private fun newContext(): TestContext = TestContext(SnapshotRemoteComposeState())

  @Test
  fun readsAndWritesFloatsThroughTheSharedStore() {
    val context = newContext()
    context.loadFloat(42, 1.5f)
    assertEquals(1.5f, context.getFloat(42), 0f)

    context.loadFloat(42, 2.5f)
    assertEquals("a second write should replace, not accumulate", 2.5f, context.getFloat(42), 0f)
  }

  @Test
  fun readsAndWritesIntegersAndColors() {
    val context = newContext()
    context.loadInteger(43, 7)
    context.loadColor(44, 0xFF102030.toInt())
    assertEquals(7, context.getInteger(43))
    assertEquals(0xFF102030.toInt(), context.getColor(44))
  }

  @Test
  fun textRoundTripsThroughTheDataStore() {
    val context = newContext()
    // `loadText` distinguishes first write from update — the branch is worth exercising both ways.
    context.loadText(45, "hello")
    assertEquals("hello", context.getText(45))
    context.loadText(45, "goodbye")
    assertEquals("goodbye", context.getText(45))
  }

  @Test
  fun unknownIdReadsDoNotThrow() {
    // The evaluator reads speculatively, so an absent id must be answerable rather than fatal.
    assertNull(newContext().getText(9999))
  }

  /**
   * The reason this store exists: reads must register with Compose's snapshot system so a
   * `derivedStateOf` over them recomputes. Without this, [GraphContext]'s whole
   * dependency-discovery design silently degrades to "never invalidates" — and nothing else in this
   * test would notice.
   */
  @Test
  fun storeReadsAreObservedBySnapshots() {
    val context = newContext()
    context.loadFloat(50, 1f)

    val observed = mutableSetOf<Any>()
    val snapshot = Snapshot.takeMutableSnapshot(readObserver = { observed.add(it) })
    try {
      snapshot.enter { context.getFloat(50) }
    } finally {
      snapshot.dispose()
    }

    assertTrue(
      "reading the store recorded no snapshot dependency — derivedStateOf would never invalidate",
      observed.isNotEmpty(),
    )
  }
}
