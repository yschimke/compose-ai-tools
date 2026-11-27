package ee.schimke.composeai.simulator.fakegms;

/** An API client with Play services' {@code getApiKey()}. */
public final class ApiKeyed {
  public final String key;

  public ApiKeyed(String key) {
    this.key = key;
  }

  public Object getApiKey() {
    return key;
  }
}
