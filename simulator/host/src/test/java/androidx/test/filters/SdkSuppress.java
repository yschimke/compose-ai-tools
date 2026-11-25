package androidx.test.filters;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A stand-in for androidx.test's, with its attributes, for {@code InstrumentationRunTest}. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface SdkSuppress {
  int minSdkVersion() default 1;

  int maxSdkVersion() default Integer.MAX_VALUE;

  String codeName() default "unset";

  int[] excludedSdks() default {};
}
