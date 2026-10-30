package ee.schimke.composeai.simulator.fakegms;

import java.util.HashMap;

/** Today's shape: a public constructor taking a concrete {@code Map} ({@code ArrayMap}). */
public final class PublicMapException extends Exception {
  public static final class KeyedMap extends HashMap<Object, Object> {}

  public final KeyedMap results;

  public PublicMapException(KeyedMap results) {
    this.results = results;
  }
}
