package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SystemFontAliasesTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void offlineModeWithEmptyCacheSkipsWithoutError() throws IOException {
    File cacheDir = temp.newFolder("empty-cache");
    assertNull(
        SystemFontAliases.resolveCachedOrDownload(
            cacheDir, "google-sans-flex", "Google Sans Flex", 400, true));
    assertTrue(SystemFontAliases.seed(new HashMap<>(), cacheDir, true).isEmpty());
  }

  @Test
  public void offlineModeReturnsPreCachedTtf() throws IOException {
    File cacheDir = temp.newFolder("warm-cache");
    File cached = new File(cacheDir, "google-sans-flex-600.ttf");
    Files.write(cached.toPath(), new byte[2048]);

    File resolved =
        SystemFontAliases.resolveCachedOrDownload(
            cacheDir, "google-sans-flex", "Google Sans Flex", 600, true);
    assertEquals(cached, resolved);
  }

  @Test
  public void defaultCacheDirHonoursSystemPropertyOverride() throws IOException {
    File custom = temp.newFolder("custom-fonts");
    String prev = System.getProperty(SystemFontAliases.CACHE_DIR_PROPERTY);
    try {
      System.setProperty(SystemFontAliases.CACHE_DIR_PROPERTY, custom.getAbsolutePath());
      assertEquals(custom, SystemFontAliases.defaultCacheDir());
    } finally {
      if (prev == null) System.clearProperty(SystemFontAliases.CACHE_DIR_PROPERTY);
      else System.setProperty(SystemFontAliases.CACHE_DIR_PROPERTY, prev);
    }
  }

  @Test
  public void alreadySeededAliasesArePreserved() throws IOException {
    File cacheDir = temp.newFolder("already-seeded");
    HashMap<String, android.graphics.Typeface> map = new HashMap<>();
    map.put("google-sans-flex", null);
    map.put("roboto-flex", null);

    List<String> seeded = SystemFontAliases.seed(map, cacheDir, true);
    assertEquals(List.of("google-sans-flex", "roboto-flex"), seeded);
  }
}
