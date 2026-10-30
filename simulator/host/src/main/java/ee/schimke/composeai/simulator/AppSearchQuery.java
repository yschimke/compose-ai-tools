package ee.schimke.composeai.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a document matches an AppSearch query, as Icing matches the common case: the query's
 * terms (whitespace-separated; {@code AND} and quotes ignored, {@code OR} and {@code -} not
 * understood) must each match a token of one of the document's string values, as a prefix under
 * {@code TERM_MATCH_PREFIX} or exactly under {@code TERM_MATCH_EXACT_ONLY}, ignoring case. An empty
 * query matches everything. Plain Java, tested without a sandbox.
 */
final class AppSearchQuery {

  /** {@code SearchSpec.TERM_MATCH_PREFIX}. */
  static final int TERM_MATCH_PREFIX = 2;

  static List<String> terms(String query) {
    List<String> terms = new ArrayList<>();
    for (String raw : query.trim().split("\\s+")) {
      String term = raw.replace("\"", "").toLowerCase(Locale.ROOT);
      if (term.isEmpty() || term.equals("and")) continue;
      terms.add(term);
    }
    return terms;
  }

  static boolean matches(List<String> values, String query, int termMatch) {
    List<String> terms = terms(query);
    if (terms.isEmpty()) return true;
    List<String> tokens = new ArrayList<>();
    for (String value : values) {
      for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
        if (!token.isEmpty()) tokens.add(token);
      }
    }
    for (String term : terms) {
      boolean found = false;
      for (String token : tokens) {
        if (termMatch == TERM_MATCH_PREFIX ? token.startsWith(term) : token.equals(term)) {
          found = true;
          break;
        }
      }
      if (!found) return false;
    }
    return true;
  }

  private AppSearchQuery() {}
}
