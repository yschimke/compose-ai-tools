package ee.schimke.composeai.simulator;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowTextToSpeech;

/**
 * Robolectric's {@code TextToSpeech} shadow, made to behave as a device's engine does: the {@code
 * OnInitListener} hears {@code SUCCESS} on the main thread once the engine is bound (Robolectric
 * calls it never, and an app that waits for it never speaks), the device's languages are
 * available, and each utterance is reported as a {@code speak} event and runs its {@code
 * UtteranceProgressListener}'s {@code onStart} and {@code onDone}. Nothing is heard. Added to the
 * shadow map by {@link SimulatorSandbox}.
 */
@Implements(TextToSpeech.class)
public class SimulatorShadowTextToSpeech extends ShadowTextToSpeech {

  /** Where {@code speak} events go: {@link SystemPrompts} sets it once the channel is open. */
  static volatile Consumer<JSONObject> events;

  @Implementation
  @Override
  protected void __constructor__(
      Context context,
      TextToSpeech.OnInitListener listener,
      String engine,
      String packageName,
      boolean useFallback) {
    super.__constructor__(context, listener, engine, packageName, useFallback);
    for (Locale locale : new Locale[] {Locale.getDefault(), Locale.US, Locale.UK}) {
      ShadowTextToSpeech.addLanguageAvailability(locale);
    }
    if (listener != null) {
      new Handler(Looper.getMainLooper()).post(() -> listener.onInit(TextToSpeech.SUCCESS));
    }
  }

  @Implementation
  @Override
  protected int speak(CharSequence text, int queueMode, Bundle params, String utteranceId) {
    int result = super.speak(text, queueMode, params, utteranceId);
    Consumer<JSONObject> sink = events;
    if (sink != null) {
      sink.accept(
          new Protocol.Json()
              .put("text", text == null ? null : text.toString())
              .put("utteranceId", utteranceId)
              .put("queue", queueMode == TextToSpeech.QUEUE_ADD ? "add" : "flush")
              .object());
    }
    UtteranceProgressListener progress = getUtteranceProgressListener();
    if (progress != null && utteranceId != null) {
      Handler main = new Handler(Looper.getMainLooper());
      main.post(() -> progress.onStart(utteranceId));
      main.post(() -> progress.onDone(utteranceId));
    }
    return result;
  }
}
