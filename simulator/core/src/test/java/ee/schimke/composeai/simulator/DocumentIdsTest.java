package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public class DocumentIdsTest {

  @Test
  public void idsAreExternalStorageProviders() {
    assertEquals("primary:", DocumentIds.of(""));
    assertEquals("primary:Download/a.pdf", DocumentIds.of("/Download/a.pdf/"));
    assertEquals("Download/a.pdf", DocumentIds.path("primary:Download/a.pdf"));
    assertEquals("", DocumentIds.path("primary:"));
  }

  @Test
  public void childrenAreNamedBelowTheirParent() {
    assertEquals("primary:Download", DocumentIds.child("primary:", "Download"));
    assertEquals("primary:Download/b.txt", DocumentIds.child("primary:Download", "b.txt"));
    assertThrows(IllegalArgumentException.class, () -> DocumentIds.child("primary:", ".."));
    assertThrows(IllegalArgumentException.class, () -> DocumentIds.child("primary:", "a/b"));
  }

  @Test
  public void otherRootsAndClimbingOutAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> DocumentIds.path("secondary:x"));
    assertThrows(IllegalArgumentException.class, () -> DocumentIds.path("primary:a/../../b"));
  }

  @Test
  public void treesContainTheirDescendants() {
    assertTrue(DocumentIds.isChild("primary:", "primary:Download/x"));
    assertTrue(DocumentIds.isChild("primary:Download", "primary:Download/x/y"));
    assertTrue(DocumentIds.isChild("primary:Download", "primary:Download"));
    assertFalse(DocumentIds.isChild("primary:Download", "primary:Downloads/x"));
  }

  @Test
  public void aTakenNameGetsANumber() {
    Set<String> taken = Set.of("note.txt", "note (1).txt", "README");
    assertEquals("other.txt", DocumentIds.unique("other.txt", taken::contains));
    assertEquals("note (2).txt", DocumentIds.unique("note.txt", taken::contains));
    assertEquals("README (1)", DocumentIds.unique("README", taken::contains));
  }
}
