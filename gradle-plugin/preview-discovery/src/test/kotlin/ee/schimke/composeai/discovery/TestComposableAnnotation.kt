package androidx.compose.runtime

/** Minimal type-use fixture; discovery matches the real annotation by FQN and needs no runtime. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class Composable
