package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted tests for [SubscriptionStore]. The store backs every `(previewId, kind)` mutation in
 * [JsonRpcServer] -- subscribe, unsubscribe, the three pruning paths -- and replaced an inlined
 * `ConcurrentHashMap` whose invariants ("drop the inner set when it goes empty", "only notify
 * onUnsubscribe when the pair really existed") were only enforced by callsite discipline. Tests
 * here pin those invariants directly so future callsite refactors can't quietly drift them.
 */
class SubscriptionStoreTest {

  @Test
  fun `subscribe records the pair and returns true on first add`() {
    val store = SubscriptionStore()
    assertTrue(store.subscribe("p1", "a11y/atf"))
    assertEquals(setOf("a11y/atf"), store.kindsFor("p1"))
  }

  @Test
  fun `subscribe is idempotent -- second add returns false`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    assertFalse(store.subscribe("p1", "a11y/atf"))
    assertEquals(setOf("a11y/atf"), store.kindsFor("p1"))
  }

  @Test
  fun `multiple kinds for the same preview accumulate in one set`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    store.subscribe("p1", "a11y/hierarchy")
    assertEquals(setOf("a11y/atf", "a11y/hierarchy"), store.kindsFor("p1"))
  }

  @Test
  fun `kindsFor returns an empty set for an unknown preview`() {
    val store = SubscriptionStore()
    assertEquals(emptySet<String>(), store.kindsFor("nope"))
  }

  @Test
  fun `unsubscribe returns true when the pair was present`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    assertTrue(store.unsubscribe("p1", "a11y/atf"))
    assertEquals(emptySet<String>(), store.kindsFor("p1"))
  }

  @Test
  fun `unsubscribe returns false on a never-subscribed pair`() {
    val store = SubscriptionStore()
    assertFalse(store.unsubscribe("p1", "a11y/atf"))
  }

  @Test
  fun `unsubscribe returns false on a kind that was subscribed under a different preview`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    assertFalse(store.unsubscribe("p2", "a11y/atf"))
    // The original pair is untouched.
    assertEquals(setOf("a11y/atf"), store.kindsFor("p1"))
  }

  @Test
  fun `unsubscribing the last kind clears hasAny for that preview`() {
    // Regression for the "empty inner set leaks" invariant: the old code called
    // computeIfPresent after every unsubscribe to drop the empty entry. The
    // store has to keep that behaviour or hasAny lies.
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    store.unsubscribe("p1", "a11y/atf")
    assertFalse(store.hasAny("p1"))
  }

  @Test
  fun `retainVisible drops every pair for previews not in the visible set`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    store.subscribe("p1", "a11y/hierarchy")
    store.subscribe("p2", "a11y/atf")
    store.subscribe("p3", "compose/recomposition")
    val dropped = store.retainVisible(setOf("p1"))
    assertEquals(setOf("p2" to "a11y/atf", "p3" to "compose/recomposition"), dropped.toSet())
    assertEquals(setOf("a11y/atf", "a11y/hierarchy"), store.kindsFor("p1"))
    assertFalse(store.hasAny("p2"))
    assertFalse(store.hasAny("p3"))
  }

  @Test
  fun `retainVisible returns an empty list when nothing needs to drop`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    assertEquals(emptyList<Pair<String, String>>(), store.retainVisible(setOf("p1", "p2")))
    assertEquals(setOf("a11y/atf"), store.kindsFor("p1"))
  }

  @Test
  fun `retainVisible with an empty visible set drops everything`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    store.subscribe("p2", "compose/recomposition")
    val dropped = store.retainVisible(emptySet())
    assertEquals(setOf("p1" to "a11y/atf", "p2" to "compose/recomposition"), dropped.toSet())
    assertFalse(store.hasAny("p1"))
    assertFalse(store.hasAny("p2"))
  }

  @Test
  fun `removeKinds drops every pair whose kind is in the set, across all previews`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    store.subscribe("p1", "compose/recomposition")
    store.subscribe("p2", "a11y/atf")
    val dropped = store.removeKinds(setOf("a11y/atf"))
    assertEquals(setOf("p1" to "a11y/atf", "p2" to "a11y/atf"), dropped.toSet())
    // p1 still has the surviving kind; p2 was fully cleared and must report empty.
    assertEquals(setOf("compose/recomposition"), store.kindsFor("p1"))
    assertFalse(store.hasAny("p2"))
  }

  @Test
  fun `removeKinds returns an empty list when no kind matches`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    assertEquals(emptyList<Pair<String, String>>(), store.removeKinds(setOf("layout/inspector")))
    assertEquals(setOf("a11y/atf"), store.kindsFor("p1"))
  }

  @Test
  fun `removeKinds with an empty kind set is a no-op`() {
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    assertEquals(emptyList<Pair<String, String>>(), store.removeKinds(emptySet()))
    assertEquals(setOf("a11y/atf"), store.kindsFor("p1"))
  }

  @Test
  fun `removeKinds clears the previewId entry when its last kind goes`() {
    // Regression: the old code called `subscriptions.values.removeIf { it.isEmpty() }` after
    // the per-preview kind sweep so the bookkeeping wouldn't accumulate empty entries. The
    // store has to keep that invariant.
    val store = SubscriptionStore()
    store.subscribe("p1", "a11y/atf")
    store.removeKinds(setOf("a11y/atf"))
    assertFalse(store.hasAny("p1"))
  }
}
