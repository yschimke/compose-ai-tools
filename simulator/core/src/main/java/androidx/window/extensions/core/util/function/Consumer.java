package androidx.window.extensions.core.util.function;

/**
 * The consumer the extensions call back with, as in {@code androidx.window.extensions.core:core}
 * (compile-only in apps, like the rest of the extensions).
 */
@FunctionalInterface
public interface Consumer<T> {
  void accept(T value);
}
