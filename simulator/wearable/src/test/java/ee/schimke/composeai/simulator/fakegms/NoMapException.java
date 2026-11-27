package ee.schimke.composeai.simulator.fakegms;

/** No constructor the simulator can fill. */
public final class NoMapException extends Exception {
  public NoMapException(String message) {
    super(message);
  }
}
