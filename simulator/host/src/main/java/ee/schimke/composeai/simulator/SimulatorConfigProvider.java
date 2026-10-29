package ee.schimke.composeai.simulator;

import javax.annotation.Priority;
import org.robolectric.annotation.Config;
import org.robolectric.pluginapi.config.GlobalConfigProvider;

/**
 * The simulator's display: Robolectric's {@code AppLoader} takes its {@link Config} from the
 * highest-priority {@link GlobalConfigProvider}, so this one turns {@code
 * composeai.simulator.qualifiers} (resource qualifiers such as {@code w360dp-h640dp-hdpi}) and
 * {@code composeai.simulator.fontScale} into the launch's config.
 */
@Priority(100)
public final class SimulatorConfigProvider implements GlobalConfigProvider {

  static final String QUALIFIERS_PROPERTY = "composeai.simulator.qualifiers";
  static final String FONT_SCALE_PROPERTY = "composeai.simulator.fontScale";

  public SimulatorConfigProvider() {}

  @Override
  public Config get() {
    Config.Builder builder = new Config.Builder();
    String qualifiers = System.getProperty(QUALIFIERS_PROPERTY);
    if (qualifiers != null && !qualifiers.isBlank()) builder.setQualifiers(qualifiers.trim());
    String fontScale = System.getProperty(FONT_SCALE_PROPERTY);
    if (fontScale != null && !fontScale.isBlank()) {
      builder.setFontScale(Float.parseFloat(fontScale.trim()));
    }
    return builder.build();
  }
}
