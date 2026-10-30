package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class AppSearchQueryTest {

  private static final int EXACT = 1;
  private static final int PREFIX = AppSearchQuery.TERM_MATCH_PREFIX;

  @Test
  public void everyTermMustMatchAToken() {
    List<String> note = List.of("Hello simulated world", "groceries");
    assertTrue(AppSearchQuery.matches(note, "hello", PREFIX));
    assertTrue(AppSearchQuery.matches(note, "HELLO groc", PREFIX));
    assertTrue(AppSearchQuery.matches(note, "hello AND world", PREFIX));
    assertFalse(AppSearchQuery.matches(note, "hello goodbye", PREFIX));
  }

  @Test
  public void exactMatchingNeedsTheWholeToken() {
    List<String> note = List.of("simulated");
    assertTrue(AppSearchQuery.matches(note, "sim", PREFIX));
    assertFalse(AppSearchQuery.matches(note, "sim", EXACT));
    assertTrue(AppSearchQuery.matches(note, "\"simulated\"", EXACT));
  }

  @Test
  public void anEmptyQueryMatchesEverything() {
    assertTrue(AppSearchQuery.matches(List.of(), "  ", PREFIX));
  }
}
