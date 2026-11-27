package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class UrlTemplatesTest {

  private static Map<String, String> values(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
    return map;
  }

  @Test
  public void queryExpansionTakesTheValuesPresent() {
    assertEquals(
        "myapp://order?item=flat%20white&size=large",
        UrlTemplates.expand("myapp://order{?item,size}", values("item", "flat white", "size", "large")));
    assertEquals("myapp://order?size=large", UrlTemplates.expand("myapp://order{?item,size}", values("size", "large")));
    assertEquals("myapp://order", UrlTemplates.expand("myapp://order{?item}", values()));
    assertEquals("https://x.dev/s?q=a&page=2", UrlTemplates.expand("https://x.dev/s?q=a{&page}", values("page", "2")));
  }

  @Test
  public void simpleExpansionEncodesAndReservedDoesNot() {
    assertEquals("myapp://feature/a%2Fb", UrlTemplates.expand("myapp://feature/{name}", values("name", "a/b")));
    assertEquals("myapp://go/a/b", UrlTemplates.expand("myapp://go/{+path}", values("path", "a/b")));
    assertEquals("myapp://x#top", UrlTemplates.expand("myapp://x{#frag}", values("frag", "top")));
  }
}
