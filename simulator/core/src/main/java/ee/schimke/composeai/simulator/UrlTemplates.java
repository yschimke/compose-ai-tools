package ee.schimke.composeai.simulator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The part of RFC 6570 URI templates App Actions' {@code <url-template>} uses, as Google Assistant
 * expands it: {@code {var}} (simple, reserved characters encoded), {@code {+var}} (reserved kept),
 * {@code {?a,b}} (a query from those present) and {@code {&a,b}} (continuing one). A variable with
 * no value is left out. Plain Java, tested without a sandbox.
 */
final class UrlTemplates {

  static String expand(String template, Map<String, String> values) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < template.length()) {
      int open = template.indexOf('{', i);
      if (open < 0) {
        out.append(template, i, template.length());
        break;
      }
      int close = template.indexOf('}', open);
      if (close < 0) throw new IllegalArgumentException("unclosed { in " + template);
      out.append(template, i, open);
      out.append(expression(template.substring(open + 1, close), values));
      i = close + 1;
    }
    return out.toString();
  }

  private static String expression(String expression, Map<String, String> values) {
    char operator = expression.isEmpty() ? 0 : expression.charAt(0);
    boolean hasOperator = operator == '?' || operator == '&' || operator == '+' || operator == '#';
    String names = hasOperator ? expression.substring(1) : expression;
    StringBuilder out = new StringBuilder();
    boolean first = true;
    for (String raw : names.split(",")) {
      String name = raw.trim();
      String value = values.get(name);
      if (value == null) continue;
      if (operator == '?' || operator == '&') {
        out.append(first ? operator : '&').append(encode(name)).append('=').append(encode(value));
      } else {
        if (first && operator == '#') out.append('#');
        if (!first) out.append(',');
        out.append(operator == '+' || operator == '#' ? value : encode(value));
      }
      first = false;
    }
    return out.toString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private UrlTemplates() {}
}
