package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping

/**
 * **One cache generation** for a catalog page and the frames it draws.
 *
 * A comparison page's HTML is assembled from the catalog on disk and served a short public
 * lifetime; the render it points at is served a longer one. Those two lifetimes are independent, so
 * after a catalog refreshes in place a browser or an intermediary can hold one generation's HTML
 * beside another generation's pixels. That has always been true of the annotation redline, whose
 * boxes are bounds in the render's pixel space — a redline one generation out of date is a reading
 * aid slightly out of date. It stopped being tolerable when the same page started carrying a parity
 * **verdict**: "padding is 24 where the spec says 16", drawn over the wrong frame, is a false claim
 * with a highlight pointing at it (issue #4695).
 *
 * The fix is to stop letting the two drift: every frame URL a generation-scoped page writes carries
 * [PARAM]`=<sha>`, naming the delivery-branch commit the page itself was assembled from. That makes
 * the pairing decidable at the point it matters — inside the asset lane — rather than a race
 * between two `max-age`s:
 * - the generation the host is serving ⇒ the bytes on disk, and the URL is now content-addressed,
 *   so the response is `immutable` rather than merely short-lived;
 * - any **other** generation ⇒ the page asking is a publish behind, and the answer it needs is that
 *   publish's bytes. The delivery branch still has them, and the server already knows how to read
 *   them: this resolves exactly as a [ServeCatalogRevision] pin does, which is why this is a
 *   parameter and not a second fetching lane.
 *
 * So the page, the verdict embedded in it and the frame beside it are one generation or the request
 * fails loudly — never a 200 pairing a claim with pixels it was not measured on.
 *
 * A generation is the same shape as a pin (a delivery-branch commit sha) and is validated by the
 * same rules, deliberately: two spellings of "is this a commit" is how the pinned lane and this one
 * would eventually disagree about which strings may steer a read off the branch. What differs is
 * the *meaning*, and it is worth keeping distinct in the URL. `at=` is a **request**: a human asked
 * for an older publish, so the page announces the pin, and the redline and the verdict are withheld
 * because they describe today's render. `gen=` is a **coherence claim** the server made to itself
 * about a page it just served, invisible to the reader, and it withholds nothing — on the current
 * generation it is the ordinary browse. Folding them into one parameter would mean either
 * announcing a pin nobody asked for, or teaching the pin lane to withhold nothing sometimes.
 */
object ServeCacheGeneration {

  /**
   * Query parameter naming the catalog generation a frame URL belongs to.
   *
   * Short because it rides on every published frame URL on a page — a comparison wall writes one
   * per card — and it is machine-written and machine-read; nothing about it is meant to be typed.
   */
  const val PARAM: String = "gen"

  /**
   * Normalize a request-supplied generation to a canonical sha, or null when it isn't one.
   *
   * Delegated to [ServeCatalogRevision.normalize] rather than re-implemented: a generation resolves
   * through the pinned-asset lane whenever it names a publish other than the one on disk, so it
   * reaches the same `raw.githubusercontent.com/<repo>/<sha>/<path>` fetch and must be admitted by
   * the same rule. A looser one here would let a ref name the tree that lane reads.
   */
  fun normalize(raw: String?): String? = ServeCatalogRevision.normalize(raw)

  /**
   * Append [PARAM] to an already-built asset [link], or return it untouched.
   *
   * Untouched for a host with no delivery-branch generation to name — an uploaded bundle, a local
   * project, a daemon-backed module. Those have no published-per-generation bytes to reconcile
   * against and no branch to read an older generation from, so scoping their frames would only mint
   * a parameter nothing can answer.
   *
   * Deliberately applied to the **asset** query and never to the page query it is derived from.
   * `gen=` on a `/compare/<id>` or `/p/<id>` link would put the server's internal coherence claim
   * into a URL people copy, share and pin, where it reads as a permalink that isn't one — and it
   * would survive into a later publish as a stale parameter on a page that is perfectly current.
   */
  fun scope(link: String, generation: String?): String {
    val gen = normalize(generation) ?: return link
    val param = "$PARAM=${WebEscaping.urlEncodeSegment(gen)}"
    return when {
      !link.contains('?') -> "$link?$param"
      link.endsWith('?') || link.endsWith('&') -> "$link$param"
      else -> "$link&$param"
    }
  }
}
