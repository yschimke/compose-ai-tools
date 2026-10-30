package ee.schimke.composeai.simulator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sent data item's assets as the agent sees them in its {@code send} event: by key, as base64,
 * the same shape {@code dataLayer put} takes in {@code assets}, so a captured item can be put back.
 * A frame carries assets as key → digest plus digest → base64 bytes; an asset larger than the cap,
 * or whose bytes this node does not hold, is left out and named in {@code omitted} instead (with its
 * base64 length, or -1 when unknown), so a control-channel line stays a manageable size.
 */
final class AgentAssets {

  /** The largest asset, in base64 characters, a {@code send} event carries: 4 MiB. */
  static final int MAX_BASE64_CHARS = 4 * 1024 * 1024;

  /**
   * The replayable assets: key → base64, for each key whose digest has bytes within [maxChars];
   * the others go in [omitted], key → base64 length or -1.
   */
  static Map<String, String> replayable(
      Map<String, String> digestsByKey,
      Map<String, String> base64ByDigest,
      int maxChars,
      Map<String, Integer> omitted) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : digestsByKey.entrySet()) {
      String bytes = base64ByDigest.get(e.getValue());
      if (bytes == null) {
        omitted.put(e.getKey(), -1);
      } else if (bytes.length() > maxChars) {
        omitted.put(e.getKey(), bytes.length());
      } else {
        out.put(e.getKey(), bytes);
      }
    }
    return out;
  }

  private AgentAssets() {}
}
