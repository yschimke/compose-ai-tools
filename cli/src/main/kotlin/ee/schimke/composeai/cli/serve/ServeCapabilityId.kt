package ee.schimke.composeai.cli.serve

import java.security.SecureRandom
import java.util.Base64

/**
 * The unguessable id that **is** the capability for a share this host hands out — a document
 * permalink ([ServeDocStore]), an uploaded image ([ServeImageStore]).
 *
 * One place, because the property that makes these links safe to publish is a property of the id
 * and nothing else: 128 bits from [SecureRandom] is not enumerable, so possession of the URL is the
 * whole grant and the server needs no per-share access list. A lane that minted a shorter or
 * less-random id would be handing out a guessable link while looking exactly like the others at the
 * call site.
 */
object ServeCapabilityId {

  private val random = SecureRandom()

  /** 128 bits of [SecureRandom], base64url — the id IS the capability. */
  fun mint(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** True when [id] could be one of ours — a cheap shape check before a map lookup. */
  fun isWellFormed(id: String): Boolean = id.matches(SHAPE)

  private val SHAPE = Regex("[A-Za-z0-9_-]{16,64}")
}
