# R8 / ProGuard rules shared by the :clients:mobile and :clients:wear release builds.
#
# The client code itself uses no reflection — `StreamMessages` builds/parses JSON through the
# kotlinx-serialization *DSL* (buildJsonObject / parseToJsonElement), not `@Serializable` reflection,
# so no model classes need keeping. These rules are almost entirely `-dontwarn`s for optional
# transitive providers that Ktor / OkHttp reference but that aren't on our runtime classpath, plus a
# defensive kotlinx-serialization block in case a @Serializable type is added later.
#
# Ktor, OkHttp, Okio, and kotlinx-coroutines all ship their own consumer R8 rules inside their
# artifacts (AGP applies them automatically), so we only top up the optional-dependency warnings.

# --- kotlinx-serialization (defensive; no reflective serializers today) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-keepclasseswithmembers class **$$serializer { *; }

# --- Ktor client + OkHttp engine: optional runtime deps referenced but not bundled ---
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn io.ktor.network.tls.**
-dontwarn java.lang.management.**

# OkHttp / Okio platform shims that R8 flags on Android (the classes only exist on the JVM).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
